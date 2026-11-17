package com.fuckad.xx.core;

import com.fuckad.xx.Config;
import com.fuckad.xx.Logs;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 奖励账本：记录每个广告对象最近一次“原生回调 / 主动补发”，用来避免重复发奖和死循环。
 */
public final class RewardLedger {

    public static final int NONE = 0;
    public static final int INJECTED = 1;
    public static final int NATURAL = 2;

    /** 去重窗口：同一对象在这个时间内只补发一次。 */
    private static final long WINDOW_MS = 5000L;

    private static final Object LOCK = new Object();
    private static final Map<Object, Map<String, Entry>> TABLE = new WeakHashMap<>();
    /** 每个监听对象的“广告会话”代次；新的展示回调会 +1，旧会话的记账自动失效。 */
    private static final Map<Object, Integer> SESSION = new WeakHashMap<>();
    /**
     * 注入标记必须跨线程生效。
     *
     * <p>聚合 SDK 常把奖励回调 post 到主线程 Handler 或另起线程执行，
     * 只靠 ThreadLocal 时子线程读不到标记，会把我们发起的补发当成原生回调，
     * 再叠加一次倍率（倍率越大越失控）。这里用全局计数 + 当前线程标记双写：
     * 只要本进程内有任何一次补发正在进行，所有线程都视为「注入中」。</p>
     */
    private static final ThreadLocal<Boolean> INJECTING = new ThreadLocal<>();
    private static final java.util.concurrent.atomic.AtomicInteger INJECT_DEPTH =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private static final class Entry {
        final int state;
        final long at;
        /** 补发时是否用了“造出来的”模型参数（接口代理 / Unsafe 空壳）。 */
        final boolean synthetic;
        /** 记账时的广告会话代次；新一轮展示会递增，旧记账自动过期。 */
        final int session;

        Entry(int state, long at, boolean synthetic, int session) {
            this.state = state;
            this.at = at;
            this.synthetic = synthetic;
            this.session = session;
        }
    }

    /** 展示回调进来时调用：开启新一轮广告会话，旧会话的去重记账作废。 */
    public static int beginSession(Object target) {
        if (target == null) return 0;
        synchronized (LOCK) {
            Integer current = SESSION.get(target);
            int next = current == null ? 1 : current + 1;
            SESSION.put(target, next);
            return next;
        }
    }

    public static boolean isSession(Object target, int session) {
        return target != null && sessionOf(target) == session;
    }

    /** 供 AdHookEngine 记录真实参数时标注会话代次。 */
    public static int sessionOfPublic(Object target) {
        return sessionOf(target);
    }

    private static int sessionOf(Object target) {
        synchronized (LOCK) {
            Integer current = SESSION.get(target);
            return current == null ? 0 : current;
        }
    }

    private RewardLedger() {
    }

    public static void beginInject() {
        INJECTING.set(Boolean.TRUE);
        INJECT_DEPTH.incrementAndGet();
    }

    public static void endInject() {
        INJECTING.remove();
        // 防御：任何异常路径下的多减都不会让计数变负
        if (INJECT_DEPTH.get() > 0) INJECT_DEPTH.decrementAndGet();
    }

    public static boolean isInjecting() {
        if (Boolean.TRUE.equals(INJECTING.get())) return true;
        return INJECT_DEPTH.get() > 0;
    }

    /** 主动补发前调用：本会话、本方法窗口内已经发过就返回 false，避免一次广告拿两份奖励。 */
    public static boolean tryInject(Object target, String sdk, String methodKey, boolean synthetic) {
        if (target == null) return false;
        if (!Config.dedupe()) return true;

        String key = entryKey(sdk, methodKey);
        long now = System.currentTimeMillis();
        int session = sessionOf(target);
        synchronized (LOCK) {
            Map<String, Entry> entries = TABLE.get(target);
            Entry entry = entries == null ? null : entries.get(key);
            if (entry != null && entry.session == session && now - entry.at < WINDOW_MS) {
                if (Logs.isEnabled()) {
                    Logs.i("跳过补发（本会话该方法已发放）: " + describe(target)
                            + " [" + sdk + "] " + methodKey);
                }
                return false;
            }
            if (entries == null) {
                entries = new HashMap<>(2);
                TABLE.put(target, entries);
            }
            entries.put(key, new Entry(INJECTED, now, synthetic, session));
            return true;
        }
    }

    /**
     * 原生奖励回调进来时判断是否要吞掉：只有同一个方法、同一会话的补发回声才吞。
     */
    public static boolean shouldSwallow(Object target, String sdk, String methodKey) {
        if (target == null || isInjecting()) return false;
        if (!Config.dedupe()) return false;

        String key = entryKey(sdk, methodKey);
        long now = System.currentTimeMillis();
        int session = sessionOf(target);
        synchronized (LOCK) {
            Map<String, Entry> entries = TABLE.get(target);
            Entry entry = entries == null ? null : entries.get(key);
            if (entry != null && entry.session == session
                    && entry.state == INJECTED && !entry.synthetic
                    && now - entry.at < WINDOW_MS) {
                if (Logs.isEnabled()) {
                    Logs.i("吞掉重复的原生奖励回调（同方法已补发过）: "
                            + describe(target) + " [" + sdk + "] " + methodKey);
                }
                return true;
            }
            return false;
        }
    }

    private static String entryKey(String sdk, String methodKey) {
        return sdk + "|" + (methodKey == null ? "" : methodKey);
    }

    /** 记录一次 App 自己的原生奖励回调（按方法分开记），避免随后又补发一次。 */
    public static void markNatural(Object target, String sdk, String methodKey) {
        if (target == null) return;
        String key = entryKey(sdk, methodKey);
        synchronized (LOCK) {
            Map<String, Entry> entries = TABLE.get(target);
            if (entries == null) {
                entries = new HashMap<>(2);
                TABLE.put(target, entries);
            }
            entries.put(key, new Entry(NATURAL, System.currentTimeMillis(), false, sessionOf(target)));
        }
        if (Logs.isEnabled()) {
            Logs.i("原生奖励回调: " + describe(target) + " [" + sdk + "] " + methodKey);
        }
    }

    /**
     * 撤销一次补发记账。只在我们这边没能把目标方法调起来时使用：
     * 目标 App 自己的回调已经抛异常的情况要保留记账，避免重试导致重复发奖。
     */
    public static void forget(Object target, String sdk, String methodKey) {
        if (target == null) return;
        String key = entryKey(sdk, methodKey);
        synchronized (LOCK) {
            Map<String, Entry> entries = TABLE.get(target);
            if (entries == null) return;
            entries.remove(key);
            if (entries.isEmpty()) TABLE.remove(target);
        }
    }

    public static String describe(Object obj) {
        if (obj == null) return "";
        try {
            String name = obj.getClass().getName();
            int idx = name.indexOf("$$");
            if (idx > 0) name = name.substring(0, idx);
            return name + "@" + Integer.toHexString(System.identityHashCode(obj));
        } catch (Throwable t) {
            return "obj?";
        }
    }
}
