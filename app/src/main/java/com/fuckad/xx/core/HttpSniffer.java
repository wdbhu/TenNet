package com.fuckad.xx.core;

import com.fuckad.xx.Config;
import com.fuckad.xx.Logs;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 原生 HTTP 嗅探（调试用）。
 *
 * <p>H5 的请求能靠注入 JS 看到，但 App 自己（Kotlin 层）发的上报走 OkHttp，
 * 看不到就会误判「服务端凭什么不发奖」。这里 hook OkHttp 的 RealCall，
 * 把 URL、响应码、响应体开头打进日志。</p>
 *
 * <p>只观察，不修改请求与响应。</p>
 */
public final class HttpSniffer {

    private HttpSniffer() {
    }

    public static int install(final XposedInterface xi, ClassLoader loader) {
        if (!Config.debug()) return 0;
        int count = 0;
        try {
            Class<?> realCall = Class.forName("okhttp3.RealCall", false, loader);
            for (final Method method : realCall.getDeclaredMethods()) {
                String name = method.getName();
                if (!"execute".equals(name) && !"enqueue".equals(name)) continue;
                try {
                    xi.hook(method).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            String url = safeUrl(chain.getThisObject());
                            Object result;
                            try {
                                result = chain.proceed();
                            } catch (Throwable t) {
                                Logs.w("HTTP 失败 " + url + " : " + t);
                                throw t;
                            }
                            if (url != null && (url.contains("tv91") || url.contains("reward")
                                    || url.contains("report"))) {
                                String body = safeBody(chain.getThisObject());
                                Logs.i("HTTP " + url + (body == null ? "" : " BODY=" + body)
                                        + " -> " + safeCode(result));
                            }
                            return result;
                        }
                    });
                    count++;
                } catch (Throwable t) {
                    Logs.d("HttpSniffer hook " + name + " 失败: " + t);
                }
            }
        } catch (Throwable t) {
            Logs.d("HttpSniffer 未安装: " + t);
        }
        if (count > 0) Logs.i("已安装 OkHttp 嗅探，数量=" + count);
        return count;
    }

    private static String safeUrl(Object call) {
        try {
            Method request = call.getClass().getMethod("request");
            Object req = request.invoke(call);
            Method urlM = req.getClass().getMethod("url");
            Object url = urlM.invoke(req);
            Method methodM = req.getClass().getMethod("method");
            Object httpMethod = methodM.invoke(req);
            return httpMethod + " " + url;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 读请求体内容。
     *
     * <p>不调用 {@code writeTo}——那会消耗 body，可能干扰 OkHttp 实际发送。
     * 改为反射遍历 RequestBody 的所有字段，把其中的 byte[] 解成字符串。
     * 大多数 JSON 请求体的字节就藏在这些字段里。</p>
     */
    private static String safeBody(Object call) {
        try {
            Method request = call.getClass().getMethod("request");
            Object req = request.invoke(call);
            Method body = req.getClass().getMethod("body");
            Object rb = body.invoke(req);
            if (rb == null) return null;
            StringBuilder sb = new StringBuilder();
            java.util.Set<Object> seen =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
            collectBytes(rb.getClass(), rb, sb, seen, 0);
            if (sb.length() == 0) return null;
            String s = sb.toString();
            return s.length() > 600 ? s.substring(0, 600) : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 递归遍历对象的字段，收集 byte[] 字段的值。 */
    private static void collectBytes(Class<?> type, Object obj, StringBuilder out,
                                     java.util.Set<Object> seen, int depth) {
        if (obj == null || depth > 4 || !seen.add(obj)) return;
        for (Class<?> t = type; t != null && t != Object.class; t = t.getSuperclass()) {
            java.lang.reflect.Field[] fields;
            try {
                fields = t.getDeclaredFields();
            } catch (Throwable ignored) {
                return;
            }
            for (java.lang.reflect.Field f : fields) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v == null) continue;
                    if (v instanceof byte[]) {
                        String s = new String((byte[]) v, "UTF-8");
                        if (s.length() > 4) {
                            out.append(s).append('\n');
                        }
                    } else if (v.getClass().getName().startsWith("okhttp3")
                            && depth < 3 && !seen.contains(v)) {
                        collectBytes(v.getClass(), v, out, seen, depth + 1);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 记录原生上报方法 reportRewardOther 的真实参数。
     *
     * <p>请求体里的 ad_group_id / load_id / code_id 等字段服务端会拿去和
     * 广告平台对账。空值会被识破，必须拿到真实值才能重放。</p>
     */
    public static int installReportHook(final XposedInterface xi, ClassLoader loader) {
        if (!Config.debug()) return 0;
        try {
            Class<?> vm = Class.forName("com.hexiang.jhfc.ui2.viewmodel.ReportViewModel", false, loader);
            for (final Method m : vm.getDeclaredMethods()) {
                if (!"reportRewardOther".equals(m.getName())) continue;
                if (m.getParameterCount() != 17) continue;
                try {
                    xi.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            java.util.List<Object> argList = chain.getArgs();
                            Object[] args = argList == null ? null : argList.toArray();
                            if (args != null && args.length == 17) {
                                Logs.i("REPORT_ARGS reportId=" + args[1]
                                        + " scene=" + args[2]
                                        + " adType=" + args[5]
                                        + " adSource=" + args[6]
                                        + " ecpm=" + args[7]
                                        + " codeId=" + args[8]
                                        + " groupId=" + args[10]
                                        + " loadId=" + args[11]
                                        + " playStart=" + args[12]
                                        + " playEnd=" + args[13]
                                        + " isReward=" + args[14]
                                        + " isCompleted=" + args[15]
                                        + " interval=" + args[16]);
                            }
                            return chain.proceed();
                        }
                    });
                    Logs.i("已安装上报参数记录 hook");
                    return 1;
                } catch (Throwable t) {
                    Logs.d("report hook 失败: " + t);
                }
            }
        } catch (Throwable t) {
            Logs.d("ReportViewModel 未找到: " + t);
        }
        return 0;
    }

    private static String safeCode(Object response) {
        if (response == null) return "null";
        try {
            Method code = response.getClass().getMethod("code");
            Object c = code.invoke(response);
            String body = "";
            try {
                Method peek = response.getClass().getMethod("peekBody", long.class);
                Object rb = peek.invoke(response, 200L);
                Method str = rb.getClass().getMethod("string");
                Object s = str.invoke(rb);
                body = String.valueOf(s);
            } catch (Throwable ignored) {
            }
            return c + " " + body;
        } catch (Throwable t) {
            return "?";
        }
    }
}
