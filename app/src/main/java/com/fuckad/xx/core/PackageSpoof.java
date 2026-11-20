package com.fuckad.xx.core;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import com.fuckad.xx.Logs;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * 安装状态伪造。
 *
 * <p>服务端不采信 App 自己上报的观看数据，只认广告平台（穿山甲/快手）的转化事件。
 * 而广告平台 SDK 判断「装没装」，最终都要落到 {@code PackageManager} 上。</p>
 *
 * <p>所以这里双管齐下：
 * <ol>
 *     <li>从 H5 的 {@code appInstalled(packageName)} 调用里收集目标包名；</li>
 *     <li>hook {@code PackageManager} 的查询方法，对这些包名返回伪造结果。</li>
 * </ol>
 * 这样 App 的 H5 检测和广告 SDK 的安装检测都会认为「已经装了」。</p>
 */
public final class PackageSpoof {

    /** 当前广告要求安装的包名（由 H5 的 appInstalled 调用收集）。 */
    private static final Set<String> TARGETS = ConcurrentHashMap.newKeySet();

    private PackageSpoof() {
    }

    public static int install(final XposedInterface xi, final ClassLoader loader) {
        if (!com.fuckad.xx.Config.debug()) return 0;
        int count = 0;
        count += hookCollector(xi, loader);
        count += hookGetPackageInfo(xi);
        count += hookGetInstalledPackages(xi);
        Logs.raw("PackageSpoof 安装完成，hook 数=" + count + "，已收集包名=" + TARGETS);
        return count;
    }

    /** 从 H5 的 appInstalled 调用里收集目标包名。 */
    private static int hookCollector(final XposedInterface xi, final ClassLoader loader) {
        try {
            Class<?> clazz = Class.forName("com.hexiang.jhfc.ui.webjs.ZsInterface", false, loader);
            for (final Method m : clazz.getDeclaredMethods()) {
                if (!"appInstalled".equals(m.getName())) continue;
                try {
                    m.setAccessible(true);
                    xi.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            try {
                                java.util.List<Object> list = chain.getArgs();
                                if (list != null && !list.isEmpty()) {
                                    String params = String.valueOf(list.get(0));
                                    int idx = params.indexOf("\"packageName\"");
                                    if (idx >= 0) {
                                        int colon = params.indexOf(':', idx);
                                        int q1 = params.indexOf('"', colon);
                                        int q2 = params.indexOf('"', q1 + 1);
                                        if (q1 > 0 && q2 > q1) {
                                            String pkg = params.substring(q1 + 1, q2);
                                            if (pkg.contains(".")) {
                                                TARGETS.add(pkg);
                                                Logs.raw("PackageSpoof 记录目标包名: " + pkg);
                                            }
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        }
                    });
                    return 1;
                } catch (Throwable t) {
                    Logs.raw("PackageSpoof hook appInstalled 失败: " + t);
                }
            }
        } catch (Throwable t) {
            Logs.raw("PackageSpoof 找不到 ZsInterface: " + t);
        }
        return 0;
    }

    /** 让 getPackageInfo 对目标包名返回伪造的 PackageInfo。 */
    private static int hookGetPackageInfo(final XposedInterface xi) {
        try {
            Class<?> pm = Class.forName("android.app.ApplicationPackageManager");
            int n = 0;
            for (final Method m : pm.getDeclaredMethods()) {
                if (!"getPackageInfo".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 2) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                try {
                    m.setAccessible(true);
                    xi.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = null;
                            boolean threw = false;
                            try {
                                r = chain.proceed();
                            } catch (Throwable t) {
                                threw = true;
                            }
                            if (r != null && !threw) return r;
                            // 原方法抛了 NameNotFoundException（没装），看是不是目标包
                            java.util.List<Object> args = chain.getArgs();
                            if (args != null && !args.isEmpty() && args.get(0) instanceof String) {
                                String pkg = (String) args.get(0);
                                boolean want = TARGETS.contains(pkg);
                                if (!want && pkg != null && pkg.contains(".") && calledFromAdSdk()) {
                                    want = true;
                                    Logs.raw("PackageSpoof 广告SDK查询伪造: " + pkg);
                                }
                                if (want) {
                                    return fakeInfo(pkg);
                                }
                            }
                            if (threw) throw new android.content.pm.PackageManager.NameNotFoundException(
                                    String.valueOf(args == null ? "" : args.get(0)));
                            return null;
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
            return n > 0 ? 1 : 0;
        } catch (Throwable t) {
            Logs.raw("PackageSpoof hook getPackageInfo 失败: " + t);
            return 0;
        }
    }

    /** 让 getInstalledPackages 的结果里带上目标包名。 */
    private static int hookGetInstalledPackages(final XposedInterface xi) {
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
                            if (!(r instanceof java.util.List)) return r;
                            @SuppressWarnings("unchecked")
                            java.util.List<PackageInfo> list = (java.util.List<PackageInfo>) r;
                            for (String pkg : TARGETS) {
                                boolean has = false;
                                for (PackageInfo pi : list) {
                                    if (pi != null && pkg.equals(pi.packageName)) {
                                        has = true;
                                        break;
                                    }
                                }
                                if (!has) {
                                    list.add(fakeInfo(pkg));
                                    Logs.raw("PackageSpoof 伪造 getInstalledPackages: " + pkg);
                                }
                            }
                            return list;
                        }
                    });
                    return 1;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Logs.raw("PackageSpoof hook getInstalledPackages 失败: " + t);
        }
        return 0;
    }

    /**
     * 当前调用是不是来自广告 SDK。
     *
     * <p>穿山甲/快手判断「广告里的 App 装没装」时，最终都会查 PackageManager。
     * 只要调用栈里出现广告 SDK 的类，就把「查不到」改写成「装了」，
     * 让 SDK 自己把安装转化上报给它的服务器。</p>
     */
    private static boolean calledFromAdSdk() {
        try {
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                String cn = e.getClassName();
                if (cn.startsWith("com.bytedance.sdk")
                        || cn.startsWith("com.kwad.")
                        || cn.startsWith("com.anythink.")
                        || cn.startsWith("com.qq.e.")
                        || cn.startsWith("com.sigmob.")
                        || cn.startsWith("com.baidu.mobads")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static PackageInfo fakeInfo(String pkg) {
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.versionName = "1.0.0";
        pi.versionCode = 1;
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = pkg;
        ai.flags = ApplicationInfo.FLAG_INSTALLED;
        ai.sourceDir = "/data/app/" + pkg + "/base.apk";
        ai.publicSourceDir = ai.sourceDir;
        pi.applicationInfo = ai;
        return pi;
    }
}
