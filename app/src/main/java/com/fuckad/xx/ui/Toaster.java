package com.fuckad.xx.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.fuckad.xx.Logs;

import java.lang.reflect.Method;

/**
 * 在目标 App 里弹 Toast。没拿到 Context 时先把文案挂起，等 Application.attach 后再补弹。
 */
public final class Toaster {

    private static volatile Context sAppContext;
    private static volatile String sPending;
    private static volatile long sLastAt;
    private static volatile String sLastText;

    private Toaster() {
    }

    public static void attach(Context context) {
        if (context == null) return;
        Context app = null;
        try {
            app = context.getApplicationContext();
        } catch (Throwable ignored) {
        }
        sAppContext = app != null ? app : context;

        String pending = sPending;
        if (pending != null) {
            sPending = null;
            show(pending);
        }
    }

    public static void show(String text) {
        if (text == null || text.length() == 0) return;

        long now = System.currentTimeMillis();
        if (text.equals(sLastText) && now - sLastAt < 1500L) return;
        sLastText = text;
        sLastAt = now;

        final Context context = resolveContext();
        if (context == null) {
            sPending = text;
            return;
        }
        final String message = text;
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            Logs.w("Toast 失败: " + t);
        }
    }

    private static Context resolveContext() {
        Context context = sAppContext;
        if (context != null) return context;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object app = currentApplication.invoke(null);
            if (app instanceof Context) {
                Context ctx = (Context) app;
                sAppContext = ctx;
                return ctx;
            }
        } catch (Throwable t) {
            Logs.d("取 Application 失败: " + t);
        }
        return null;
    }
}
