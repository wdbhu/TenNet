## TenNet v7.26 (726)

顺着日志把「跳过广告奖励不到账」查到底了，问题在 `fireLastClose`。

### 现象

日志里 `onReward` 补了三次，游戏一点变化都没有：

```
23:41:59.012  补发 GDT  → GDTATRewardedVideoAdapter$b#onReward()
23:41:59.067  补发 TOPN → ATRewardVideoAd$1#onReward(ATAdInfo)
23:41:59.068  补发 TOPN → VideoHelper$1#onReward(ATAdInfo)
23:41:59.514  关闭前替 SDK 触发结束回调 onADClose
```

但整份日志里 **`onVideoComplete` 一次都没触发过**。

### 原因

`fireLastClose` 遍历结束回调时，调到第一个能起来就 `return`：

```java
for (Method close : closes) {
    close.invoke(target);
    Logs.i("关闭前替 SDK 触发结束回调 " + close.getName());
    return true;      // 调到第一个就退出
}
```

而 `closes` 是按 `closeRank` 排过序的：

```java
onAdClose      → 0   （排第一）
onVideoComplete → 1   （排第二）
```

所以永远只调 `onAdClose`，`onVideoComplete` 拿不到机会。游戏侧的状态机要「视频播放完成」才认奖励，只收到 `onReward` 会被判成异常，奖励直接不发。

广告页还被自动关在了 2 秒（`关闭延迟=2000ms`），正常激励视频要 15 到 30 秒，从时长上也对不上。

### 改动

结束回调改成按「完成 → 关闭」两段走完，不再提前 return：

- 第一段：`onVideoComplete` / `onVideoEnd` / `onVideoSkipToEnd` / `PlayEnd`
- 第二段：其余（`onAdClose` / `onAdDismiss` / `onSkippedVideo`）

补发顺序现在是：先 `onReward` 记账发奖，再 `onVideoComplete` 标记看完，最后 `onAdClose` 收尾。

### 另外修了错误日志

`onError` 里那一堆 `com.qq.e.comm.util.AdError@6522f57` 完全没信息。原因是这些 SDK 的错误对象都不重写 `toString()`，我却只做了 `String.valueOf(obj)`。

现在用反射读 `getErrorCode` / `getCode` / `getErrCode` 和 `getErrorMsg` / `getMessage` / `getMsg` / `getDesc` 等常见命名，日志会变成：

```
E 广告失败 ...#onError(AdError,) [GDT] argc=1 (0:AdError)=code=5004, msg=no ad
```

### 说明

签名不变，可以直接覆盖装 v7.25。

改完后自动关广告会多触发一次 `onVideoComplete`，这是这次修复的重点。如果游戏那边仍然不到账，把新的日志发我，重点看 `关闭前替 SDK 触发结束回调 onVideoComplete` 有没有出现。
