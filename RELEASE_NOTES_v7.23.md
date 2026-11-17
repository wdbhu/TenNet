## TenNet v7.23 (723)

这版只做一件事：**把广告失败的原因记进日志**。

### 背景

v7.22 及更早，模块只 hook 奖励回调和结束回调（`onReward*`、`onAdClose`、`onSkipped`、`onVideoComplete`、`onAdShow` 这些），**完全没有 hook 任何错误回调**。

结果是：广告播不出来时，`tunnet.log` 里一个字节都不会留。你只能看到广告对象被创建（`捕获广告实例 xxx`），之后什么都没有，分不清是没填充、网络错还是平台拒绝。

### 新增

规则表里加了一组「只记日志」的错误回调规则。它们的奖励名和触发名都是空的，`deliverOnTrigger=false`，不参与发奖，也不改变任何原有流程，只负责把失败信息写进日志：

```
11-17 20:xx:xx.xxx E 广告失败 com.bytedance.msdk.adapter.baidu.BaiduRewardLoader$BaiduRewardVideoAd#onAdFailed(int,java.lang.String,) [BAIDU-加载] argc=2 (0:int)=40001, (1:String)=no ad fill
```

覆盖的 SDK 与接口：

| SDK | 接口 | 错误方法 |
| --- | --- | --- |
| 穿山甲 | `TTAdNative$RewardVideoAdListener` | `onError` |
| 穿山甲信息流 | `TTAdNative$FeedAdListener` | `onError` |
| 穿山甲插全屏 | `TTAdNative$FullScreenVideoAdListener` | `onError` |
| GroMore 加载 | `GMRewardedAdLoadListener` | `onAdLoadFail` |
| GroMore 展示 | `GMRewardedAdShowListener` | `onAdShowFail` |
| 优量汇 | `RewardVideoADListener` | `onNoAd`、`onADLoad` |
| 快手 | `KsRewardVideoAd$RewardAdInteractionListener` | `onError` |
| 百度 | `RewardVideoAd$RewardVideoAdListener` | `onAdFailed` |
| TopOn | `ATRewardVideoListener` | `onRewardVideoAdLoadFail` 等 |
| Sigmob | `WindRewardVideoAdListener` | `onRewardAdLoadError` |
| 小米 MIMO | `RewardVideoAd$RewardVideoLoadListener` | `onAdLoadFailed` |
| SmartDigimkt | `SDMRewardVideoListener` | `onAdError` |
| 趣头条 | `RewardVideoAdListener` | `onError` |
| TapAD | `DirichletRewardVideoAd$RewardAdInteractionListener` | `onError` |
| 汇川 | `RewardedVideoAd$AdListener` | `onAdError` |

参数会被展开成可读文本：错误码原样输出，`Throwable` 取 message，其余 `toString`。

### 另外

`Rule` 类新增 `errorNames` 字段和 `isError(String)` 判断；`AdHookEngine` 新增 `installError(...)`，在主流程、实现类 hook、接口兜底三处都会装配。

### 说明

- 功能行为与 v7.22 完全一致，唯一区别是失败时多了日志
- 日志页看 `E 广告失败` 开头的行，`[xxx-加载]` 是 SDK 名，后面是原始错误码和错误信息
- 签名不变（SHA-256 `7663aa00…`），可直接覆盖安装 v7.22
