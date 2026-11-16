package com.fuckad.xx.core;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 保存目标进程的 Application Context，避免依赖隐藏 API ActivityThread。 */
public final class AppCtx {

    private static volatile Context sContext;

    private AppCtx() {
    }

    public static void set(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        sContext = app == null ? context : app;
    }

    public static Context get() {
        Context ctx = sContext;
        if (ctx != null) return ctx;
        ctx = reflectApp();
        if (ctx != null) {
            set(ctx);
            return sContext;
        }
        ctx = unsafeApp();
        if (ctx != null) {
            set(ctx);
            return sContext;
        }
        return sContext;
    }

    /** 常规反射；Android 9+ 对 hidden API 会直接抛异常。 */
    private static Context reflectApp() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method current = at.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            Object app = current.invoke(null);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method current = at.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object thread = current.invoke(null);
            if (thread != null) {
                Method getApp = at.getDeclaredMethod("getApplication");
                getApp.setAccessible(true);
                Object app = getApp.invoke(thread);
                if (app instanceof Context) return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 绕过 hidden API 限制：用 Unsafe 直接读 ActivityThread 的静态字段和 mInitialApplication。 */
    private static Context unsafeApp() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);

            Method staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class);
            Method staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
            Method objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
            Method getObject = unsafeClass.getMethod("getObject", Object.class, long.class);

            Class<?> at = Class.forName("android.app.ActivityThread");
            Field currentThreadField = at.getDeclaredField("sCurrentActivityThread");
            currentThreadField.setAccessible(true);
            Object base = staticFieldBase.invoke(unsafe, currentThreadField);
            long off = (Long) staticFieldOffset.invoke(unsafe, currentThreadField);
            Object thread = getObject.invoke(unsafe, base, off);
            if (thread == null) return null;

            Field initialApp = at.getDeclaredField("mInitialApplication");
            initialApp.setAccessible(true);
            long appOff = (Long) objectFieldOffset.invoke(unsafe, initialApp);
            Object app = getObject.invoke(unsafe, thread, appOff);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) {
        }
        return null;
    }
}
