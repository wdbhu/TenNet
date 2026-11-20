package com.fuckad.xx;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import com.fuckad.xx.core.AdHookEngine;
import com.fuckad.xx.core.AppCtx;
import com.fuckad.xx.core.RiskHookEngine;
import com.fuckad.xx.core.AdCloser;
import com.fuckad.xx.core.WebSniffer;
import com.fuckad.xx.core.HttpSniffer;
import com.fuckad.xx.core.RewardBypass;
import com.fuckad.xx.core.PackageSpoof;
import com.fuckad.xx.core.UnityBridgeProbe;
import com.fuckad.xx.ui.Toaster;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 模块入口。libxposed API 102 的加载顺序：
 * onModuleLoaded -> onPackageLoaded -> onPackageReady。
 */
public final class Main extends XposedModule {

    private static final AtomicBoolean sInstalled = new AtomicBoolean(false);
    private static volatile String sModulePackageName;
    /** onModuleLoaded 里记录，system_server 绝对不注入。 */
    private static volatile boolean sSystemServer;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        sSystemServer = param.isSystemServer();
        Logs.raw("模块已加载：process=" + param.getProcessName()
                + " pid=" + Process.myPid()
                + " systemServer=" + param.isSystemServer());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        // onPackageLoaded 在 App 启动关键路径上，任何耗时操作都会拖慢冷启动。
        // 这里直接不做事，全部推迟到 onPackageReady。
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        final String pkg = param.getPackageName();
        final ApplicationInfo info = param.getApplicationInfo();
        final ClassLoader loader = param.getClassLoader();

