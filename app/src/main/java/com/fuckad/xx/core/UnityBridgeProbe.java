package com.fuckad.xx.core;

import com.fuckad.xx.Logs;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * Unity 广告桥诊断。
 *
 * <p>杰青这类 Unity 游戏，奖励回调是 Java 桥转给 C# 层的：
 * {@code VideoHelper$1.onReward()} 里读 {@code VideoHelper.this.mListener}
 * 再调 {@code mListener.onReward(placementId, info)}。
 * 广告页被强关后 Unity 可能已经把代理置空，回调就被静默丢弃——
 * 表现为「模块提示已补发，游戏毫无反应」。</p>
 *
 * <p>这里拦截桥接方法，把 mListener 的状态打出来，判断是不是丢在这一层。</p>
 */
public final class UnityBridgeProbe {

    private UnityBridgeProbe() {
    }

    public static int install(final XposedInterface xi, final ClassLoader loader) {
        if (!com.fuckad.xx.Config.debug()) return 0;
        int count = 0;
        Logs.raw("UnityBridgeProbe 开始安装");
        try {
            Class<?> helper = Class.forName("com.anythink.unitybridge.videoad.VideoHelper", false, loader);
            Logs.raw("UnityBridgeProbe 找到 VideoHelper，内部类=" + helper.getDeclaredClasses().length);
            // 遍历它的所有内部类（VideoHelper$1 等），找 onReward
            java.util.List<Class<?>> inners = new java.util.ArrayList<>();
            for (Class<?> c : helper.getDeclaredClasses()) inners.add(c);
            // 兜底：直接用已知匿名类名加载
            for (String n : new String[]{"com.anythink.unitybridge.videoad.VideoHelper$1",
                    "com.anythink.unitybridge.videoad.VideoHelper$2"}) {
                try {
                    inners.add(Class.forName(n, false, loader));
                } catch (Throwable ignored) {
                }
            }
            Logs.raw("UnityBridgeProbe 待处理类数=" + inners.size());
            for (Class<?> inner : inners) {
                for (final Method m : inner.getDeclaredMethods()) {
                    if (!"onReward".equals(m.getName())) continue;
                    if (m.getParameterCount() != 1) continue;
                    try {
                        m.setAccessible(true);
                        xi.hook(m).intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object self = chain.getThisObject();
                                Object helperObj = readField(self, "this$0");
                                Object listener = helperObj == null ? null : readField(helperObj, "mListener");
                                Logs.raw("UnityBridge onReward 进入: listener="
                                        + (listener == null ? "null" : listener.getClass().getName()));
                                Object result;
                                try {
                                    result = chain.proceed();
                                } catch (Throwable t) {
                                    Logs.raw("UnityBridge onReward 抛异常: " + t);
                                    throw t;
                                }
                                Logs.raw("UnityBridge onReward 返回: " + result);
                                return result;
                            }
                        });
                        count++;
                    } catch (Throwable ignored) {
                    }
                }
            }
            // 同时记录 setListener，看 Unity 什么时候注册/注销
            for (final Method m : helper.getDeclaredMethods()) {
                if (!"setListener".equals(m.getName())) continue;
                if (m.getParameterCount() != 1) continue;
                try {
                    m.setAccessible(true);
                    xi.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            java.util.List<Object> args = chain.getArgs();
                            Object l = (args == null || args.isEmpty()) ? null : args.get(0);
                            Logs.raw("UnityBridge setListener: "
                                    + (l == null ? "null（注销）" : l.getClass().getName()));
                            return chain.proceed();
                        }
                    });
                    count++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Logs.raw("UnityBridgeProbe 安装失败: " + t);
        }
        if (count > 0) Logs.raw("UnityBridgeProbe 安装完成，hook 数=" + count);
        return count;
    }

    private static Object readField(Object obj, String name) {
        if (obj == null) return null;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
