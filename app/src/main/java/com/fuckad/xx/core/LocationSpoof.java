package com.fuckad.xx.core;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.fuckad.xx.Logs;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/**
 * 伪装定位。
 *
 * <p>只在该 App 被 LSPosed 勾选作用域、且设置页里给这个 App 选了城市时安装。</p>
 *
 * <p>覆盖范围：</p>
 * <ul>
 *   <li>{@link Location} 全部 getter（经纬度、精度、海拔、速度、方位、时间、provider、
 *       isMock/isFromMockProvider）；</li>
 *   <li>{@link LocationManager}：getLastKnownLocation、isProviderEnabled、isLocationEnabled、
 *       getProviders/getAllProviders/getBestProvider；</li>
 *   <li>{@link LocationManager#requestLocationUpdates} 全部重载（含 Looper、Executor、
 *       PendingIntent 版本）和 requestSingleUpdate、getCurrentLocation：注册成功后立刻回调
 *       一次伪装位置，800ms 再补一次；</li>
 *   <li>Settings.Secure 的 location_mode / location_providers_allowed；</li>
 *   <li>GMS 的 LocationResult.getLocations / getLastLocation（类存在时）。</li>
 * </ul>
 *
 * <p>只覆盖 Java 层：native 直接读 /dev/gnss、或服务端按 IP/基站校验的改不了。</p>
 */
public final class LocationSpoof {

    private static final int PRIORITY = XposedInterface.PRIORITY_DEFAULT + 70;
    private static final float ACCURACY = 12f;
    private static final double ALTITUDE = 42d;
    private static final String PROVIDER = "gps";

    private LocationSpoof() {
    }

    public static void install(XposedInterface xi, double lat, double lng, ClassLoader loader) {
        Logs.raw("伪装定位：开始 " + lat + "," + lng);
        int count = 0;
        count += hookLocationGetters(xi, lat, lng);
        count += hookLocationManager(xi, lat, lng);
        count += hookProviders(xi);
        count += hookSettings(xi);
        count += hookLocationRequests(xi, lat, lng);
        count += hookGmsLocation(xi, lat, lng, loader);
        Logs.raw("伪装定位：完成 " + count + " 项，伪装坐标 " + lat + "," + lng
                + "，自检 " + fakeLocation(lat, lng).getLatitude() + ","
                + fakeLocation(lat, lng).getLongitude());
    }

    /** 造一个"真机 GPS 定位"对象；坐标等 getter 也被 hook，读出来就是伪装值。 */
    private static Location fakeLocation(double lat, double lng) {
        Location location = new Location(PROVIDER);
        location.setLatitude(lat);
        location.setLongitude(lng);
        location.setAccuracy(ACCURACY);
        location.setAltitude(ALTITUDE);
        location.setSpeed(0f);
        location.setBearing(0f);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return location;
    }

