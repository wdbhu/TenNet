package com.fuckad.xx.core;

import android.os.Handler;
import android.os.Looper;

import com.fuckad.xx.Config;
import com.fuckad.xx.Logs;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import io.github.libxposed.api.XposedInterface;

/**
 * WebView 请求嗅探（调试用）。
 *
 * <p>有些 App 的领奖逻辑跑在 H5 里，请求参数只有它自己知道。
 * 这里在页面加载后注入一段脚本，把 XHR / fetch 的地址与请求体存进
 * {@code window.__tn_reqs}，模块再定时用 evaluateJavascript 读回来写日志。
 * 不走 console，避免 X5 的 console 不进 logcat 的问题。</p>
 *
 * <p>只读不改，不影响 App 自己的请求。</p>
 */
public final class WebSniffer {

    private static final String JS =
            "(function(){if(window.__tn_req)return;window.__tn_req=1;"
            + "window.__tn_reqs=window.__tn_reqs||[];"
            + "var P=function(s){try{window.__tn_reqs.push(String(s).slice(0,900));"
            + "if(window.__tn_reqs.length>60)window.__tn_reqs.shift()}catch(e){}};"
            + "var X=XMLHttpRequest.prototype,o=X.open,s=X.send;"
            + "X.open=function(m,u){this.__m=m;this.__u=u;return o.apply(this,arguments)};"
            + "X.send=function(b){try{P(this.__m+' '+(this.__u||'')+' || '+(b===undefined?'':b))}catch(e){}"
            + "return s.apply(this,arguments)};"
            + "if(window.fetch){var f=window.fetch;window.fetch=function(u,op){"
            + "try{P('fetch '+u+' || '+((op||{}).body||''))}catch(e){};return f.apply(this,arguments)}}"
            + "})();";

    private static final String READ = "JSON.stringify(window.__tn_reqs||[])";

    private WebSniffer() {
    }

    public static int install(final XposedInterface xi, final ClassLoader loader) {
        if (!Config.debug()) {
            Logs.d("请求嗅探未启用（debug 关闭）");
            return 0;
        }
        // X5 内核动态加载，onPackageReady 时类往往还不在 ClassLoader 里。
        // 后台反复尝试，等 App 真正初始化 WebView 后再挂。
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 60; i++) {
                    try {
                        if (tryInstall(xi, loader) > 0) {
                            Logs.i("已安装 WebView 请求嗅探（第 " + i + " 次尝试）");
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                Logs.d("WebView 请求嗅探安装超时");
            }
        }, "TunNet-WebSniffer");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    private static int tryInstall(XposedInterface xi, ClassLoader loader) {
        int count = 0;
        Class<?> clazz;
        try {
            clazz = Class.forName("com.tencent.smtt.sdk.WebView", false, loader);
        } catch (Throwable t) {
            try {
                clazz = Class.forName("com.tencent.smtt.sdk.WebView", false,
                        WebSniffer.class.getClassLoader());
            } catch (Throwable t2) {
                return 0;
            }
        }
        for (final Method method : clazz.getDeclaredMethods()) {
            if (!"loadUrl".equals(method.getName())) continue;
            if (method.getParameterTypes().length != 1) continue;
            if (method.getParameterTypes()[0] != String.class) continue;
            try {
                xi.hook(method).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        inject(chain.getThisObject());
                        return result;
                    }
                });
                count++;
            } catch (Throwable t) {
                Logs.d("WebSniffer hook 失败: " + t);
            }
        }
        return count;
    }

    private static void inject(final Object webView) {
        if (webView == null) return;
        final Handler main = new Handler(Looper.getMainLooper());
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                eval(webView, JS);
                Logs.d("已注入请求嗅探脚本");
                // 之后每 4 秒把捕获到的请求读一次
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        eval(webView, READ);
                        main.postDelayed(this, 4000L);
                    }
                }, 4000L);
            }
        }, 1500L);
    }

    /** 调 WebView 的 evaluateJavascript；回调用动态代理接，拿到结果写日志。 */
    private static void eval(final Object webView, final String script) {
        if (webView == null) return;
        try {
            for (final Method m : webView.getClass().getMethods()) {
                if (!"evaluateJavascript".equals(m.getName())) continue;
                Class<?>[] types = m.getParameterTypes();
                if (types.length != 2 || types[0] != String.class) continue;
                Object callback = null;
                try {
                    final Class<?> cbType = types[1];
                    callback = Proxy.newProxyInstance(cbType.getClassLoader(),
                            new Class<?>[]{cbType},
                            new java.lang.reflect.InvocationHandler() {
                                @Override
                                public Object invoke(Object proxy, Method method, Object[] args) {
                                    if ("onReceiveValue".equals(method.getName())
                                            && args != null && args.length > 0) {
                                        String v = String.valueOf(args[0]);
                                        if (v != null && v.length() > 2 && !"[]".equals(v)) {
                                            Logs.i("H5请求: " + v);
                                        }
                                    }
                                    return null;
                                }
                            });
                } catch (Throwable ignored) {
                    callback = null;
                }
                m.setAccessible(true);
                m.invoke(webView, script, callback);
                return;
            }
        } catch (Throwable t) {
            Logs.d("evaluateJavascript 失败: " + t);
        }
    }
}
