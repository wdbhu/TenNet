package com.fuckad.xx.core;

import android.content.ContentResolver;
import android.os.Build;

import com.fuckad.xx.Logs;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.NetworkInterface;
import java.util.HashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

/**
 * 设备伪装：把进程里能读到的设备身份全部改成一台荣耀 WLN-AN00。
 *
 * <p>只在 LSPosed 勾选的作用域 App 进程里运行，且该 App 在设置页被单独勾选。</p>
 *
 * <p>覆盖范围：</p>
 * <ul>
 *   <li>Build 全部 static final 字段（MODEL / BRAND / MANUFACTURER / DEVICE / PRODUCT /
 *       BOARD / HARDWARE / FINGERPRINT / ID / DISPLAY / TAGS / TYPE / USER / HOST /
 *       SERIAL / RADIO / BOOTLOADER / SOC_MANUFACTURER / SOC_MODEL / SKU / ODM_SKU /
 *       VENDOR / TIME / SUPPORTED_ABIS 等），并且反射兜底写一次；</li>
 *   <li>{@code android.os.SystemProperties.get} 全部重载，返回对应 ro.* 的伪装值；</li>
 *   <li>Settings.Secure android_id / bluetooth_name / device_name，
 *       Settings.Global 的 adb_enabled / development_settings_enabled；</li>
 *   <li>TelephonyManager（IMEI / MEID / 序列号 / IMSI / ICCID / SIM 运营商 / 电话类型）；</li>
 *   <li>WifiInfo.getMacAddress、NetworkInterface.getHardwareAddress 的 MAC 地址；</li>
 *   <li>Build.getSerial / Build.getRadioVersion；</li>
 *   <li>模拟器痕迹：QEMU/goldfish/ranchu 路径、isRunningInTestHarness、
 *       常见硬件 feature 白名单。</li>
 * </ul>
 *
 * <p>只作用于 Java 层；native 直接读 /proc、服务端行为风控、以及读系统属性的 native 代码
 * （不经 SystemProperties.get）不在范围内。</p>
 */
public final class DeviceSpoof {

    private static final int PRIORITY = XposedInterface.PRIORITY_DEFAULT + 60;

    /** 设置页展示用的伪装型号名。 */
    public static final String PROFILE_NAME = "荣耀 WLN-AN00";

    private static final String MODEL = "WLN-AN00";
    private static final String MARKET_NAME = "荣耀 WLN-AN00";
    private static final String MANUFACTURER = "HONOR";
    private static final String BRAND = "HONOR";
    private static final String DEVICE = "WLN";
    private static final String PRODUCT = "WLN-AN00";
    private static final String BOARD = "kalama";
    private static final String HARDWARE = "qcom";
    private static final String SOC_MANUFACTURER = "QTI";
    private static final String SOC_MODEL = "SM8550";
    private static final String BUILD_ID = "BQ2A.251110.001";
    private static final String INCREMENTAL = "2.0.0.1";
    private static final String FINGERPRINT =
            "HONOR/WLN-AN00/WLN:16/BQ2A.251110.001/2.0.0.1:user/release-keys";
    private static final String DESCRIPTION =
            "WLN-AN00-user 16 BQ2A.251110.001 2.0.0.1 release-keys";
    private static final String FLAVOR = "WLN-AN00-user";
    private static final String TAGS = "release-keys";
    private static final String TYPE = "user";
    private static final String USER = "build";
    private static final String HOST = "build-host";
    private static final String RADIO = "2.0.0.1";
    private static final String BOOTLOADER = "unknown";
    private static final String SKU = "WLN-AN00";
    private static final String SERIAL = "WLN5F2C9A7B";
    private static final String ANDROID_ID = "3f7a91c2d54b8e06";
    private static final String BLUETOOTH_NAME = "HONOR WLN-AN00";
    private static final String IMEI = "867400000000001";
    private static final String MEID = "A0000000000001";
    private static final String ICCID = "89860000000000000001";
    private static final String IMSI = "460001234567890";
    private static final String SIM_OPERATOR = "46000";
    private static final String SIM_OPERATOR_NAME = "中国移动";
    private static final String MAC = "6c:1e:5a:8d:31:07";
    private static final long BUILD_TIME = 1764128685000L;
    private static final String[] ABIS = {"arm64-v8a", "armeabi-v7a", "armeabi"};
    private static final String[] ABIS_32 = {"armeabi-v7a", "armeabi"};
    private static final String[] ABIS_64 = {"arm64-v8a"};