    private static int hookLocationGetters(XposedInterface xi, final double lat, final double lng) {
        int count = 0;
        count += hookNoArg(xi, Location.class, "getLatitude", lat);
        count += hookNoArg(xi, Location.class, "getLongitude", lng);
        count += hookNoArg(xi, Location.class, "getAccuracy", ACCURACY);
        count += hookNoArg(xi, Location.class, "getAltitude", ALTITUDE);
        count += hookNoArg(xi, Location.class, "getSpeed", 0f);
        count += hookNoArg(xi, Location.class, "getBearing", 0f);
        count += hookNoArg(xi, Location.class, "getVerticalAccuracyMeters", ACCURACY);
        count += hookNoArg(xi, Location.class, "getSpeedAccuracyMetersPerSecond", 0.1f);
        count += hookNoArg(xi, Location.class, "getBearingAccuracyDegrees", 1f);
        count += hook(xi, Location.class, "getTime", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) {
                return System.currentTimeMillis();
            }
        });
        count += hook(xi, Location.class, "getElapsedRealtimeNanos", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) {
                return SystemClock.elapsedRealtimeNanos();
            }
        });
        count += hook(xi, Location.class, "getProvider", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) {
                return PROVIDER;
            }
        });
        count += hook(xi, Location.class, "isFromMockProvider", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) {
                return Boolean.FALSE;
            }
        });
        count += hook(xi, Location.class, "isMock", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) {
                return Boolean.FALSE;
            }
        });
        return count;
    }

    private static int hookNoArg(XposedInterface xi, Class<?> clazz, String name, final Object value) {
        return hook(xi, clazz, name, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) {
                return value;
            }
        });
    }

    private static int hookLocationManager(XposedInterface xi, final double lat, final double lng) {
        int count = 0;
        try {
            Method last = LocationManager.class.getDeclaredMethod("getLastKnownLocation", String.class);
            count += hookMethod(xi, last, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) {
                    return fakeLocation(lat, lng);
                }
            });
        } catch (Throwable ignored) {
        }
        count += hook(xi, LocationManager.class, "isProviderEnabled", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) {
                return Boolean.TRUE;
            }
        });
        count += hook(xi, LocationManager.class, "isLocationEnabled", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) {
                return Boolean.TRUE;
            }
        });
        return count;
    }

    private static int hookProviders(XposedInterface xi) {
        int count = 0;
        try {
            Method method = LocationManager.class.getDeclaredMethod("getProviders", boolean.class);
            count += hookMethod(xi, method, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) {
                    return providerList();
                }
            });
        } catch (Throwable ignored) {
        }
        try {
            Method method = LocationManager.class.getDeclaredMethod("getAllProviders");
            count += hookMethod(xi, method, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) {
                    return providerList();
                }
            });
        } catch (Throwable ignored) {
        }
        try {
            Method method = LocationManager.class.getDeclaredMethod(
                    "getBestProvider", Criteria.class, boolean.class);
            count += hookMethod(xi, method, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) {
                    return PROVIDER;
                }
            });
        } catch (Throwable ignored) {
        }
        return count;
    }

    private static List<String> providerList() {
        List<String> providers = new ArrayList<>();
        providers.add(PROVIDER);
        providers.add("network");
        providers.add("passive");
        return providers;
    }

    private static int hookSettings(XposedInterface xi) {
        int count = 0;
        try {
            Class<?> secure = Class.forName("android.provider.Settings$Secure");
            try {
                Method getInt = secure.getDeclaredMethod("getInt", ContentResolver.class, String.class);
                count += hookMethod(xi, getInt, new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        String name = chain.getArg(1) == null ? null : chain.getArg(1).toString();
                        if ("location_mode".equals(name)) return 3;
                        return chain.proceed();
                    }
                });
            } catch (Throwable ignored) {
            }
            try {
                Method getString = secure.getDeclaredMethod("getString", ContentResolver.class, String.class);
                count += hookMethod(xi, getString, new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        String name = chain.getArg(1) == null ? null : chain.getArg(1).toString();
                        if ("location_providers_allowed".equals(name)) return "gps,network";
                        return chain.proceed();
                    }
                });
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            Logs.w("伪装定位：Settings hook 失败 " + t);
        }
        return count;
    }

    private static int hookLocationRequests(XposedInterface xi, final double lat, final double lng) {
        int count = 0;
        // (provider, minTime, minDistance, listener) —— 有的版本带 Looper
        for (Class<?>[] signature : new Class<?>[][]{
                {String.class, long.class, float.class, LocationListener.class},
                {String.class, long.class, float.class, LocationListener.class, Looper.class},
                {String.class, long.class, float.class, Executor.class, LocationListener.class},
        }) {
            count += hookUpdates(xi, lat, lng, signature);
        }
        // PendingIntent 版本：广播出去
        for (Class<?>[] signature : new Class<?>[][]{
                {String.class, long.class, float.class, PendingIntent.class},
        }) {
            count += hookUpdatesIntent(xi, lat, lng, signature);
        }
        // requestSingleUpdate
        for (Class<?>[] signature : new Class<?>[][]{
                {String.class, LocationListener.class, Looper.class},
                {String.class, PendingIntent.class},
                {Criteria.class, LocationListener.class, Looper.class},
                {Criteria.class, PendingIntent.class},
        }) {
            count += hookSingle(xi, lat, lng, signature);
        }
        // getCurrentLocation(provider, cancellationSignal, executor, consumer)
        try {
            Method method = LocationManager.class.getDeclaredMethod("getCurrentLocation",
                    String.class, CancellationSignal.class, Executor.class, Consumer.class);
            count += hookMethod(xi, method, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) {
                    Object consumer = chain.getArg(3);
                    if (consumer instanceof Consumer) {
                        final Consumer<Location> target = (Consumer<Location>) consumer;
                        postToMain(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    target.accept(fakeLocation(lat, lng));
                                } catch (Throwable t) {
                                    Logs.w("伪装定位：getCurrentLocation 回调失败 " + t);
                                }
                            }
                        });
                    }
                    return null;
                }
            });
        } catch (Throwable ignored) {
        }
        return count;
    }

    private static int hookUpdates(XposedInterface xi, final double lat, final double lng,
                                   Class<?>[] signature) {
        try {
            Method method = LocationManager.class.getDeclaredMethod("requestLocationUpdates", signature);
            return hookMethod(xi, method, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object listener = findListener(chain);
                    Object result = proceedQuietly(chain);
                    if (listener instanceof LocationListener) {
                        deliver((LocationListener) listener, lat, lng);
                    }
                    return result;
                }
            });
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookUpdatesIntent(XposedInterface xi, final double lat, final double lng,
                                         Class<?>[] signature) {
        try {
            Method method = LocationManager.class.getDeclaredMethod("requestLocationUpdates", signature);
            return hookMethod(xi, method, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object pending = findArg(chain, PendingIntent.class);
                    Object result = proceedQuietly(chain);
                    if (pending instanceof PendingIntent) {
                        sendToPendingIntent((PendingIntent) pending, lat, lng);
                    }
                    return result;
                }
            });
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookSingle(XposedInterface xi, final double lat, final double lng,
                                  Class<?>[] signature) {
        try {
            Method method = LocationManager.class.getDeclaredMethod("requestSingleUpdate", signature);
            return hookMethod(xi, method, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object listener = findListener(chain);
                    Object pending = findArg(chain, PendingIntent.class);
                    Object result = proceedQuietly(chain);
                    if (listener instanceof LocationListener) {
                        deliver((LocationListener) listener, lat, lng);
                    } else if (pending instanceof PendingIntent) {
                        sendToPendingIntent((PendingIntent) pending, lat, lng);
                    }
                    return result;
                }
            });
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Object proceedQuietly(XposedInterface.Chain chain) {
        try {
            return chain.proceed();
        } catch (Throwable t) {
            Logs.w("伪装定位：注册真实定位失败（已忽略，照样回填伪装位置）" + t);
            return null;
        }
    }

    private static Object findListener(XposedInterface.Chain chain) {
        return findArg(chain, LocationListener.class);
    }

    private static Object findArg(XposedInterface.Chain chain, Class<?> type) {
        for (int i = 0; i < 6; i++) {
            Object arg;
            try {
                arg = chain.getArg(i);
            } catch (Throwable t) {
                return null;
            }
            if (arg == null) continue;
            if (type.isInstance(arg)) return arg;
        }
        return null;
    }

    /** 立刻回填一次，800ms 再补一次，避免 App 等第二次定位。 */
    private static void deliver(final LocationListener listener, final double lat, final double lng) {
        postToMain(new Runnable() {
            @Override
            public void run() {
                try {
                    listener.onLocationChanged(fakeLocation(lat, lng));
                } catch (Throwable t) {
                    Logs.w("伪装定位：onLocationChanged 回调失败 " + t);
                }
            }
        });
        postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    listener.onLocationChanged(fakeLocation(lat, lng));
                } catch (Throwable t) {
                }
            }
        }, 800L);
    }

    private static void sendToPendingIntent(final PendingIntent pending, final double lat, final double lng) {
        postToMain(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = new Intent();
                    intent.putExtra(LocationManager.KEY_LOCATION_CHANGED, fakeLocation(lat, lng));
                    Context context = context();
                    if (context != null) {
                        pending.send(context, 0, intent);
                    } else {
                        Logs.w("伪装定位：拿不到 Context，跳过 PendingIntent 回填");
                    }
                } catch (Throwable t) {
                    Logs.w("伪装定位：PendingIntent 回填失败 " + t);
                }
            }
        });
    }

    private static Context context() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method current = at.getDeclaredMethod("currentApplication");
            Object app = current.invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void postToMain(Runnable runnable) {
        try {
            new Handler(Looper.getMainLooper()).post(runnable);
        } catch (Throwable t) {
            runnable.run();
        }
    }

    private static void postDelayed(Runnable runnable, long delayMs) {
        try {
            new Handler(Looper.getMainLooper()).postDelayed(runnable, delayMs);
        } catch (Throwable ignored) {
        }
    }

    private static int hookGmsLocation(XposedInterface xi, final double lat, final double lng,
                                       ClassLoader loader) {
        int count = 0;
        Class<?> result = loadClass("com.google.android.gms.location.LocationResult", loader);
        if (result == null) return 0;
        try {
            Method getLocations = result.getDeclaredMethod("getLocations");
            count += hookMethod(xi, getLocations, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) {
                    List<Location> list = new ArrayList<>();
                    list.add(fakeLocation(lat, lng));
                    return list;
                }
            });
        } catch (Throwable ignored) {
        }
        try {
            Method getLast = result.getDeclaredMethod("getLastLocation");
            count += hookMethod(xi, getLast, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) {
                    return fakeLocation(lat, lng);
                }
            });
        } catch (Throwable ignored) {
        }
        Class<?> availability = loadClass("com.google.android.gms.location.LocationAvailability", loader);
        if (availability != null) {
            try {
                Method isAvailable = availability.getDeclaredMethod("isLocationAvailable");
                count += hookMethod(xi, isAvailable, new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) {
                        return Boolean.TRUE;
                    }
                });
            } catch (Throwable ignored) {
            }
        }
        if (count > 0) Logs.raw("伪装定位：GMS 定位 hook " + count + " 项");
        return count;
    }

    private static Class<?> loadClass(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader != null ? loader : LocationSpoof.class.getClassLoader());
        } catch (Throwable t) {
            try {
                return Class.forName(name);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static int hook(XposedInterface xi, Class<?> clazz, String name,
                            XposedInterface.Hooker hooker) {
        try {
            Method method = clazz.getDeclaredMethod(name);
            return hookMethod(xi, method, hooker);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookMethod(XposedInterface xi, Method method, XposedInterface.Hooker hooker) {
        try {
            xi.hook(method).setPriority(PRIORITY).intercept(hooker);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }
}
