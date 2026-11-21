package com.fuckad.xx.core;

import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;

import com.fuckad.xx.Config;
import com.fuckad.xx.Logs;
import com.fuckad.xx.ui.Toaster;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 核心引擎：扫描 dex -> 找到实现类 -> hook 奖励/结束回调。
 */
public final class AdHookEngine {

    /** 最近一次广告展示的 adapter 实例，用来在自动关闭前替 SDK 触发结束回调。 */
    private static volatile java.lang.ref.WeakReference<Object> sLastAdTargetRef;
    /**
     * 本轮广告里出现过的所有监听对象。
     *
     * <p>聚合 SDK 是分层回调：底层 SDK（快手/穿山甲）→ 聚合适配器（AnyThink）→ App 自己的监听器。
     * 只记住「最后一个」对象时，App 层的 onRewardedVideoAdPlayEnd 永远补不到，
     * App 就认为视频没看完而不发奖。这里把本轮出现过的一层层的对象都记下来。</p>
     */
    private static final java.util.List<java.lang.ref.WeakReference<Object>> sSessionTargets =
            new java.util.ArrayList<>();
    /** 最近一次补发奖励的时间，3 秒内不重复补，避免关闭+SDK 回调双发。 */
    private static volatile long sLastRewardAt;
    private static volatile long sLastAdAt;
    private static final java.util.Map<Class<?>, java.util.List<Method>> sCloseMethods = new java.util.HashMap<>();
    /**
     * 最近一次「真实」奖励回调实参，按 方法签名 存一份。
     * 补发时优先复用它——自己用 Unsafe 造的空壳参数会被 App 读字段 NPE。
     *
     * <p>只作为「最近一轮」的临时缓存：带时间戳，超过 TTL 自动作废；
     * 且每次真实回调都会整体覆盖。不能拿它做「是否已经发过奖励」的判断，
     * 那个判断交给 RewardLedger（按对象+会话+窗口记账）。</p>
     */
    private static final java.util.Map<String, RealArgs> sRealRewardArgs =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 真实参数缓存的存活时间：超过一轮广告就作废，避免拿着上一轮的交易号去补发。 */
    private static final long REAL_ARGS_TTL_MS = 90_000L;

    /** 带采集时间的真实回调实参。 */
    private static final class RealArgs {
        final Object[] args;
        final long at;
        final int session;

