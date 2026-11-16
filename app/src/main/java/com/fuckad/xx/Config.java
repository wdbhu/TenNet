package com.fuckad.xx;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;



/**
 * 模块配置。运行在目标 App 进程里，通过 LSPosed 的 remote preferences 读取本模块的设置。
 */
public final class Config {

    /** SharedPreferences 文件名，设置页与 hook 进程必须一致。 */
    public static final String PREFS_NAME = "ad_helper";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_INJECT_ON_SKIP = "inject_on_skip";
    public static final String KEY_TOAST = "toast";
    public static final String KEY_DEBUG = "debug";
    public static final String KEY_DEDUPE = "dedupe";
    public static final String KEY_AUTO_CLOSE = "auto_close";
    public static final String KEY_AUTO_CLOSE_DELAY = "auto_close_delay";
    public static final String KEY_REWARD_DELAY = "reward_delay";
    public static final String KEY_REWARD_MULTIPLIER = "reward_multiplier";
    public static final String KEY_EMULATOR_SPOOF = "emulator_spoof";
    public static final String KEY_SPOOF_APPS = "spoof_apps";
    public static final String KEY_LOCATION_SPOOF = "location_spoof";
    public static final String KEY_SPOOF_LOCATIONS = "spoof_locations";
    public static final String KEY_RISK_BYPASS = "risk_bypass";
    public static final String KEY_FP_RESETS = "fp_resets";
    public static final String KEY_RISK_APPS = "risk_apps";

    /** 勾选名单：设置页勾选哪些 App，hook 就只在这些 App 里生效。 */
    public static final String KEY_SCOPE_APPS = "scope_apps";

    private static volatile boolean sEnabled = true;
    private static volatile boolean sInjectOnSkip = true;
    private static volatile boolean sToast = true;
    private static volatile boolean sDedupe = true;
    private static volatile boolean sDebug = true;
    private static volatile boolean sAutoClose = false;
    private static volatile int sAutoCloseDelay = 2500;
    private static volatile int sRewardDelay = 0;
    private static volatile int sRewardMultiplier = 1;
    private static volatile boolean sEmulatorSpoof = false;
    private static volatile Set<String> sSpoofApps = java.util.Collections.emptySet();
    private static volatile boolean sLocationSpoof = false;
    private static volatile Map<String, String[]> sLocations = java.util.Collections.emptyMap();
    private static volatile boolean sRiskBypass = true;
    private static volatile Map<String, Integer> sFpResets = java.util.Collections.emptyMap();
    private static volatile Set<String> sRiskApps = java.util.Collections.emptySet();

    /**
     * null = 用户从没在设置页配置过勾选名单，保持旧行为（全部生效）；
     * 一旦配置过（哪怕空列表），就严格按名单放行：勾上才注入。
     */
    private static volatile Set<String> sScopeApps = null;

    private Config() {
    }

    public static void load(SharedPreferences sp) {
        if (sp == null) {
            syncLogs();
            return;
        }
        try {
            sEnabled = sp.getBoolean(KEY_ENABLED, true);
            sInjectOnSkip = sp.getBoolean(KEY_INJECT_ON_SKIP, true);
            sToast = sp.getBoolean(KEY_TOAST, true);
            sDedupe = sp.getBoolean(KEY_DEDUPE, true);
            sDebug = sp.getBoolean(KEY_DEBUG, true);
            sAutoClose = sp.getBoolean(KEY_AUTO_CLOSE, false);
            sAutoCloseDelay = sp.getInt(KEY_AUTO_CLOSE_DELAY, 2500);
            sRewardDelay = sp.getInt(KEY_REWARD_DELAY, 0);
            sRewardMultiplier = sp.getInt(KEY_REWARD_MULTIPLIER, 1);
            sEmulatorSpoof = sp.getBoolean(KEY_EMULATOR_SPOOF, false);
            Set<String> spoofApps = sp.getStringSet(KEY_SPOOF_APPS, null);
            sSpoofApps = spoofApps == null
                    ? java.util.Collections.<String>emptySet()
                    : new HashSet<>(spoofApps);
            sLocationSpoof = sp.getBoolean(KEY_LOCATION_SPOOF, false);
            sRiskBypass = sp.getBoolean(KEY_RISK_BYPASS, true);
            sFpResets = parseResets(sp.getStringSet(KEY_FP_RESETS, null));
            Set<String> riskApps = sp.getStringSet(KEY_RISK_APPS, null);
            sRiskApps = riskApps == null
                    ? java.util.Collections.<String>emptySet()
                    : new HashSet<>(riskApps);
            Set<String> scopeApps = sp.getStringSet(KEY_SCOPE_APPS, null);
            sScopeApps = scopeApps == null ? null : new HashSet<>(scopeApps);
            Set<String> locations = sp.getStringSet(KEY_SPOOF_LOCATIONS, null);
            Map<String, String[]> locMap = new HashMap<>();
            if (locations != null) {
                for (String entry : locations) {
                    if (entry == null) continue;
                    String[] parts = entry.split("\\|");
                    if (parts.length < 3) continue;
                    try {
                        Double.parseDouble(parts[1]);
                        Double.parseDouble(parts[2]);
                        String name = parts.length > 3 ? parts[3] : parts[0];
                        locMap.put(parts[0], new String[]{parts[1], parts[2], name});
                    } catch (Throwable ignored) {
                    }
                }
            }
            sLocations = locMap;
        } catch (Throwable t) {
            Logs.e("读取配置失败，使用默认值", t);
        }
        syncLogs();
    }

    private static void syncLogs() {
        Logs.setEnabled(sDebug);
    }

    public static boolean enabled() {
        return sEnabled;
    }

    public static boolean injectOnSkip() {
        return sInjectOnSkip;
    }