        // onPackageReady 仍处于启动阶段，remotePreferences() 是跨进程调用会阻塞。
        // 延后执行，避开冷启动的关键路径：
        // 用 postDelayed(120ms) + 后台线程做扫描，主线程只负责最后的 hook 安装
        //（Handler.postIdle 是隐藏 API，不能直接用）。
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                install(pkg, info, loader, "ready");
            }
        }, 120L);
    }

    private void install(String packageName,
                         ApplicationInfo info,
                         ClassLoader loader,
                         String stage) {
        if (loader == null || info == null) return;
        if (sSystemServer) {
            Logs.w("跳过 system_server，模块只处理勾选的 App");
            return;
        }
        if ("android".equals(packageName) || "system".equals(packageName)) return;
        if (packageName != null && packageName.equals(modulePackageName())) return;

        // 只处理该 App 的主进程。一个 App 常常有 5~20 个子进程（:push、:service、:sandbox 等），
        // 每个进程都跑一遍 remotePreferences + dex 扫描会让整机卡到无法启动。
        if (!isMainProcess(packageName)) {
            return;
        }

        if (!sInstalled.compareAndSet(false, true)) {
            Logs.d("本进程已安装过，跳过 " + stage);
            return;
        }
        // 从这里往下的每个提前返回都会释放 sInstalled，
        // 否则本进程再也没有第二次安装机会
        //（典型场景：先关总开关/不勾选启动一次，再打开开关 → 必须冷启动才生效）

        // 日志文件必须最先打开：之前放在配置读取之后，
        // 「remote prefs keys」「配置: enabled=...」这些排查时最有用的前几行
        // 只进了内存环形缓冲，落不到 cache/tunnet.log。
        try {
            if (info.dataDir != null) {
                java.io.File cache = new java.io.File(info.dataDir, "cache");
                if (cache.isDirectory() || cache.mkdirs()) {
                    Logs.setFile(new java.io.File(cache, "tunnet.log"));
                    Logs.installCrashHandler();
                }
            }
        } catch (Throwable t) {
            Logs.w("日志文件初始化失败: " + t);
        }

        try {
            SharedPreferences sp = remotePreferences();
            // 先读一次 getAll 触发共享内存映射，再注册监听，最后 load。
            // 顺序反了的话目标进程里的 remote prefs 会停在空 map 上，
            // 之后任何一次 commit 都会反向把模块侧的设置覆盖成默认值。
            java.util.Map<String, ?> snap = null;
            try {
                snap = sp == null ? null : sp.getAll();
            } catch (Throwable t) {
                Logs.w("remote prefs 读取失败: " + t);
            }
            Logs.raw("remote prefs keys=" + (snap == null ? "null" : snap.keySet().toString()));
            if (sp != null) {
                try {
                    sp.registerOnSharedPreferenceChangeListener(
                            new SharedPreferences.OnSharedPreferenceChangeListener() {
                                @Override
                                public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                                    // 日志快照也走这个组，改动它不该触发配置重载
                                    if (key != null && key.startsWith("log_")) return;
                                    Config.load(p);
                                    Logs.i("配置已刷新: " + key);
                                }
                            });
                } catch (Throwable t) {
                    Logs.w("配置监听注册失败: " + t);
                }
            }
            Config.load(sp);
        } catch (Throwable t) {
            Logs.e("读取配置失败，使用默认值", t);
            Config.load(null);
        }

        Logs.raw("配置: enabled=" + Config.enabled()
                + " injectOnSkip=" + Config.injectOnSkip()
                + " autoClose=" + Config.autoClose()
                + " toast=" + Config.toast()
                + " debug=" + Config.debug()
                + " 环境检测绕过=" + Config.shouldRiskBypass(packageName)
                + " 奖励倍率=" + Config.rewardMultiplier() + "x"
                + " 关闭延迟=" + Config.autoCloseDelayMs() + "ms"
                + " 补发时机=" + Config.rewardDelayMs() + "ms"
                + " 去重=" + Config.dedupe());
        // 日志回传只走 cache/tunnet.log：RemotePreferences 是「模块→目标」单向的，
        // 目标进程侧 commit() 静默失败（实测 log_<包名> 从不落库），
        // ContentProvider 又解析不到模块的 Provider。文件是这个进程自己的目录，
        // 写入必然成功，模块侧用 root 读取。
        Logs.raw("日志文件通道: " + packageName);
        Logs.raw("注入 " + packageName + " (" + stage + ")"
                + " api=" + getApiVersion()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion());

        if (!Config.enabled()) {
            Logs.i("模块已关闭，跳过安装");
            // 释放占位：用户随后打开总开关时，该进程应能在下一次 onPackageReady 重新安装，
            // 不必强制冷启动
            sInstalled.set(false);
            return;
        }

        // 勾选名单闸门：设置页取消勾选的 App，即使 LSPosed 仍然注入本模块，
        // 这里也直接退出，不装任何 hook。
        // 注意：名单为空（从未配置）时是「全部不生效」，不是「全部生效」——
        // Config.LEGACY_ALL_ENABLED = false，作用域必须在设置页或 LSPosed 里勾选。
        if (!Config.isAppEnabled(packageName)) {
            Logs.i("本 App 未被勾选，跳过安装 " + packageName);
            sInstalled.set(false);
            return;
        }

        // 设备/定位伪装已从界面移除：这两项对环境检测没有实际作用，
        // 改 Build/坐标反而容易和系统真实值对不上，被一致性交叉校验抓到。
        Logs.d("设备/定位伪装已停用（功能下线）");

        final ClassLoader classLoader = loader;
        final boolean doRisk = Config.shouldRiskBypass(packageName);
        final ApplicationInfo appInfo = info;
        Logs.raw("准备启动后台注入线程");
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                // 扫描是纯 CPU/IO 活，降成后台优先级，别和 App 启动抢主线程
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {
                }
                Logs.raw("后台注入线程开始");

                // 环境检测绕过放到后台，避免阻塞 App 启动关键路径
                if (doRisk) {
                    try {
                        int riskHooks = RiskHookEngine.install(Main.this, classLoader);
                        Logs.raw("环境检测绕过 hook 数量=" + riskHooks);
                    } catch (Throwable t) {
                        Logs.e("环境检测绕过安装失败", t);
                    }
                } else {
                    Logs.i("环境检测绕过：本 App 未被勾选，跳过");
                }

                try {
                    hookApplicationAttach(classLoader);
                } catch (Throwable t) {
                    Logs.w("applicationAttach hook 失败: " + t);
                }

                final AdHookEngine engine = new AdHookEngine(Main.this);
                final AdHookEngine.ScanResult result;
                try {
                    result = engine.scan(appInfo);
                } catch (Throwable t) {
                    Logs.e("dex 扫描失败", t);
                    // 扫描失败不应让该进程永久失去 hook 机会
                    sInstalled.set(false);
                    return;
                }
                Logs.raw("扫描结果就绪，切到主线程安装 hook");
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            int hooks = engine.apply(classLoader, result);
                            Logs.raw("hook 安装完成，数量=" + hooks);
                            // 没有任何 hook 时不再打扰用户：不少 App（含微信）本来就没有
                            // 激励视频 SDK，弹提示纯噪音。要排查时把「调试日志」打开即可。
                            if (hooks == 0 && Config.debug()) {
                                Logs.i("本 App 未识别到广告 SDK，未装任何 hook");
                            }
                        } catch (Throwable t) {
                            Logs.e("安装 hook 失败", t);
                        }
                        try {
                            if (Config.autoClose()) {
                                AdCloser.install(Main.this);
                            }
                        } catch (Throwable t) {
                            Logs.e("自动关广告安装失败", t);
                        }
                        try {
                            // 发版默认关闭: WebSniffer.install(Main.this, classLoader);
                        } catch (Throwable t) {
                            Logs.e("请求嗅探安装失败", t);
                        }
                        try {
                            // 发版默认关闭: HttpSniffer.install(Main.this, classLoader);
                        } catch (Throwable t) {
                            Logs.e("OkHttp 嗅探安装失败", t);
                        }
                        try {
                            // 发版默认关闭: HttpSniffer.installReportHook(Main.this, classLoader);
                        } catch (Throwable t) {
                            Logs.e("上报参数记录安装失败", t);
                        }
                        try {
                            // 发版默认关闭（调试期实验功能）: RewardBypass.install(Main.this, classLoader);
                        } catch (Throwable t) {
                            Logs.e("下载任务绕过安装失败", t);
                        }
                        try {
                            // 发版默认关闭（调试期实验功能）: PackageSpoof.install(Main.this, classLoader);
                        } catch (Throwable t) {
                            Logs.e("安装状态伪造失败", t);
                        }
                        try {
                            // 发版默认关闭（调试期实验功能）: UnityBridgeProbe.install(Main.this, classLoader);
                        } catch (Throwable t) {
                            Logs.e("Unity 桥诊断安装失败", t);
                        }
                        // hook 已经生效，这时再把 DexKit 慢速索引放到后台补扫，
                        // 不占 App 冷启动的关键路径。
                        try {
                            engine.slowRescan(classLoader, appInfo, result);
                        } catch (Throwable t) {
                            Logs.w("DexKit 补扫调度失败: " + t);
                        }
                    }
                });
            }
        }, "AdSpeed-Hook");
        thread.setDaemon(true);
        thread.start();
        Logs.raw("后台注入线程已启动");
    }

    /** 判断当前进程是否是该包的主进程。 */
    private static boolean isMainProcess(String packageName) {
        try {
            String proc = null;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                proc = android.app.Application.getProcessName();
            }
            if (proc == null || proc.isEmpty()) {
                // 兜底：读 /proc/self/cmdline
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.FileReader("/proc/self/cmdline"))) {
                    StringBuilder sb = new StringBuilder();
                    int c;
                    while ((c = r.read()) > 0) sb.append((char) c);
                    proc = sb.toString();
                }
            }
            // 拿不到进程名时保守返回 false（不是主进程）：
            // 以前两处都返回 true，子进程会被当成主进程重复跑扫描+装 hook，
            // 正是上面注释要避免的整机卡顿。
            if (proc == null || proc.isEmpty()) return false;
            return proc.equals(packageName);
        } catch (Throwable t) {
            return false;
        }
    }

    private SharedPreferences remotePreferences() {
        try {
            return getRemotePreferences(Config.PREFS_NAME);
        } catch (Throwable t) {
            Logs.w("remote prefs 不可用: " + t);
            return null;
        }
    }

    /** 尽早拿到 Application Context，供 Toast 使用。 */
    private void hookApplicationAttach(ClassLoader loader) {
        try {
            Class<?> application = Class.forName("android.app.Application", false, loader);
            Method attach = application.getDeclaredMethod("attach", Context.class);
            hook(attach).setPriority(50).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Object arg = chain.getArg(0);
                        if (arg instanceof Context) {
                            Toaster.attach((Context) arg);
                            AppCtx.set((Context) arg);
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                }
            });
            Logs.i("已 hook Application.attach");
        } catch (Throwable t) {
            Logs.w("hook Application.attach 失败（不影响主流程）: " + t);
        }
        // attach 往往在模块加载前就跑完了，再补一个 onCreate 兜住 Context
        try {
            Class<?> application = Class.forName("android.app.Application", false, loader);
            Method onCreate = application.getDeclaredMethod("onCreate");
            hook(onCreate).setPriority(50).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Object self = chain.getThisObject();
                        if (self instanceof Context) {
                            Toaster.attach((Context) self);
                            AppCtx.set((Context) self);
                            Logs.i("已拿到 Application Context（onCreate）");
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                }
            });
            Logs.i("已 hook Application.onCreate");
        } catch (Throwable t) {
            Logs.w("hook Application.onCreate 失败（不影响主流程）: " + t);
        }
    }

    private String modulePackageName() {
        String name = sModulePackageName;
        if (name != null) return name;
        try {
            ApplicationInfo info = getModuleApplicationInfo();
            if (info != null && info.packageName != null) {
                name = info.packageName;
                sModulePackageName = name;
            }
        } catch (Throwable ignored) {
        }
        return name;
    }
}