    /** ro.* 属性表：SystemProperties.get 命中 key 时直接返回伪装值。 */
    private static final Map<String, String> PROPS = new HashMap<>();

    private static final String[] QEMU_PATHS = {
            "/dev/qemu_pipe", "/dev/socket/qemud", "/dev/qemu_trace",
            "/system/lib/libc_malloc_debug_qemu.so",
            "/system/lib64/libc_malloc_debug_qemu.so",
            "/sys/qemu_trace", "/system/bin/qemu-props",
            "/init.goldfish.rc", "/init.ranchu.rc",
            "/system/lib/libgoldfish.so", "/system/lib64/libgoldfish.so"
    };

    private static final String[] TRUE_FEATURES = {
            "android.hardware.camera",
            "android.hardware.camera.flash",
            "android.hardware.camera.autofocus",
            "android.hardware.camera.any",
            "android.hardware.telephony",
            "android.hardware.telephony.gsm",
            "android.hardware.sensor.accelerometer",
            "android.hardware.sensor.gyroscope",
            "android.hardware.sensor.compass",
            "android.hardware.sensor.proximity",
            "android.hardware.sensor.light",
            "android.hardware.wifi",
            "android.hardware.bluetooth",
            "android.hardware.bluetooth_le",
            "android.hardware.touchscreen",
            "android.hardware.screen.portrait",
            "android.hardware.nfc",
            "android.hardware.location.gps",
            "android.hardware.location.network",
            "android.hardware.audio.output",
            "android.hardware.microphone",
            "android.hardware.vulkan.version",
            "android.hardware.ram.normal"
    };

    private static Object sUnsafe;
    private static Method sStaticFieldOffset;
    private static Method sStaticFieldBase;
    private static Method sPutObject;
    private static Method sPutLong;
    private static boolean sUnsafeLoaded;

    static {
        PROPS.put("ro.product.model", MODEL);
        PROPS.put("ro.product.marketname", MARKET_NAME);
        PROPS.put("ro.config.marketing_name", MARKET_NAME);
        PROPS.put("ro.product.manufacturer", MANUFACTURER);
        PROPS.put("ro.product.brand", BRAND);
        PROPS.put("ro.product.device", DEVICE);
        PROPS.put("ro.product.name", PRODUCT);
        PROPS.put("ro.product.board", BOARD);
        PROPS.put("ro.product.cpu.abi", "arm64-v8a");
        PROPS.put("ro.product.cpu.abi2", "armeabi");
        PROPS.put("ro.product.cpu.abilist", "arm64-v8a,armeabi-v7a,armeabi");
        PROPS.put("ro.product.cpu.abilist32", "armeabi-v7a,armeabi");
        PROPS.put("ro.product.cpu.abilist64", "arm64-v8a");
        PROPS.put("ro.build.fingerprint", FINGERPRINT);
        PROPS.put("ro.build.id", BUILD_ID);
        PROPS.put("ro.build.display.id", BUILD_ID);
        PROPS.put("ro.build.tags", TAGS);
        PROPS.put("ro.build.type", TYPE);
        PROPS.put("ro.build.user", USER);
        PROPS.put("ro.build.host", HOST);
        PROPS.put("ro.build.description", DESCRIPTION);
        PROPS.put("ro.build.flavor", FLAVOR);
        PROPS.put("ro.build.product", DEVICE);
        PROPS.put("ro.build.version.incremental", INCREMENTAL);
        PROPS.put("ro.build.version.release", "16");
        PROPS.put("ro.build.version.sdk", "36");
        PROPS.put("ro.build.date.utc", String.valueOf(BUILD_TIME / 1000L));
        PROPS.put("ro.hardware", HARDWARE);
        PROPS.put("ro.boot.hardware", HARDWARE);
        PROPS.put("ro.bootloader", BOOTLOADER);
        PROPS.put("ro.board.platform", BOARD);
        PROPS.put("ro.soc.manufacturer", SOC_MANUFACTURER);
        PROPS.put("ro.soc.model", SOC_MODEL);
        PROPS.put("ro.serialno", SERIAL);
        PROPS.put("ro.boot.serialno", SERIAL);
        PROPS.put("ro.debuggable", "0");
        PROPS.put("ro.secure", "1");
        PROPS.put("ro.adb.secure", "1");
        PROPS.put("ro.kernel.qemu", "");
        PROPS.put("ro.kernel.qemu.gles", "");
        PROPS.put("ro.boot.qemu", "");
        PROPS.put("ro.boot.qemu.avd_name", "");
        PROPS.put("ro.boot.qemu.audio", "");
        PROPS.put("qemu.hw.mainkeys", "");
        // 分区分区属性：很多检测会同时读 system/vendor/odm/product 的副本
        for (String partition : new String[]{"system", "vendor", "odm", "product", "system_ext", "bootimage"}) {
            PROPS.put("ro." + partition + ".build.fingerprint", FINGERPRINT);
            PROPS.put("ro." + partition + ".build.id", BUILD_ID);
            PROPS.put("ro." + partition + ".build.version.incremental", INCREMENTAL);
            PROPS.put("ro." + partition + ".build.tags", TAGS);
            PROPS.put("ro." + partition + ".build.type", TYPE);
            PROPS.put("ro." + partition + ".build.date.utc", String.valueOf(BUILD_TIME / 1000L));
            PROPS.put("ro." + partition + ".build.description", DESCRIPTION);
            PROPS.put("ro." + partition + ".build.flavor", FLAVOR);
            PROPS.put("ro.product." + partition + ".model", MODEL);
            PROPS.put("ro.product." + partition + ".brand", BRAND);
            PROPS.put("ro.product." + partition + ".manufacturer", MANUFACTURER);
            PROPS.put("ro.product." + partition + ".device", DEVICE);
            PROPS.put("ro.product." + partition + ".name", PRODUCT);
            PROPS.put("ro.product." + partition + ".marketname", MARKET_NAME);
        }
    }

