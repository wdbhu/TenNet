## TenNet v7.25 (725)

把整个模块的代码过了一遍，翻出一堆老问题。这版全修了，一共 20 处。

先说最要命的几个。

**自动关广告会重复发奖。** `fireLastClose` 里直接 `Method.invoke` 调奖励方法，没走账本、没带注入标记，唯一的守卫 `sLastRewardAt` 只在它自己内部更新——真实回调从来不写这个值。结果就是真实奖励到账之后，自动关广告又补一份。开了倍率更夸张，被 hook 的奖励方法会把这次补发当成原生回调，再乘一次。现在改成走 `RewardLedger` 记账，带注入标记。

**用一会儿就不发奖了。** `sRealRewardArgs` 是方法级的 map，永不清空，`hasRealRewardArgs` 只做 `containsKey`。意思是这个进程只要收到过一次真实奖励，之后每一轮展示补发都会被跳过。现在按会话代次加 90 秒 TTL 判断，只认本轮。

**补发用的是上一轮的交易号。** 上一轮的真实参数被无条件复用，服务端按 ID 去重会拒发，或者发到错误档位。现在同样校验会话和时效，过期就重新构造。

**跨线程的注入标记失效。** `INJECTING` 是 ThreadLocal，聚合 SDK 把回调 post 到别的线程就读不到，我们自己发起的补发被当成原生回调再放大一次。改成 ThreadLocal + 全局深度计数。

作用域那块也修了。

`currentScope()` 以前直接返回 LSPosed 的 scope 表，但 hook 侧闸门读的是 `scope_apps` 名单。所以在 LSPosed 管理器里勾了 App，界面显示已启用，实际一行 hook 都不装。现在返回两者的交集。

`add()` 是先写名单再请求授权，用户点「拒绝」之后名单里那条不撤销。这个坑比较阴——那个 App 之后只要被别的方式加进 scope 就会被 hook，界面却显示没勾。

`XposedStatusProvider.ensureRegistered()` 先置 `registered = true` 再 `runCatching`，注册抛异常就永久 true，服务永远是 null。连带所有设置静默走本地兜底、首页一直显示未连接。现在注册成功才置位。

DexKit 那条挺意外。

`searchPackages("*")` 在 DexKit 里不是通配符。它内部把 `"*"` 规范化成 `"L*/"` 去建包名 trie，真实类描述符 `Lcom/kwad/...` 在 `L` 之后是 `c` 不是 `*`，全部被剔除。也就是说 `findExtra` 恒返回空，慢扫从上线起就没工作过。现在不设包过滤，靠 `descriptor` 精确筛。

顺带发现 `getOrCreateBridge` 的双重检查锁是假的——锁外 create、锁内直接覆盖，两个后台线程并发首建时各建一份，旧的那份永不 close。一份 dex 索引几十到几百 MB。

还有 `scanKey` 只拼了 `sdk` 和 `targetDesc`，方法名不参与哈希。以后再给现有 SDK 加方法名，target APK 没变、key 也不变，走旧缓存，新方法对应的类永远扫不出来。现在方法名进哈希，缓存版本升到 v2。

风控和日志：

`hookNull` 以前不限返回类型、无条件返回 null。目标方法要是声明成 `boolean`，框架拆箱直接抛异常，等于让这个检测点每次调用都炸。现在只接受引用类型。

`sHooked` 是先 add 再 hook，失败不回滚，一次失败这个风控点在本进程里就永久失效。

`ro.secure` 以前返回 `"0"`，那是「设备不安全」的意思，跟同一个函数里把 `ro.debuggable` 置 0、`ro.build.tags` 置 release-keys 自相矛盾，反而是个强 root 信号。改成 `"1"`。

`Logs.w` 以前跟着调试开关走，而开关在 `Config.load` 时才设置，晚于 `Logs.setFile`。所以「日志文件初始化失败」这种最早也最关键的告警自己写不出去。现在 `w()` 跟 `e()` 一样不受开关控制。

还有两个会误伤的。

自动关广告的 `isAdScreen` 只做包前缀匹配。穿山甲同一个前缀下有 `TTDelegateActivity`（隐私协议代理）、`TTWebViewLandingActivity`、`TTRewardVideoLandingActivity`，全部命中，直接 finish。用户还没点隐私同意就被踢出去，广告不结算。现在排除 Landing / Delegate / Privacy 这些标记，另外要求包边界——`com.tapad` 不再匹配 `com.tapadXxx`。

`closeNow` 不复查 `autoClose()`，在广告播放期间关掉开关也没用，排队的任务照样把页面关掉。

零碎的：

- `GDT-加载` 把 `onADLoad` 当错误回调了，那是加载成功。移除，只留 `onNoAd`
- `isMainProcess` 两处兜底都返回 `true`，子进程会被当主进程重复跑扫描、装全套 hook
- `sInstalled` 在所有判断之前就置 true，之后关总开关、没勾选、扫描失败任一条出现，这个进程就永久没第二次机会。三处提前返回现在都会释放
- `onPackageReady` 用 `Handler.post`，注释写的是「延后到消息队列空闲」，但 post 就是排到当前消息后面，一样在冷启动关键路径上跑 `remotePreferences` 和 `mkdirs`。改成 `postDelayed` 120ms（`postIdle` 是隐藏 API，用不了）
- 日志页列表只扫 `/data_mirror` 一条前缀，清空却用三条，读写不对称；`su` 调用没超时，授权框不响应会永久卡住。现在三条都扫，`su` 加 8 秒超时

### 没验过的东西

这 20 处改动我只做了编译和静态核对，没在真机上跑过。发奖那四条动的是「发几次、什么时候发」的核心路径，而出问题那个游戏的广告本来就是 no fill，拿它测不出来。

要验的话建议这样：找一个广告能正常播的游戏，倍率设 3，看一次广告数奖励到账几次，应该是 3 次，不能 4 次或 6 次。连续刷三次，每次都得是 3 次。再开自动关广告重复一遍，那是 `fireLastClose` 最容易出双发的地方。

不对就把带「补发」「原生奖励回调」「吞掉」的日志行发我。

签名没变，可以直接覆盖装。`DeviceSpoof`、`LocationSpoof`、`DexIndex` 这三个死代码文件一行没动。规则总数还是 37 条，首页显示 16 家。
