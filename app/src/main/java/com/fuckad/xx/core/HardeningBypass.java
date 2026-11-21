package com.fuckad.xx.core;

import android.content.pm.ApplicationInfo;

import com.fuckad.xx.Logs;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 加固应用的注入对抗。
 *
 * <p>360 加固（libjiagu）、梆梆、爱加密这类壳会在进程启动时检查运行环境，
 * 发现 Xposed 就直接退出进程，现象是「App 打不开、也没有崩溃堆栈」。</p>
 *
 * <p>它的检测分两层：Java 层（查类、查已装包、看堆栈）和 native 层
 * （直接读 /proc/self/maps）。这里只处理 Java 层——native 层的系统调用
 * 拦不住，不要指望全套都能躲。</p>
 *
 * <p>为避免影响正常应用，只在检测到加固特征时才启用。</p>
 */
public final class HardeningBypass {

    /** 加固类型。识别出来后按类型选对应策略。 */
    public enum Hardener {
        NONE("无"),
        JIAGU("360加固"),
        BANGCLE("梆梆"),
        IJIAMI("爱加密"),
        NETEASE("网易易盾"),
        ALI("阿里聚安全"),
        UNKNOWN("未知加固");

        final String label;
        Hardener(String label) { this.label = label; }
    }

    /** 特征文件 -> 加固类型。 */
    private static final String[][] MARKERS = {
            {".jiagu", "JIAGU"},
            {"libjiagu.so", "JIAGU"},
            {"libjiagu_64.so", "JIAGU"},
            {"libjiagu_a64.so", "JIAGU"},
            {"libDexHelper.so", "BANGCLE"},
            {"libSecShell.so", "BANGCLE"},
            {"libexec.so", "IJIAMI"},
            {"libexecmain.so", "IJIAMI"},
            {"libnesec.so", "NETEASE"},
            {"libmobisec.so", "ALI"},
    };

    /** 需要从视图里隐藏的包前缀（Xposed 框架、本模块自己）。 */
    private static final String[] HIDDEN_PREFIXES = {
            "de.robv.android.xposed",
            "org.lsposed",
            "com.TunNet.n520",
    };

    private HardeningBypass() {
    }

