package com.fuckad.xx.core;

import com.fuckad.xx.Logs;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 微信小程序激励视频的奖励绕过。
 *
 * <p>小程序广告和原生 App 完全是两套东西：广告由微信自己的平台渲染，
 * 回调走 JS。小程序里典型写法是：</p>
 *
 * <pre>
 *   const ad = wx.createRewardedVideoAd({...});
 *   ad.onClose(res => { if (res &amp;&amp; res.isEnded) grantReward(); });
 * </pre>
 *
 * <p>也就是说，最终判断「看完没有」的，是 JS 层读一个布尔字段
 * {@code isEnded}。这个字段由微信客户端通过 J2V8 桥写进 JS 对象，
 * 而 J2V8 的 Java 侧读布尔值只有一个入口：
 * {@code com.eclipsesource.mmv8.V8Object#getBoolean(String)}。</p>
 *
 * <p>所以这里不碰 V8 引擎内部，只在桥接层把与「看完了」相关的布尔字段
 * 一律返回 true。比直接改引擎轻得多，也不用逆向 27MB 的 libmmv8.so。</p>
 *
 * <p>注意：只对微信进程生效；且若小程序的奖励由它自己的服务端校验，
 * 客户端改这个值可能仍然无效（与原生 S2S 校验同理）。</p>
 */
public final class WeChatMiniProgram {

    /** 广告结果里表示「看完 / 有效」的常见字段名（含微信各类广告 API）。 */
    private static final String[] REWARD_KEYS = {
            "isEnded",
            "isEnd",
            "isCompleted",
            "isComplete",
            "isValid",
            "isReward",
            "isRewarded",
            "completed",
            "ended",
    };

    private WeChatMiniProgram() {
    }

    public static int install(final XposedInterface xi, final ClassLoader loader) {
        int n = 0;
        n += installNativeProbe();
        n += hookV8GetBoolean(xi, loader);
        n += hookV8GetInteger(xi, loader);
        if (n > 0) Logs.raw("小程序广告适配: hook 数=" + n);
        return n;
    }

    /**
     * 加载 native 探针，拦 v8::JSON::Parse 看服务端返回的奖励数据长什么样。
     *
     * <p>库由外部（root 或模块自身）放到目标进程 cache 目录下，
     * 这里只负责按绝对路径加载——小程序进程的 ClassLoader 看不到模块 APK。</p>
     */
    private static int installNativeProbe() {
        try {
            android.content.Context ctx = AppCtx.get();
            if (ctx == null) return 0;
            java.io.File so = new java.io.File(ctx.getCacheDir(), "libv8probe.so");
            if (!so.exists() || so.length() == 0) {
                return 0;
            }
            System.load(so.getAbsolutePath());
            Logs.raw("V8 探针已加载: " + so.getAbsolutePath());
            return 1;
        } catch (Throwable t) {
            Logs.raw("V8 探针加载失败: " + t);
            return 0;
        }
    }

    /**
     * 拦 V8Object.getBoolean(String)。
     *
     * <p>这是 J2V8 读 JS 布尔字段的唯一入口，小程序广告结果对象
     * 一定经过它。</p>
     */
    private static int hookV8GetBoolean(final XposedInterface xi, final ClassLoader loader) {
        try {
            Class<?> v8obj = Class.forName("com.eclipsesource.mmv8.V8Object", false, loader);
            int n = 0;
            for (final Method m : v8obj.getDeclaredMethods()) {
                if (!"getBoolean".equals(m.getName())) continue;
                if (m.getParameterCount() != 1) continue;
                if (m.getReturnType() != boolean.class) continue;
                try {
                    m.setAccessible(true);
                    xi.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            String key = keyOf(chain.getArgs());
                            if (key != null && isRewardKey(key)) {
                                Logs.raw("小程序广告: getBoolean(" + key + ") -> true");
                                return Boolean.TRUE;
                            }
                            return chain.proceed();
                        }
                    });
                    n++;
                } catch (Throwable t) {
                    Logs.raw("小程序适配 hook getBoolean 失败: " + t);
                }
            }
            return n;
        } catch (Throwable t) {
            // 不在微信进程 / 没有 J2V8，正常
            return 0;
        }
    }

    /** 有些广告结果用数字表达（0/1），一并处理。 */
    private static int hookV8GetInteger(final XposedInterface xi, final ClassLoader loader) {
        try {
            Class<?> v8obj = Class.forName("com.eclipsesource.mmv8.V8Object", false, loader);
            int n = 0;
            for (final Method m : v8obj.getDeclaredMethods()) {
                if (!"getInteger".equals(m.getName())) continue;
                if (m.getParameterCount() != 1) continue;
                if (m.getReturnType() != int.class) continue;
                try {
                    m.setAccessible(true);
                    xi.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            String key = keyOf(chain.getArgs());
                            if (key != null && isRewardKey(key)) {
                                Logs.raw("小程序广告: getInteger(" + key + ") -> 1");
                                return 1;
                            }
                            return chain.proceed();
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String keyOf(List<Object> args) {
        if (args == null || args.isEmpty()) return null;
        Object v = args.get(0);
        return v instanceof String ? (String) v : null;
    }

    private static boolean isRewardKey(String key) {
        for (String k : REWARD_KEYS) {
            if (k.equals(key)) return true;
        }
        return false;
    }
}