    public static boolean toast() {
        return sToast;
    }

    /** 广告出现后等多久补发奖励（毫秒），0 = 立即，和 1.0 行为一致。 */
    public static int rewardDelayMs() {
        return sRewardDelay;
    }

    public static int autoCloseDelayMs() {
        int v = sAutoCloseDelay;
        if (v < 500) v = 500;      // 最短 0.5 秒，界面上给的是 1 秒起
        if (v > 30000) v = 30000;
        return v;
    }

    public static boolean autoClose() {
        return sAutoClose;
    }

    public static boolean debug() {
        return sDebug;
    }

    public static boolean dedupe() {
        return sDedupe;
    }

    /**
     * 奖励倍率：1 = 正常单次（原样透传、不放大），2..10 = 按倍率重复调用回调。
     *
     * 早先 1 被当成"不修改"的特殊值、由 0 表示，导致界面上的「1x」实际等于
     * 一份没生效的配置。现在 1 就是字面意义的单次，0 与负数一律归一到 1。
     */
    public static int rewardMultiplier() {
        int v = sRewardMultiplier;
        if (v < 1) v = 1;
        if (v > 10) v = 10;
        return v;
    }

    /** 单次透传：倍率为 1 时不重写回调参数、不放大数量。 */
    public static boolean rewardPassthrough() {
        return rewardMultiplier() <= 1;
    }

    /**
     * 是否对这个包做环境检测绕过。
     *
     * <p>旧逻辑只看 risk_apps 白名单：新装的 App 从来没人往白名单里加过，
     * 于是「勾选作用域」看起来毫无反应——环境检测绕过一个都没装。
     * 现在改为默认跟随主开关，只有显式配置过 risk_apps 且不在其中时才跳过。</p>
     */
    public static boolean shouldRiskBypass(String packageName) {
        if (packageName == null) return false;
        if (!sRiskBypass) return false;
        if (sRiskApps.isEmpty()) return true;
        return sRiskApps.contains(packageName);
    }

    /** 每个 App 各自的指纹重置计数：变化一次就给它换一台"新机器"。 */
    public static int fpResetFor(String packageName) {
        if (packageName == null) return 0;
        Integer value = sFpResets.get(packageName);
        return value == null ? 0 : value;
    }

    /** 当前勾选名单（可能为空集；从未配置过时返回空集仅用于展示）。 */
    public static Set<String> scopeApps() {
        Set<String> apps = sScopeApps;
        return apps == null ? java.util.Collections.<String>emptySet() : apps;
    }

    /**
     * 该 App 是否被勾选。
     *
     * <p>名单从未配置过（{@code null}）时视为"全部关闭"——这样新用户装上后
     * 不会因为模块被注入到所有进程而误伤银行、游戏等敏感 App。
     * 用户只要在模块里勾一次任意 App，名单就不再是 null，之后严格按名单判断。</p>
     *
     * <p>想恢复"装上即全部生效"的旧行为，把 {@link #LEGACY_ALL_ENABLED} 改成 true。</p>
     */
    public static boolean isAppEnabled(String packageName) {
        if (packageName == null) return false;
        Set<String> apps = sScopeApps;
        if (apps == null) return LEGACY_ALL_ENABLED;
        return apps.contains(packageName);
    }

    /** 名单未配置时是否全部放行。默认 false：默认全关，避免误伤。 */
    private static final boolean LEGACY_ALL_ENABLED = false;

    private static Map<String, Integer> parseResets(Set<String> raw) {
        if (raw == null || raw.isEmpty()) return java.util.Collections.emptyMap();
        Map<String, Integer> map = new HashMap<>();
        for (String entry : raw) {
            if (entry == null) continue;
            int sep = entry.lastIndexOf('|');
            if (sep <= 0) continue;
            try {
                map.put(entry.substring(0, sep), Integer.parseInt(entry.substring(sep + 1)));
            } catch (Throwable ignored) {
            }
        }
        return map;
    }

    /** 设备伪装 / 模拟器绕过。 */
    public static boolean emulatorSpoof() {
        return sEmulatorSpoof;
    }

    /** 只有这里选中的、且在作用域里的 App 才启用设备伪装。 */
    public static boolean shouldSpoof(String packageName) {
        return packageName != null && sSpoofApps.contains(packageName);
    }

    /** 是否开启伪装定位。 */
    public static boolean locationSpoof() {
        return sLocationSpoof;
    }

    /** 返回 {纬度, 经度, 位置名}，没有配置返回 null。 */
    public static String[] locationFor(String packageName) {
        return packageName == null ? null : sLocations.get(packageName);
    }
    // ================= 本地写入（供设置页调用） =================

    /** 模块 App 自己的 SharedPreferences，和 hook 进程读的是同一份文件。 */
    private static volatile android.content.SharedPreferences sLocal;

    public static void bindLocal(android.content.Context ctx) {
        if (ctx != null && sLocal == null) {
            sLocal = ctx.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        }
    }

    /** 模块 App 自己的 SharedPreferences；供设置页做本地兜底读写。 */
    public static android.content.SharedPreferences local() {
        return sLocal;
    }

    public static void setLocal(String key, boolean v) {
        sLocal.edit().putBoolean(key, v).apply();
        reloadFromLocal();
    }

    public static void setLocal(String key, int v) {
        sLocal.edit().putInt(key, v).apply();
        reloadFromLocal();
    }

    public static void setLocal(String key, String v) {
        sLocal.edit().putString(key, v).apply();
        reloadFromLocal();
    }

    /** 写入后立刻刷新静态缓存，界面读到的就是最新值。 */
    private static void reloadFromLocal() {
        if (sLocal != null) load(sLocal);
    }

    public static boolean riskBypass() {
        return sRiskBypass;
    }

}
