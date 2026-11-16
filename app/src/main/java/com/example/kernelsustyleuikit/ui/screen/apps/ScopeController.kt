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
    @Volatile
    private var modulePkg: String? = null

    private fun pkgOf(ctx: Context?): String =
        modulePkg ?: ctx?.packageName ?: ""

    @Volatile
    private var rootAvailable: Boolean? = null

    fun init(ctx: Context) {
        modulePkg = ctx.packageName
        XposedStatusProvider.ensureRegistered()
    }


    /**
     * 是否能用 root 静默改写作用域。只缓存成功结果：
     * 用户可能中途才在 KernelSU 里授权，永久缓存 false 会让模块一直以为没 root。
     */
    fun rootOk(): Boolean {
        rootAvailable?.let { if (it) return true }
        val ok = RootScopeWriter.hasRoot()
        if (ok) rootAvailable = true
        return ok
    }

    /** 有 root 时，勾选/取消只需要用户重开目标 App；没 root 还需要去 LSPosed 管理器勾。 */
    fun needsLsposedManualPick(): Boolean = !rootOk()

    /** 框架服务是否可用；不可用且没 root 时列表只展示。 */
    fun ready(): Boolean = XposedStatusProvider.service() != null || rootOk()

    /**
     * 当前勾选的 App 集合。
     *
     * 取"名单"和"LSPosed scope 表"的交集：
     *  - 名单（scope_apps）决定 hook 侧是否真的装 hook
     *  - scope 表决定 lspd 是否把模块注入到该 App 的进程
     * 两边都满足才真正生效，界面必须显示这个交集，否则会骗用户。
     *
     * 例外：没有 root 读不到 scope 表时，只能退回只信名单。
     */
    fun currentScope(ctx: Context? = null): Set<String> {
        // 优先用 LSPosed 服务的 scope（这是 lspd 真正认的注入范围）
        runCatching { XposedStatusProvider.service()?.scope?.toSet() }
            .getOrNull()?.let { if (it.isNotEmpty()) return it }
        // 服务没连上时退回名单（展示用）
        return TunNetPrefs.readStringSet(Config.KEY_SCOPE_APPS) ?: emptySet()
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
        // 名单同步一份，供 hook 侧兜底判断（scope 生效时它并不是必要条件）
        runCatching {
            val current = TunNetPrefs.readStringSet(Config.KEY_SCOPE_APPS)
                ?: currentScope(ctx)
            TunNetPrefs.writeStringSet(Config.KEY_SCOPE_APPS, current.toMutableSet().apply { add(pkg) })
        }

        val svc = XposedStatusProvider.service()
        if (svc == null) {
            onDone(false)
            return
        }
        runCatching {
            val now = svc.scope.toMutableSet()
            if (pkg in now) {
                onDone(true)
                return
            }
            now.add(pkg)
            svc.requestScope(now.toList(), object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(scope: MutableList<String>) {
                    onDone(pkg in scope)
                }

                override fun onScopeRequestFailed(message: String) {
                    onDone(false)
                }
            })
        }.onFailure { onDone(false) }
    }

    fun remove(ctx: Context, pkg: String, onDone: (Boolean) -> Unit = {}) {
        runCatching {
            val current = TunNetPrefs.readStringSet(Config.KEY_SCOPE_APPS)
                ?: currentScope(ctx)
            TunNetPrefs.writeStringSet(Config.KEY_SCOPE_APPS, current.toMutableSet().apply { remove(pkg) })
        }

        val svc = XposedStatusProvider.service()
        if (svc == null) {
            onDone(true)
            return
        }
        // removeScope 不弹窗，立即把该 App 从注入范围移除
        runCatching {
            if (pkg in svc.scope) svc.removeScope(listOf(pkg))
            onDone(true)
        }.onFailure { onDone(true) }
    }

}
