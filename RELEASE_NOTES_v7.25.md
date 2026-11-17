## TenNet v7.25 (725)

一轮完整代码审计后的修复版。共 20 项，按影响面分组。

### 一、发奖逻辑（可能重复发奖 / 奖励不到账）

- **关闭广告前补发会重复发奖**：`fireLastClose` 以前直接 `invoke` 奖励方法，不带注入标记、不记账，唯一的守卫 `sLastRewardAt` 只在它自己内部更新，真实回调从不写 → 真实奖励到账后再补一份。现在走 `RewardLedger.tryInject` 记账 + `beginInject` 标记。
- **补发永久失效**：`sRealRewardArgs` 是方法级 map 且永不清空，`hasRealRewardArgs` 只做 `containsKey` → 进程里收到过一次真实奖励后，之后每轮展示补发全被跳过（表现为"用一会儿就不发奖了"）。现在按会话代次 + 90 秒 TTL 判断，只认本轮。
- **跨轮复用旧参数**：补发时无条件复用上一轮的真实参数（旧交易号 / 旧 placement），服务端按 ID 去重会拒发、发到错误档位。现在同样加了会话 + TTL 校验，过期就重新构造。
- **跨线程注入标记失效**：`INJECTING` 是 `ThreadLocal`，聚合 SDK 把回调 post 到其他线程时读不到标记，会把补发当原生回调再乘一次倍率。现在改成 ThreadLocal + 全局深度计数，任一线程在补发中即视为注入中。
- **倍率递归叠加**：`multiplyNaturalReward` 增加 `isInjecting()` 前置判断，注入过程中不再放大。

### 二、设置与作用域（改了不生效）

- **作用域显示与实际不一致**：`currentScope()` 以前直接返回 LSPosed 的 scope 表，而 hook 侧闸门读的是 `scope_apps` 名单 → 在 LSPosed 管理器里勾了，界面显示已启用，实际一行 hook 都不装。现在返回两者交集。
- **取消授权后名单不回滚**：`add()` 先写名单再请求授权，用户点"拒绝"后名单里那条不会撤销。现在失败时回滚到授权前状态，写入失败也不再对 UI 谎报成功。
- **服务注册失败后永不重试**：`XposedStatusProvider.ensureRegistered()` 先置 `registered=true` 再 `runCatching`，注册抛异常就永久 true → 服务永远 null，所有设置静默走本地兜底、首页一直显示未连接。现在注册成功才置位。
- **进设置页 `fill_null_args` 显示错误**：`refresh()` 漏了 `tnFillNullArgs`（`refreshTn()` 里有），已补。
- **关闭延迟下拉框显示错位**：`DelayOptions.AUTO_CLOSE` 不含 2500，而默认值就是 2500 → 显示"5 秒"实际 2.5 秒。已加入 2500。

### 三、hook 装配（静默漏 hook）

- **DexKit 慢扫从未生效**：`searchPackages("*")` 在 DexKit 里不是通配符，`BuildPackagesMatchTrie` 会把它规范化成 `L*/`，真实类描述符永远匹配不上 → `findExtra` 恒定返回空。已去掉包过滤，靠 `descriptor` 精确筛选。
- **DexKit 索引并发泄漏**：`getOrCreateBridge` 双重检查锁失效（锁外 create、锁内覆盖），并发时各建一份、旧的永不 close。现在建桥在锁内完成。
- **缓存键不含方法名**：`scanKey` 只拼 `sdk:targetDesc` + APK 信息，以后给现有 SDK 增补方法名时会命中旧缓存、新方法对应的类永远扫不出来。现在方法名参与哈希，缓存版本升到 v2。
- **`else-if` 丢弃语义**：同一方法名同时命中 reward/trigger/error 时只装第一个。改成三个独立 `if`。
- **继承深度上限 8 层**：聚合 SDK 的 adapter 链很容易超过，超了静默漏 hook。提到 24 层。
- **构造函数 hook 失败不重试**：`captureInstances` 失败后不移除标记，该类的实例捕获永久失效。现在失败时回滚标记。
- **懒扫描永久停用**：`LAZY_COUNT` 只增不减，累计 8 个 loader 后本进程再也不扫插件。上限提到 32。

### 四、风控与日志

- **`hookNull` 给基本类型返回 null**：目标方法声明为 `boolean`/`int` 时框架拆箱抛异常，等于让该检测点每次调用都崩。现在只接受引用类型。
- **风控 hook 失败后永久失效**：`sHooked` 先 add 再 hook，失败不回滚。现在失败时移除，允许重试；集合改为线程安全。
- **`ro.secure` 主动自曝**：以前返回 `"0"`（设备不安全），与同时把 `ro.debuggable` 置 0、`ro.build.tags` 置 release-keys 自相矛盾。改为 `"1"`。
- **失败日志被调试开关连带删除**：`Logs.w` 跟随 `sEnabled`，而 `sEnabled` 在 `Config.load` 时才设置、晚于 `Logs.setFile` → "日志文件初始化失败"这类最早最关键的告警写不出去。现在 `w()` 与 `e()` 一样不受开关控制。
- **日志滚动上限失守**：`renameTo` 失败时不降级，文件无上限增长。现在失败时截断保底。

### 五、页面误关

- **落地页/隐私页被当广告页关掉**：`AdCloser.isAdScreen` 只做包前缀匹配，`TTDelegateActivity`（隐私协议）、`TTWebViewLandingActivity`、`TTRewardVideoLandingActivity` 全部命中 → 用户还没点同意就被 finish。现在排除 Landing/Delegate/Privacy/Agreement/Permission/WebView/Browser 等标记，并要求包边界（`com.tapad` 不再匹配 `com.tapadXxx`）。
- **关开关不生效**：`closeNow` 不复查 `autoClose()`，用户在广告播放期间关掉开关，已排队任务照常 finish。现在执行前复查。

### 六、其他

- **`GDT-加载` 把成功回调当错误**：`onADLoad` 是加载成功回调，挂进 error 名单后每次正常加载都输出 `E 广告失败`。已移除，只保留 `onNoAd`。
- **`isMainProcess` 兜底方向反了**：两处失败都返回 `true`，子进程会被当成主进程重复跑扫描、装全套 hook。改为返回 `false`。
- **`sInstalled` 早置位导致无法重试**：CAS 在所有判断之前置 true，之后任一提前 return（总开关关闭、未勾选、扫描失败）该进程就永久没有第二次机会。三处提前返回现在都会释放。
- **主线程做跨进程 + 磁盘 IO**：`onPackageReady` 用 `Handler.post` 直接在冷启动关键路径上跑 `remotePreferences()`、`sp.getAll()`、`mkdirs()`。改为 `postDelayed(120ms)`。
- **日志页读写路径不对称且无超时**：列表只扫 `/data_mirror` 一条前缀而清空用三条；`su` 无超时，授权框不响应会永久卡住。现在三条前缀都扫，`su` 调用统一带 8 秒超时。

### 说明

- 签名不变（SHA-256 `7663aa00…`），可直接覆盖安装 v7.24
- `DeviceSpoof` / `LocationSpoof` / `DexIndex` 一行未动
- 规则总数仍为 37，首页「已支持的 SDK」仍显示 16（真实家数）