    private DeviceSpoof() {
    }

    public static void install(XposedInterface xi) {
        Logs.raw("设备伪装：开始安装 " + PROFILE_NAME);
        int count = 0;
        count += spoofBuildFields();
        count += hookBuildApi(xi);
        count += hookSystemProperties(xi);
        count += hookSettings(xi);
        count += hookTelephony(xi);
        count += hookWifi(xi);
        count += hookActivityManager(xi);
        count += hookPackageManager(xi);
        count += hookFileExists(xi);
        count += verify();
        Logs.raw("设备伪装：安装完成，共 " + count + " 项");
    }

    /** 自检：把 App 里真正读到的值和期望值比一遍，结果进 logcat（TAG=TunNet）。 */
    private static int verify() {
        int ok = 0;
        if (MODEL.equals(Build.MODEL)) ok++;
        if (MANUFACTURER.equals(Build.MANUFACTURER)) ok++;
        if (BRAND.equals(Build.BRAND)) ok++;
        if (DEVICE.equals(Build.DEVICE)) ok++;
        if (FINGERPRINT.equals(Build.FINGERPRINT)) ok++;
        Logs.raw("设备伪装自检：MODEL=" + Build.MODEL
                + " BRAND=" + Build.BRAND
                + " MANUFACTURER=" + Build.MANUFACTURER
                + " DEVICE=" + Build.DEVICE
                + " PRODUCT=" + Build.PRODUCT
                + " FINGERPRINT=" + Build.FINGERPRINT
                + " SERIAL=" + Build.SERIAL
                + " | 期望 " + PROFILE_NAME + "，命中 " + ok + "/5");
        String propModel = propGet("ro.product.model");
        Logs.raw("设备伪装自检：SystemProperties ro.product.model=" + propModel
                + (MODEL.equals(propModel) ? " （命中）" : " （未命中，App 可能绕过 Java 层）"));
        if (!MODEL.equals(Build.MODEL)) {
            Logs.w("设备伪装：Build.MODEL 写入失败，仍为 " + Build.MODEL);
        }
        if (!FINGERPRINT.equals(Build.FINGERPRINT)) {
            Logs.w("设备伪装：Build.FINGERPRINT 写入失败，仍为 " + Build.FINGERPRINT);
        }
        return ok;
    }