        RealArgs(Object[] args, long at, int session) {
            this.args = args;
            this.at = at;
            this.session = session;
        }
    }
    /**
     * 按「参数类型」共享的真实回调对象。
     *
     * TopOn 这类聚合层会把同一个广告回调分发给多个监听器（SDK 内部适配层 + App 自己的监听器），
     * 前者能拿到真实 ATAdInfo，后者在百度/快手源下可能收到 null。
     * 这里把见过的真实对象按类型存一份，后续谁收到 null 就用它顶上。
     */
    /**
     * 按类型缓存真实回调对象。
     *
     * 必须用弱引用：广告对象（ATAdInfo 等）动辄持有素材 URL、大段 JSON，
     * 强引用会把每轮广告的对象都留在内存里，刷几十次广告就是几十 MB。
     */
    private static final java.util.Map<Class<?>, java.lang.ref.WeakReference<Object>> sRealArgByType =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Class<?>, java.lang.ref.WeakReference<Object>>());
    private static final java.util.Map<Class<?>, Method> sRewardMethods = new java.util.HashMap<>();
    /** 每个奖励方法对应的 SDK 名，fireLastClose 补发时用它记账，和 deliver 的记账键同构，避免双发。 */
    private static final java.util.Map<Class<?>, String> sRewardSdk = new java.util.HashMap<>();
    private static final java.util.Set<Class<?>> sCtorHooked = new java.util.HashSet<>();
    private static volatile long sLastFireAt;

    /**
     * 关闭广告页之前调用：让 SDK 走一次 onAdClose/onVideoComplete，奖励才有机会发出去。
     *
     * <p>这里补发奖励是「我们自己发起」的调用，必须走 RewardLedger 记账并带注入标记，
     * 否则被 hook 的奖励方法会把它当成原生回调再放大一次（倍率越大越失控）。
     * 以前这里直接 invoke、只靠 sLastRewardAt 做 3 秒节流，而 sLastRewardAt
     * 只在它内部被写、真实回调从不更新，等于节流失效 → 真实奖励到账后再补一份。</p>
     */
    public static boolean fireLastClose() {
        Object target = sLastAdTargetRef == null ? null : sLastAdTargetRef.get();
        // 先伪造观看时长：有些 App 把「播了多少秒」上报给服务端做发奖判定，
        // 只补回调不改时长，服务端仍然判「没看完」。
        // 发版默认关闭：伪造播放时长会影响正常应用，调试时再打开
        // if (Config.debug()) {
        //     fakePlayDuration(target);
        //     for (Object other : sessionTargets()) {
        //         if (other != target) fakePlayDuration(other);
        //     }
        // }
        if (target == null) return false;
        long now = System.currentTimeMillis();
        if (now - sLastAdAt > 20_000L) return false;
        if (now - sLastFireAt < 3_000L) return false;
        sLastFireAt = now;
        boolean fired = false;

        // 先补一次奖励：CSJ 的 onRewardVerify(true,1,...) / GDT 的 onReward(Map)
        // 注册用的是声明类（接口），运行期拿到的是匿名实现类，两者不同。
        // 直接 get(target.getClass()) 永远查不到 —— 关闭前兜底补发会静默失效。
        Method reward = rewardMethodFor(target);
        String rewardSdk = rewardSdkFor(target);
        if (reward != null) {
            // sdk 命名空间必须和 deliver() 里一致，否则两处记账互不命中、导致双发
            final String sdk = rewardSdk != null ? rewardSdk : "close";
            final String methodKey = keyOf(reward);
            // 真实奖励已经到账（本会话同一方法已记账）就不再补，避免双发
            boolean alreadyDone = !RewardLedger.tryInject(target, sdk, methodKey, false);
            if (alreadyDone) {
                if (Logs.isEnabled()) {
                    Logs.d("关闭前跳过补发：本会话已发放 " + reward.getName());
                }
            } else {
                try {
                    reward.setAccessible(true);
                    Object[] args = ArgFixer.buildRewardArgs(
                            reward.getParameterTypes(), new Object[0]);
                    if (args != null) {
                        boolean synthetic = ArgFixer.anyNeedsSynthesis(
                                reward.getParameterTypes());
                        RewardLedger.beginInject();
                        try {
                            reward.invoke(target, args);
                        } finally {
                            RewardLedger.endInject();
                        }
                        sLastRewardAt = now;
                        Logs.i("关闭前补发奖励 " + reward.getName() + " @ "
                                + target.getClass().getName()
                                + (synthetic ? "（构造参数）" : ""));
                        fired = true;
                    } else {
                        RewardLedger.forget(target, sdk, methodKey);
                    }
                } catch (Throwable t) {
                    RewardLedger.forget(target, sdk, methodKey);
                    Logs.w("关闭前补发奖励失败: " + t);
                }
            }
        }
        java.util.List<Method> closes = closeMethodsFor(target);
        if (closes == null || closes.isEmpty()) return fired;

        /*
         * 结束回调必须按「看完 → 发奖 → 关闭」的顺序全部走一遍，不能只调第一个。
         *
         * 以前的写法是遍历到第一个能调起来的就 return，而 closeRank 把 onAdClose
         * 排在最前，结果 onVideoComplete 永远不被触发。游戏侧的状态机要求
         * 「视频播放完成」才会认这次奖励，光有 onReward 会被判成异常不发奖
         * （实测日志：onAdClose 触发了，onVideoComplete 一次都没出现，奖励不到账）。
         */
        boolean any = false;
        // 先「完成」类：onVideoComplete / onVideoEnd / onVideoSkipToEnd
        for (Method close : closes) {
            if (!isCompleteMethod(close.getName())) continue;
            if (invokeClose(close, target)) any = true;
        }
        // 再「关闭」类：onAdClose / onAdDismiss / onSkippedVideo
        for (Method close : closes) {
            if (isCompleteMethod(close.getName())) continue;
            if (invokeClose(close, target)) any = true;
        }

        /*
         * 聚合 SDK 的分层回调：上面只处理了「最后那个对象」。
         * App 自己的监听器（例如 TaKuReward$loadAd$1$1）在另一层，
         * 它的 onRewardedVideoAdPlayEnd 才是 App 判定「看完视频」的依据。
         * 这里对本轮出现过的其余层只补「播放完成」类回调，
         * 不碰奖励和关闭，避免重复发奖或重复关页面。
         */
        for (Object other : sessionTargets()) {
            if (other == null || other == target) continue;
            // 奖励也要逐层补：Cocos/Unity 这类游戏的桥接层
            // （RewardVideoHelper / VideoHelper）是最后一跳，
            // 只补完成回调不补 onReward，JS/C# 层永远收不到发奖信号。
            Method otherReward = rewardMethodFor(other);
            if (otherReward != null) {
                String osdk = rewardSdkFor(other);
                final String osdkName = osdk != null ? osdk : "close";
                final String okey = keyOf(otherReward);
                if (RewardLedger.tryInject(other, osdkName, okey, false)) {
                    try {
                        otherReward.setAccessible(true);
                        Object[] oargs = buildArgsPreferReal(otherReward.getParameterTypes(), null);
                        RewardLedger.beginInject();
                        try {
                            otherReward.invoke(other, oargs);
                        } finally {
                            RewardLedger.endInject();
                        }
                        Logs.i("补发其他层奖励 " + otherReward.getName() + " @ "
                                + other.getClass().getName());
                        fired = true;
                    } catch (Throwable t) {
                        RewardLedger.forget(other, osdkName, okey);
                        Logs.w("补发其他层奖励失败 " + otherReward.getName() + ": " + t);
                    }
                }
            }
            java.util.List<Method> list = closeMethodsFor(other);
            if (list == null) continue;
            for (Method m : list) {
                if (!isCompleteMethod(m.getName())) continue;
                if (invokeClose(m, other)) any = true;
            }
        }
        return fired || any;
    }

    /**
     * 伪造「观看开始时间」，让 App 上报的观看时长看起来足够长。
     *
     * <p>有些 App（聚合 + H5 那类）发奖不看客户端回调，而是把
     * 「播放开始时间 / 当前时间」上报给服务端，由服务端算时长决定发不发奖。
     * 只补回调不改这个时间，服务端永远判「没看完」。</p>
     *
     * <p>这里沿 this$0 找到外层对象（真实字段常挂在外部类上），
     * 把这个对象里的 playStartTime 之类往前挪一段。</p>
     */
    private static void fakePlayDuration(Object target) {
        if (target == null) return;
        java.util.Set<Object> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
        fakePlayDuration(target, seen, 0);
    }

    private static void fakePlayDuration(Object obj, java.util.Set<Object> seen, int depth) {
        if (obj == null || depth > 2 || !seen.add(obj)) return;
        long now = System.currentTimeMillis();
        for (Class<?> type = obj.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            java.lang.reflect.Field[] fields;
            try {
                fields = type.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (java.lang.reflect.Field f : fields) {
                try {
                    Class<?> ft = f.getType();
                    if (ft == long.class || ft == java.lang.Long.class) {
                        String n = f.getName().toLowerCase();
                        boolean looksLikeStart = n.contains("playstart")
                                || n.contains("videostart")
                                || n.contains("watchstart")
                                || n.contains("playstarttime");
                        if (!looksLikeStart) continue;
                        f.setAccessible(true);
                        Object cur = f.get(obj);
                        long v = cur instanceof Long ? (Long) cur : 0L;
                        // 只动「刚刚」的时间戳，避免误改无关时间
                        if (v <= 0 || Math.abs(now - v) > 10 * 60 * 1000L) continue;
                        f.set(obj, now - 35_000L);
                        Logs.i("已伪造观看时长: " + type.getSimpleName() + "."
                                + f.getName() + " -> 往前 35s");
                    } else if (!ft.isPrimitive() && depth < 2) {
                        String fn = f.getName();
                        if (!fn.startsWith("this$") && !fn.startsWith("val$")) continue;
                        f.setAccessible(true);
                        Object inner = f.get(obj);
                        if (inner != null) fakePlayDuration(inner, seen, depth + 1);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 登记本轮广告出现过的监听对象（去重）。 */
    private static void rememberSessionTarget(Object self) {
        if (self == null) return;
        synchronized (sSessionTargets) {
            for (java.util.Iterator<java.lang.ref.WeakReference<Object>> it =
                    sSessionTargets.iterator(); it.hasNext(); ) {
                Object old = it.next().get();
                if (old == null) {
                    it.remove();
                } else if (old == self) {
                    return;
                }
            }
            sSessionTargets.add(new java.lang.ref.WeakReference<>(self));
            if (sSessionTargets.size() > 32) sSessionTargets.remove(0);
        }
    }

    /** 新一轮广告展示开始时清空上一轮的登记。 */
    private static void clearSessionTargets() {
        synchronized (sSessionTargets) {
            sSessionTargets.clear();
        }
    }

    /** 本轮出现过的监听对象快照。 */
    private static java.util.List<Object> sessionTargets() {
        java.util.List<Object> out = new java.util.ArrayList<>();
        synchronized (sSessionTargets) {
            for (java.util.Iterator<java.lang.ref.WeakReference<Object>> it =
                    sSessionTargets.iterator(); it.hasNext(); ) {
                Object o = it.next().get();
                if (o == null) {
                    it.remove();
                } else {
                    out.add(o);
                }
            }
        }
        return out;
    }

    /** 按 target 的类层级 + 全部接口，找已注册的奖励方法。 */
    private static Method rewardMethodFor(Object target) {
        if (target == null) return null;
        for (Class<?> t : classHierarchy(target)) {
            synchronized (sRewardMethods) {
                Method m = sRewardMethods.get(t);
                if (m != null) return m;
            }
        }
        return null;
    }

    /** 同上，取这个奖励方法对应的 SDK 命名空间。 */
    private static String rewardSdkFor(Object target) {
        if (target == null) return null;
        for (Class<?> t : classHierarchy(target)) {
            synchronized (sRewardMethods) {
                if (!sRewardMethods.containsKey(t)) continue;
                String sdk = sRewardSdk.get(t);
                if (sdk != null) return sdk;
            }
        }
        return null;
    }

    /** target 的类、父类、全部接口（含接口的父接口）。 */
    private static java.util.List<Class<?>> classHierarchy(Object target) {
        java.util.List<Class<?>> out = new java.util.ArrayList<>();
        if (target == null) return out;
        java.util.Set<Class<?>> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<Class<?>> queue = new java.util.ArrayDeque<>();
        queue.add(target.getClass());
        while (!queue.isEmpty()) {
            Class<?> t = queue.poll();
            if (t == null || t == Object.class || !seen.add(t)) continue;
            out.add(t);
            Class<?> p = t.getSuperclass();
            if (p != null) queue.add(p);
            for (Class<?> i : t.getInterfaces()) queue.add(i);
        }
        return out;
    }

    /** 是否是「视频播放完成」这类回调（游戏发奖的前提条件）。 */
    private static boolean isCompleteMethod(String name) {
        return name.contains("onVideoComplete")
                || name.contains("onVideoEnd")
                || name.contains("onVideoSkipToEnd")
                || name.contains("PlayEnd");
    }

    /**
     * 取出这个广告对象可用的结束回调。
     *
     * <p>注册时 sCloseMethods 的 key 是 {@code method.getDeclaringClass()}，
     * 也就是「声明方法的接口」；而这里拿到的 target 是「运行时实现类」。
     * 两者通常不相等（例如声明类是 KsRewardVideoAd$RewardAdInteractionListener，
     * 运行时是 KsRewardVideoAdControl$1 这个匿名内部类），
     * 直接 get(target.getClass()) 永远查不到，结束回调一次都不会补发，
     * App 侧因此认为「没看完视频」而不给奖励。</p>
     *
     * <p>这里按 target 的父类和全部接口（含接口的父接口）逐个匹配。</p>
     */
    private static java.util.List<Method> closeMethodsFor(Object target) {
        if (target == null) return null;
        java.util.LinkedHashMap<String, Method> merged = new java.util.LinkedHashMap<>();
        java.util.Set<Class<?>> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<Class<?>> queue = new java.util.ArrayDeque<>();
        queue.add(target.getClass());
        synchronized (sCloseMethods) {
            while (!queue.isEmpty()) {
                Class<?> type = queue.poll();
                if (type == null || !seen.add(type)) continue;
                if (type != Object.class) {
                    java.util.List<Method> list = sCloseMethods.get(type);
                    if (list != null) {
                        for (Method m : list) merged.putIfAbsent(m.getName(), m);
                    }
                }
                Class<?> parent = type.getSuperclass();
                if (parent != null) queue.add(parent);
                for (Class<?> itf : type.getInterfaces()) queue.add(itf);
            }
        }
        if (merged.isEmpty()) return null;
        java.util.List<Method> out = new java.util.ArrayList<>(merged.values());
        // 重新按「完成 → 关闭」排序，合并后顺序不可信
        java.util.Collections.sort(out, new java.util.Comparator<Method>() {
            @Override
            public int compare(Method a, Method b) {
                return closeRank(a.getName()) - closeRank(b.getName());
            }
        });
        return out;
    }

    /**
     * 从本轮真实回调里找同类型（或父类型）的对象。
     *
     * <p>ATAdInfo 这类聚合 SDK 的 final 对象构造不出非空实例，
     * 但真实回调一定带着它；补发时复用它，App 才不会读到 null。</p>
     */
    private static Object realArgFor(Class<?> type) {
        if (type == null) return null;
        if (type.isPrimitive()) return null;
        java.lang.ref.WeakReference<Object> ref = sRealArgByType.get(type);
        Object direct = ref == null ? null : ref.get();
        if (direct != null) return direct;
        synchronized (sRealArgByType) {
            for (java.util.Map.Entry<Class<?>, java.lang.ref.WeakReference<Object>> e
                    : sRealArgByType.entrySet()) {
                if (!type.isAssignableFrom(e.getKey())) continue;
                Object value = e.getValue() == null ? null : e.getValue().get();
                if (value != null) return value;
            }
        }
        return null;
    }

    /** 构造回调实参：真实对象 > 触发回调里的实参 > 默认值。 */
    private static Object[] buildArgsPreferReal(Class<?>[] types, Object[] triggerArgs) {
        if (types == null || types.length == 0) return new Object[0];
        Object[] out = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            Object value = null;
            if (triggerArgs != null && !type.isPrimitive()) {
                for (Object arg : triggerArgs) {
                    if (arg != null && type.isInstance(arg)) {
                        value = arg;
                        break;
                    }
                }
            }
            if (value == null) value = realArgFor(type);
            if (value == null) value = ArgFixer.defaultValue(type);
            out[i] = value;
        }
        return out;
    }

    /** 调一个结束回调，失败只记日志。带参数的方法用 ArgFixer 构造入参。 */
    private static boolean invokeClose(Method close, Object target) {
        try {
            close.setAccessible(true);
            Class<?>[] types = close.getParameterTypes();
            Object[] args = buildArgsPreferReal(types, null);
            close.invoke(target, args);
            Logs.i("关闭前替 SDK 触发结束回调 " + close.getName()
                    + " @ " + target.getClass().getName());
            return true;
        } catch (Throwable t) {
            Logs.w("关闭前触发结束回调失败 " + close.getName() + ": " + t);
            return false;
        }
    }

    /**
     * 复制一份参数，把其中的字符串（一般是广告 ID / 交易号）换成唯一值。
     * 很多游戏按“这笔奖励只发一次”去重，换掉 ID 才有机会拿到多份。
     */

    /** 比默认优先级（50）高一点，保证参数修复发生在 App 自己的回调逻辑之前。 */
    private static final int PRIORITY = XposedInterface.PRIORITY_DEFAULT + 50;

    private static Handler sMain;
    private static volatile AdHookEngine sEngine;
    /** 已经懒扫描过的插件 ClassLoader，避免重复扫。 */
    private static final Set<ClassLoader> LAZY_SCANNED =
            Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>());
    private static final AtomicInteger LAZY_COUNT = new AtomicInteger();
    private final XposedInterface xi;
    /** 已 hook 的方法签名，避免重复 hook。 */
    private final Map<String, Boolean> hooked = new ConcurrentHashMap<>();
    /** 类名 -> (方法名 -> 同名重载方法列表)，补发奖励时直接用，省掉重复反射。 */
    private final Map<String, Map<String, List<Method>>> classMethods = new ConcurrentHashMap<>();

    public AdHookEngine(XposedInterface xi) {
        this.xi = xi;
        sEngine = this;
        // 让 ArgFixer 在遇到 null 对象参数时能取到同轮广告的真实对象
        ArgFixer.setRealArgSource(new ArgFixer.RealArgSource() {
            @Override
            public Object find(Class<?> type) {
                java.lang.ref.WeakReference<Object> ref = sRealArgByType.get(type);
                Object direct = ref == null ? null : ref.get();
                if (direct != null) return direct;
                // 退一步：找兼容子类型（接口/父类）
                synchronized (sRealArgByType) {
                    for (java.util.Map.Entry<Class<?>, java.lang.ref.WeakReference<Object>> e
                            : sRealArgByType.entrySet()) {
                        if (type.isAssignableFrom(e.getKey())) {
                            Object v = e.getValue() == null ? null : e.getValue().get();
                            if (v != null) return v;
                        }
                    }
                }
                return null;
            }
        });
    }

    /** 扫描结果，交给主线程去装 hook。 */
    public static final class ScanResult {
        final List<Rules.Rule> rules;
        final Map<String, List<String>> impls;

        ScanResult(List<Rules.Rule> rules, Map<String, List<String>> impls) {
            this.rules = rules;
            this.impls = impls;
        }
    }

    /** 只做 dex 扫描，可以在后台线程跑。 */
    public ScanResult scan(ApplicationInfo info) {
        final android.content.Context ctx = AppCtx.get();
        List<File> apks = DexIndex.apkPathsOf(info);
        List<Rules.Rule> rules = Rules.enabled();
        Logs.raw("开始扫描 dex，APK 数=" + apks.size() + "，规则数=" + rules.size());
        long start = System.currentTimeMillis();

        String key = scanKey(rules, apks);
        File cache = cacheFile(info);
        Map<String, List<String>> impls = readCache(cache, key, rules);
        if (impls != null) {
            Logs.raw("扫描缓存命中，用时 " + (System.currentTimeMillis() - start) + "ms");
            return new ScanResult(rules, impls);
        }

        // 第一步只跑 DexIndex（10ms 级），让 hook 尽快装上，不拖 App 启动。
        // DexKit 的全量索引（1s 级）放到 hook 生效之后的后台线程补，见 slowRescan()。
        ImplFinder.Result found = ImplFinder.findFast(rules, apks);
        impls = found.impls;
        Logs.raw("dex 快速扫描完成，用时 " + (System.currentTimeMillis() - start) + "ms");
        if (cache != null && found.classCount > 0) {
            writeCache(cache, key, impls);
        }
        return new ScanResult(rules, impls);
    }

    private static File cacheFile(ApplicationInfo info) {
        if (info == null || info.dataDir == null) return null;
        try {
            File dir = new File(info.dataDir, "cache");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            return new File(dir, "tunnet_scan.cache");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String scanKey(List<Rules.Rule> rules, List<File> apks) {
        StringBuilder sb = new StringBuilder();
        sb.append("v2");
        for (Rules.Rule rule : rules) {
            // 方法名必须参与，否则升级后「只加了新方法名」的规则会命中旧缓存，
            // 新方法对应的实现类永远扫不出来
            sb.append('|').append(rule.sdk).append(':').append(rule.targetDesc);
            for (String n : rule.rewardNames) sb.append('+').append(n);
            for (String n : rule.triggerNames) sb.append('~').append(n);
            for (String n : rule.errorNames) sb.append('-').append(n);
        }
        for (File file : apks) {
            sb.append('|').append(file.getAbsolutePath())
                    .append(':').append(file.length())
                    .append(':').append(file.lastModified());
        }
        return sha256(sb.toString());
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static Map<String, List<String>> readCache(File cache, String key, List<Rules.Rule> rules) {
        if (cache == null || !cache.isFile()) return null;
        if (cache.length() <= 0 || cache.length() > 4L * 1024 * 1024) return null;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(cache), StandardCharsets.UTF_8));
            if (!"TUNNET_SCAN".equals(reader.readLine())) return null;
            if (!"2".equals(reader.readLine())) return null;
            if (!key.equals(reader.readLine())) return null;

            Map<String, List<String>> out = new LinkedHashMap<>();
            for (Rules.Rule rule : rules) {
                out.put(rule.targetDesc, new ArrayList<String>());
            }
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                List<String> list = out.get(line.substring(0, tab));
                if (list == null) continue;
                String rest = line.substring(tab + 1);
                if (rest.isEmpty()) continue;
                for (String className : rest.split(",")) {
                    if (!className.isEmpty()) list.add(className);
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 清理上次崩溃/被杀留下的临时缓存文件，避免 cache 目录越积越多。 */
    private static void cleanupTemp(File cache) {
        File dir = cache.getParentFile();
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            String name = file.getName();
            if (!name.startsWith(cache.getName() + ".tmp.")) continue;
            if (now - file.lastModified() > 10L * 60 * 1000) {
                file.delete();
            }
        }
    }

    private static void writeCache(File cache, String key, Map<String, List<String>> impls) {
        if (cache == null || impls == null) return;
        cleanupTemp(cache);
        File tmp = new File(cache.getParentFile(),
                cache.getName() + ".tmp." + android.os.Process.myPid());
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(tmp), StandardCharsets.UTF_8));
            writer.write("TUNNET_SCAN\n");
            writer.write("2\n");
            writer.write(key);
            writer.write('\n');
            for (Map.Entry<String, List<String>> entry : impls.entrySet()) {
                writer.write(entry.getKey());
                writer.write('\t');
                List<String> list = entry.getValue();
                if (list != null) {
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) writer.write(',');
                        writer.write(list.get(i));
                    }
                }
                writer.write('\n');
            }
            writer.flush();
            writer.close();
            writer = null;
            if (!tmp.renameTo(cache)) {
                cache.delete();
                if (!tmp.renameTo(cache)) {
                    tmp.delete();
                    return;
                }
            }
            Logs.raw("扫描缓存已写入");
        } catch (Throwable t) {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Throwable ignored) {
                }
            }
            tmp.delete();
        }
    }

    /** 安装 hook，必须在主线程调用（LSPosed 在后台线程装 hook 会卡住）。 */
    public int apply(ClassLoader loader, ScanResult result) {
        if (loader == null || result == null) return 0;
        List<Rules.Rule> rules = result.rules;
        Map<String, List<String>> impls = result.impls;

        int sdkCount = 0;
        int hookCount = 0;
        for (Rules.Rule rule : rules) {
            List<String> classes = impls.get(rule.targetDesc);
            if (classes != null && !classes.isEmpty()) {
                sdkCount++;
                Logs.raw("命中 SDK " + rule.sdk + "，实现类 " + classes.size() + " 个");
                for (String className : classes) {
                    hookCount += hookImpl(rule, loader, className);
                }
            } else if (classExists(rule, loader)) {
                int n = hookInterfaceFallback(rule, loader);
                if (n > 0) sdkCount++;
                hookCount += n;
            }
        }


        String summary = "TunNet 已注入 · SDK " + sdkCount + " · Hook " + hookCount;
        Logs.raw(summary);
        if (Config.toast() && hookCount > 0) {
            Toaster.show(summary);
        }
        return hookCount;
    }

    /**
     * DexKit 慢速补全：在 hook 已经生效之后跑，找出 DexIndex 漏掉的实现类。
     *
     * 完全在后台线程执行，找到新类后回主线程增量安装 hook，不影响已生效的部分。
     * 这样启动关键路径只有 10ms 级的 DexIndex，DexKit 的 1s 级全量索引被挪出冷启动。
     */
    public void slowRescan(ClassLoader loader, ApplicationInfo info, ScanResult fast) {
        if (loader == null || fast == null || info == null) return;
        final List<Rules.Rule> rules = fast.rules;
        final Map<String, List<String>> known = fast.impls;


        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {
                }
                long start = System.currentTimeMillis();
                try {
                    ImplFinder.Result extra = ImplFinder.findExtra(AppCtx.get(), rules, known);
                    int total = 0;
                    for (List<String> list : extra.impls.values()) {
                        if (list != null) total += list.size();
                    }
                    if (total == 0) {
                        Logs.raw("DexKit 补扫无新增，用时 "
                                + (System.currentTimeMillis() - start) + "ms");
                        ImplFinder.release(AppCtx.get());
                        return;
                    }
                    Logs.raw("DexKit 补扫新增 " + total + " 个类，用时 "
                            + (System.currentTimeMillis() - start) + "ms");
                    final Map<String, List<String>> merged = new LinkedHashMap<>();
                    for (Rules.Rule rule : rules) {
                        List<String> all = new ArrayList<>();
                        List<String> a = known.get(rule.targetDesc);
                        List<String> b = extra.impls.get(rule.targetDesc);
                        if (a != null) all.addAll(a);
                        if (b != null) all.addAll(b);
                        merged.put(rule.targetDesc, all);
                    }
                    // 把补扫结果并进扫描缓存：下次启动直接命中，不用再跑 1s 级的 DexKit。
                    try {
                        List<File> apks = DexIndex.apkPathsOf(info);
                        File cache = cacheFile(info);
                        if (cache != null && !apks.isEmpty()) {
                            writeCache(cache, scanKey(rules, apks), merged);
                            Logs.raw("DexKit 补扫结果已写入缓存");
                        }
                    } catch (Throwable t) {
                        Logs.w("DexKit 补扫缓存写入失败: " + t);
                    }
                    // 索引已经转成结果，native 那边不用再常驻
                    try {
                        ImplFinder.release(AppCtx.get());
                    } catch (Throwable ignored) {
                    }
                    mainHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                int hooks = apply(loader, new ScanResult(rules, merged));
                                Logs.raw("DexKit 补扫 hook 安装完成，数量=" + hooks);
                            } catch (Throwable t) {
                                Logs.w("DexKit 补扫安装失败: " + t);
                            }
                        }
                    });
                } catch (Throwable t) {
                    Logs.w("DexKit 补扫失败: " + t);
                }
            }
        }, "TunNet-dexkit");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 广告页出现时，如果它的 ClassLoader 不是主 App 的，说明来自动态插件：
     * 扫一遍该 loader 的 dex/apk，补 hook 标准 SDK 实现类。
     */
    public static void lazyRescan(ClassLoader loader) {
        final AdHookEngine engine = sEngine;
        if (engine == null || loader == null) return;
        if (loader == AdHookEngine.class.getClassLoader()) return;
        // 上限内允许重扫；以前只增不减，累计 8 个 loader 后本进程永久停用
        if (LAZY_COUNT.get() >= 32) return;
        synchronized (LAZY_SCANNED) {
            if (!LAZY_SCANNED.add(loader)) return;
        }
        final List<File> paths = dexPathsOf(loader);
        if (paths.isEmpty()) {
            synchronized (LAZY_SCANNED) {
                LAZY_SCANNED.remove(loader);
            }
            return;
        }
        LAZY_COUNT.incrementAndGet();


        Logs.raw("动态插件懒扫描: " + paths.size() + " 个 dex/apk");
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final android.content.Context lazyCtx = AppCtx.get();
                    final ImplFinder.Result found = ImplFinder.find(lazyCtx, Rules.enabled(), paths);
                    boolean any = false;
                    for (List<String> classes : found.impls.values()) {
                        if (classes != null && !classes.isEmpty()) {
                            any = true;
                            break;
                        }
                    }
                    if (!any) return;
                    final ScanResult result = new ScanResult(Rules.enabled(), found.impls);
                    mainHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            engine.apply(loader, result);
                        }
                    });
                } catch (Throwable t) {
                    Logs.w("动态插件懒扫描失败: " + t);
                }
            }
        }, "TunNet-lazy-scan");
        thread.setDaemon(true);
        thread.start();
    }

    private static List<File> dexPathsOf(ClassLoader loader) {
        List<File> out = new ArrayList<>();
        try {
            Class<?> base = Class.forName("dalvik.system.BaseDexClassLoader");
            if (!base.isInstance(loader)) return out;
            Field pathListField = base.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(loader);
            if (pathList == null) return out;
            Field elementsField = pathList.getClass().getDeclaredField("dexElements");
            elementsField.setAccessible(true);
            Object[] elements = (Object[]) elementsField.get(pathList);
            if (elements == null) return out;
            for (Object element : elements) {
                File file = elementPath(element);
                if (file == null || !file.isFile()) continue;
                if (!out.contains(file)) out.add(file);
            }
        } catch (Throwable t) {
            Logs.w("读取插件 dex 路径失败: " + t);
        }
        return out;
    }

    private static File elementPath(Object element) {
        if (element == null) return null;
        for (String name : new String[]{"path", "file"}) {
            try {
                Field field = element.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(element);
                if (value instanceof File) return (File) value;
                if (value instanceof String) return new File((String) value);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private boolean classExists(Rules.Rule rule, ClassLoader loader) {
        try {
            String name = ImplFinder.toName(rule.targetDesc);
            if (name == null) return false;
            Class.forName(name, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int hookImpl(Rules.Rule rule, ClassLoader loader, String className) {
        try {
            Class<?> clazz = Class.forName(className, false, loader);
            Map<String, List<Method>> methods = collectMethods(clazz);
            classMethods.put(className, methods);

            int hits = 0;
            for (Map.Entry<String, List<Method>> entry : methods.entrySet()) {
                String name = entry.getKey();
                boolean reward = rule.isReward(name);
                boolean trigger = rule.isTrigger(name);
                boolean error = rule.isError(name);
                if (!reward && !trigger && !error) continue;
                for (Method method : entry.getValue()) {
                    // 用多个独立 if 而不是 else-if：同一个方法名可能在一条规则里
                    // 同时是 reward 和 trigger（如 LIQUID 的 onVideoComplete），
                    // else-if 会静默丢掉后面的语义。hooked 去重保证不会重复安装。
                    if (reward && installReward(rule, method)) hits++;
                    if (trigger && installTrigger(rule, method)) hits++;
                    if (error && installError(rule, method)) hits++;
                }
            }
            return hits;
        } catch (Throwable t) {
            Logs.w("加载实现类失败 " + className + " : " + t);
            return 0;
        }
    }

    /** 收集类及其父类的实例方法（跳过静态、抽象、合成方法），同名重载全部保留。 */
    private Map<String, List<Method>> collectMethods(Class<?> clazz) {
        Map<String, List<Method>> out = new LinkedHashMap<>();
        Class<?> current = clazz;
        int depth = 0;
        while (current != null && current != Object.class && depth++ < 24) {
            Method[] declared;
            try {
                declared = current.getDeclaredMethods();
            } catch (Throwable t) {
                break;
            }
            for (Method method : declared) {
                int mod = method.getModifiers();
                if (Modifier.isAbstract(mod) || Modifier.isStatic(mod)) continue;
                if (method.isSynthetic() || method.isBridge()) continue;
                String name = method.getName();
                if (name.startsWith("lambda$")) continue;
                List<Method> list = out.get(name);
                if (list == null) {
                    list = new ArrayList<>(2);
                    out.put(name, list);
                }
                if (hasOverload(list, method)) continue;
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                }
                list.add(method);
            }
            current = current.getSuperclass();
        }
        return out;
    }

    private static boolean hasOverload(List<Method> methods, Method candidate) {
        Class<?>[] types = candidate.getParameterTypes();
        for (Method method : methods) {
            if (Arrays.equals(method.getParameterTypes(), types)) return true;
        }
        return false;
    }

    private void rememberRewardMethod(Method method, String sdk) {
        String name = method.getName();
        if (!name.equals("onRewardVerify") && !name.equals("onRewardArrived")
                && !name.equals("onReward") && !name.equals("onRewardSuccess")
                && !name.equals("onRewarded") && !name.equals("onAdReward")
                && !name.equals("onFeedRewardCountDown")) {
            return;
        }
        Class<?>[] types = method.getParameterTypes();
        boolean hasBoolean = types.length > 0 && types[0] == boolean.class;
        synchronized (sRewardMethods) {
            Method old = sRewardMethods.get(method.getDeclaringClass());
            if (old == null || hasBoolean) {
                sRewardMethods.put(method.getDeclaringClass(), method);
                sRewardSdk.put(method.getDeclaringClass(), sdk == null ? "close" : sdk);
            }
        }
    }

    /** 广告回调对象在广告展示前就构造出来，构造时记一份，即使 SDK 不回 onAdShow 也能补发。 */
    private void captureInstances(final Class<?> clazz) {
        synchronized (sCtorHooked) {
            if (clazz == null || !sCtorHooked.add(clazz)) return;
        }
        try {
            for (java.lang.reflect.Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                final java.lang.reflect.Constructor<?> target = ctor;
                try {
                    target.setAccessible(true);
                    xi.hook(target).setPriority(PRIORITY).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            Object self = chain.getThisObject();
                            if (self != null) {
                                sLastAdTargetRef = new java.lang.ref.WeakReference<>(self);
                                sLastAdAt = System.currentTimeMillis();
                                rememberSessionTarget(self);
                                if (Logs.isEnabled()) {
                                    Logs.d("捕获广告实例 " + self.getClass().getName());
                                }
                            }
                            return result;
                        }
                    });
                } catch (Throwable ignored) {
                    // 单个构造函数失败不影响其他构造函数
                }
            }
        } catch (Throwable t) {
            // 整体失败：把标记撤回，否则这个类的实例捕获在本进程里永久失效，
            // 连带 fireLastClose 的 sLastAdTargetRef 一直拿不到值
            synchronized (sCtorHooked) {
                sCtorHooked.remove(clazz);
            }
        }
    }

    private boolean installReward(final Rules.Rule rule, final Method method) {
        final String key = keyOf(method);
        if (hooked.containsKey(key)) return false;
        final Class<?>[] types = method.getParameterTypes();
        final Class<?> returnType = method.getReturnType();
        try {
            xi.hook(method)
                    .setPriority(PRIORITY)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            try {
                                Object self = chain.getThisObject();
                                if (Logs.isEnabled()) Logs.d("奖励回调: " + key);

                                // 我们自己补发进去的调用：只修参数，不做去重
                                if (RewardLedger.isInjecting()) {
                                    Object[] fixed = ArgFixer.fixRewardArgs(types, toArray(chain.getArgs()));
                                    return fixed != null ? chain.proceed(fixed) : chain.proceed();
                                }

                                // 补发后 App 自己又回了一次：吞掉，避免重复发奖
                                if (self != null && RewardLedger.shouldSwallow(self, rule.sdk, key)) {
                                    return ArgFixer.defaultValue(returnType);
                                }

                                if (self != null) RewardLedger.markNatural(self, rule.sdk, key);

                                Object[] original = toArray(chain.getArgs());
                                // 真实回调（不是我们补发进去的）把实参存一份，关闭时补发直接复用，
                                // 避免自造空壳参数被 App 读字段 NPE
                                if (original != null && original.length > 0) {
                                    // 带时间戳与会话代次：只代表「本轮」，不能用来判断「发过没发过」
                                    sRealRewardArgs.put(key, new RealArgs(
                                            original.clone(),
                                            System.currentTimeMillis(),
                                            self == null ? 0 : RewardLedger.sessionOfPublic(self)));
                                    // 同时按类型记一份：同一次广告的多个监听器共享同一个真实对象
                                    for (Object arg : original) {
                                        if (arg != null) {
                                            sRealArgByType.put(arg.getClass(),
                                                    new java.lang.ref.WeakReference<>(arg));
                                        }
                                    }
                                }
                                // 不修改档：不重写 boolean/int，但 null 的对象参数必须补
                                // （TopOn 在百度/快手源下 ATAdInfo 传 null，App 侧是 Kotlin
                                //   非空参数，直接 NPE，奖励就到不了账）
                                final boolean passthrough = Config.rewardPassthrough();
                                if (passthrough && Logs.isEnabled()) {
                                    Logs.d("奖励回调原样放行(倍率不动，仅 1 次): " + key);
                                }
                                // 参数为 null 时补不补，由设置开关控制（默认关）。
                                // 有些聚合层（TopOn）在百度/快手源下会把 ATAdInfo 传 null，
                                // 而 App 侧监听器是 Kotlin 非空参数，直接 NPE，奖励到不了账。
                                // 但 Java 写法的 null 往往有业务含义，所以默认不补。
                                Object[] fixed;
                                if (passthrough) {
                                    fixed = Config.fillNullArgs()
                                            ? ArgFixer.fillNullArgs(types, original)
                                            : null;
                                } else {
                                    fixed = ArgFixer.fixRewardArgs(types, original);
                                }
                                Object result = fixed != null ? chain.proceed(fixed) : chain.proceed();
                                if (!passthrough) {
                                    multiplyNaturalReward(self, method, fixed, original);
                                }
                                return result;
                            } catch (Throwable t) {
                                Logs.w("奖励回调执行异常 " + key + " : " + t);
                                throw t;
                            }
                        }
                    });
            hooked.put(key, Boolean.TRUE);
            rememberRewardMethod(method, rule.sdk);
            captureInstances(method.getDeclaringClass());
            Logs.i("hook 奖励回调 " + key + " [" + rule.sdk + "]");
            return true;
        } catch (Throwable t) {
            Logs.w("hook 失败 " + key + " : " + t);
            return false;
        }
    }

    /**
     * 原生奖励回调额外补发 (倍率-1) 次：用注入标记绕过本 hook，
     * 不再走去重/记账，让 App 自己的奖励逻辑多跑几次。
     */
    /**
     * 原生奖励回调额外补发 (倍率-1) 次，使总调用次数正好等于倍率。
     *
     * 注意：这里只补发「额外」次数，因为 chain.proceed() 已经产出了第 1 次。
     */
    private static void multiplyNaturalReward(Object self, Method method,
                                              Object[] fixed, Object[] original) {
        int times = Config.rewardMultiplier();
        if (times <= 1 || self == null || method == null) return;
        // 补发过程中（含跨线程回声）绝不再放大，否则会递归叠加
        if (RewardLedger.isInjecting()) return;
        Object[] args = fixed != null ? fixed : original;
        RewardLedger.beginInject();
        try {
            for (int i = 1; i < times; i++) {
                try {
                    method.invoke(self, args);
                } catch (Throwable t) {
                    Logs.w("奖励倍率额外回调失败: " + t);
                }
            }
        } finally {
            RewardLedger.endInject();
        }
    }




    /**
     * 取本轮广告的真实回调参数。
     *
     * <p>只在本会话 + TTL 内返回，否则 null 走重新构造。
     * 以前无条件复用上一轮缓存，会拿旧交易号/旧 placement 去补发：
     * 服务端按 ID 去重会判重复拒绝，或发到错误档位。</p>
     */
    private static Object[] freshRealArgs(String methodKey, int expectedLen, Object self) {
        if (self == null) return null;
        RealArgs real = sRealRewardArgs.get(methodKey);
        if (real == null) return null;
        if (real.args == null || real.args.length != expectedLen) return null;
        if (real.session != RewardLedger.sessionOfPublic(self)) return null;
        if (System.currentTimeMillis() - real.at > REAL_ARGS_TTL_MS) return null;
        return real.args;
    }

    /** 关闭回调优先级：数字越小越先调用。 */
    private static int closeRank(String name) {
        if (name.contains("onAdClose") || name.contains("onADClose")) return 0;
        if (name.contains("onVideoComplete") || name.contains("onVideoEnd")
                || name.contains("PlayEnd") || name.contains("onVideoSkipToEnd")) return 1;
        if (name.contains("onSkipped") || name.contains("onAdDismiss")) return 2;
        return 3;
    }

    private static boolean isCloseMethod(String key) {
        return key.contains("onAdClose") || key.contains("onADClose")
                || key.contains("onSkippedVideo") || key.contains("onVideoComplete")
                || key.contains("onAdDismiss") || key.contains("onRewardVerify")
                // 播放结束：App 用它给「是否看完」置位，缺了它就会被判成没看完整
                || key.contains("PlayEnd") || key.contains("onVideoEnd")
                || key.contains("onVideoSkipToEnd");
    }

    /**
     * 挂钩错误/失败回调，只记日志，不改变原有返回值。
     *
     * <p>之前规则表里没有这类方法，导致「播放失败」时模块一条记录都没有，
     * 无法区分是没填充、网络错还是平台审核拒绝。现在把错误码、错误信息
     * 原样打进 tunnet.log。</p>
     */
    private boolean installError(final Rules.Rule rule, final Method method) {
        final String key = keyOf(method);
        if (hooked.containsKey(key)) return false;
        final Class<?>[] types = method.getParameterTypes();
        try {
            xi.hook(method)
                    .setPriority(PRIORITY)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result;
                            try {
                                Object[] args = toArray(chain.getArgs());
                                Logs.e("广告失败 " + key + " [" + rule.sdk + "] "
                                        + describeErrorArgs(types, args));
                            } catch (Throwable t) {
                                Logs.w("记录广告失败回调出错: " + t);
                            }
                            try {
                                result = chain.proceed();
                            } catch (Throwable t) {
                                Logs.w("广告失败回调内部抛出异常 " + key + " : " + t);
                                throw t;
                            }
                            return result;
                        }
                    });
            hooked.put(key, Boolean.TRUE);
            Logs.i("hook 错误回调 " + key + " [" + rule.sdk + "]");
            return true;
        } catch (Throwable t) {
            Logs.w("hook 错误回调失败 " + key + " : " + t);
            return false;
        }
    }

    /** 把错误回调的实参转成可读文本：错误码原样输出，Throwable 取 message，其余 toString。 */
    private static String describeErrorArgs(Class<?>[] types, Object[] args) {
        if (args == null || args.length == 0) return "";
        StringBuilder sb = new StringBuilder("argc=").append(args.length).append(' ');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            String typeName = i < types.length ? simpleName(types[i]) : "?";
            sb.append('(').append(i).append(':').append(typeName).append(")=");
            Object value = args[i];
            if (value == null) {
                sb.append("null");
                continue;
            }
            if (value instanceof Throwable) {
                Throwable t = (Throwable) value;
                sb.append(t.getClass().getName()).append(':').append(t.getMessage());
                continue;
            }
            // 广告 SDK 的错误对象基本都不重写 toString()，
            // 例如 com.qq.e.comm.util.AdError 打出来只有 "AdError@6522f57"，
            // 拿不到错误码等于没记。这里用反射把常见取值方法读出来。
            String detail = describeObject(value);
            sb.append(detail != null ? detail : safeToString(value));
        }
        return sb.toString();
    }

    /**
     * 反射读对象的常用字段/方法，拼成可读文本。
     *
     * <p>错误对象、结果对象通常是混淆过的，没有 toString()。
     * 依次尝试 getErrorCode / getCode / getErrorMsg / getMessage / getMsg 等命名，
     * 任意一个取到值就返回。</p>
     */
    private static String describeObject(Object value) {
        if (value == null) return null;
        Class<?> clazz = value.getClass();
        String[] codeNames = {"getErrorCode", "getCode", "getErrCode", "getErrorNo"};
        String[] msgNames = {"getErrorMsg", "getMessage", "getMsg",
                "getErrorString", "getDesc", "getDescription"};

        Object code = invokeNoArg(clazz, value, codeNames);
        Object msg = invokeNoArg(clazz, value, msgNames);
        if (code == null && msg == null) return null;

        StringBuilder sb = new StringBuilder();
        if (code != null) sb.append("code=").append(safeToString(code));
        if (msg != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("msg=").append(safeToString(msg));
        }
        return sb.toString();
    }

    private static Object invokeNoArg(Class<?> clazz, Object target, String[] names) {
        for (String name : names) {
            try {
                Method m = clazz.getMethod(name);
                if (m.getParameterCount() != 0) continue;
                m.setAccessible(true);
                Object result = m.invoke(target);
                if (result == null) continue;
                String text = String.valueOf(result);
                if (!text.isEmpty()) return result;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String safeToString(Object value) {
        try {
            return String.valueOf(value);
        } catch (Throwable t) {
            return "<toString 失败>";
        }
    }

    private static String simpleName(Class<?> type) {
        if (type == null) return "?";
        String name = type.getName();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private boolean installTrigger(final Rules.Rule rule, final Method method) {
        final String key = keyOf(method);
        if (hooked.containsKey(key)) return false;
        final boolean start = isStartMethod(key);
        // 这个游戏的 onAdClose 会读取 onRewardVerify 写下的奖励标志，
        // 必须在关闭前先把标志置 true，否则 JS 收到 onClose(reward=false)。
        final boolean liquidClose = "LIQUID".equals(rule.sdk) && key.contains("onAdClose");
        // 记下可以作为"结束"触发的方法，自动关闭广告时用它替 SDK 收尾
        if (isCloseMethod(key)) {
            synchronized (sCloseMethods) {
                java.util.List<Method> list = sCloseMethods.get(method.getDeclaringClass());
                if (list == null) {
                    list = new java.util.ArrayList<>(4);
                    sCloseMethods.put(method.getDeclaringClass(), list);
                }
                boolean dup = false;
                for (Method old : list) {
                    if (old.getName().equals(method.getName())) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    list.add(method);
                    // 关闭前优先走 onAdClose：App 的奖励上报挂在这条上，
                    // onVideoComplete 往往只在特定配置下才转调 onAdClose。
                    java.util.Collections.sort(list, new java.util.Comparator<Method>() {
                        @Override
                        public int compare(Method a, Method b) {
                            return closeRank(a.getName()) - closeRank(b.getName());
                        }
                    });
                }
            }
        }
        try {
            xi.hook(method)
                    .setPriority(PRIORITY)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result;
                            if (Logs.isEnabled()) Logs.d("触发回调: " + key);
                            final Object self = chain.getThisObject();
                            if (self != null) {
                                sLastAdTargetRef = new java.lang.ref.WeakReference<>(self);
                                sLastAdAt = System.currentTimeMillis();
                                rememberSessionTarget(self);
                            }
                            // 触发方法里的真实参数（placement id / ATAdInfo 等）留给补发复用
                            final Object[] triggerArgs = toArray(chain.getArgs());
                            // 展示/结束回调里的真实对象（尤其 ATAdInfo）留一份。
                            // 自动关闭时回调已经离开，构造不出同类型的非空实例，
                            // 只能复用这里存下的真实对象，否则 App 收到 null 会
                            // 当成“没看完”或直接空指针，奖励就发不出来。
                            if (triggerArgs != null) {
                                for (Object arg : triggerArgs) {
                                    if (arg != null) {
                                        sRealArgByType.put(arg.getClass(),
                                                new java.lang.ref.WeakReference<>(arg));
                                    }
                                }
                            }
                            if (start) {
                                // 新一轮广告展示：让上一轮的去重记账过期，
                                // 并清空上一轮登记的监听对象（否则旧对象会被当成同一轮去补发）
                                clearSessionTargets();
                                rememberSessionTarget(self);
                                final int session = RewardLedger.beginSession(self);
                                // 展示类回调：按设置决定补发时机，默认立即（和 1.0 一致）
                                // 展示回调一进来就立即补发，不强制延迟。
                                //
                                // 老版本（com.fuckad.xx 5.87）这里 rewardDelayMs 默认 0，
                                // 老版能到账就是因为 0ms 立刻补，赶在 GDT 报错之前。
                                // 展示回调一进来就补发，不强制延迟。
                                final long delay = Config.rewardDelayMs();
                                if (Logs.isEnabled()) {
                                    Logs.d("展示类回调，补发延迟 " + delay + "ms: " + key);
                                }
                                Runnable fire = new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!RewardLedger.isSession(self, session)) {
                                            if (Logs.isEnabled()) Logs.d("跳过过期补发: " + key);
                                            return;
                                        }
                                        deliver(rule, self, triggerArgs);
                                    }
                                };
                                if (delay <= 0) {
                                    fire.run();
                                } else {
                                    mainHandler().postDelayed(fire, delay);
                                }
                                return chain.proceed();
                            }
                            if (liquidClose) {
                                setLiquidRewardFlag(self);
                                return chain.proceed();
                            }
                            // 先补发再放行：App 的 onAdClose 会检查奖励标志，
                            // 如果等 proceed 之后才补，标志置得太晚，奖励不会到账。
                            deliver(rule, self, triggerArgs);
                            return chain.proceed();
                        }
                    });
            hooked.put(key, Boolean.TRUE);
            captureInstances(method.getDeclaringClass());
            Logs.i("hook 结束回调 " + key + " [" + rule.sdk + "]");
            return true;
        } catch (Throwable t) {
            Logs.w("hook 失败 " + key + " : " + t);
            return false;
        }
    }

    /** 关闭前把游戏内部的奖励标志置 true（调用 onRewardVerify(true,1,1,"")）。 */
    private void setLiquidRewardFlag(Object self) {
        if (self == null) return;
        try {
            Method target = null;
            for (Method candidate : self.getClass().getMethods()) {
                if (matchesRewardVerify(candidate)) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) {
                for (Method candidate : self.getClass().getDeclaredMethods()) {
                    if (matchesRewardVerify(candidate)) {
                        target = candidate;
                        break;
                    }
                }
            }
            if (target == null) return;
            target.setAccessible(true);
            target.invoke(self, true, 1, 1, "");
        } catch (Throwable t) {
            Logs.w("设置 LIQUID 奖励标志失败: " + t);
        }
    }

    private static boolean matchesRewardVerify(Method method) {
        if (!"onRewardVerify".equals(method.getName())) return false;
        Class<?>[] types = method.getParameterTypes();
        return types.length == 4
                && types[0] == boolean.class
                && types[1] == int.class
                && types[2] == int.class
                && types[3] == String.class;
    }

    /**
     * 百度这条链在自动关广告后不会触发 App 的 onAdClose，JS 收不到 onClose(true)。
     * 从适配器对象里取出 Navigate → getAdInteractionListener()，补一次 onAdClose。
     */
    private void closeLiquidListener(Object self) {
        if (self == null) return;
        try {
            for (java.lang.reflect.Field field : self.getClass().getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                }
                Object holder;
                try {
                    holder = field.get(self);
                } catch (Throwable ignored) {
                    continue;
                }
                if (holder == null) continue;
                Method getter = findNoArg(holder.getClass(), "getAdInteractionListener");
                if (getter == null) continue;
                Object listener = getter.invoke(holder);
                if (listener == null) continue;
                Method close = findNoArg(listener.getClass(), "onAdClose");
                if (close == null) return;
                close.setAccessible(true);
                close.invoke(listener);
                Logs.i("补调 onAdClose -> " + listener.getClass().getName() + " [LIQUID]");
                return;
            }
        } catch (Throwable t) {
            Logs.w("补调 onAdClose 失败: " + t);
        }
    }

    private static Method findNoArg(Class<?> clazz, String name) {
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == 0) {
                return method;
            }
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == 0) {
                return method;
            }
        }
        return null;
    }

    /** 广告被跳过/关闭后，主动调一次奖励回调。 */
    private void deliver(Rules.Rule rule, Object self, Object[] triggerArgs) {
        if (self == null || !rule.deliverOnTrigger || !Config.injectOnSkip()) return;

        // 方案 B（只触发 onVideoComplete、不手动发奖）会导致 TopOn 内部 NPE 闪退，
        // 见 VideoHelper$1$13.run(VideoHelper.java:271) —— 它要 toString() 一个
        // 只在真实播放时才会生成的 ATAdInfo，客户端伪造不出来。已回滚到手动补发。
        if (Logs.isEnabled()) {
            Logs.d("准备补发: " + rule.sdk + " 实例=" + RewardLedger.describe(self));
        }
        String rewardName = rule.primaryRewardName();
        if (rewardName == null) return;

        List<Method> candidates = methodsFor(self.getClass(), rewardName);
        if (candidates.isEmpty()) {
            Logs.w("找不到补发用的奖励回调 " + rewardName + " @ " + self.getClass().getName());
            return;
        }

        // 同名重载里挑一个参数真能构造出来的。
        // 以前只看 canProvide()（它认为「能用 Unsafe 造空壳」就算可提供），
        // 但实际构造可能返回 null —— 比如 anythink 的 ATAdInfo，
        // 目标 App 的 onReward(null) 会直接 NPE（getRequestId() on null），
        // 奖励发不出去。这里改成：先把参数真的造一遍，有 null 就换下一个候选。
        // 老版本（com.fuckad.xx 5.87）就是按 methodsFor 返回顺序取第一个 allProvideable，
        // 不做任何重载排序。之前加的"参数最多优先"排序可能反而挑错了方法，
        // 这里恢复老版行为。
        Method target = null;
        Object[] builtArgs = null;
        String firstBad = null;
        for (Method method : candidates) {
            Class<?>[] types = method.getParameterTypes();
            if (!allProvideable(types)) {
                if (firstBad == null) firstBad = badParam(types);
                continue;
            }
            // 优先复用真实回调参数，其次才构造
            Object[] cache = freshRealArgs(keyOf(method), types.length, self);
            Object[] probe;
            if (cache != null) {
                probe = cache.clone();
            } else {
                probe = ArgFixer.buildRewardArgs(types, triggerArgs);
            }
            if (probe == null) continue;
            // 构造不出来的对象参数，换成本轮真实回调里的同一个对象
            for (int i = 0; i < probe.length; i++) {
                if (probe[i] == null && !types[i].isPrimitive()) {
                    Object real = realArgFor(types[i]);
                    if (real != null) probe[i] = real;
                }
            }
            boolean hasNull = false;
            for (int i = 0; i < probe.length; i++) {
                if (probe[i] == null && !types[i].isPrimitive()) {
                    hasNull = true;
                    break;
                }
            }
            if (hasNull) {
                if (firstBad == null) {
                    firstBad = method.getName() + " 的参数无法构造非空实例";
                }
                continue;
            }
            target = method;
            builtArgs = probe;
            break;
        }
        if (target == null) {
            Logs.w("跳过补发：参数 " + firstBad + " 无法安全构造 [" + rule.sdk + "]");
            return;
        }

        String methodKey = keyOf(target);
        boolean synthetic = ArgFixer.anyNeedsSynthesis(target.getParameterTypes());
        Object[] cached = freshRealArgs(methodKey, target.getParameterTypes().length, self);
        Object[] args;
        if (cached != null) {
            args = cached.clone();
            synthetic = false;
            if (Logs.isEnabled()) {
                Logs.d("补发复用真实参数: " + rewardName + " [" + rule.sdk + "]");
            }
        } else {
            // 直接用上面探测通过的那份，别再造一次（可能又造出 null）
            args = builtArgs;
        }
        if (!RewardLedger.tryInject(self, rule.sdk, methodKey, synthetic)) return;
        if (synthetic && Logs.isEnabled()) {
            Logs.d("补发使用构造参数，真实回调不吞: " + rewardName + " [" + rule.sdk + "]");
        }

        RewardLedger.beginInject();
        try {
            if ("BAIDU".equals(rule.sdk)) {
                boolean ok = deliverBaidu(self);
                if (ok) {
                    Logs.i("已补发奖励 Baidu onReward+onAdClose -> "
                            + RewardLedger.describe(self) + " [BAIDU]");
                    if (Config.toast()) Toaster.show("已补发奖励 · BAIDU");
                } else {
                    RewardLedger.forget(self, rule.sdk, methodKey);
                    Logs.w("百度补偿失败: " + rewardName);
                }
                return;
            }
            target.setAccessible(true);
            int times = Math.max(1, Config.rewardMultiplier());
            for (int i = 0; i < times; i++) {
                target.invoke(self, args);
            }
            Logs.i("已补发奖励" + (times > 1 ? (" x" + times) : "")
                    + " " + keyOf(target)
                    + " -> " + RewardLedger.describe(self) + " [" + rule.sdk + "]");
            if (Config.toast()) Toaster.show("已补发奖励 · " + rule.sdk);
        } catch (InvocationTargetException ite) {
            // 目标 App 自己的回调抛异常：保留记账，避免重试造成重复发奖
            Logs.w("补发奖励时目标回调抛异常 " + rewardName + " : " + ite.getCause());
        } catch (Throwable t) {
            // 我们这边没调起来（参数类型不匹配等）：撤销记账，允许下一次 trigger 再试
            RewardLedger.forget(self, rule.sdk, methodKey);
            Logs.w("补发奖励失败 " + rewardName + " : " + t);
        } finally {
            RewardLedger.endInject();
        }
    }

    /**
     * 百度：直接调用 App 的 rewardVideoAdListener.onReward + onAdClose，
     * 绕过 SDK 的 onRewardVerify，避免广告页还开着时触发原生崩溃。
     */
    private boolean deliverBaidu(Object self) {
        try {
            Object wrapper = field(self, "this$0");
            if (wrapper == null) return false;
            Object listener = field(wrapper, "rewardVideoAdListener");
            if (listener == null) return false;
            Method onReward = null;
            Method onClose = null;
            for (Method method : listener.getClass().getMethods()) {
                if ("onReward".equals(method.getName()) && method.getParameterTypes().length == 1) {
                    onReward = method;
                } else if ("onAdClose".equals(method.getName())
                        && method.getParameterTypes().length == 0) {
                    onClose = method;
                }
            }
            if (onReward == null || onClose == null) return false;
            Object[] args = ArgFixer.buildRewardArgs(onReward.getParameterTypes(), new Object[0]);
            if (args == null) return false;
            onReward.setAccessible(true);
            onClose.setAccessible(true);
            // 直接调 listener，不存在前置的原生回调，所以循环次数就是总次数
            int times = Math.max(1, Config.rewardMultiplier());
            for (int i = 0; i < times; i++) {
                onReward.invoke(listener, args);
            }
            onClose.invoke(listener);
            Logs.i("百度补偿 onReward" + (times > 1 ? (" x" + times) : "") + " + onAdClose -> "
                    + listener.getClass().getName());
            return true;
        } catch (Throwable t) {
            Logs.w("百度补偿失败: " + t);
            return false;
        }
    }

    private static Object field(Object obj, String name) {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private List<Method> methodsFor(Class<?> clazz, String name) {
        Map<String, List<Method>> methods = classMethods.get(clazz.getName());
        List<Method> list = methods == null ? null : methods.get(name);
        if (list == null || list.isEmpty()) {
            list = findMethodsByName(clazz, name);
        }
        return list;
    }

    private static boolean allProvideable(Class<?>[] types) {
        for (Class<?> type : types) {
            // 能复用本轮真实回调对象时，即使构造不出来也算可用
            if (!ArgFixer.canProvide(type) && realArgFor(type) == null) return false;
        }
        return true;
    }

    private static String badParam(Class<?>[] types) {
        for (Class<?> type : types) {
            if (!ArgFixer.canProvide(type) && realArgFor(type) == null) return type.getName();
        }
        return "?";
    }

    private static List<Method> findMethodsByName(Class<?> clazz, String name) {
        List<Method> out = new ArrayList<>();
        Class<?> current = clazz;
        int depth = 0;
        while (current != null && current != Object.class && depth++ < 24) {
            try {
                for (Method method : current.getDeclaredMethods()) {
                    if (!method.getName().equals(name)) continue;
                    if (Modifier.isAbstract(method.getModifiers())) continue;
                    if (method.isSynthetic() || method.isBridge()) continue;
                    if (hasOverload(out, method)) continue;
                    try {
                        method.setAccessible(true);
                    } catch (Throwable ignored) {
                    }
                    out.add(method);
                }
            } catch (Throwable ignored) {
            }
            current = current.getSuperclass();
        }
        return out;
    }

    /** dex 扫描没找到实现类时的兜底：直接 hook 接口里带默认实现的方法。 */
    private int hookInterfaceFallback(Rules.Rule rule, ClassLoader loader) {
        try {
            String name = ImplFinder.toName(rule.targetDesc);
            if (name == null) return 0;
            Class<?> clazz = Class.forName(name, false, loader);
            int hits = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (Modifier.isAbstract(method.getModifiers())) continue;
                String methodName = method.getName();
                boolean r = rule.isReward(methodName);
                boolean t = rule.isTrigger(methodName);
                boolean e = rule.isError(methodName);
                if (r && installReward(rule, method)) hits++;
                if (t && installTrigger(rule, method)) hits++;
                if (e && installError(rule, method)) hits++;
            }
            if (hits > 0) {
                Logs.i("兜底 hook 接口 " + name + " 命中 " + hits + " 个方法 [" + rule.sdk + "]");
            }
            return hits;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean isStartMethod(String key) {
        int hash = key.indexOf('#');
        int paren = key.indexOf('(');
        String name = hash >= 0 && paren > hash ? key.substring(hash + 1, paren) : key;
        return name.contains("Show") || name.contains("PlayStart");
    }

    private static Handler mainHandler() {
        if (sMain == null) {
            sMain = new Handler(Looper.getMainLooper());
        }
        return sMain;
    }

    private static Object[] toArray(List<Object> list) {
        return list == null ? null : list.toArray();
    }

    private static String keyOf(Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getDeclaringClass().getName())
                .append('#')
                .append(method.getName())
                .append('(');
        for (Class<?> type : method.getParameterTypes()) {
            sb.append(type.getName()).append(',');
        }
        return sb.append(')').toString();
    }
}
