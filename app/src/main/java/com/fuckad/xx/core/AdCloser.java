package com.fuckad.xx.core;

import android.app.Activity;
import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;

import com.fuckad.xx.Config;
import com.fuckad.xx.Logs;
import com.fuckad.xx.ui.Toaster;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * 自动关广告：广告页（Activity 或 Dialog）出现后，等一小会儿就关掉。
 *
 * <p>关掉之后大多数 SDK 会回调 onAdClose / onSkippedVideo 这类“结束”方法，
 * 我们的触发 hook 正好在这些方法里补发奖励。</p>
 */
public final class AdCloser {

    private static final int PRIORITY = XposedInterface.PRIORITY_DEFAULT + 40;
    /** 留足够时间让 SDK 走完展示回调、也留出补发奖励的时间窗口。 */

    private static final String[] AD_PACKAGE_PREFIXES = {
            // 有奖励回调、关了能补发的
            "com.bytedance.sdk.openadsdk",   // 穿山甲
            "com.byazt",                     // 穿山甲混淆后的 Activity/Dialog 包名
            "com.bytedance.msdk",            // GroMore
            "com.qq.e",                      // 优量汇
            "com.kwad",                      // 快手
            "com.miui.zeus.mimo",            // 小米 MIMO
            "com.alimm.tanx",                // 阿里 Tanx
            "com.sigmob",                    // Sigmob
            "com.baidu.mobads",              // 百度联盟
            "com.smartdigimkt",              // SmartDigimkt
            "com.anythink",                  // TopOn 聚合
            // 只负责关广告页，是否到账看 App 的判定
            "com.noah.adn",                   // 汇川广告
            "com.octopus.ad",                 // 章鱼广告
            "com.coohua",                    // 趣头条广告 SDK（CAdVideo*）
            "com.tapsdk",                    // TapAD（TapTap 广告）
            "com.tapad",
            "com.mbridge.msdk",              // Mintegral
            "com.applovin",                  // AppLovin
            "com.ironsource",                // IronSource
            "com.vungle",                    // Vungle
            "com.facebook.ads",              // Meta Audience Network
            "com.inmobi",                    // InMobi
            "com.beizi",                     // 倍孜
            "com.ubix"                       // UBiX
            // 刻意不包含 AdMob / Unity：这两家没有可用的补发链路，关掉只会白丢奖励。
    };

    private static final Map<Object, Long> SCHEDULED =
            Collections.synchronizedMap(new WeakHashMap<Object, Long>());
    /** 没收到展示回调时的兜底：至少留这么久，别把还没渲染出来的广告页关掉。 */
    private static final long FALLBACK_MIN_MS = 1200L;

    private static Handler sMain;

    private AdCloser() {
    }

    public static void install(XposedInterface xi) {
        Logs.raw("AdCloser 开始安装");
        int count = 0;
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            xi.hook(onResume).setPriority(PRIORITY).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        if (self instanceof Activity) {
                            onScreen("Activity", self);
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
            count++;
        } catch (Throwable t) {
            Logs.raw("自动关广告：Activity hook 失败 " + t);
        }

        try {
            Method show = Dialog.class.getDeclaredMethod("show");
            xi.hook(show).setPriority(PRIORITY).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        if (self instanceof Dialog) {
                            onScreen("Dialog", self);
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
            count++;
        } catch (Throwable t) {
            Logs.raw("自动关广告：Dialog hook 失败 " + t);
        }

        Logs.raw("自动关广告已启用，生效 hook 数=" + count);
    }

    private static void onScreen(String kind, final Object target) {
        final String name = target.getClass().getName();
        if (Logs.isEnabled()) {
            Logs.d(kind + " 出现: " + name + (isAdScreen(name) ? "  [命中广告]" : ""));
        }
        if (!isAdScreen(name)) return;
        // 广告页常常来自动态加载的插件 ClassLoader（穿山甲 pangle_p、优量汇 gdt_plugin、
        // SecShell 解密壳等），主 APK 静态扫描扫不到它们。这里触发懒扫描，把这个 loader
        // 的 dex/apk 也扫一遍，补 hook 标准 SDK 实现类。否则奖励 hook 装不上，自动关广告
        // 只能硬 finish()，会把激励视频 SDK 的状态机卡死在“展示中”。
        AdHookEngine.lazyRescan(target.getClass().getClassLoader());
        if (SCHEDULED.put(target, System.currentTimeMillis()) != null) return;
        long delay = Math.max(0L, Config.autoCloseDelayMs());
        if (Logs.isEnabled()) {
            Logs.d("关闭计时: " + delay + "ms: " + name);
        }
        mainHandler().postDelayed(new Runnable() {
            @Override
            public void run() {
                closeNow(target, name);
            }
        }, delay);
    }

    private static void closeNow(Object target, String name) {
        // 用户在广告播放期间关掉「自动关闭」，已排队的任务不应再执行
        if (!Config.autoClose()) {
            SCHEDULED.remove(target);
            return;
        }
        try {
            if (target instanceof Activity) {
                Activity activity = (Activity) target;
                if (!activity.isFinishing()) {
                    Logs.raw("自动关闭广告页 " + name);
                    // 恢复老版本（5.87）行为：先 fireLastClose 补发，再 finish。
                    // 之前试的 moveTaskToBack 退后台方案没解决到账问题，
                    // 老版本能到账靠的是"展示回调立即补发"，跟关闭方式无关。
                    final boolean fired = AdHookEngine.fireLastClose();
                    if (fired) {
                        mainHandler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    activity.finish();
                                } catch (Throwable ignored) {
                                }
                            }
                        }, 250);
                    } else {
                        activity.finish();
                    }
                }
            } else if (target instanceof Dialog) {
                Dialog dialog = (Dialog) target;
                if (dialog.isShowing()) {
                    Logs.raw("自动关闭广告弹窗 " + name);
                    dialog.dismiss();
                }
            }
        } catch (Throwable t) {
            Logs.raw("自动关闭失败: " + t);
        } finally {
            SCHEDULED.remove(target);
        }
    }

    private static synchronized Handler mainHandler() {
        if (sMain == null) {
            sMain = new Handler(Looper.getMainLooper());
        }
        return sMain;
    }

    /**
     * 落地页 / 隐私页 / 授权页不能关。
     *
     * <p>它们和激励视频共用同一个 SDK 包前缀，但用户还没看完广告，
     * 关掉等于广告不结算、奖励回调也不会来。以前只看前缀，会把这些页面一起 finish。</p>
     */
    private static final String[] LANDING_MARKERS = {
            "Landing",            // TTWebViewLandingActivity / TTRewardVideoLandingActivity
            "Delegate",           // TTDelegateActivity（隐私协议/权限代理）
            "Privacy",            // 隐私相关
            "Agreement",
            "Permission",
            "WebView",
            "Browser",
    };

    static boolean isAdScreen(String className) {
        if (className == null) return false;
        // 落地页/隐私页一律不关
        for (String marker : LANDING_MARKERS) {
            if (className.contains(marker)) return false;
        }
        for (String prefix : AD_PACKAGE_PREFIXES) {
            if (className.startsWith(prefix)) {
                // 要求包边界：com.tapad 不能匹配 com.tapadXxx
                if (className.length() == prefix.length()) return true;
                char next = className.charAt(prefix.length());
                return next == '.' || next == '$';
            }
        }
        return false;
    }
}
