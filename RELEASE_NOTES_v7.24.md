## TenNet v7.24 (724)

首页「已支持的 SDK」数字修正为真实家数。

### 问题

那个数字读的是 `Rules.all().size`，即**规则条数**，不是 SDK 家数。两处偏差：

1. 同一家 SDK 常有多条规则
   - CSJ / CSJ-Feed
   - GDT / GDT-Reward
   - ADMOB / ADMOB-Legacy
   - TANX / TANX-Express
   - 汇川 / 汇川-API
   - 趣头条 / 趣头条-锁屏
2. v7.23 新增了 15 条「只挂错误回调记日志」的规则，它们不提供发奖能力，却也被算进去了

所以 v7.23 显示 37，但这个数从来没代表过 SDK 家数，v7.22 时的 23 同样不准。

### 修复

`HomeViewModel` 的统计改为：

```kotlin
Rules.all()
    .asSequence()
    .filter { it.rewardNames.isNotEmpty() }   // 只算能发奖的规则
    .map { it.sdk.substringBefore('-') }      // CSJ-Feed → CSJ，同家合并
    .distinct()
    .count()
```

当前规则表下结果为 **16**，与真实支持的家数一致：

```
ADMOB  BAIDU  CSJ  GDT  GM  KS  LIQUID  MIMO
SDM  SIGMOB  TANX  TAPS  TOPN  UNITY  汇川  趣头条
```

### 变更范围

只改了 `ui/viewmodel/HomeViewModel.kt` 一处统计逻辑，外加版本号。

- `Rules.java`、`AdHookEngine.java` 一行未动，错误回调 hook 行为完全不变
- 发奖逻辑、作用域、自动关广告均无变化
- 新增的 15 条错误规则仍在，日志里照常输出 `广告失败 ...`

### 说明

- 签名不变（SHA-256 `7663aa00…`），可直接覆盖安装 v7.23
- 首页数字从 37 变为 16 是修正，不是功能减少