    private static String propGet(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getDeclaredMethod("get", String.class);
            get.setAccessible(true);
            Object value = get.invoke(null, key);
            return value == null ? "null" : value.toString();
        } catch (Throwable t) {
            return "n/a";
        }
    }

    // ---------------------------------------------------------------- Build 字段

    /** 直接改 Build 的 static final 字段，App 读 Build.MODEL 时拿到的就是伪装值。 */
    private static int spoofBuildFields() {
        int count = 0;
        count += setStaticString(Build.class, "MODEL", MODEL);
        count += setStaticString(Build.class, "MANUFACTURER", MANUFACTURER);
        count += setStaticString(Build.class, "BRAND", BRAND);
        count += setStaticString(Build.class, "DEVICE", DEVICE);
        count += setStaticString(Build.class, "PRODUCT", PRODUCT);
        count += setStaticString(Build.class, "BOARD", BOARD);
        count += setStaticString(Build.class, "HARDWARE", HARDWARE);
        count += setStaticString(Build.class, "FINGERPRINT", FINGERPRINT);
        count += setStaticString(Build.class, "ID", BUILD_ID);
        count += setStaticString(Build.class, "DISPLAY", BUILD_ID);
        count += setStaticString(Build.class, "TAGS", TAGS);
        count += setStaticString(Build.class, "TYPE", TYPE);
        count += setStaticString(Build.class, "USER", USER);
        count += setStaticString(Build.class, "HOST", HOST);
        count += setStaticString(Build.class, "SERIAL", SERIAL);
        count += setStaticString(Build.class, "RADIO", RADIO);
        count += setStaticString(Build.class, "BOOTLOADER", BOOTLOADER);
        count += setStaticString(Build.class, "SOC_MANUFACTURER", SOC_MANUFACTURER);
        count += setStaticString(Build.class, "SOC_MODEL", SOC_MODEL);
        count += setStaticString(Build.class, "SKU", SKU);
        count += setStaticString(Build.class, "ODM_SKU", SKU);
        count += setStaticString(Build.class, "VENDOR", BRAND.toLowerCase());
        count += setStaticObject(Build.class, "SUPPORTED_ABIS", ABIS);
        count += setStaticObject(Build.class, "SUPPORTED_32_BIT_ABIS", ABIS_32);
        count += setStaticObject(Build.class, "SUPPORTED_64_BIT_ABIS", ABIS_64);
        count += setStaticLong(Build.class, "TIME", BUILD_TIME);
        return count;
    }

    private static boolean loadUnsafe() {
        if (sUnsafeLoaded) return sUnsafe != null;
        sUnsafeLoaded = true;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            sUnsafe = theUnsafe.get(null);
            sStaticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
            sStaticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class);
            sPutObject = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
            sPutLong = unsafeClass.getMethod("putLong", Object.class, long.class, long.class);
        } catch (Throwable t) {
            sUnsafe = null;
            Logs.w("设备伪装：sun.misc.Unsafe 不可用，退化到反射写法（部分字段可能改不动）");
        }
        return sUnsafe != null;
    }

    private static int setStaticString(Class<?> clazz, String name, String value) {
        return setStaticObject(clazz, name, value);
    }

    private static int setStaticObject(Class<?> clazz, String name, Object value) {
        boolean written = false;
        if (loadUnsafe()) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                long offset = (Long) sStaticFieldOffset.invoke(sUnsafe, field);
                Object base = sStaticFieldBase.invoke(sUnsafe, field);
                sPutObject.invoke(sUnsafe, base, offset, value);
                written = true;
            } catch (Throwable ignored) {
            }
        }
        if (!written) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                field.set(null, value);
                written = true;
            } catch (Throwable ignored) {
            }
        }
        return written ? 1 : 0;
    }

    private static int setStaticLong(Class<?> clazz, String name, long value) {
        if (!loadUnsafe()) return 0;
        try {
            Field field = clazz.getDeclaredField(name);
            field.setAccessible(true);
            long offset = (Long) sStaticFieldOffset.invoke(sUnsafe, field);
            Object base = sStaticFieldBase.invoke(sUnsafe, field);
            sPutLong.invoke(sUnsafe, base, offset, value);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- 框架方法

    private static int hookBuildApi(XposedInterface xi) {
        int count = 0;
        try {
            Method getSerial = Build.class.getDeclaredMethod("getSerial");
            count += hookMethod(xi, getSerial, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) {
                    return SERIAL;
                }
            });
        } catch (Throwable ignored) {
        }
        try {
            Method getRadioVersion = Build.class.getDeclaredMethod("getRadioVersion");
            count += hookMethod(xi, getRadioVersion, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) {
                    return RADIO;
                }
            });
        } catch (Throwable ignored) {
        }
        return count;
    }

    private static int hookSystemProperties(XposedInterface xi) {
        int count = 0;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            try {
                Method get1 = sp.getDeclaredMethod("get", String.class);
                count += hookMethod(xi, get1, new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        String key = keyOf(chain.getArg(0));
                        String value = key == null ? null : PROPS.get(key);
                        return value != null ? value : chain.proceed();
                    }
                });
            } catch (Throwable ignored) {
            }
            try {
                Method get2 = sp.getDeclaredMethod("get", String.class, String.class);
                count += hookMethod(xi, get2, new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        String key = keyOf(chain.getArg(0));
                        String value = key == null ? null : PROPS.get(key);
                        return value != null ? value : chain.proceed();
                    }
                });
            } catch (Throwable ignored) {
            }
            try {
                Method getBoolean = sp.getDeclaredMethod("getBoolean", String.class, boolean.class);
                count += hookMethod(xi, getBoolean, new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        String key = keyOf(chain.getArg(0));
                        if (key != null && PROPS.containsKey(key)) {
                            String value = PROPS.get(key);
                            return "1".equals(value) || "true".equalsIgnoreCase(value);
                        }
                        return chain.proceed();
                    }
                });
            } catch (Throwable ignored) {
            }
            try {
                Method getInt = sp.getDeclaredMethod("getInt", String.class, int.class);
                count += hookMethod(xi, getInt, new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        String key = keyOf(chain.getArg(0));
                        if (key != null && PROPS.containsKey(key)) {
                            try {
                                return Integer.parseInt(PROPS.get(key));
                            } catch (Throwable ignored) {
                                return 0;
                            }
                        }
                        return chain.proceed();
                    }
                });
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            Logs.w("设备伪装：SystemProperties hook 失败 " + t);
        }
        return count;
    }

    private static String keyOf(Object arg) {
        return arg == null ? null : arg.toString();
    }

    private static int hookSettings(XposedInterface xi) {
        int count = 0;
        try {
            Class<?> secure = Class.forName("android.provider.Settings$Secure");
            Method getString = secure.getDeclaredMethod("getString", ContentResolver.class, String.class);
            count += hookMethod(xi, getString, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    String name = keyOf(chain.getArg(1));
                    if ("android_id".equals(name)) return ANDROID_ID;
                    if ("bluetooth_name".equals(name)) return BLUETOOTH_NAME;
                    if ("device_name".equals(name)) return BLUETOOTH_NAME;
                    return chain.proceed();
                }
            });
        } catch (Throwable ignored) {
        }
        try {
            Class<?> global = Class.forName("android.provider.Settings$Global");
            Method getString = global.getDeclaredMethod("getString", ContentResolver.class, String.class);
            count += hookMethod(xi, getString, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    String name = keyOf(chain.getArg(1));
                    if ("device_name".equals(name) || "bluetooth_name".equals(name)) {
                        return BLUETOOTH_NAME;
                    }
                    return chain.proceed();
                }
            });
            Method getInt = global.getDeclaredMethod("getInt", ContentResolver.class, String.class);
            count += hookMethod(xi, getInt, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    String name = keyOf(chain.getArg(1));
                    if ("adb_enabled".equals(name)
                            || "development_settings_enabled".equals(name)
                            || "stay_on_while_plugged_in".equals(name)) {
                        return 0;
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable ignored) {
        }
        return count;
    }

    private static int hookTelephony(XposedInterface xi) {
        int count = 0;
        try {
            Class<?> tm = Class.forName("android.telephony.TelephonyManager");
            count += hookConst(xi, tm, "getDeviceId", IMEI);
            count += hookConst(xi, tm, "getImei", IMEI);
            count += hookConst(xi, tm, "getMeid", MEID);
            count += hookConst(xi, tm, "getSubscriberId", IMSI);
            count += hookConst(xi, tm, "getSimSerialNumber", ICCID);
            count += hookConst(xi, tm, "getSimOperator", SIM_OPERATOR);
            count += hookConst(xi, tm, "getNetworkOperator", SIM_OPERATOR);
            count += hookConst(xi, tm, "getSimOperatorName", SIM_OPERATOR_NAME);
            count += hookConst(xi, tm, "getNetworkOperatorName", SIM_OPERATOR_NAME);
            count += hookConst(xi, tm, "getSimCountryIso", "cn");
            count += hookConst(xi, tm, "getNetworkCountryIso", "cn");
            count += hookConst(xi, tm, "getLine1Number", "");
            count += hookConst(xi, tm, "getVoiceMailNumber", "");
        } catch (Throwable ignored) {
        }
        try {
            Class<?> tm = Class.forName("android.telephony.TelephonyManager");
            Method phoneType = tm.getDeclaredMethod("getPhoneType");
            count += hookMethod(xi, phoneType, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) {
                    return 1; // PHONE_TYPE_GSM
                }
            });
            Method simState = tm.getDeclaredMethod("getSimState");
            count += hookMethod(xi, simState, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) {
                    return 5; // SIM_STATE_READY
                }
            });
        } catch (Throwable ignored) {
        }
        return count;
    }

    private static int hookConst(XposedInterface xi, Class<?> clazz, String name, final String value) {
        try {
            Method method = clazz.getDeclaredMethod(name);
            return hookMethod(xi, method, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) {
                    return value;
                }
            });
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookWifi(XposedInterface xi) {
        int count = 0;
        try {
            Class<?> wifiInfo = Class.forName("android.net.wifi.WifiInfo");
            Method mac = wifiInfo.getDeclaredMethod("getMacAddress");
            count += hookMethod(xi, mac, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) {
                    return MAC;
                }
            });
        } catch (Throwable ignored) {
        }
        try {
            Method mac = NetworkInterface.class.getDeclaredMethod("getHardwareAddress");
            count += hookMethod(xi, mac, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object self = chain.getThisObject();
                    String name = self instanceof NetworkInterface
                            ? ((NetworkInterface) self).getName() : null;
                    if (name != null && (name.startsWith("wlan") || name.startsWith("eth"))) {
                        return macBytes();
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable ignored) {
        }
        return count;
    }

    private static byte[] macBytes() {
        String[] parts = MAC.split(":");
        byte[] bytes = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                bytes[i] = (byte) Integer.parseInt(parts[i], 16);
            } catch (Throwable t) {
                bytes[i] = 0;
            }
        }
        return bytes;
    }

    private static int hookActivityManager(XposedInterface xi) {
        try {
            Class<?> am = Class.forName("android.app.ActivityManager");
            Method method = am.getDeclaredMethod("isRunningInTestHarness");
            return hookMethod(xi, method, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) {
                    return Boolean.FALSE;
                }
            });
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookPackageManager(XposedInterface xi) {
        try {
            Class<?> apm = Class.forName("android.app.ApplicationPackageManager");
            Method method = apm.getDeclaredMethod("hasSystemFeature", String.class);
            return hookMethod(xi, method, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object feature = chain.getArg(0);
                    if (feature != null) {
                        for (String name : TRUE_FEATURES) {
                            if (name.equals(feature)) return Boolean.TRUE;
                        }
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookFileExists(XposedInterface xi) {
        try {
            Method method = File.class.getDeclaredMethod("exists");
            return hookMethod(xi, method, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object self = chain.getThisObject();
                    if (self instanceof File) {
                        String path = ((File) self).getPath();
                        for (String qemu : QEMU_PATHS) {
                            if (path.equals(qemu) || path.startsWith(qemu + "/")) {
                                return Boolean.FALSE;
                            }
                        }
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookMethod(XposedInterface xi, Method method, XposedInterface.Hooker hooker) {
        try {
            xi.hook(method).setPriority(PRIORITY).intercept(hooker);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }
}
