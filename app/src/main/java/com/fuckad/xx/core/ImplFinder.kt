package com.fuckad.xx.core

import android.content.Context
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/**
 * 用 DexKit 替代原 DexIndex + ImplFinder。
 *
 * 原实现只能按「类继承关系」找实现类，App 加固/混淆后接口描述符变了就扫不到。
 * DexKit 支持按字符串常量搜索类/方法，不受混淆影响，能穿透 SecShell 等加固壳。
 */
object ImplFinder {

    /** 模块包名，用来定位自己的 APK。 */
    private const val MODULE_PKG = "com.TunNet.n520"

    private val BRIDGES = Collections.synchronizedMap(WeakHashMap<Context, DexKitBridge>())

    /** 接口描述符 -> 实现类名列表 */
    class Result(
        @JvmField val impls: Map<String, List<String>>,
        @JvmField val classCount: Int,
    )

    /** 快速路径：只跑 DexIndex（10ms 级），先让 hook 立刻装上。 */
    @JvmStatic
    fun findFast(rules: List<Rules.Rule>, apks: List<File>): Result = fallback(rules, apks)

    /**
     * 慢速补全：用 DexKit 全量索引，找出 DexIndex 漏掉的类。
     *
     * 单独调用，放在 hook 已生效之后的后台线程里跑，不阻塞 App 启动。
     * @param known 已经找到的（描述符 -> 类名），返回时只包含新增部分
     */
    @JvmStatic
    fun findExtra(context: Context?, rules: List<Rules.Rule>,
                  known: Map<String, List<String>>): Result {
        if (context == null) return Result(emptyMap(), 0)
        val bridge = getOrCreateBridge(context) ?: return Result(emptyMap(), 0)
        val extra = LinkedHashMap<String, List<String>>()
        var added = 0

        for (rule in rules) {
            val existing = known[rule.targetDesc] ?: emptyList()
            val names = mutableListOf<String>()
            try {
                val found = bridge.findClass {
                    searchPackages("*")
                    matcher {
                        addInterface {
                            descriptor(rule.targetDesc)
                        }
                    }
                }
                for (cls in found) {
                    val name = cls.name
                    if (name.isNotEmpty() && !isPlatform(name) && name !in existing) {
                        names.add(name)
                        added++
                    }
                }
            } catch (t: Throwable) {
                // 单条规则失败不影响其他规则
            }
            if (names.isNotEmpty()) extra[rule.targetDesc] = names
        }
        return Result(extra, added)
    }

    /** 兼容旧调用：两步合并成一次同步查询。 */
    @JvmStatic
    fun find(context: Context?, rules: List<Rules.Rule>, apks: List<File>): Result {
        if (context == null || rules.isEmpty() || apks.isEmpty()) return Result(emptyMap(), 0)
        val base = findFast(rules, apks)
        val more = findExtra(context, rules, base.impls)

        val impls = LinkedHashMap<String, List<String>>()
        for (rule in rules) {
            val merged = mutableListOf<String>()
            merged.addAll(base.impls[rule.targetDesc] ?: emptyList())
            merged.addAll(more.impls[rule.targetDesc] ?: emptyList())
            impls[rule.targetDesc] = merged
        }
        val all = mutableSetOf<String>()
        for (list in impls.values) all.addAll(list)
        return Result(impls, all.size)
    }

    /** 是否已尝试过加载 dexkit 的 so，避免反复失败。 */
    private var soLoadTried = false
    private var soLoadedOk = false

