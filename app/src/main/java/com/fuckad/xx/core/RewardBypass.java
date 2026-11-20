package com.fuckad.xx.core;

import com.fuckad.xx.Config;
import com.fuckad.xx.Logs;

import java.lang.reflect.Method;

import android.os.Handler;
import android.os.Looper;

import io.github.libxposed.api.XposedInterface;

/**
 * 下载/试玩类广告的领奖绕过。
 *
 * <p>hexiang 这套「超级奖励」的安装检测完全在客户端：
 * {@code SuperRewardManager.updateSuperState()} 里判断
 * {@code AppUtils.isInstall(...)} 和 {@code getLastTime() <= 0}，
 * 两个条件都成立就 {@code postCallback(code=10, "可以领取奖励")}，
 * H5 收到 code=10 立刻调 {@code /api/ad/task/reward}。</p>
 *
 * <p>也就是说：只要让这两个客户端判断恒为真，H5 就会走领奖分支，
 * 不用真的安装广告里的 App、也不用试玩够时长。</p>
 */
public final class RewardBypass {

    private RewardBypass() {
    }

    public static int install(final XposedInterface xi, final ClassLoader loader) {
        if (!com.fuckad.xx.Config.debug()) return 0;
        int count = 0;
        count += hookBoolean(loader, xi, "com.hexiang.jhfc.utils.AppUtils", "isInstall", 2);
        count += hookBoolean(loader, xi, "com.hexiang.jhfc.utils.Utils", "checkAppInstalled", 2);
        count += hookZeroInt(loader, xi, "com.hexiang.jhfc.sdk.SuperRewardManager", "getLastTime");
        count += hookZeroInt(loader, xi, "com.hexiang.jhfc.utils.AppUtils", "getTimePkgUsed");
        count += hookConstInt(loader, xi, "com.hexiang.jhfc.bean.ActiveConfig", "getState", 2);
        count += hookConstString(loader, xi, "com.hexiang.jhfc.bean.ActiveConfig", "getInstallPkg", "com.example.adapp");
        count += hookTriggerUpdate(loader, xi);
        Logs.raw("BYPASS 安装完成，hook 数=" + count);
        return count;
    }

    /** 让「是否已安装」恒为 true。 */
    private static int hookBoolean(final ClassLoader loader, final XposedInterface xi,
                                   String className, final String methodName, int argc) {
        try {
            Class<?> clazz = Class.forName(className, false, loader);
            Logs.raw("BYPASS 找到类 " + className + " 方法数=" + clazz.getDeclaredMethods().length);
            for (final Method m : clazz.getDeclaredMethods()) {
                if (!methodName.equals(m.getName())) continue;
                if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) continue;
                if (m.getParameterCount() != argc) continue;
                try {
                    m.setAccessible(true);
                    xi.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Logs.i("BYPASS " + methodName + " -> true");
                            return Boolean.TRUE;
                        }
                    });
                    Logs.i("已 hook " + className + "#" + methodName + " -> true");
                    return 1;
                } catch (Throwable t) {
                    Logs.raw("BYPASS hook " + methodName + " 失败: " + t);
                }
            }
        } catch (Throwable t) {
            Logs.raw("BYPASS 类加载失败 " + className + ": " + t);
        }
        return 0;
    }

    /**
     * 主动触发 updateSuperState()。
     * 该方法原本只在收到 PACKAGE_ADDED 时被调用，不装 App 就永不执行。
     */
    private static int hookTriggerUpdate(final ClassLoader loader, final XposedInterface xi) {
        try {
            Class<?> clazz = Class.forName("com.hexiang.jhfc.sdk.SuperRewardManager", false, loader);
            for (final Method m : clazz.getDeclaredMethods()) {
                if (!"startSuperRewardAd".equals(m.getName())) continue;
                try {
                    m.setAccessible(true);
                    xi.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            final Object self = chain.getThisObject();
                            Logs.raw("BYPASS 捕获超级奖励实例");
                            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        Method up = self.getClass().getDeclaredMethod("updateSuperState");
                                        up.setAccessible(true);
                                        up.invoke(self);
                                        Logs.raw("BYPASS 已主动触发 updateSuperState");
                                    } catch (Throwable t) {
                                        Logs.raw("BYPASS 主动触发失败: " + t);
                                    }
                                }
                            }, 4500L);
                            return result;
                        }
                    });
                    Logs.raw("已 hook startSuperRewardAd（主动触发）");
                    return 1;
                } catch (Throwable t) {
                    Logs.raw("BYPASS hook startSuperRewardAd 失败: " + t);
                }
            }
        } catch (Throwable t) {
            Logs.raw("BYPASS 类加载失败 SuperRewardManager: " + t);
        }
        return 0;
    }

    private static int hookConstInt(final ClassLoader loader, final XposedInterface xi,
                                    String className, final String methodName, final int value) {
        try {
            Class<?> clazz = Class.forName(className, false, loader);
            for (final Method m : clazz.getDeclaredMethods()) {
                if (!methodName.equals(m.getName())) continue;
                if (m.getReturnType() != int.class && m.getReturnType() != Integer.class) continue;
                try {
                    m.setAccessible(true);
                    xi.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Logs.raw("BYPASS " + methodName + " -> " + value);
                            return value;
                        }
                    });
                    Logs.raw("已 hook " + className + "#" + methodName + " -> " + value);
                    return 1;
                } catch (Throwable t) {
                    Logs.raw("BYPASS hook " + methodName + " 失败: " + t);
                }
            }
        } catch (Throwable t) {
            Logs.raw("BYPASS 类加载失败 " + className + ": " + t);
        }
        return 0;
    }

    private static int hookConstString(final ClassLoader loader, final XposedInterface xi,
                                       String className, final String methodName, final String value) {
        try {
            Class<?> clazz = Class.forName(className, false, loader);
            for (final Method m : clazz.getDeclaredMethods()) {
                if (!methodName.equals(m.getName())) continue;
                if (m.getReturnType() != String.class) continue;
                try {
                    m.setAccessible(true);
                    xi.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            return value;
                        }
                    });
                    Logs.raw("已 hook " + className + "#" + methodName + " -> " + value);
                    return 1;
                } catch (Throwable t) {
                    Logs.raw("BYPASS hook " + methodName + " 失败: " + t);
                }
            }
        } catch (Throwable t) {
            Logs.raw("BYPASS 类加载失败 " + className + ": " + t);
        }
        return 0;
    }

    /** 让「剩余试玩时间」恒为 0（达到目标）。 */
    private static int hookZeroInt(final ClassLoader loader, final XposedInterface xi,
                                   String className, final String methodName) {
        try {
            Class<?> clazz = Class.forName(className, false, loader);
            for (final Method m : clazz.getDeclaredMethods()) {
                if (!methodName.equals(m.getName())) continue;
                if (m.getReturnType() != int.class && m.getReturnType() != Integer.class) continue;
                try {
                    m.setAccessible(true);
                    xi.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Logs.i("BYPASS " + methodName + " -> 0");
                            return 0;
                        }
                    });
                    Logs.i("已 hook " + className + "#" + methodName + " -> 0");
                    return 1;
                } catch (Throwable t) {
                    Logs.raw("BYPASS hook " + methodName + " 失败: " + t);
                }
            }
        } catch (Throwable t) {
            Logs.raw("BYPASS 类加载失败 " + className + ": " + t);
        }
        return 0;
    }
}
