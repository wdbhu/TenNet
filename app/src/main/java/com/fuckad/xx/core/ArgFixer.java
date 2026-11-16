package com.fuckad.xx.core;

import android.os.Bundle;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 补发奖励时的参数修复。
 *
 * <p>大部分 SDK 的奖励回调带 {@code boolean isReward} / 枚举状态 / 数量，直接反射调用原方法
 * 很容易被判成“没看完”。这里统一把参数改成“看起来已经完成”的值。</p>
 *
 * <p>少部分回调（Sigmob 的 WindRewardInfo、TopOn 的 ATAdInfo、AdMob 的 RewardItem）带模型类
 * 参数，1.x 的 canProvide 直接放弃补发。4.2 起对接口用动态代理、对普通类用无参构造 /
 * Unsafe.allocateInstance 造一个“空壳”对象再调用；即使目标 App 解引用字段抛异常，也会被
 * deliver() 捕获，不会把目标 App 搞崩。</p>
 */
public final class ArgFixer {

    private static final String[] COMPLETED_CANDIDATES = {
            "COMPLETED", "COMPLETE", "SUCCESS", "SUCCEEDED", "FINISHED", "VERIFIED", "REWARDED"
    };

    /** 平台类不碰：给 Context / Activity 之类造空壳容易出事。 */
    private static final String[] FRAMEWORK_PREFIXES = {
            "android.", "androidx.", "java.", "javax.", "kotlin.", "dalvik.", "com.android."
    };

    private static final Map<Class<?>, Boolean> PROVIDABLE = new ConcurrentHashMap<>();

    private static volatile boolean sUnsafeTried;
    private static volatile Object sUnsafe;
    private static volatile Method sAllocateInstance;

    private ArgFixer() {
    }

