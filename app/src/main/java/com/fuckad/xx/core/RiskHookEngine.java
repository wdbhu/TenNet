package com.fuckad.xx.core;

import android.content.Context;

import com.fuckad.xx.Logs;

import org.json.JSONArray;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * 环境检测绕过。
 *
 * 针对两类常见风控改写叶子检测方法的返回值：
 * 1) 自研风控 SDK（com.langtaier.gameframework.security.*，云启聚宝仓那一套）：
 *    root / 模拟器 / 云手机 / 抓包 / 代理 / VPN / 软路由 / HOOK / IP 抖动 / 聚集 / 指纹变化 / 多开 / 无障碍 / 调试。
 * 2) 广告与设备指纹 SDK（快手 kwad 环境位、weapon 指纹、Unity 侧 DeviceInfoPlugin）。
 *
 * 全部按"类名 + 方法名 + 参数个数 + 返回类型"定位，类不存在或签名变化时静默跳过，不影响其它 App。
 */
public final class RiskHookEngine {

    private static final int PRIORITY = 50;

    private static final Set<String> sHooked = new HashSet<>();

    private RiskHookEngine() {
    }

    public static int install(XposedInterface xi, ClassLoader loader) {
        int n = 0;
        n += installRiskSdk(xi, loader);
        n += installAdSdk(xi, loader);
        n += installSystemProps(xi, loader);
        n += installUnityPlugin(xi, loader);
        n += installFingerprint(xi, loader);
        n += installThirdPartyRiskSdk(xi, loader);
        return n;
    }

    // ---------------- 自研风控 SDK ----------------

    private static int installRiskSdk(XposedInterface xi, ClassLoader loader) {
        final String pkg = "com.langtaier.gameframework.security.";
        int n = 0;

        // 模拟器：直接短路总分
        n += add(hookInt(xi, loader, pkg + "AdvancedEmulatorDetector", "detectEmulator", 1, 0));
        // 模拟器子项（detectEmulator 内部也会单独调用）
        n += hookPrefixInt(xi, loader, pkg + "AdvancedEmulatorDetector", "check", 0);
        n += hookPrefixInt(xi, loader, pkg + "AdvancedEmulatorDetector", "compute", 0);
        // 云手机：子项全部归零，总分自然为 0
        n += hookPrefixInt(xi, loader, pkg + "AdvancedEmulatorDetector", "checkCloudPhone", 0);

        // HOOK / 脚本外挂
        n += add(hookInt(xi, loader, pkg + "AntiHookDetector", "detectHookRisk", 0, 0));
        n += add(hookBool(xi, loader, pkg + "AntiHookDetector", "isXposedDetected", 0));
        n += add(hookBool(xi, loader, pkg + "AntiHookDetector", "isFridaDetected", 0));
        n += add(hookBool(xi, loader, pkg + "AntiHookDetector", "isOtherHookDetected", 0));

        // 抓包
        n += add(hookInt(xi, loader, pkg + "PacketCaptureDetector", "detectCaptureRisk", 1, 0));
        n += add(hookBool(xi, loader, pkg + "PacketCaptureDetector", "isProxyExist", 0));
        n += add(hookBool(xi, loader, pkg + "PacketCaptureDetector", "isProxyConnectionDetected", 0));
        n += add(hookBool(xi, loader, pkg + "PacketCaptureDetector", "isPacketCaptureAppInstalled", 1));

        // 系统代理
        n += add(hookInt(xi, loader, pkg + "ProxyDetector", "detectProxyRisk", 1, 0));
        n += add(hookBool(xi, loader, pkg + "ProxyDetector", "isSystemProxyEnabled", 0));
        n += add(hookBool(xi, loader, pkg + "ProxyDetector", "hasCaptureProcess", 1));
        n += add(hookBool(xi, loader, pkg + "ProxyDetector", "hasCapturePackage", 1));
        n += add(hookBool(xi, loader, pkg + "ProxyDetector", "hasAbnormalCertificates", 0));

        // VPN
        n += add(hookInt(xi, loader, pkg + "VpnDetector", "detectVpnRisk", 1, 0));
        n += add(hookBool(xi, loader, pkg + "VpnDetector", "isVpnEnabled", 1));
        n += add(hookBool(xi, loader, pkg + "VpnDetector", "hasVpnApps", 1));
        n += add(hookBool(xi, loader, pkg + "VpnDetector", "hasVirtualNetworkInterface", 0));

        // 软路由
        n += add(hookInt(xi, loader, pkg + "SoftRouterDetector", "detectSoftRouter", 1, 0));
        n += add(hookBool(xi, loader, pkg + "SoftRouterDetector", "detectAbnormalGateway", 0));
        n += add(hookBool(xi, loader, pkg + "SoftRouterDetector", "detectAbnormalARP", 0));
        n += add(hookBool(xi, loader, pkg + "SoftRouterDetector", "isPCUserAgent", 0));

        // IP 抖动 / 聚集 / 指纹变化
        n += add(hookInt(xi, loader, pkg + "IpChangeTracker", "getIpChangeRiskScore", 1, 0));
        n += hookPrefixInt(xi, loader, pkg + "CrowdingDetector", "detect", 0);
        n += add(hookInt(xi, loader, pkg + "FingerprintChangeDetector", "detectFingerprintChange", 2, 0));

        // 设备信息采集（DeviceInfoRequest 的原始字段来源）：真实包名在 utils 下
        String info = "com.langtaier.gameframework.utils.DeviceInfoCollector";
        n += add(hookBool(xi, loader, info, "checkRootMethod1", 0));
        n += add(hookBool(xi, loader, info, "checkRootMethod2", 0));
        n += add(hookBool(xi, loader, info, "checkRootMethod3", 0));
        n += add(hookBool(xi, loader, info, "isRooted", 0));
        n += add(hookBool(xi, loader, info, "isEmulator", 0));
        n += add(hookBool(xi, loader, info, "isAppCloned", 0));
        n += add(hookBool(xi, loader, info, "isDebuggable", 0));
        n += add(hookBool(xi, loader, info, "isUsbDebugEnabled", 0));
        n += add(hookBool(xi, loader, info, "isUsingVpn", 0));
        n += add(hookBool(xi, loader, info, "isAccessibilityEnabled", 0));

        // 安装列表：服务端会按应用清单打分，直接给空
        n += hookStringValue(xi, loader, info, "getAppListJson", "[]");
        n += hookStringValue(xi, loader, info, "getInstalledAppsJson", "[]");

        // 黑名单
        n += add(hookNull(xi, loader, pkg + "BlacklistManager", "checkBlacklist", 2));
        n += add(hookNull(xi, loader, pkg + "BlacklistManager", "checkBlacklistByFingerprint", 2));

        if (n > 0) Logs.i("风控 SDK 绕过已安装，数量=" + n);
        return n;
    }

