package com.example.kernelsustyleuikit.ui.screen.apps

import android.content.Context
import com.example.kernelsustyleuikit.ui.util.XposedStatusProvider
import com.example.kernelsustyleuikit.ui.viewmodel.TunNetPrefs
import com.fuckad.xx.Config
import io.github.libxposed.service.XposedService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 作用域控制 —— 名单制，零授权弹窗。
 *
 * ## 为什么这样设计
 *
 * LSPosed 对 libxposed 模块只提供了 `requestScope()` 来添加作用域，
 * 而它必然弹出"允许"授权通知；并且 lspd 的 scope 管理 Binder 只点对点
 * 发给模块进程和官方管理器，root 也拿不到，所以无法绕过。
 *
 * 因此改用"注入范围由 lspd 兜底 + 生效范围由名单决定"的两层结构：
 *
 *  1. module.prop 里 `staticScope=true`：lspd 不检查 scope 表、
 *     直接把模块注入每个 App，这一步不需要任何授权 → 永不弹窗。
 *  2. 勾选写入 remote prefs 的 `scope_apps` 名单；hook 侧 Main.install()
 *     用 `Config.isAppEnabled()` 读这份名单，未勾选的 App 直接跳过安装。
 *
 * 结果：勾选/取消只改模块自己的配置，不触发任何 LSPosed 授权交互。
 */
object ScopeController {

    /**
     * 模块包名，用来查 LSPosed 库里的 scope 行。
     *
     * 曾经给了一个写死的默认值 com.example.kernelsustyleuikit，
     * 但真实包名是 com.TunNet.n520。首页刷新比应用页早，
     * 那时 init() 还没跑，就会用错包名查库 → 一条作用域都查不到，
     * 表现为「首页已启用 0」而应用页其实有勾选。
     * 现在缺省直接取 Context 的包名。
     */
    fun init(ctx: Context) {
        XposedStatusProvider.ensureRegistered()
    }


    /** 框架服务是否可用；不可用则勾选走 requestScope 必然失败，列表只展示。 */
    fun ready(): Boolean = XposedStatusProvider.service() != null

    /**
     * 当前勾选的 App 集合 —— 只以 scope_apps 名单为准。
     *
     * 为什么不掺和 LSPosed 的 scope 表：
     *
     * lspd 是常驻进程，以 WAL 模式持有 modules_config.db。root 直写虽然
     * 能进 WAL，但 lspd 内存里的 scope 是权威状态，它 checkpoint 时会把
     * 内存状态写回，覆盖外部插入 —— 造成「直写返回成功、界面显示已勾选、
     * 实际库里是空」的三方不一致（实测：db 里 TenNet scope 为空，
     * service.scope 却返回 2 个）。
     *
     * 而 hook 侧真正读的是 Config.isAppEnabled() → scope_apps 名单。
     * 界面必须和这个数据源一致，否则数字就是假的。
     *
     * scope 表仍然要写（root 直写保留），那是给 lspd 下次重启后加载用的；
     * 但「当前勾选状态」只看名单。
     */
    fun currentScope(ctx: Context? = null): Set<String> {
        val listed = TunNetPrefs.readStringSet(Config.KEY_SCOPE_APPS) ?: emptySet()
        if (listed.isEmpty()) return listed

        // 过滤掉已卸载的包：勾选过的应用卸载后，名单里会留下脏数据，
        // 首页「已启用」会虚高（实测：残留 2 个已卸载包，显示 2 而应用页全没勾）。
        // 有 Context 时按已安装集合过滤；没有 Context 就原样返回（只影响显示）。
        val c = ctx
        if (c == null) return listed
        return runCatching {
            val pm = c.packageManager
            val installed = pm.getInstalledPackages(0)
                .map { it.packageName }
                .toHashSet()
            listed.filterTo(HashSet()) { it in installed }
        }.getOrDefault(listed)
    }

    /**
     * 勾选一个 App。
     *
     * 走 LSPosed 官方 requestScope：第一次勾选会弹一次"允许"授权通知，
     * 用户点允许后 LSPosed 会记住这个模块，之后再勾其他 App 不再弹。
     *
     * 只有 scope 表里有的 App 才会被注入模块 —— 没勾选的 App
     * 完全不加载，不会留下任何痕迹（银行/游戏类检测不到）。
     */
    fun add(ctx: Context, pkg: String, onDone: (Boolean) -> Unit = {}) {
        // 先记下原名单，授权失败时回滚。
        val before = TunNetPrefs.readStringSet(Config.KEY_SCOPE_APPS)

        val svc = XposedStatusProvider.service()
        if (svc == null) {
            onDone(false)
            return
        }

        fun commitList() {
            runCatching {
                // 用 currentScope(ctx)（已过滤掉已卸载包）作为基线，而不是未过滤的 before，
                // 否则已卸载应用的脏数据会一直留在名单里清不掉。
                val current = currentScope(ctx)
                TunNetPrefs.writeStringSet(
                    Config.KEY_SCOPE_APPS, current.toMutableSet().apply { add(pkg) })
            }
        }

        runCatching {
            val now = svc.scope.toMutableSet()
            if (pkg in now) {
                commitList()
                onDone(true)
                return
            }
            now.add(pkg)
            svc.requestScope(now.toList(), object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(scope: MutableList<String>) {
                    if (pkg in scope) commitList() else rollback(before)
                    onDone(pkg in scope)
                }

                override fun onScopeRequestFailed(message: String) {
                    rollback(before)
                    onDone(false)
                }
            })
        }.onFailure {
            rollback(before)
            onDone(false)
        }
    }

    /** 名单回滚到授权前的状态；before 为 null 表示原本就没配置过，直接清空。 */
    private fun rollback(before: Set<String>?) {
        runCatching {
            if (before == null) {
                TunNetPrefs.writeStringSet(Config.KEY_SCOPE_APPS, emptySet())
            } else {
                TunNetPrefs.writeStringSet(Config.KEY_SCOPE_APPS, before)
            }
        }
    }

    fun remove(ctx: Context, pkg: String, onDone: (Boolean) -> Unit = {}) {
        // 取消勾选：名单写入结果要检查，写失败不能对 UI 谎报成功。
        // 用 currentScope(ctx)（已过滤已卸载包）作为基线，顺带清掉脏数据。
        val wrote = runCatching {
            val current = currentScope(ctx)
            TunNetPrefs.writeStringSet(
                Config.KEY_SCOPE_APPS, current.toMutableSet().apply { remove(pkg) })
        }.getOrDefault(false)
        if (!wrote) {
            onDone(false)
            return
        }

        val svc = XposedStatusProvider.service()
        if (svc == null) {
            onDone(true)
            return
        }
        runCatching {
            if (pkg in svc.scope) svc.removeScope(listOf(pkg))
            onDone(true)
        }.onFailure { onDone(true) }
    }

}