    public static Object[] defaultArgsFor(Class<?>[] types) {
        if (types == null || types.length == 0) return new Object[0];
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            args[i] = defaultValue(types[i]);
        }
        return args;
    }

    /**
     * 补发参数：优先复用触发方法里的真实参数（placement id、ATAdInfo 等），
     * 没有再回退到默认值/空壳。很多 App 是靠 placement id 查奖励配置的，
     * 传空字符串会“回调进了但不到账”。
     */
    public static Object[] buildRewardArgs(Class<?>[] types, Object[] triggerArgs) {
        if (types == null || types.length == 0) return new Object[0];
        Object[] out = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            Object reused = findReusable(type, triggerArgs);
            out[i] = reused != null ? reused : defaultValue(type);
        }
        return out;
    }

    private static Object findReusable(Class<?> type, Object[] args) {
        if (type == null || args == null) return null;
        if (type.isPrimitive()) return null;
        // 容器不冒险复用：触发回调里的 Map/Bundle 可能是展示数据，不是奖励参数
        if (Map.class.isAssignableFrom(type) || List.class.isAssignableFrom(type)
                || type == Bundle.class) {
            return null;
        }
        for (Object arg : args) {
            if (arg != null && type.isInstance(arg)) return arg;
        }
        return null;
    }

    public static Object defaultValue(Class<?> type) {
        if (type == null) return null;
        if (type == boolean.class || type == Boolean.class) return Boolean.TRUE;
        if (type == int.class || type == Integer.class) return 1;
        if (type == long.class || type == Long.class) return 1L;
        if (type == float.class || type == Float.class) return 1f;
        if (type == double.class || type == Double.class) return 1d;
        if (type == short.class || type == Short.class) return (short) 1;
        if (type == byte.class || type == Byte.class) return (byte) 1;
        if (type == char.class || type == Character.class) return '0';
        if (type == String.class || type == CharSequence.class) return "";
        if (type == Bundle.class) return new Bundle();
        if (Map.class.isAssignableFrom(type)) return new HashMap<String, Object>();
        if (List.class.isAssignableFrom(type)) return new ArrayList<Object>();
        if (type.isEnum()) {
            Object promoted = promoteEnum(type, null);
            if (promoted != null) return promoted;
            Object[] constants = type.getEnumConstants();
            return constants != null && constants.length > 0 ? constants[0] : null;
        }
        if (type.isArray()) {
            try {
                return Array.newInstance(type.getComponentType(), 0);
            } catch (Throwable t) {
                return null;
            }
        }
        if (canProvide(type)) {
            return instantiate(type);
        }
        return null;
    }

    /**
     * 按需复制并修正参数：
     * <ul>
     *     <li>boolean 一律改成 true；</li>
     *     <li>枚举尽量提升成“完成”状态；</li>
     *     <li>同时存在 boolean 和 int 时，把第一个 int（一般是数量）补成 1。</li>
     * </ul>
     *
     * @return 需要替换的新数组；没有改动时原样返回
     */
    public static Object[] fixRewardArgs(Class<?>[] types, Object[] args) {
        if (types == null || types.length == 0) return null;

        boolean hasBoolean = false;
        for (Class<?> type : types) {
            if (type == boolean.class || type == Boolean.class) {
                hasBoolean = true;
                break;
            }
        }
        int firstInt = firstIntIndex(types);

        Object[] out = args;
        boolean copied = false;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            Object current = args != null && i < args.length ? args[i] : null;
            Object replaced = current;

            if (type == boolean.class || type == Boolean.class) {
                replaced = Boolean.TRUE;
            } else if (type.isEnum()) {
                Object promoted = promoteEnum(type, current);
                if (promoted != null && promoted != current) {
                    replaced = promoted;
                }
            } else if (hasBoolean && i == firstInt
                    && (type == int.class || type == Integer.class)) {
                if (!(current instanceof Integer) || ((Integer) current) <= 0) {
                    replaced = 1;
                }
            }

            if (replaced != current) {
                if (!copied) {
                    out = copy(args, types.length);
                    copied = true;
                }
                out[i] = replaced;
            }
        }
        return out;
    }

    public static int firstIntIndex(Class<?>[] types) {
        if (types == null) return -1;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (type == int.class || type == Integer.class) return i;
        }
        return -1;
    }

    /**
     * 尝试把枚举参数提升成“完成”状态。当前值已经是完成态、或找不到合适常量时返回 null。
     */
    public static Object promoteEnum(Class<?> type, Object current) {
        if (type == null || !type.isEnum()) return null;
        Object[] constants;
        try {
            constants = type.getEnumConstants();
        } catch (Throwable t) {
            return null;
        }
        if (constants == null || constants.length == 0) return null;

        String currentName = String.valueOf(current);
        if (isCompletedName(currentName)) return null;

        for (String candidate : COMPLETED_CANDIDATES) {
            for (Object constant : constants) {
                if (candidate.equals(((Enum<?>) constant).name())) {
                    return constant;
                }
            }
        }
        for (Object constant : constants) {
            if (isCompletedName(((Enum<?>) constant).name())) {
                return constant;
            }
        }
        return null;
    }

    /** 名字里带这些关键字，就认为它是“已完成”状态。 */
    public static boolean isCompletedName(String name) {
        if (name == null) return false;
        return name.contains("COMPLETE")
                || name.contains("SUCCESS")
                || name.contains("FINISH")
                || name.contains("VERIFIED")
                || name.contains("REWARDED");
    }

    /**
     * 这个类型的参数我们能不能安全地造一个出来。
     *
     * <p>基本类型 / 字符串 / 容器 / 枚举直接给默认值；接口用动态代理；普通类先试无参构造，
     * 再退到 Unsafe.allocateInstance。平台类（android.* 等）拒绝构造。</p>
     */
    public static boolean canProvide(Class<?> type) {
        if (type == null) return false;
        if (type.isPrimitive()) return true;
        if (type == Boolean.class || type == Integer.class || type == Long.class
                || type == Float.class || type == Double.class || type == Short.class
                || type == Byte.class || type == Character.class) {
            return true;
        }
        if (type == String.class || type == CharSequence.class) return true;
        if (type == Bundle.class) return true;
        if (Map.class.isAssignableFrom(type)) return true;
        if (List.class.isAssignableFrom(type)) return true;
        if (type.isEnum()) return true;
        if (type.isArray()) return true;

        Boolean cached = PROVIDABLE.get(type);
        if (cached != null) return cached;

        boolean ok;
        try {
            if (isFramework(type)) {
                ok = false;
            } else if (type.isInterface()) {
                ok = Modifier.isPublic(type.getModifiers());
            } else {
                ok = hasNoArgCtor(type) || unsafeAvailable();
            }
        } catch (Throwable t) {
            ok = false;
        }
        PROVIDABLE.put(type, ok);
        return ok;
    }

    /**
     * 这个参数是不是“我们造出来的”对象（接口代理 / Unsafe 空壳），
     * 而不是调用方原本传进来的真实对象。
     */
    public static boolean needsSynthesis(Class<?> type) {
        if (type == null) return false;
        if (type.isPrimitive()) return false;
        if (type == Boolean.class || type == Integer.class || type == Long.class
                || type == Float.class || type == Double.class || type == Short.class
                || type == Byte.class || type == Character.class) {
            return false;
        }
        if (type == String.class || type == CharSequence.class) return false;
        if (type == Bundle.class) return false;
        if (Map.class.isAssignableFrom(type)) return false;
        if (List.class.isAssignableFrom(type)) return false;
        if (type.isEnum()) return false;
        if (type.isArray()) return false;
        return canProvide(type);
    }

    public static boolean anyNeedsSynthesis(Class<?>[] types) {
        if (types == null) return false;
        for (Class<?> type : types) {
            if (needsSynthesis(type)) return true;
        }
        return false;
    }

    private static Object instantiate(Class<?> type) {
        if (type.isInterface()) {
            return proxyOf(type);
        }
        Object instance = null;
        try {
            java.lang.reflect.Constructor<?> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            instance = ctor.newInstance();
        } catch (Throwable ignored) {
        }
        if (instance == null) {
            instance = unsafeAllocate(type);
        }
        if (instance != null) {
            fillRewardDefaults(type, instance);
        }
        return instance;
    }

    /**
     * 空壳对象默认全是 0/null，很多 App 会读 {@code info.isReward()} 之类的字段，
     * 读到 false 就只回调不发奖。这里把 boolean 置 true、0 的 int 置 1、
     * 字符串置空串、Map/List 置空容器，让空壳看起来是“已完成”的。
     */
    private static void fillRewardDefaults(Class<?> type, Object instance) {
        Class<?> current = type;
        int depth = 0;
        while (current != null && current != Object.class && depth++ < 3) {
            for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Class<?> fieldType = field.getType();
                    if (fieldType == boolean.class || fieldType == Boolean.class) {
                        field.setBoolean(instance, true);
                    } else if (fieldType == int.class || fieldType == Integer.class) {
                        if (field.getInt(instance) == 0) field.setInt(instance, 1);
                    } else if (fieldType == String.class) {
                        if (field.get(instance) == null) field.set(instance, "");
                    } else if (Map.class.isAssignableFrom(fieldType)) {
                        if (field.get(instance) == null) field.set(instance, new HashMap<String, Object>());
                    } else if (List.class.isAssignableFrom(fieldType)) {
                        if (field.get(instance) == null) field.set(instance, new ArrayList<Object>());
                    }
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }
    }

    private static Object proxyOf(final Class<?> iface) {
        try {
            return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if (method.getDeclaringClass() == Object.class) {
                                String name = method.getName();
                                if ("equals".equals(name)) {
                                    return args != null && args.length == 1 && proxy == args[0];
                                }
                                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                                return "AdSpeedProxy";
                            }
                            Class<?> returnType = method.getReturnType();
                            if (returnType == void.class) return null;
                            return defaultValue(returnType);
                        }
                    });
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasNoArgCtor(Class<?> type) {
        try {
            type.getDeclaredConstructor();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object unsafeAllocate(Class<?> type) {
        initUnsafe();
        Method allocate = sAllocateInstance;
        Object unsafe = sUnsafe;
        if (allocate == null || unsafe == null) return null;
        try {
            return allocate.invoke(unsafe, type);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean unsafeAvailable() {
        initUnsafe();
        return sAllocateInstance != null;
    }

    /** 用反射取 sun.misc.Unsafe，避免编译期直接引用隐藏 API。 */
    private static synchronized void initUnsafe() {
        if (sUnsafeTried) return;
        sUnsafeTried = true;
        try {
            Class<?> clazz = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field field = clazz.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            Method allocate = clazz.getMethod("allocateInstance", Class.class);
            allocate.setAccessible(true);
            sUnsafe = unsafe;
            sAllocateInstance = allocate;
        } catch (Throwable ignored) {
        }
    }

    private static boolean isFramework(Class<?> type) {
        String name = type.getName();
        for (String prefix : FRAMEWORK_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private static Object[] copy(Object[] src, int length) {
        Object[] out = new Object[length];
        if (src != null) {
            System.arraycopy(src, 0, out, 0, Math.min(src.length, length));
        }
        return out;
    }
}