    // ---------------- 广告 / 指纹 SDK ----------------

    private static int installAdSdk(XposedInterface xi, ClassLoader loader) {
        int n = 0;

        // 快手 kwad 环境位：isRoot / isXPosed / isFrameworkHooked / isVirtual / isAdbEnabled / isEmulator / isGroupControl
        for (String setter : new String[]{"cl", "cm", "cn", "cp", "cq", "cr"}) {
            n += add(hookVoidBooleanFalse(xi, loader, "com.kwad.sdk.k.a.d", setter));
        }

        // 快手 weapon 设备指纹：Magisk / mounts 检测、反射 hook 检测
        n += add(hookMethodInt(xi, loader, "com.kuaishou.weapon.p0.af", "a", 0));
        n += add(hookMethodInt(xi, loader, "com.kuaishou.weapon.p0.an", "a", 3));
        // frida / substrate / xposed 扫描结果
        n += add(hookJsonArray(xi, loader, "com.kuaishou.weapon.p0.de", "b"));
        n += add(hookJsonArray(xi, loader, "com.kuaishou.weapon.p0.de", "c"));

        if (n > 0) Logs.i("广告 SDK 环境位已改写，数量=" + n);
        return n;
    }

    private static int installUnityPlugin(XposedInterface xi, ClassLoader loader) {
        int n = 0;
        final String cls = "com.example.unityplugin.DeviceInfoPlugin";
        try {
            Class<?> c = Class.forName(cls, false, loader);
            for (Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() != boolean.class) continue;
                String name = m.getName();
                if (!name.startsWith("check") && !name.startsWith("is")) continue;
                if (hookMethod(xi, m, Boolean.FALSE)) n++;
            }
        } catch (Throwable ignored) {
        }
        if (n > 0) Logs.i("Unity 设备信息插件已改写，数量=" + n);
        return n;
    }

    /** 系统属性出口：native 库读不到的 Java 侧检测基本都走这里。 */
    private static int installSystemProps(XposedInterface xi, ClassLoader loader) {
        int n = 0;
        try {
            Class<?> props = Class.forName("android.os.SystemProperties", false, loader);
            for (java.lang.reflect.Method method : props.getDeclaredMethods()) {
                if (!method.getName().equals("get")) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length != 1 || types[0] != String.class) continue;
                final Method target = method;
                final String key = "android.os.SystemProperties#get/1";
                if (!sHooked.add(key)) break;
                try {
                    xi.hook(target).setPriority(PRIORITY).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            String name = (String) chain.getArg(0);
                            String fake = fakeProp(name);
                            if (fake != null) return fake;
                            return chain.proceed();
                        }
                    });
                    n++;
                    Logs.d("风控 hook " + key + " -> 属性过滤");
                } catch (Throwable ignored) {
                }
                break;
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /** 只拦确定用于检测的属性，其余原样放行。 */
    private static String fakeProp(String name) {
        if (name == null) return null;
        switch (name) {
            case "ro.debuggable":
            case "ro.secure":
            case "ro.kernel.qemu":
                return "0";
            case "ro.build.tags":
                return "release-keys";
            case "ro.build.type":
                return "user";
            default:
                return null;
        }
    }

    // ---------------- 设备指纹换机 ----------------

    /**
     * 服务端按设备指纹拉黑时，本地零分也没用。这里把指纹换成"按种子派生"的稳定假值：
     * 同一种子每次启动结果一致（不会被判指纹跳变），换种子就等于换一台机器。
     */
    private static int installFingerprint(XposedInterface xi, ClassLoader loader) {
        final String cls = "com.langtaier.gameframework.security.DeviceFingerprint";
        int n = 0;
        try {
            Class.forName(cls, false, loader);
        } catch (Throwable ignored) {
            return 0;
        }
        n += add(hookBool(xi, loader, cls, "isDualEnvironment", 1));
        n += add(hookStringDynamic(xi, loader, cls, "generateHardwareOnly", "fp", 64));
        n += add(hookStringDynamic(xi, loader, cls, "generate", "fp", 64));
        // 服务端除了 deviceId，还会用 OAID / android_id / Widevine 交叉认设备
        n += add(hookStringDynamic(xi, loader, cls, "getAndroidId", "aid", 16));
        n += add(hookStringDynamic(xi, loader, cls, "getWidevineDeviceId", "wv", 32));
        n += add(hookStringDynamic(xi, loader,
                "com.langtaier.gameframework.utils.DeviceInfoCollector", "getOaid", "oaid", 32));
        // 自研风控 SDK 的签名包里，oaid 走的是 Lt8/a$a;->h()，绕开上面的采集方法
        n += add(hookStringDynamic(xi, loader, "t8.a$a", "h", "oaid", 32));
        // 快手 EGID / DFP：Orange 与 KWEGIDDFP 是 native 库的 Java 入口
        n += add(hookStringDynamic(xi, loader,
                "com.yxcorp.kuaishou.addfp.android.Orange", "getClockWrapper",
                "egid", 64, 3));
        n += add(hookStringDynamic(xi, loader,
                "com.yxcorp.kuaishou.addfp.KWEGIDDFP", "doSign", "egid", 64, 3));
        n += add(hookStringDynamic(xi, loader,
                "com.yxcorp.kuaishou.addfp.KWEGIDDFP", "doSignV2", "egid", 64, 3));
        // 快手 sgcore 的两个 JNI 导出（getClock / getMagic）
        n += add(hookStringDynamic(xi, loader,
                "com.kwai.sgcore.SGCore", "getClock", "egid", 64, 3));
        n += add(hookStringDynamic(xi, loader,
                "com.kwai.sgcore.SGCore", "getMagic", "egid", 64, 3));
        if (n > 0) Logs.i("设备指纹 hook 已安装，数量=" + n + "（首次调用时生成新指纹）");
        return n;
    }

    /** 指纹要等 App 有 Context 才能算，所以放到被调用时生成并缓存。 */
    private static boolean hookStringDynamic(XposedInterface xi, ClassLoader loader,
                                             String cls, String name,
                                             final String purpose, final int length) {
        return hookStringDynamic(xi, loader, cls, name, purpose, length, 1);
    }

    private static boolean hookStringDynamic(XposedInterface xi, ClassLoader loader,
                                             String cls, String name,
                                             final String purpose, final int length,
                                             int maxParams) {
        Method target = null;
        try {
            Class<?> c = Class.forName(cls, false, loader);
            for (Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() != String.class) continue;
                if (!m.getName().equals(name)) continue;
                if (m.getParameterCount() > maxParams) continue;
                target = m;
                break;
            }
        } catch (Throwable ignored) {
        }
        if (target == null) return false;
        final String key = target.getDeclaringClass().getName() + "#" + target.getName()
                + "/" + target.getParameterCount();
        if (!sHooked.add(key)) return false;
        try {
            xi.hook(target).setPriority(PRIORITY).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    String fake = derived(purpose, length);
                    return fake != null ? fake : chain.proceed();
                }
            });
            Logs.d("风控 hook " + key + " -> 动态指纹");
            return true;
        } catch (Throwable t) {
            Logs.w("风控 hook 失败 " + key + " : " + t);
            return false;
        }
    }

    private static android.content.Context appContext() {
        android.content.Context cached = AppCtx.get();
        if (cached != null) return cached;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method current = at.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            Object app = current.invoke(null);
            if (app instanceof android.content.Context) return (android.content.Context) app;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static volatile String sFakeFingerprint;
    private static volatile String sSeedCache;

    /** 读取/生成种子；配置里的重置计数变化时换新种子。 */
    private static String fakeFingerprint() {
        String cached = sFakeFingerprint;
        if (cached != null) return cached;
        android.content.Context ctx = appContext();
        if (ctx == null) return null;
        try {
            java.io.File file = new java.io.File(ctx.getFilesDir(), "tunnet_fp_seed");
            String seed = null;
            int storedReset = -1;
            if (file.isFile()) {
                String text = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                int sep = text.indexOf('|');
                if (sep > 0) {
                    try {
                        storedReset = Integer.parseInt(text.substring(0, sep));
                    } catch (Throwable ignored) {
                    }
                    seed = text.substring(sep + 1);
                }
            }
            if (seed == null || seed.isEmpty() || storedReset != com.fuckad.xx.Config.fpResetFor(ctx.getPackageName())) {
                seed = Long.toHexString(new java.security.SecureRandom().nextLong())
                        + Long.toHexString(System.nanoTime());
                String text = com.fuckad.xx.Config.fpResetFor(ctx.getPackageName()) + "|" + seed;
                java.io.FileOutputStream out = new java.io.FileOutputStream(file, false);
                try {
                    out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } finally {
                    out.close();
                }
                Logs.i("生成新的指纹种子（重置计数=" + com.fuckad.xx.Config.fpResetFor(ctx.getPackageName()) + "）");
            }
            sSeedCache = seed;
            String value = sha256(seed + "|" + ctx.getPackageName());
            if (value != null) {
                sFakeFingerprint = value;
                Logs.i("设备指纹已替换: " + value.substring(0, 12) + "...");
            }
            return value;
        } catch (Throwable t) {
            Logs.w("指纹种子处理失败: " + t);
            return null;
        }
    }

    /** 用同一个种子派生不同用途的假值，避免 OAID 和 deviceId 长一样。 */
    private static String derived(String purpose, int length) {
        String base = sFakeFingerprint;
        if (base == null) {
            fakeFingerprint();
            base = sFakeFingerprint;
        }
        if (base == null) return null;
        String value = sha256(base + "|" + purpose);
        if (value == null) return null;
        return value.length() <= length ? value : value.substring(0, length);
    }

    private static String sha256(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 基础 hook 封装 ----------------

    private static int add(boolean ok) {
        return ok ? 1 : 0;
    }

    private static boolean hookInt(XposedInterface xi, ClassLoader loader, String cls,
                                   String name, int paramCount, int value) {
        return hookMethod(xi, find(loader, cls, name, paramCount, "int"), value);
    }

    private static boolean hookBool(XposedInterface xi, ClassLoader loader, String cls,
                                    String name, int paramCount) {
        return hookMethod(xi, find(loader, cls, name, paramCount, "boolean"), Boolean.FALSE);
    }

    private static boolean hookNull(XposedInterface xi, ClassLoader loader, String cls,
                                    String name, int paramCount) {
        return hookMethod(xi, find(loader, cls, name, paramCount, null), null);
    }

    private static boolean hookMethodInt(XposedInterface xi, ClassLoader loader, String cls,
                                         String name, int paramCount) {
        return hookMethod(xi, find(loader, cls, name, paramCount, "int"), 0);
    }

    /** 把某类里所有返回 int、名字以 prefix 开头的方法都改成固定值。 */
    private static int hookPrefixInt(XposedInterface xi, ClassLoader loader, String cls,
                                     String prefix, int value) {
        int n = 0;
        try {
            Class<?> c = Class.forName(cls, false, loader);
            for (Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() != int.class) continue;
                if (!m.getName().startsWith(prefix)) continue;
                if (hookMethod(xi, m, value)) n++;
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /** JSONArray 结果清空（快手 weapon 的 hook 扫描结果）。 */
    private static boolean hookJsonArray(XposedInterface xi, ClassLoader loader, String cls, String name) {
        Method target = null;
        try {
            Class<?> c = Class.forName(cls, false, loader);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getReturnType() != JSONArray.class) continue;
                target = m;
                break;
            }
        } catch (Throwable ignored) {
        }
        return hookMethod(xi, target, new JSONArray());
    }

    /** 把某类里所有返回 String、指定名字的方法都改成固定值。 */
    private static int hookStringValue(XposedInterface xi, ClassLoader loader, String cls,
                                       String name, String value) {
        int n = 0;
        try {
            Class<?> c = Class.forName(cls, false, loader);
            for (Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() != String.class) continue;
                if (!m.getName().equals(name)) continue;
                if (hookMethod(xi, m, value)) n++;
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /** 布尔 setter：无论传什么都按 false 执行。 */
    private static boolean hookVoidBooleanFalse(XposedInterface xi, ClassLoader loader,
                                                String cls, String name) {
        Method target = null;
        try {
            Class<?> c = Class.forName(cls, false, loader);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != boolean.class) continue;
                target = m;
                break;
            }
        } catch (Throwable ignored) {
        }
        if (target == null) return false;
        final String key = target.getDeclaringClass().getName() + "#" + target.getName();
        if (!sHooked.add(key)) return false;
        try {
            xi.hook(target).setPriority(PRIORITY).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object[] args = chain.getArgs().toArray();
                    if (args.length == 1) args[0] = Boolean.FALSE;
                    return chain.proceed(args);
                }
            });
            Logs.d("风控 hook " + key + " -> false");
            return true;
        } catch (Throwable t) {
            Logs.w("风控 hook 失败 " + key + " : " + t);
            return false;
        }
    }

    private static boolean hookMethod(XposedInterface xi, final Method target, final Object value) {
        if (target == null) return false;
        final String key = target.getDeclaringClass().getName() + "#" + target.getName()
                + "/" + target.getParameterCount();
        if (!sHooked.add(key)) return false;
        try {
            target.setAccessible(true);
            xi.hook(target).setPriority(PRIORITY).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return value;
                }
            });
            Logs.d("风控 hook " + key + " -> " + value);
            return true;
        } catch (Throwable t) {
            Logs.w("风控 hook 失败 " + key + " : " + t);
            return false;
        }
    }

    private static Method find(ClassLoader loader, String cls, String name,
                              int paramCount, String returnType) {
        try {
            Class<?> c = Class.forName(cls, false, loader);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterCount() != paramCount) continue;
                if (returnType != null && !m.getReturnType().getName().equals(returnType)) continue;
                return m;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ---------------- 第三方风控 / 检测 SDK ----------------
    //
    // 这一类是广告联盟（穿山甲、优量汇、快手等）和游戏自身集成的现成检测库，
    // 特点是类名固定、方法名固定，直接按名 hook 即可。
    //
    // 已确认被「幸福花开」这类游戏使用：
    //   com.scottyab.rootbeer.RootBeer        主流 root 检测库（RootBeer）
    //   com.snail.antifake.*                  模拟器检测（含 native libemulator_check.so）
    //   com.coohua.adv2.utils.CheckPermissionUtils  ADB/无障碍/AppOps/root 检测聚合
    //   com.tencent.bugly.* / 御安全 vsecbox   加固壳的完整性检测

    private static int installThirdPartyRiskSdk(XposedInterface xi, ClassLoader loader) {
        int n = 0;

        // ── RootBeer：把 isRooted() 和内部各 check 全部改成 false ──
        n += add(hookBool(xi, loader, "com.scottyab.rootbeer.RootBeer", "isRooted", 0));
        n += add(hookBool(xi, loader, "com.scottyab.rootbeer.RootBeer",
                "isRootedWithBusyBoxCheck", 0));
        n += add(hookBool(xi, loader, "com.scottyab.rootbeer.RootBeer", "detectRootCloakingApps", 0));
        n += add(hookBool(xi, loader, "com.scottyab.rootbeer.RootBeer", "checkSuExists", 0));
        n += add(hookBool(xi, loader, "com.scottyab.rootbeer.RootBeer", "checkForBinary", 1));
        n += add(hookBool(xi, loader, "com.scottyab.rootbeer.RootBeer", "checkForDangerousProps", 0));
        n += add(hookBool(xi, loader, "com.scottyab.rootbeer.RootBeer", "checkForRWPaths", 0));
        // native 侧：RootBeerNative.checkForRoot([String]) -> int
        n += add(hookInt(xi, loader, "com.scottyab.rootbeer.RootBeerNative", "checkForRoot", 1, 0));

        // ── snail antifake：模拟器检测（已按反编译的真实签名写） ──
        // EmulatorDetectUtil: isEmulator(Context) / isEmulatorFromAll(Context) / detectS() native
        n += add(hookBool(xi, loader, "com.snail.antifake.jni.EmulatorDetectUtil", "isEmulator", 1));
        n += add(hookBool(xi, loader, "com.snail.antifake.jni.EmulatorDetectUtil",
                "isEmulatorFromAll", 1));
        n += add(hookBool(xi, loader, "com.snail.antifake.jni.EmulatorDetectUtil", "detectS", 0));
        // EmuCheckUtil: 全是 (Context) 或 () 的布尔判定
        final String emu = "com.snail.antifake.deviceid.emulator.EmuCheckUtil";
        n += add(hookBool(xi, loader, emu, "mayOnEmulator", 1));
        n += add(hookBool(xi, loader, emu, "isEmulatorViaBuild", 1));
        n += add(hookBool(xi, loader, emu, "mayOnEmulatorViaQEMU", 1));
        n += add(hookBool(xi, loader, emu, "isFakeEmulatorFromIMEI", 1));
        n += add(hookBool(xi, loader, emu, "hasQemuSocket", 0));
        n += add(hookBool(xi, loader, emu, "hasQemuPipe", 0));
        n += add(hookBool(xi, loader, emu, "isEmulatorViaBuild", 1));

        // ── coohua CheckPermissionUtils：ADB / 无障碍 / AppOps / root 聚合检测 ──
        final String cpu = "com.coohua.adv2.utils.CheckPermissionUtils";
        n += add(hookBool(xi, loader, cpu, "checkADBEnable", 1));
        n += add(hookBool(xi, loader, cpu, "isAccessibilityEnabled", 1));
        n += add(hookBool(xi, loader, cpu, "getAppOps", 1));
        n += add(hookBool(xi, loader, cpu, "isRoot", 1));
        n += add(hookBool(xi, loader, cpu, "isMRt", 1));

        // ── 通用：常见 "环境安全" 判定类 ──
        final String[] genericBool = {
                "isRooted", "isRoot", "checkRoot", "hasRoot",
                "isEmulator", "isRunningInEmulator", "isVirtualApp", "isAppCloned",
                "isXposedDetected", "isHooked", "isDebuggerConnected",
                "isDebuggable", "isTampered", "isSignatureTampered",
        };
        final String[] genericCls = {
                "com.tencent.bugly.crashreport.CrashReport",
                "com.tencent.stat.StatService",
        };
        for (String cls : genericCls) {
            for (String m : genericBool) {
                n += add(hookBool(xi, loader, cls, m, 0));
            }
        }

        return n;
    }

}