    /** 识别这个应用用的是什么加固。 */
    public static Hardener detectHardener(ApplicationInfo info) {
        if (info == null || info.dataDir == null) return Hardener.NONE;
        String[] found = collectMarkers(info);
        if (found.length == 0) return Hardener.NONE;
        for (String[] pair : MARKERS) {
            for (String f : found) {
                if (f.contains(pair[0])) {
                    try {
                        return Hardener.valueOf(pair[1]);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        // 没匹配到任何已知特征 —— 这就是普通应用。
        // 以前这里返回 UNKNOWN，而 dataDir 里总会有文件，
        // 结果「所有 App 都被当成未知加固」，白白加载了 maps 拦截库。
        return Hardener.NONE;
    }

    /** 收集 dataDir / lib 目录下的文件名。 */
    private static String[] collectMarkers(ApplicationInfo info) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            File dir = new File(info.dataDir);
            String[] names = dir.list();
            if (names != null) java.util.Collections.addAll(out, names);
            if (info.nativeLibraryDir != null) {
                String[] libs = new File(info.nativeLibraryDir).list();
                if (libs != null) java.util.Collections.addAll(out, libs);
            }
        } catch (Throwable ignored) {
        }
        return out.toArray(new String[0]);
    }

    /**
     * OPPO 云服务（com.heytap.cloud）适配。
     *
     * <p>它的 Application 启动时调用 {@code ir.e.d()}，其中
     * {@code wm.s.f35944b.w()} 判断是否 OPPO 设备；只有返回 true 才会
     * 反射调用 {@code com.oplus.content.OplusFeatureConfigManager}，
     * 而该类在非 OPPO 设备上不存在，直接抛 NoClassDefFoundError 导致启动崩溃。</p>
     *
     * <p>把 {@code w()} 钩成 false，它就会走 {@code hasSystemFeature}
     * 的兜底分支，不碰 OPPO 专有类。</p>
     *
     * <p>时机要求高：{@code wm.s} 在 Application 创建前尚未加载，
     * 所以改在 {@code ClassLoader.loadClass} 上等它，一加载立刻挂钩。</p>
     */
    private static final java.util.concurrent.atomic.AtomicBoolean OPLUS_HOOKING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static int hookOplusDeviceCheck(final XposedInterface xi, final ClassLoader loader) {
        try {
            Class<?> cl = Class.forName("java.lang.ClassLoader", false, loader);
            Method m = cl.getDeclaredMethod("loadClass", String.class, boolean.class);
            m.setAccessible(true);
            xi.hook(m).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    java.util.List<Object> a = chain.getArgs();
                    String name = (a == null || a.isEmpty()) ? null : String.valueOf(a.get(0));
                    if (name != null && name.equals("wm.s")
                            && !OPLUS_HOOKING.getAndSet(true)) {
                        try {
                            Class<?> cls = Class.forName("wm.s", false, loader);
                            Method w = cls.getDeclaredMethod("w");
                            w.setAccessible(true);
                            xi.hook(w).intercept(new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain c2) throws Throwable {
                                    Logs.raw("Oplus适配: wm.s.w() -> false（跳过 OPPO 专有类）");
                                    return false;
                                }
                            });
                            Logs.raw("Oplus适配: wm.s.w() 已挂钩");
                        } catch (Throwable t) {
                            Logs.raw("Oplus适配: hook wm.s.w() 失败: " + t);
                        } finally {
                            OPLUS_HOOKING.set(false);
                        }
                    }
                    return chain.proceed();
                }
            });
            Logs.raw("Oplus适配: 已挂载 wm.s 加载监听");
            return 1;
        } catch (Throwable t) {
            Logs.raw("Oplus适配 安装失败: " + t);
            return 0;
        }
    }

    /**
     * 尽早版：只装 native 的 maps 拦截，不碰 Java hook。
     *
     * <p>加固壳在进程启动的头几十毫秒就会读 maps 做环境检查，
     * 走 onPackageReady 太晚（实测进程 84ms 就被杀）。这里在
     * onPackageLoaded 阶段先把 fake maps 和 .so 装好。</p>
     */
    /**
     * 拦 Java 层的自杀调用。
     *
     * <p>实测壳的自毁走的是 SIGKILL（signal 9），native 层拦不住；
     * 但 SIGKILL 可能是它先调 {@code Process.killProcess(myPid())} 或
     * {@code System.exit()} 造成的，这两个都在 Java 层，可以拦。</p>
     */
    public static int hookKillProcess(final XposedInterface xi, final ClassLoader loader) {
        int n = 0;
        try {
            Class<?> proc = Class.forName("android.os.Process", false, loader);
            for (final Method m : proc.getDeclaredMethods()) {
                if (!"killProcess".equals(m.getName())) continue;
                if (m.getParameterCount() != 1) continue;
                try {
                    m.setAccessible(true);
                    xi.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            List<Object> a = chain.getArgs();
                            Logs.raw("拦截 Process.killProcess(" + (a == null || a.isEmpty() ? "?" : a.get(0)) + ")");
                            return null;
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Logs.raw("hook killProcess 失败: " + t);
        }
        try {
            Method exit = System.class.getDeclaredMethod("exit", int.class);
            exit.setAccessible(true);
            xi.hook(exit).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    List<Object> a = chain.getArgs();
                    Logs.raw("拦截 System.exit(" + (a == null || a.isEmpty() ? "?" : a.get(0)) + ")");
                    return null;
                }
            });
            n++;
        } catch (Throwable ignored) {
        }
        return n;
    }

    public static int installEarly(android.content.pm.ApplicationInfo info) {
        if (!com.fuckad.xx.Config.riskBypass()) return 0;
        if (detectHardener(info) == Hardener.NONE) return 0;
        int n = installMapsHookAt(info);
        Logs.raw("早期 maps 拦截: " + (n > 0 ? "已启用" : "未启用"));
        return n;
    }

    /** 用 ApplicationInfo.dataDir 拼路径，不依赖 Context。 */
    private static int installMapsHookAt(android.content.pm.ApplicationInfo info) {
        try {
            if (info == null || info.dataDir == null) return 0;
            File cache = new File(info.dataDir, "cache");
            if (!cache.isDirectory() && !cache.mkdirs()) return 0;
            File out = new File(cache, "tunnet_maps.txt");
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/maps"));
                 java.io.BufferedWriter w = new java.io.BufferedWriter(
                    new java.io.FileWriter(out))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String low = line.toLowerCase();
                    if (low.contains("xposed") || low.contains("lsposed")
                            || low.contains("dexposed") || low.contains("tunnet")
                            || low.contains("edxposed")) continue;
                    w.write(line); w.newLine();
                }
            }
            android.system.Os.setenv("TUNNET_MAPS_PATH", out.getAbsolutePath(), true);
            File so = new File(cache, "libtunnetmaps.so");
            if (!so.exists() || so.length() == 0) {
                // 模块的 dex 是内存加载的，早期拿不到模块 APK 路径；
                // 退而用 su 从模块 APK 或已部署位置取一份。
                String moduleApk = findModuleApk();
                if (moduleApk == null) {
                    moduleApk = findViaSu(so);
                }
                if (moduleApk == null) {
                    Logs.raw("早期拦截: 找不到模块 APK 路径");
                    return 0;
                }
                if (so.exists() && so.length() > 0) {
                    // su 已经把它放好
                } else try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(moduleApk)) {
                    java.util.zip.ZipEntry e = zip.getEntry("lib/arm64-v8a/libtunnetmaps.so");
                    if (e == null) return 0;
                    try (java.io.InputStream in = zip.getInputStream(e);
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(so)) {
                        byte[] buf = new byte[8192]; int len;
                        while ((len = in.read(buf)) > 0) fos.write(buf, 0, len);
                    }
                }
            }
            System.load(so.getAbsolutePath());
            return 1;
        } catch (Throwable t) {
            Logs.raw("早期 maps 拦截失败: " + t);
            return 0;
        }
    }

    /** 用 su 直接把 .so 从模块 APK 拷进目标 App 的 cache。 */
    private static String findViaSu(File dest) {
        try {
            String[] candidates = {
                    "/data/app/~~*/*com.TunNet.n520*/base.apk",
            };
            String cmd = "su -c 'for f in /data/app/*/*TunNet*/base.apk; do "
                    + "unzip -p \"$f\" lib/arm64-v8a/libtunnetmaps.so > " + dest.getAbsolutePath()
                    + " 2>/dev/null && break; done'";
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            p.waitFor();
            if (dest.exists() && dest.length() > 0) {
                Logs.raw("早期拦截: su 部署 so 成功");
                return dest.getAbsolutePath();
            }
        } catch (Throwable t) {
            Logs.raw("早期拦截: su 部署失败 " + t);
        }
        return null;
    }

    /** 从 PackageManager 全局拿模块 APK 路径（早期没有 Context 时用）。 */
    private static String findModuleApk() {
        // 1) 首选：本类的 CodeSource —— 模块的 ClassLoader 指向模块 APK
        try {
            java.security.CodeSource cs =
                    HardeningBypass.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                String p = cs.getLocation().getPath();
                if (p != null && p.endsWith(".apk")) return p;
            }
        } catch (Throwable ignored) {
        }
        // 2) 退路：ActivityThread.currentApplication()
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method cur = at.getDeclaredMethod("currentApplication");
            cur.setAccessible(true);
            Object app = cur.invoke(null);
            if (app == null) return null;
            Method pm = app.getClass().getMethod("getPackageManager");
            Object mgr = pm.invoke(app);
            Method gai = mgr.getClass().getMethod("getApplicationInfo", String.class, int.class);
            Object ai = gai.invoke(mgr, "com.TunNet.n520", 0);
            java.lang.reflect.Field f = ai.getClass().getField("sourceDir");
            return (String) f.get(ai);
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static int install(final XposedInterface xi, final ClassLoader loader,
                              ApplicationInfo info) {
        // 前提：用户在模块里打开了「绕过检测」
        if (!com.fuckad.xx.Config.riskBypass()) {
            return 0;
        }
        Hardener h = detectHardener(info);
        if (h == Hardener.NONE) return 0;
        Logs.raw("检测到 " + h.label + "，自动启用对应绕过策略");
        int n = 0;
        // 所有加固都要挡的：查类、查包、看堆栈
        n += hookClassLoader(xi);
        n += hookPackageQuery(xi);
        n += hookStackTrace(xi);
        n += hookSystemProps(xi);
        // 按类型追加：这几个壳重点读 maps
        if (h == Hardener.JIAGU || h == Hardener.BANGCLE
                || h == Hardener.IJIAMI || h == Hardener.UNKNOWN) {
            n += hookFileRead(xi);
            n += installMapsHook();
        }
        Logs.raw("加固对抗 hook 数=" + n + "（类型=" + h.label + "）");
        return n;
    }

    /** 查 Xposed 相关类时抛 ClassNotFoundException。 */
    private static int hookClassLoader(final XposedInterface xi) {
        try {
            Method m = ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class);
            m.setAccessible(true);
            xi.hook(m).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    List<Object> args = chain.getArgs();
                    String name = (args == null || args.isEmpty()) ? null : String.valueOf(args.get(0));
                    if (name != null && shouldHide(name) && !calledFromSelf()) {
                        throw new ClassNotFoundException(name);
                    }
                    return chain.proceed();
                }
            });
            return 1;
        } catch (Throwable t) {
            Logs.raw("加固对抗: hook loadClass 失败 " + t);
            return 0;
        }
    }

    /** 已安装列表 / 包信息里隐藏模块自己。 */
    private static int hookPackageQuery(final XposedInterface xi) {
        int n = 0;
        try {
            Class<?> pm = Class.forName("android.app.ApplicationPackageManager");
            for (final Method m : pm.getDeclaredMethods()) {
                if (!"getInstalledPackages".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 1) continue;
                try {
                    m.setAccessible(true);
                    xi.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            if (!(r instanceof List) || calledFromSelf()) return r;
                            @SuppressWarnings("unchecked")
                            List<Object> list = (List<Object>) r;
                            for (int i = list.size() - 1; i >= 0; i--) {
                                Object pi = list.get(i);
                                if (pi == null) continue;
                                String pkg = readPackageName(pi);
                                if (pkg != null && shouldHide(pkg)) list.remove(i);
                            }
                            return r;
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Logs.raw("加固对抗: hook 包查询失败 " + t);
        }
        return n > 0 ? 1 : 0;
    }

    /** 异常堆栈里去掉 Xposed 相关的帧。 */
    private static int hookStackTrace(final XposedInterface xi) {
        try {
            Method m = Throwable.class.getDeclaredMethod("getOurStackTrace");
            m.setAccessible(true);
            xi.hook(m).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object r = chain.proceed();
                    if (!(r instanceof StackTraceElement[])) return r;
                    StackTraceElement[] all = (StackTraceElement[]) r;
                    java.util.List<StackTraceElement> keep = new java.util.ArrayList<>(all.length);
                    for (StackTraceElement e : all) {
                        String cn = e.getClassName();
                        if (cn != null && shouldHide(cn)) continue;
                        keep.add(e);
                    }
                    if (keep.size() == all.length) return all;
                    return keep.toArray(new StackTraceElement[0]);
                }
            });
            return 1;
        } catch (Throwable t) {
            Logs.raw("加固对抗: hook 堆栈失败 " + t);
            return 0;
        }
    }

    /** 读 /proc/self/maps 时把框架相关的行藏掉（只挡 Java 层读取）。 */
    private static int hookFileRead(final XposedInterface xi) {
        try {
            Method m = Class.forName("java.io.FileInputStream")
                    .getDeclaredMethod("readBytes", byte[].class, int.class, int.class);
            m.setAccessible(true);
            xi.hook(m).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object r = chain.proceed();
                    if (calledFromSelf()) return r;
                    List<Object> args = chain.getArgs();
                    if (args == null || args.size() < 1 || !(args.get(0) instanceof byte[])) return r;
                    byte[] buf = (byte[]) args.get(0);
                    if (buf.length == 0) return r;
                    String text = new String(buf, "UTF-8");
                    if (!text.contains("xposed") && !text.contains("lsposed")
                            && !text.contains("TunNet")) {
                        return r;
                    }
                    String filtered = text.replaceAll("(?m)^.*(xposed|Xposed|lsposed|LSPosed|TunNet).*\\n?", "");
                    byte[] out = filtered.getBytes("UTF-8");
                    System.arraycopy(out, 0, buf, 0, Math.min(out.length, buf.length));
                    for (int i = out.length; i < buf.length; i++) buf[i] = 0;
                    Logs.raw("加固对抗: 过滤 maps 读取");
                    return r;
                }
            });
            return 1;
        } catch (Throwable t) {
            Logs.raw("加固对抗: hook 文件读取失败（不影响其他手段）");
            return 0;
        }
    }

    /** 属性里藏掉框架痕迹。 */
    private static int hookSystemProps(final XposedInterface xi) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Method get = props.getDeclaredMethod("get", String.class, String.class);
            get.setAccessible(true);
            xi.hook(get).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    List<Object> args = chain.getArgs();
                    String key = (args == null || args.isEmpty()) ? null : String.valueOf(args.get(0));
                    if (key != null && (key.contains("xposed") || key.contains("lsposed"))) {
                        return "";
                    }
                    return chain.proceed();
                }
            });
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 装 native 的 maps 拦截库。
     *
     * <p>libjiagu 在 native 层直接 fopen("/proc/self/maps") 读内存布局，
     * Java hook 完全够不着。这里用一个很小的 .so 覆盖 fopen 符号：
     * 路径是 maps 时改读我们预先净化好的副本，壳照常能解析，但看不到
     * xposed / lsposed / 模块相关的行。</p>
     */
    private static int installMapsHook() {
        try {
            android.content.Context ctx = AppCtx.get();
            if (ctx == null) return 0;
            File out = new File(ctx.getCacheDir(), "tunnet_maps.txt");
            // 生成净化副本
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/maps"));
                 java.io.BufferedWriter w = new java.io.BufferedWriter(
                    new java.io.FileWriter(out))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String low = line.toLowerCase();
                    if (low.contains("xposed") || low.contains("lsposed")
                            || low.contains("dexposed") || low.contains("tunnet")
                            || low.contains("edxposed")) {
                        continue;
                    }
                    w.write(line);
                    w.newLine();
                }
            }
            android.system.Os.setenv("TUNNET_MAPS_PATH", out.getAbsolutePath(), true);
            File soFile = new File(ctx.getCacheDir(), "libtunnetmaps.so");
            if (!soFile.exists() || soFile.length() == 0) {
                String moduleApk = ctx.getPackageManager()
                        .getApplicationInfo("com.TunNet.n520", 0).sourceDir;
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(moduleApk)) {
                    java.util.zip.ZipEntry entry = zip.getEntry("lib/arm64-v8a/libtunnetmaps.so");
                    if (entry == null) { Logs.raw("模块APK内无 libtunnetmaps.so"); return 0; }
                    try (java.io.InputStream in = zip.getInputStream(entry);
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(soFile)) {
                        byte[] buf = new byte[8192]; int len;
                        while ((len = in.read(buf)) > 0) fos.write(buf, 0, len);
                    }
                }
            }
            System.load(soFile.getAbsolutePath());
            Logs.raw("maps 拦截库已加载: " + soFile.getAbsolutePath());
            return 1;
        } catch (Throwable t) {
            Logs.raw("maps 拦截库加载失败: " + t);
            return 0;
        }
    }

    private static boolean isProbeCommand(String cmd) {
        String c = cmd.toLowerCase();
        return c.contains("pm list packages")
                || c.contains("pm path")
                || c.contains("/proc/self/maps")
                || c.contains("ps -a")
                || c.contains("su -c");
    }

    private static boolean shouldHide(String name) {
        if (name == null) return false;
        for (String p : HIDDEN_PREFIXES) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }

    /** 模块自己的调用要放行，否则会把自己加载 Xposed API 的路径挡掉。 */
    private static boolean calledFromSelf() {
        try {
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                String cn = e.getClassName();
                if (cn != null && cn.startsWith("com.fuckad.xx")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String readPackageName(Object packageInfo) {
        try {
            java.lang.reflect.Field f = packageInfo.getClass().getField("packageName");
            Object v = f.get(packageInfo);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable ignored) {
        }
        return null;
    }
}