package com.fuckad.xx.core;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.ResultReceiver;

import com.fuckad.xx.Logs;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 跳过小程序/小游戏的 GDT（优量汇）激励视频。
 *
 * <p>思路参考 TCQT：**不碰广告播放，直接伪造广告 SDK 的回传**。</p>
 *
 * <p>GDT 激励视频的启动方式是构造一个 Intent，里面装两样东西：</p>
 * <ul>
 *     <li>{@code data}：{@code GdtMotiveVideoPageData}，描述这次广告；</li>
 *     <li>{@code result_receiver}：{@code ResultReceiver}，广告页看完后会往里回传结果。</li>
 * </ul>
 *
 * <p>小程序拿到 ResultReceiver 的返回后，读 {@code profitable_flag}
 * 判断「是否有效观看」。所以这里只要在 Activity 启动前把
 * {@code profitable_flag = true} 提前 send 回去，再把启动拦掉，
 * 小程序就会认为广告看完了——广告页根本不会弹出来。</p>
 *
 * <p>比改 JS 字段可靠得多：小程序信的是 GDT SDK 自己的回传协议，
 * 不是我们编的值。</p>
 */
public final class SkipGdtRewardAd {

    /** GDT 激励视频页的数据类。微信和 QQ 用的是同一个 SDK。 */
    private static final String PAGE_DATA =
            "com.tencent.gdtad.basics.motivevideo.data.GdtMotiveVideoPageData";

    /** 回传里表示「有效观看」的布尔字段。 */
    private static final String PROFITABLE_FLAG = "profitable_flag";

    /** 已知的激励广告 ResultReceiver 实现。 */
    private static final String[] REWARD_RECEIVERS = {
            "com.tencent.qqmini.proxyimpl.AdProxyImpl$AdResultReceiver",
            "com.tencent.mobileqq.minigame.ad.ams.AmsRewardedVideoAdView$AmsMotiveVideoResultReceiver",
            // 微信侧可能的实现（命名不同，一并放行）
            "com.tencent.mm.plugin.appbrand.ad.AppBrandAdResultReceiver",
            "com.tencent.mm.plugin.appbrand.jsapi.ad.JsApiRewardedVideoAd$ResultReceiver",
    };

    /** 普通 ResultReceiver 走这条退路时要求的 refId。 */
    private static final String MINIAPP_REF_ID = "biz_src_miniapp";

    private SkipGdtRewardAd() {
    }

    public static int install(final XposedInterface xi) {
        int n = 0;
        try {
            Class<?> instr = Class.forName("android.app.Instrumentation");
            for (final Method m : instr.getDeclaredMethods()) {
                if (!"execStartActivity".equals(m.getName())) continue;
                // 找 Intent 参数的位置
                final int idx = indexOfIntent(m.getParameterTypes());
                if (idx < 0) continue;
                try {
                    m.setAccessible(true);
                    xi.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            List<Object> args = chain.getArgs();
                            if (args != null && idx < args.size()) {
                                Object a = args.get(idx);
                                if (a instanceof Intent && trySkip((Intent) a)) {
                                    // 已经回传「看完了」，拦掉 Activity 启动
                                    Logs.raw("GDT激励: 已回传完成，拦截广告页启动");
                                    return null;
                                }
                            }
                            return chain.proceed();
                        }
                    });
                    n++;
                } catch (Throwable t) {
                    Logs.raw("GDT激励: hook execStartActivity 失败: " + t);
                }
            }
            // 兜底：Activity 真的起来了就在 onCreate 里补一次
            n += hookActivityOnCreate(xi);
        } catch (Throwable t) {
            Logs.raw("GDT激励 安装失败: " + t);
        }
        if (n > 0) Logs.raw("GDT激励跳过: hook 数=" + n);
        return n;
    }

    /** Activity 已经创建时补发（execStartActivity 没拦住的场景）。 */
    private static int hookActivityOnCreate(final XposedInterface xi) {
        try {
            Class<?> instr = Class.forName("android.app.Instrumentation");
            for (final Method m : instr.getDeclaredMethods()) {
                if (!"callActivityOnCreate".equals(m.getName())) continue;
                if (m.getParameterTypes().length < 2) continue;
                if (m.getParameterTypes()[0] != Activity.class) continue;
                try {
                    m.setAccessible(true);
                    xi.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            List<Object> args = chain.getArgs();
                            if (args != null && !args.isEmpty() && args.get(0) instanceof Activity) {
                                Activity act = (Activity) args.get(0);
                                Intent intent = act.getIntent();
                                if (intent != null && trySkip(intent)) {
                                    Logs.raw("GDT激励: onCreate 补发完成，关闭广告页");
                                    act.finish();
                                    return null;
                                }
                            }
                            return chain.proceed();
                        }
                    });
                    return 1;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 判断这个 Intent 是不是 GDT 激励广告，是就直接回传完成。 */
    private static boolean trySkip(Intent intent) {
        try {
            if (intent == null) return false;
            if (!intent.hasExtra("result_receiver") || !intent.hasExtra("data")) return false;
            Bundle extras = intent.getExtras();
            if (extras == null) return false;
            // 微信自己的 ClassLoader，反序列化才能成功
            try {
                extras.setClassLoader(SkipGdtRewardAd.class.getClassLoader());
            } catch (Throwable ignored) {
            }
            Object data = extras.getSerializable("data");
            Parcelable recv = extras.getParcelable("result_receiver");
            if (!(recv instanceof ResultReceiver)) return false;
            if (!isMiniReward(data, (ResultReceiver) recv)) return false;

            Bundle out = new Bundle();
            out.putBoolean(PROFITABLE_FLAG, true);
            ((ResultReceiver) recv).send(-1, out);
            Logs.raw("GDT激励: 已发送 profitable_flag=true");
            return true;
        } catch (Throwable t) {
            Logs.raw("GDT激励 处理异常: " + t);
            return false;
        }
    }

    /** 页面对不对 + 接收器对不对。 */
    private static boolean isMiniReward(Object data, ResultReceiver recv) {
        if (data == null || recv == null) return false;
        if (!PAGE_DATA.equals(data.getClass().getName())) return false;

        String rc = recv.getClass().getName();
        for (String known : REWARD_RECEIVERS) {
            if (known.equals(rc)) return true;
        }
        // 退路：标准 ResultReceiver + refId 标记
        if ("android.os.ResultReceiver".equals(rc)) {
            try {
                Field f = data.getClass().getField("refId");
                Object v = f.get(data);
                return MINIAPP_REF_ID.equals(v);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static int indexOfIntent(Class<?>[] types) {
        for (int i = 0; i < types.length; i++) {
            if (types[i] == Intent.class) return i;
        }
        return -1;
    }
}