    /**
     * 加载 DexKit 的 native 库。
     *
     * LSPosed 模块的 APK 里虽然有 libdexkit.so，但目标 App 进程不会自动加载它，
     * 直接调 DexKitBridge.create() 会抛：
     *   UnsatisfiedLinkError: No implementation found for nativeInitDexKit
     * 所以这里把 so 从模块 APK 解出来落到目标 App 私有目录，再 System.load()。
     */
    private fun ensureSoLoaded(context: Context): Boolean {
        if (soLoadedOk) return true
        if (soLoadTried) return false
        soLoadTried = true

        // 从模块 APK 里提取对应 ABI 的 so
        try {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val entryName = "lib/$abi/libdexkit.so"

            // 模块自己的 APK 路径
            val moduleApk = try {
                context.packageManager.getApplicationInfo(
                    MODULE_PKG, 0
                ).sourceDir
            } catch (t: Throwable) {
                null
            } ?: return false

            val dir = java.io.File(context.filesDir, "dexkit_lib").apply { mkdirs() }
            val out = java.io.File(dir, "libdexkit-$abi.so")
            // 已存在且大小一致就复用，避免每次都解压
            java.util.zip.ZipFile(moduleApk).use { zip ->
                val entry = zip.getEntry(entryName) ?: return false
                if (out.exists() && out.length() == entry.size) {
                    System.load(out.absolutePath)
                    soLoadedOk = true
                    return true
                }
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
            System.load(out.absolutePath)
            soLoadedOk = true
            return true
        } catch (t: Throwable) {
            return false
        }
    }

    /**
     * 取（或建）这个 Context 对应的 DexKitBridge。
     *
     * DexKitBridge 是 Closeable，底层持有一份完整的 dex 索引（native 内存，
     * 几十到几百 MB）。用完必须 close，否则会一直占着直到进程结束。
     */
    private fun getOrCreateBridge(context: Context): DexKitBridge? {
        synchronized(BRIDGES) {
            BRIDGES[context]?.let { return it }
        }
        if (!ensureSoLoaded(context)) return null
        return try {
            val apkPath = context.applicationInfo.sourceDir
            val bridge = DexKitBridge.create(apkPath)
            synchronized(BRIDGES) { BRIDGES[context] = bridge }
            bridge
        } catch (t: Throwable) {
            null
        }
    }

    /** 用完释放 native 索引，避免常驻占内存。 */
    @JvmStatic
    fun release(context: Context?) {
        if (context == null) return
        val bridge: DexKitBridge?
        synchronized(BRIDGES) {
            bridge = BRIDGES.remove(context)
        }
        try {
            bridge?.close()
        } catch (t: Throwable) {
            // 关不掉也不影响下次重建
        }
    }

    /**
     * 原 DexIndex 实现作为兜底。
     *
     * 注意：必须做「传递闭包」——一个类即使不直接实现目标接口，只要它的父类或父接口
     * 实现过，也要算进去。原 Java 版用 maskOf 递归实现，这里保持一致，
     * 否则会漏掉继承链上的实现类（实测 140 个 hook 会掉到 125 个）。
     */
    private fun fallback(rules: List<Rules.Rule>, apks: List<File>): Result {
        val result = LinkedHashMap<String, List<String>>()
        for (rule in rules) result[rule.targetDesc] = emptyList()

        val index = mutableMapOf<String, ClassInfo>()
        for (apk in apks) {
            val visitor = DexIndex.Visitor { name, superName, interfaces, access ->
                if (name != null && !index.containsKey(name)) {
                    index[name] = ClassInfo(superName, interfaces, access)
                }
            }
            DexIndex.scanPath(apk, visitor)
        }

        // 每个类命中哪些规则：按类缓存，递归算父类 + 接口
        val hitCache = HashMap<String, Set<String>>()

        fun hits(name: String?): Set<String> {
            if (name == null) return emptySet()
            hitCache[name]?.let { return it }
            val info = index[name]
            if (info == null) {
                // 找不到定义（比如系统接口）：只判断它自己是不是目标
                return rules.filter { it.targetDesc == name }.map { it.targetDesc }.toSet()
            }
            // 先占位，避免 dex 里出现循环继承时无限递归
            hitCache[name] = emptySet()
            val out = HashSet<String>()
            for (rule in rules) if (rule.targetDesc == name) out.add(rule.targetDesc)
            out.addAll(hits(info.superName))
            info.interfaces?.forEach { out.addAll(hits(it)) }
            hitCache[name] = out
            return out
        }

        for ((name, info) in index) {
            if (info.access and (DexIndex.ACC_ABSTRACT or DexIndex.ACC_INTERFACE) != 0) continue
            val dotted = name.removePrefix("L").removeSuffix(";").replace('/', '.')
            if (isPlatform(dotted)) continue

            val matched = hits(name)
            if (matched.isEmpty()) continue
            for (desc in matched) {
                result[desc] = result[desc]!! + dotted
            }
        }
        return Result(result, index.size)
    }

    private class ClassInfo(
        val superName: String?,
        val interfaces: Array<String>?,
        val access: Int,
    )

    private fun isPlatform(dotted: String): Boolean {
        return dotted.startsWith("android.") || dotted.startsWith("androidx.") ||
                dotted.startsWith("java.") || dotted.startsWith("javax.") ||
                dotted.startsWith("kotlin.") || dotted.startsWith("dalvik.") ||
                dotted.startsWith("com.android.")
    }

    @JvmStatic
    fun toName(desc: String): String? {
        if (!desc.startsWith("L") || !desc.endsWith(";")) return null
        return desc.substring(1, desc.length - 1).replace('/', '.')
    }
}
