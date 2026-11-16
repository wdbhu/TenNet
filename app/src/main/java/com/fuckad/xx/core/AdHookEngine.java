package com.fuckad.xx.core;

import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;

import com.fuckad.xx.Config;
import com.fuckad.xx.Logs;
import com.fuckad.xx.ui.Toaster;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 核心引擎：扫描 dex -> 找到实现类 -> hook 奖励/结束回调。
 */
public final class AdHookEngine {

    /** 最近一次广告展示的 adapter 实例，用来在自动关闭前替 SDK 触发结束回调。 */
    private static volatile java.lang.ref.WeakReference<Object> sLastAdTargetRef;
    /** 最近一次补发奖励的时间，3 秒内不重复补，避免关闭+SDK 回调双发。 */
    private static volatile long sLastRewardAt;
    private static volatile long sLastAdAt;
    private static final java.util.Map<Class<?>, java.util.List<Method>> sCloseMethods = new java.util.HashMap<>();
    /**
     * 最近一次「真实」奖励回调实参，按 方法签名 存一份。
     * 补发时优先复用它——自己用 Unsafe 造的空壳参数会被 App 读字段 NPE。
     */
    private static final java.util.Map<String, Object[]> sRealRewardArgs =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * 按「参数类型」共享的真实回调对象。
     *
     * TopOn 这类聚合层会把同一个广告回调分发给多个监听器（SDK 内部适配层 + App 自己的监听器），
     * 前者能拿到真实 ATAdInfo，后者在百度/快手源下可能收到 null。
     * 这里把见过的真实对象按类型存一份，后续谁收到 null 就用它顶上。
     */
    /**
     * 按类型缓存真实回调对象。
     *
     * 必须用弱引用：广告对象（ATAdInfo 等）动辄持有素材 URL、大段 JSON，
     * 强引用会把每轮广告的对象都留在内存里，刷几十次广告就是几十 MB。
     */
    private static final java.util.Map<Class<?>, java.lang.ref.WeakReference<Object>> sRealArgByType =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Class<?>, java.lang.ref.WeakReference<Object>>());
    private static final java.util.Map<Class<?>, Method> sRewardMethods = new java.util.HashMap<>();
    private static final java.util.Set<Class<?>> sCtorHooked = new java.util.HashSet<>();
    private static volatile long sLastFireAt;
    /** 广告真正开始展示的时间（onAdShow 这类展示回调），自动关闭用它算延迟。 */
    private static volatile long sLastShowAt;

    /** 关闭广告页之前调用：让 SDK 走一次 onAdClose/onVideoComplete，奖励才有机会发出去。 */
    public static boolean fireLastClose() {
        Object target = sLastAdTargetRef == null ? null : sLastAdTargetRef.get();
        if (target == null) return false;
        long now = System.currentTimeMillis();
        if (now - sLastAdAt > 20_000L) return false;
        if (now - sLastFireAt < 3_000L) return false;
        sLastFireAt = now;
        boolean fired = false;
        // 先补一次奖励：CSJ 的 onRewardVerify(true,1,...) / GDT 的 onReward(Map)
        Method reward = sRewardMethods.get(target.getClass());
        if (reward != null && now - sLastRewardAt < 3_000L) {
            reward = null;
        }
        if (reward != null) {
            try {
                reward.setAccessible(true);
                Object[] args = ArgFixer.buildRewardArgs(reward.getParameterTypes(), new Object[0]);
                reward.invoke(target, args);
                sLastRewardAt = now;
                Logs.i("关闭前补发奖励 " + reward.getName() + " @ "
                        + target.getClass().getName());
                fired = true;
            } catch (Throwable t) {
                Logs.w("关闭前补发奖励失败: " + t);
            }
        }
        java.util.List<Method> closes;
        synchronized (sCloseMethods) {
            closes = sCloseMethods.get(target.getClass());
            if (closes == null) closes = null;
            else closes = new java.util.ArrayList<>(closes);
        }
        if (closes == null || closes.isEmpty()) return fired;
        for (Method close : closes) {
            try {
                close.setAccessible(true);
                close.invoke(target);
                Logs.i("关闭前替 SDK 触发结束回调 " + close.getName()
                        + " @ " + target.getClass().getName());
                return true;
            } catch (Throwable t) {
                Logs.w("关闭前触发结束回调失败 " + close.getName() + ": " + t);
            }
        }
        return false;
    }

    /**
     * 复制一份参数，把其中的字符串（一般是广告 ID / 交易号）换成唯一值。
     * 很多游戏按“这笔奖励只发一次”去重，换掉 ID 才有机会拿到多份。
     */

    /** 比默认优先级（50）高一点，保证参数修复发生在 App 自己的回调逻辑之前。 */
    private static final int PRIORITY = XposedInterface.PRIORITY_DEFAULT + 50;

    private static Handler sMain;
    private static volatile AdHookEngine sEngine;
    /** 已经懒扫描过的插件 ClassLoader，避免重复扫。 */
    private static final Set<ClassLoader> LAZY_SCANNED =
            Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>());
    private static final AtomicInteger LAZY_COUNT = new AtomicInteger();
    private final XposedInterface xi;
    /** 已 hook 的方法签名，避免重复 hook。 */
    private final Map<String, Boolean> hooked = new ConcurrentHashMap<>();
    /** 类名 -> (方法名 -> 同名重载方法列表)，补发奖励时直接用，省掉重复反射。 */
    private final Map<String, Map<String, List<Method>>> classMethods = new ConcurrentHashMap<>();

    public AdHookEngine(XposedInterface xi) {
        this.xi = xi;
        sEngine = this;
        // 让 ArgFixer 在遇到 null 对象参数时能取到同轮广告的真实对象
        ArgFixer.setRealArgSource(new ArgFixer.RealArgSource() {
            @Override
            public Object find(Class<?> type) {
                java.lang.ref.WeakReference<Object> ref = sRealArgByType.get(type);
                Object direct = ref == null ? null : ref.get();
                if (direct != null) return direct;
                // 退一步：找兼容子类型（接口/父类）
                synchronized (sRealArgByType) {
                    for (java.util.Map.Entry<Class<?>, java.lang.ref.WeakReference<Object>> e
                            : sRealArgByType.entrySet()) {
                        if (type.isAssignableFrom(e.getKey())) {
                            Object v = e.getValue() == null ? null : e.getValue().get();
                            if (v != null) return v;
                        }
                    }
                }
                return null;
            }
        });
    }

    /** 扫描结果，交给主线程去装 hook。 */
    public static final class ScanResult {
        final List<Rules.Rule> rules;
        final Map<String, List<String>> impls;

        ScanResult(List<Rules.Rule> rules, Map<String, List<String>> impls) {
            this.rules = rules;
            this.impls = impls;
        }
    }

    /** 只做 dex 扫描，可以在后台线程跑。 */
    public ScanResult scan(ApplicationInfo info) {
        final android.content.Context ctx = AppCtx.get();
        List<File> apks = DexIndex.apkPathsOf(info);
        List<Rules.Rule> rules = Rules.enabled();
        Logs.raw("开始扫描 dex，APK 数=" + apks.size() + "，规则数=" + rules.size());
        long start = System.currentTimeMillis();

        String key = scanKey(rules, apks);
        File cache = cacheFile(info);
        Map<String, List<String>> impls = readCache(cache, key, rules);
        if (impls != null) {
            Logs.raw("扫描缓存命中，用时 " + (System.currentTimeMillis() - start) + "ms");
            return new ScanResult(rules, impls);
        }

        // 第一步只跑 DexIndex（10ms 级），让 hook 尽快装上，不拖 App 启动。
        // DexKit 的全量索引（1s 级）放到 hook 生效之后的后台线程补，见 slowRescan()。
        ImplFinder.Result found = ImplFinder.findFast(rules, apks);
        impls = found.impls;
        Logs.raw("dex 快速扫描完成，用时 " + (System.currentTimeMillis() - start) + "ms");
        if (cache != null && found.classCount > 0) {
            writeCache(cache, key, impls);
        }
        return new ScanResult(rules, impls);
    }

    private static File cacheFile(ApplicationInfo info) {
        if (info == null || info.dataDir == null) return null;
        try {
            File dir = new File(info.dataDir, "cache");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            return new File(dir, "tunnet_scan.cache");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String scanKey(List<Rules.Rule> rules, List<File> apks) {
        StringBuilder sb = new StringBuilder();
        sb.append("v1");
        for (Rules.Rule rule : rules) {
            sb.append('|').append(rule.sdk).append(':').append(rule.targetDesc);
        }
        for (File file : apks) {
            sb.append('|').append(file.getAbsolutePath())
                    .append(':').append(file.length())
                    .append(':').append(file.lastModified());
        }
        return sha256(sb.toString());
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static Map<String, List<String>> readCache(File cache, String key, List<Rules.Rule> rules) {
        if (cache == null || !cache.isFile()) return null;
        if (cache.length() <= 0 || cache.length() > 4L * 1024 * 1024) return null;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(cache), StandardCharsets.UTF_8));
            if (!"TUNNET_SCAN".equals(reader.readLine())) return null;
            if (!"1".equals(reader.readLine())) return null;
            if (!key.equals(reader.readLine())) return null;

            Map<String, List<String>> out = new LinkedHashMap<>();
            for (Rules.Rule rule : rules) {
                out.put(rule.targetDesc, new ArrayList<String>());
            }
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                List<String> list = out.get(line.substring(0, tab));
                if (list == null) continue;
                String rest = line.substring(tab + 1);
                if (rest.isEmpty()) continue;
                for (String className : rest.split(",")) {
                    if (!className.isEmpty()) list.add(className);
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 清理上次崩溃/被杀留下的临时缓存文件，避免 cache 目录越积越多。 */
    private static void cleanupTemp(File cache) {
        File dir = cache.getParentFile();
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            String name = file.getName();
            if (!name.startsWith(cache.getName() + ".tmp.")) continue;
            if (now - file.lastModified() > 10L * 60 * 1000) {
                file.delete();
            }
        }
    }

    private static void writeCache(File cache, String key, Map<String, List<String>> impls) {
        if (cache == null || impls == null) return;
        cleanupTemp(cache);
        File tmp = new File(cache.getParentFile(),
                cache.getName() + ".tmp." + android.os.Process.myPid());
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(tmp), StandardCharsets.UTF_8));
            writer.write("TUNNET_SCAN\n");
            writer.write("1\n");
            writer.write(key);
            writer.write('\n');
            for (Map.Entry<String, List<String>> entry : impls.entrySet()) {
                writer.write(entry.getKey());
                writer.write('\t');
                List<String> list = entry.getValue();
                if (list != null) {
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) writer.write(',');
                        writer.write(list.get(i));
                    }
                }
                writer.write('\n');
            }
            writer.flush();
            writer.close();
            writer = null;
            if (!tmp.renameTo(cache)) {
                cache.delete();
                if (!tmp.renameTo(cache)) {
                    tmp.delete();
                    return;
                }
            }
            Logs.raw("扫描缓存已写入");
        } catch (Throwable t) {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Throwable ignored) {
                }
            }
            tmp.delete();
        }
    }

    /** 安装 hook，必须在主线程调用（LSPosed 在后台线程装 hook 会卡住）。 */
    public int apply(ClassLoader loader, ScanResult result) {
        if (loader == null || result == null) return 0;
        List<Rules.Rule> rules = result.rules;
        Map<String, List<String>> impls = result.impls;

        int sdkCount = 0;
        int hookCount = 0;
        for (Rules.Rule rule : rules) {
            List<String> classes = impls.get(rule.targetDesc);
            if (classes != null && !classes.isEmpty()) {
                sdkCount++;
                Logs.raw("命中 SDK " + rule.sdk + "，实现类 " + classes.size() + " 个");
                for (String className : classes) {
                    hookCount += hookImpl(rule, loader, className);
                }
            } else if (classExists(rule, loader)) {
                int n = hookInterfaceFallback(rule, loader);
                if (n > 0) sdkCount++;
                hookCount += n;
            }
        }


        String summary = "TunNet 已注入 · SDK " + sdkCount + " · Hook " + hookCount;
        Logs.raw(summary);
        if (Config.toast() && hookCount > 0) {
            Toaster.show(summary);
        }
        return hookCount;
    }

    /**
     * DexKit 慢速补全：在 hook 已经生效之后跑，找出 DexIndex 漏掉的实现类。
     *
     * 完全在后台线程执行，找到新类后回主线程增量安装 hook，不影响已生效的部分。
     * 这样启动关键路径只有 10ms 级的 DexIndex，DexKit 的 1s 级全量索引被挪出冷启动。
     */
    public void slowRescan(ClassLoader loader, ApplicationInfo info, ScanResult fast) {
        if (loader == null || fast == null || info == null) return;
        final List<Rules.Rule> rules = fast.rules;
        final Map<String, List<String>> known = fast.impls;


        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {
                }
                long start = System.currentTimeMillis();
                try {
                    ImplFinder.Result extra = ImplFinder.findExtra(AppCtx.get(), rules, known);
                    int total = 0;
                    for (List<String> list : extra.impls.values()) {
                        if (list != null) total += list.size();
                    }
                    if (total == 0) {
                        Logs.raw("DexKit 补扫无新增，用时 "
                                + (System.currentTimeMillis() - start) + "ms");
                        ImplFinder.release(AppCtx.get());
                        return;
                    }
                    Logs.raw("DexKit 补扫新增 " + total + " 个类，用时 "
                            + (System.currentTimeMillis() - start) + "ms");
                    final Map<String, List<String>> merged = new LinkedHashMap<>();
                    for (Rules.Rule rule : rules) {
                        List<String> all = new ArrayList<>();
                        List<String> a = known.get(rule.targetDesc);
                        List<String> b = extra.impls.get(rule.targetDesc);
                        if (a != null) all.addAll(a);
                        if (b != null) all.addAll(b);
                        merged.put(rule.targetDesc, all);
                    }
                    // 把补扫结果并进扫描缓存：下次启动直接命中，不用再跑 1s 级的 DexKit。
                    try {
                        List<File> apks = DexIndex.apkPathsOf(info);
                        File cache = cacheFile(info);
                        if (cache != null && !apks.isEmpty()) {
                            writeCache(cache, scanKey(rules, apks), merged);
                            Logs.raw("DexKit 补扫结果已写入缓存");
                        }
                    } catch (Throwable t) {
                        Logs.w("DexKit 补扫缓存写入失败: " + t);
                    }
                    // 索引已经转成结果，native 那边不用再常驻
                    try {
                        ImplFinder.release(AppCtx.get());
                    } catch (Throwable ignored) {
                    }
                    mainHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                int hooks = apply(loader, new ScanResult(rules, merged));
                                Logs.raw("DexKit 补扫 hook 安装完成，数量=" + hooks);
                            } catch (Throwable t) {
                                Logs.w("DexKit 补扫安装失败: " + t);
                            }
                        }
                    });
                } catch (Throwable t) {
                    Logs.w("DexKit 补扫失败: " + t);
                }
            }
        }, "TunNet-dexkit");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 广告页出现时，如果它的 ClassLoader 不是主 App 的，说明来自动态插件：
     * 扫一遍该 loader 的 dex/apk，补 hook 标准 SDK 实现类。
     */
    public static void lazyRescan(ClassLoader loader) {
        final AdHookEngine engine = sEngine;
        if (engine == null || loader == null) return;
        if (loader == AdHookEngine.class.getClassLoader()) return;
        if (LAZY_COUNT.get() >= 8) return;
        synchronized (LAZY_SCANNED) {
            if (!LAZY_SCANNED.add(loader)) return;
        }
        final List<File> paths = dexPathsOf(loader);
        if (paths.isEmpty()) {
            synchronized (LAZY_SCANNED) {
                LAZY_SCANNED.remove(loader);
            }
            return;
        }
        LAZY_COUNT.incrementAndGet();


        Logs.raw("动态插件懒扫描: " + paths.size() + " 个 dex/apk");
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final android.content.Context lazyCtx = AppCtx.get();
                    final ImplFinder.Result found = ImplFinder.find(lazyCtx, Rules.enabled(), paths);
                    boolean any = false;
                    for (List<String> classes : found.impls.values()) {
                        if (classes != null && !classes.isEmpty()) {
                            any = true;
                            break;
                        }
                    }
                    if (!any) return;
                    final ScanResult result = new ScanResult(Rules.enabled(), found.impls);
                    mainHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            engine.apply(loader, result);
                        }
                    });
                } catch (Throwable t) {
                    Logs.w("动态插件懒扫描失败: " + t);
                }
            }
        }, "TunNet-lazy-scan");
        thread.setDaemon(true);
        thread.start();
    }

    private static List<File> dexPathsOf(ClassLoader loader) {
        List<File> out = new ArrayList<>();
        try {
            Class<?> base = Class.forName("dalvik.system.BaseDexClassLoader");
            if (!base.isInstance(loader)) return out;
            Field pathListField = base.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(loader);
            if (pathList == null) return out;
            Field elementsField = pathList.getClass().getDeclaredField("dexElements");
            elementsField.setAccessible(true);
            Object[] elements = (Object[]) elementsField.get(pathList);
            if (elements == null) return out;
            for (Object element : elements) {
                File file = elementPath(element);
                if (file == null || !file.isFile()) continue;
                if (!out.contains(file)) out.add(file);
            }
        } catch (Throwable t) {
            Logs.w("读取插件 dex 路径失败: " + t);
        }
        return out;
    }

    private static File elementPath(Object element) {
        if (element == null) return null;
        for (String name : new String[]{"path", "file"}) {
            try {
                Field field = element.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(element);
                if (value instanceof File) return (File) value;
                if (value instanceof String) return new File((String) value);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private boolean classExists(Rules.Rule rule, ClassLoader loader) {
        try {
            String name = ImplFinder.toName(rule.targetDesc);
            if (name == null) return false;
            Class.forName(name, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int hookImpl(Rules.Rule rule, ClassLoader loader, String className) {
        try {
            Class<?> clazz = Class.forName(className, false, loader);
            Map<String, List<Method>> methods = collectMethods(clazz);
            classMethods.put(className, methods);

            int hits = 0;
            for (Map.Entry<String, List<Method>> entry : methods.entrySet()) {
                String name = entry.getKey();
                boolean reward = rule.isReward(name);
                boolean trigger = rule.isTrigger(name);
                if (!reward && !trigger) continue;
                for (Method method : entry.getValue()) {
                    if (reward) {
                        if (installReward(rule, method)) hits++;
                    } else if (installTrigger(rule, method)) {
                        hits++;
                    }
                }
            }
            return hits;
        } catch (Throwable t) {
            Logs.w("加载实现类失败 " + className + " : " + t);
            return 0;
        }
    }

    /** 收集类及其父类的实例方法（跳过静态、抽象、合成方法），同名重载全部保留。 */
    private Map<String, List<Method>> collectMethods(Class<?> clazz) {
        Map<String, List<Method>> out = new LinkedHashMap<>();
        Class<?> current = clazz;
        int depth = 0;
        while (current != null && current != Object.class && depth++ < 8) {
            Method[] declared;
            try {
                declared = current.getDeclaredMethods();
            } catch (Throwable t) {
                break;
            }
            for (Method method : declared) {
                int mod = method.getModifiers();
                if (Modifier.isAbstract(mod) || Modifier.isStatic(mod)) continue;
                if (method.isSynthetic() || method.isBridge()) continue;
                String name = method.getName();
                if (name.startsWith("lambda$")) continue;
                List<Method> list = out.get(name);
                if (list == null) {
                    list = new ArrayList<>(2);
                    out.put(name, list);
                }
                if (hasOverload(list, method)) continue;
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                }
                list.add(method);
            }
            current = current.getSuperclass();
        }
        return out;
    }

    private static boolean hasOverload(List<Method> methods, Method candidate) {
        Class<?>[] types = candidate.getParameterTypes();
        for (Method method : methods) {
            if (Arrays.equals(method.getParameterTypes(), types)) return true;
        }
        return false;
    }

    private void rememberRewardMethod(Method method) {
        String name = method.getName();
        if (!name.equals("onRewardVerify") && !name.equals("onRewardArrived")
                && !name.equals("onReward") && !name.equals("onRewardSuccess")
                && !name.equals("onRewarded") && !name.equals("onAdReward")
                && !name.equals("onFeedRewardCountDown")) {
            return;
        }
        Class<?>[] types = method.getParameterTypes();
        boolean hasBoolean = types.length > 0 && types[0] == boolean.class;
        synchronized (sRewardMethods) {
            Method old = sRewardMethods.get(method.getDeclaringClass());
            if (old == null || hasBoolean) {
                sRewardMethods.put(method.getDeclaringClass(), method);
            }
        }
    }

    /** 广告回调对象在广告展示前就构造出来，构造时记一份，即使 SDK 不回 onAdShow 也能补发。 */
    private void captureInstances(final Class<?> clazz) {
        synchronized (sCtorHooked) {
            if (clazz == null || !sCtorHooked.add(clazz)) return;
        }
        try {
            for (java.lang.reflect.Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                final java.lang.reflect.Constructor<?> target = ctor;
                try {
                    target.setAccessible(true);
                    xi.hook(target).setPriority(PRIORITY).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            Object self = chain.getThisObject();
                            if (self != null) {
                                sLastAdTargetRef = new java.lang.ref.WeakReference<>(self);
                                sLastAdAt = System.currentTimeMillis();
                                if (Logs.isEnabled()) {
                                    Logs.d("捕获广告实例 " + self.getClass().getName());
                                }
                            }
                            return result;
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean installReward(final Rules.Rule rule, final Method method) {
        final String key = keyOf(method);
        if (hooked.containsKey(key)) return false;
        final Class<?>[] types = method.getParameterTypes();
        final Class<?> returnType = method.getReturnType();
        try {
            xi.hook(method)
                    .setPriority(PRIORITY)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            try {
                                Object self = chain.getThisObject();
                                if (Logs.isEnabled()) Logs.d("奖励回调: " + key);

                                // 我们自己补发进去的调用：只修参数，不做去重
                                if (RewardLedger.isInjecting()) {
                                    Object[] fixed = ArgFixer.fixRewardArgs(types, toArray(chain.getArgs()));
                                    return fixed != null ? chain.proceed(fixed) : chain.proceed();
                                }

                                // 补发后 App 自己又回了一次：吞掉，避免重复发奖
                                if (self != null && RewardLedger.shouldSwallow(self, rule.sdk, key)) {
                                    return ArgFixer.defaultValue(returnType);
                                }

                                if (self != null) RewardLedger.markNatural(self, rule.sdk, key);

                                Object[] original = toArray(chain.getArgs());
                                // 真实回调（不是我们补发进去的）把实参存一份，关闭时补发直接复用，
                                // 避免自造空壳参数被 App 读字段 NPE
                                if (original != null && original.length > 0) {
                                    sRealRewardArgs.put(key, original.clone());
                                    // 同时按类型记一份：同一次广告的多个监听器共享同一个真实对象
                                    for (Object arg : original) {
                                        if (arg != null) {
                                            sRealArgByType.put(arg.getClass(),
                                                    new java.lang.ref.WeakReference<>(arg));
                                        }
                                    }
                                }
                                // 不修改档：不重写 boolean/int，但 null 的对象参数必须补
                                // （TopOn 在百度/快手源下 ATAdInfo 传 null，App 侧是 Kotlin
                                //   非空参数，直接 NPE，奖励就到不了账）
                                final boolean passthrough = Config.rewardPassthrough();
                                if (passthrough && Logs.isEnabled()) {
                                    Logs.d("奖励回调原样放行(倍率不动，仅 1 次): " + key);
                                }
                                Object[] fixed = passthrough
                                        ? ArgFixer.fillNullArgs(types, original)
                                        : ArgFixer.fixRewardArgs(types, original);
                                Object result = fixed != null ? chain.proceed(fixed) : chain.proceed();
                                if (!passthrough) {
                                    multiplyNaturalReward(self, method, fixed, original);
                                }
                                return result;
                            } catch (Throwable t) {
                                Logs.w("奖励回调执行异常 " + key + " : " + t);
                                throw t;
                            }
                        }
                    });
            hooked.put(key, Boolean.TRUE);
            rememberRewardMethod(method);
            captureInstances(method.getDeclaringClass());
            Logs.i("hook 奖励回调 " + key + " [" + rule.sdk + "]");
            return true;
        } catch (Throwable t) {
            Logs.w("hook 失败 " + key + " : " + t);
            return false;
        }
    }

    /**
     * 原生奖励回调额外补发 (倍率-1) 次：用注入标记绕过本 hook，
     * 不再走去重/记账，让 App 自己的奖励逻辑多跑几次。
     */
    /**
     * 原生奖励回调额外补发 (倍率-1) 次，使总调用次数正好等于倍率。
     *
     * 注意：这里只补发「额外」次数，因为 chain.proceed() 已经产出了第 1 次。
     */
    private static void multiplyNaturalReward(Object self, Method method,
                                              Object[] fixed, Object[] original) {
        int times = Config.rewardMultiplier();
        if (times <= 1 || self == null || method == null) return;
        Object[] args = fixed != null ? fixed : original;
        RewardLedger.beginInject();
        try {
            for (int i = 1; i < times; i++) {
                try {
                    method.invoke(self, args);
                } catch (Throwable t) {
                    Logs.w("奖励倍率额外回调失败: " + t);
                }
            }
        } finally {
            RewardLedger.endInject();
        }
    }

    /** 展示回调触发补发前，至少等这么久，让真实奖励回调先到手。 */
    private static final long MIN_SHOW_WAIT_MS = 1500L;

    /** 该规则对应的奖励方法是否已经收到过真实回调（说明奖励已经由 SDK 发了）。 */
    private boolean hasRealRewardArgs(Rules.Rule rule, Object self) {
        if (self == null) return false;
        String name = rule.primaryRewardName();
        if (name == null) return false;
        for (Method m : methodsFor(self.getClass(), name)) {
            if (sRealRewardArgs.containsKey(keyOf(m))) return true;
        }
        return false;
    }

    /** 关闭回调优先级：数字越小越先调用。 */
    private static int closeRank(String name) {
        if (name.contains("onAdClose") || name.contains("onADClose")) return 0;
        if (name.contains("onVideoComplete") || name.contains("onVideoEnd")) return 1;
        if (name.contains("onSkipped") || name.contains("onAdDismiss")) return 2;
        return 3;
    }

    private static boolean isCloseMethod(String key) {
        return key.contains("onAdClose") || key.contains("onADClose")
                || key.contains("onSkippedVideo") || key.contains("onVideoComplete")
                || key.contains("onAdDismiss") || key.contains("onRewardVerify");
    }

    private boolean installTrigger(final Rules.Rule rule, final Method method) {
        final String key = keyOf(method);
        if (hooked.containsKey(key)) return false;
        final boolean start = isStartMethod(key);
        // 这个游戏的 onAdClose 会读取 onRewardVerify 写下的奖励标志，
        // 必须在关闭前先把标志置 true，否则 JS 收到 onClose(reward=false)。
        final boolean liquidClose = "LIQUID".equals(rule.sdk) && key.contains("onAdClose");
        // 记下可以作为"结束"触发的方法，自动关闭广告时用它替 SDK 收尾
        if (isCloseMethod(key) && method.getParameterTypes().length == 0) {
            synchronized (sCloseMethods) {
                java.util.List<Method> list = sCloseMethods.get(method.getDeclaringClass());
                if (list == null) {
                    list = new java.util.ArrayList<>(4);
                    sCloseMethods.put(method.getDeclaringClass(), list);
                }
                boolean dup = false;
                for (Method old : list) {
                    if (old.getName().equals(method.getName())) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    list.add(method);
                    // 关闭前优先走 onAdClose：App 的奖励上报挂在这条上，
                    // onVideoComplete 往往只在特定配置下才转调 onAdClose。
                    java.util.Collections.sort(list, new java.util.Comparator<Method>() {
                        @Override
                        public int compare(Method a, Method b) {
                            return closeRank(a.getName()) - closeRank(b.getName());
                        }
                    });
                }
            }
        }
        try {
            xi.hook(method)
                    .setPriority(PRIORITY)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result;
                            if (Logs.isEnabled()) Logs.d("触发回调: " + key);
                            final Object self = chain.getThisObject();
                            if (self != null) {
                                sLastAdTargetRef = new java.lang.ref.WeakReference<>(self);
                                sLastAdAt = System.currentTimeMillis();
                            }
                            // 触发方法里的真实参数（placement id / ATAdInfo 等）留给补发复用
                            final Object[] triggerArgs = toArray(chain.getArgs());
                            if (start) {
                                sLastShowAt = System.currentTimeMillis();
                                // 新一轮广告展示：让上一轮的去重记账过期
                                final int session = RewardLedger.beginSession(self);
                                // 展示类回调：按设置决定补发时机，默认立即（和 1.0 一致）
                                int baseDelay = Config.rewardDelayMs();
                                // 百度在广告页还开着时补发会触发 App 原生崩溃，
                                // 自动关广告开启时等到关闭之后再补。
                                if ("BAIDU".equals(rule.sdk) && Config.autoClose()) {
                                    baseDelay = Math.max(baseDelay, Config.autoCloseDelayMs() + 800);
                                }
                                final long delay = Math.max((long) baseDelay, MIN_SHOW_WAIT_MS);
                                if (Logs.isEnabled()) {
                                    Logs.d("展示类回调，等 " + delay + "ms 真实奖励回调再决定补发: " + key);
                                }
                                mainHandler().postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!RewardLedger.isSession(self, session)) {
                                            if (Logs.isEnabled()) Logs.d("跳过过期补发: " + key);
                                            return;
                                        }
                                        // 真实奖励回调已经来过（正常看完/SDK 自己发了）：
                                        // 不再补发，避免重复发奖，也避免用构造参数去碰 App 的字段。
                                        if (hasRealRewardArgs(rule, self)) {
                                            if (Logs.isEnabled()) {
                                                Logs.d("真实奖励回调已发生，跳过展示补发: " + key);
                                            }
                                            return;
                                        }
                                        deliver(rule, self, triggerArgs);
                                    }
                                }, delay);
                                return chain.proceed();
                            }
                            if (liquidClose) {
                                setLiquidRewardFlag(self);
                                return chain.proceed();
                            }
                            // 先补发再放行：App 的 onAdClose 会检查奖励标志，
                            // 如果等 proceed 之后才补，标志置得太晚，奖励不会到账。
                            deliver(rule, self, triggerArgs);
                            return chain.proceed();
                        }
                    });
            hooked.put(key, Boolean.TRUE);
            captureInstances(method.getDeclaringClass());
            Logs.i("hook 结束回调 " + key + " [" + rule.sdk + "]");
            return true;
        } catch (Throwable t) {
            Logs.w("hook 失败 " + key + " : " + t);
            return false;
        }
    }

    /** 关闭前把游戏内部的奖励标志置 true（调用 onRewardVerify(true,1,1,"")）。 */
    private void setLiquidRewardFlag(Object self) {
        if (self == null) return;
        try {
            Method target = null;
            for (Method candidate : self.getClass().getMethods()) {
                if (matchesRewardVerify(candidate)) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) {
                for (Method candidate : self.getClass().getDeclaredMethods()) {
                    if (matchesRewardVerify(candidate)) {
                        target = candidate;
                        break;
                    }
                }
            }
            if (target == null) return;
            target.setAccessible(true);
            target.invoke(self, true, 1, 1, "");
        } catch (Throwable t) {
            Logs.w("设置 LIQUID 奖励标志失败: " + t);
        }
    }

    private static boolean matchesRewardVerify(Method method) {
        if (!"onRewardVerify".equals(method.getName())) return false;
        Class<?>[] types = method.getParameterTypes();
        return types.length == 4
                && types[0] == boolean.class
                && types[1] == int.class
                && types[2] == int.class
                && types[3] == String.class;
    }

    /**
     * 百度这条链在自动关广告后不会触发 App 的 onAdClose，JS 收不到 onClose(true)。
     * 从适配器对象里取出 Navigate → getAdInteractionListener()，补一次 onAdClose。
     */
    private void closeLiquidListener(Object self) {
        if (self == null) return;
        try {
            for (java.lang.reflect.Field field : self.getClass().getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                }
                Object holder;
                try {
                    holder = field.get(self);
                } catch (Throwable ignored) {
                    continue;
                }
                if (holder == null) continue;
                Method getter = findNoArg(holder.getClass(), "getAdInteractionListener");
                if (getter == null) continue;
                Object listener = getter.invoke(holder);
                if (listener == null) continue;
                Method close = findNoArg(listener.getClass(), "onAdClose");
                if (close == null) return;
                close.setAccessible(true);
                close.invoke(listener);
                Logs.i("补调 onAdClose -> " + listener.getClass().getName() + " [LIQUID]");
                return;
            }
        } catch (Throwable t) {
            Logs.w("补调 onAdClose 失败: " + t);
        }
    }

    private static Method findNoArg(Class<?> clazz, String name) {
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == 0) {
                return method;
            }
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == 0) {
                return method;
            }
        }
        return null;
    }

    /** 广告被跳过/关闭后，主动调一次奖励回调。 */
    private void deliver(Rules.Rule rule, Object self, Object[] triggerArgs) {
        if (self == null || !rule.deliverOnTrigger || !Config.injectOnSkip()) return;
        if (Logs.isEnabled()) {
            Logs.d("准备补发: " + rule.sdk + " 实例=" + RewardLedger.describe(self));
        }
        String rewardName = rule.primaryRewardName();
        if (rewardName == null) return;

        List<Method> candidates = methodsFor(self.getClass(), rewardName);
        if (candidates.isEmpty()) {
            Logs.w("找不到补发用的奖励回调 " + rewardName + " @ " + self.getClass().getName());
            return;
        }

        // 同名重载里挑一个参数真能构造出来的。
        // 以前只看 canProvide()（它认为「能用 Unsafe 造空壳」就算可提供），
        // 但实际构造可能返回 null —— 比如 anythink 的 ATAdInfo，
        // 目标 App 的 onReward(null) 会直接 NPE（getRequestId() on null），
        // 奖励发不出去。这里改成：先把参数真的造一遍，有 null 就换下一个候选。
        Method target = null;
        Object[] builtArgs = null;
        String firstBad = null;
        for (Method method : candidates) {
            Class<?>[] types = method.getParameterTypes();
            if (!allProvideable(types)) {
                if (firstBad == null) firstBad = badParam(types);
                continue;
            }
            // 优先复用真实回调参数，其次才构造
            Object[] cache = sRealRewardArgs.get(keyOf(method));
            Object[] probe;
            if (cache != null && cache.length == types.length) {
                probe = cache.clone();
            } else {
                probe = ArgFixer.buildRewardArgs(types, triggerArgs);
            }
            if (probe == null) continue;
            boolean hasNull = false;
            for (int i = 0; i < probe.length; i++) {
                if (probe[i] == null && !types[i].isPrimitive()) {
                    hasNull = true;
                    break;
                }
            }
            if (hasNull) {
                if (firstBad == null) {
                    firstBad = method.getName() + " 的参数无法构造非空实例";
                }
                continue;
            }
            target = method;
            builtArgs = probe;
            break;
        }
        if (target == null) {
            Logs.w("跳过补发：参数 " + firstBad + " 无法安全构造 [" + rule.sdk + "]");
            return;
        }

        String methodKey = keyOf(target);
        boolean synthetic = ArgFixer.anyNeedsSynthesis(target.getParameterTypes());
        Object[] cached = sRealRewardArgs.get(methodKey);
        Object[] args;
        if (cached != null && cached.length == target.getParameterTypes().length) {
            args = cached.clone();
            synthetic = false;
            if (Logs.isEnabled()) {
                Logs.d("补发复用真实参数: " + rewardName + " [" + rule.sdk + "]");
            }
        } else {
            // 直接用上面探测通过的那份，别再造一次（可能又造出 null）
            args = builtArgs;
        }
        if (!RewardLedger.tryInject(self, rule.sdk, methodKey, synthetic)) return;
        if (synthetic && Logs.isEnabled()) {
            Logs.d("补发使用构造参数，真实回调不吞: " + rewardName + " [" + rule.sdk + "]");
        }

        RewardLedger.beginInject();
        try {
            if ("BAIDU".equals(rule.sdk)) {
                boolean ok = deliverBaidu(self);
                if (ok) {
                    Logs.i("已补发奖励 Baidu onReward+onAdClose -> "
                            + RewardLedger.describe(self) + " [BAIDU]");
                    if (Config.toast()) Toaster.show("已补发奖励 · BAIDU");
                } else {
                    RewardLedger.forget(self, rule.sdk, methodKey);
                    Logs.w("百度补偿失败: " + rewardName);
                }
                return;
            }
            target.setAccessible(true);
            int times = Math.max(1, Config.rewardMultiplier());
            for (int i = 0; i < times; i++) {
                target.invoke(self, args);
            }
            Logs.i("已补发奖励" + (times > 1 ? (" x" + times) : "") + " " + rewardName
                    + " -> " + RewardLedger.describe(self) + " [" + rule.sdk + "]");
            if (Config.toast()) Toaster.show("已补发奖励 · " + rule.sdk);
        } catch (InvocationTargetException ite) {
            // 目标 App 自己的回调抛异常：保留记账，避免重试造成重复发奖
            Logs.w("补发奖励时目标回调抛异常 " + rewardName + " : " + ite.getCause());
        } catch (Throwable t) {
            // 我们这边没调起来（参数类型不匹配等）：撤销记账，允许下一次 trigger 再试
            RewardLedger.forget(self, rule.sdk, methodKey);
            Logs.w("补发奖励失败 " + rewardName + " : " + t);
        } finally {
            RewardLedger.endInject();
        }
    }

    /**
     * 百度：直接调用 App 的 rewardVideoAdListener.onReward + onAdClose，
     * 绕过 SDK 的 onRewardVerify，避免广告页还开着时触发原生崩溃。
     */
    private boolean deliverBaidu(Object self) {
        try {
            Object wrapper = field(self, "this$0");
            if (wrapper == null) return false;
            Object listener = field(wrapper, "rewardVideoAdListener");
            if (listener == null) return false;
            Method onReward = null;
            Method onClose = null;
            for (Method method : listener.getClass().getMethods()) {
                if ("onReward".equals(method.getName()) && method.getParameterTypes().length == 1) {
                    onReward = method;
                } else if ("onAdClose".equals(method.getName())
                        && method.getParameterTypes().length == 0) {
                    onClose = method;
                }
            }
            if (onReward == null || onClose == null) return false;
            Object[] args = ArgFixer.buildRewardArgs(onReward.getParameterTypes(), new Object[0]);
            if (args == null) return false;
            onReward.setAccessible(true);
            onClose.setAccessible(true);
            // 直接调 listener，不存在前置的原生回调，所以循环次数就是总次数
            int times = Math.max(1, Config.rewardMultiplier());
            for (int i = 0; i < times; i++) {
                onReward.invoke(listener, args);
            }
            onClose.invoke(listener);
            Logs.i("百度补偿 onReward" + (times > 1 ? (" x" + times) : "") + " + onAdClose -> "
                    + listener.getClass().getName());
            return true;
        } catch (Throwable t) {
            Logs.w("百度补偿失败: " + t);
            return false;
        }
    }

    private static Object field(Object obj, String name) {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private List<Method> methodsFor(Class<?> clazz, String name) {
        Map<String, List<Method>> methods = classMethods.get(clazz.getName());
        List<Method> list = methods == null ? null : methods.get(name);
        if (list == null || list.isEmpty()) {
            list = findMethodsByName(clazz, name);
        }
        return list;
    }

    private static boolean allProvideable(Class<?>[] types) {
        for (Class<?> type : types) {
            if (!ArgFixer.canProvide(type)) return false;
        }
        return true;
    }

    private static String badParam(Class<?>[] types) {
        for (Class<?> type : types) {
            if (!ArgFixer.canProvide(type)) return type.getName();
        }
        return "?";
    }

    private static List<Method> findMethodsByName(Class<?> clazz, String name) {
        List<Method> out = new ArrayList<>();
        Class<?> current = clazz;
        int depth = 0;
        while (current != null && current != Object.class && depth++ < 8) {
            try {
                for (Method method : current.getDeclaredMethods()) {
                    if (!method.getName().equals(name)) continue;
                    if (Modifier.isAbstract(method.getModifiers())) continue;
                    if (method.isSynthetic() || method.isBridge()) continue;
                    if (hasOverload(out, method)) continue;
                    try {
                        method.setAccessible(true);
                    } catch (Throwable ignored) {
                    }
                    out.add(method);
                }
            } catch (Throwable ignored) {
            }
            current = current.getSuperclass();
        }
        return out;
    }

    /** dex 扫描没找到实现类时的兜底：直接 hook 接口里带默认实现的方法。 */
    private int hookInterfaceFallback(Rules.Rule rule, ClassLoader loader) {
        try {
            String name = ImplFinder.toName(rule.targetDesc);
            if (name == null) return 0;
            Class<?> clazz = Class.forName(name, false, loader);
            int hits = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (Modifier.isAbstract(method.getModifiers())) continue;
                String methodName = method.getName();
                if (rule.isReward(methodName) && installReward(rule, method)) hits++;
                else if (rule.isTrigger(methodName) && installTrigger(rule, method)) hits++;
            }
            if (hits > 0) {
                Logs.i("兜底 hook 接口 " + name + " 命中 " + hits + " 个方法 [" + rule.sdk + "]");
            }
            return hits;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean isStartMethod(String key) {
        int hash = key.indexOf('#');
        int paren = key.indexOf('(');
        String name = hash >= 0 && paren > hash ? key.substring(hash + 1, paren) : key;
        return name.contains("Show") || name.contains("PlayStart");
    }

    private static Handler mainHandler() {
        if (sMain == null) {
            sMain = new Handler(Looper.getMainLooper());
        }
        return sMain;
    }

    private static Object[] toArray(List<Object> list) {
        return list == null ? null : list.toArray();
    }

    private static String keyOf(Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getDeclaringClass().getName())
                .append('#')
                .append(method.getName())
                .append('(');
        for (Class<?> type : method.getParameterTypes()) {
            sb.append(type.getName()).append(',');
        }
        return sb.append(')').toString();
    }
}
