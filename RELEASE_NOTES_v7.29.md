## TenNet v7.29 (729)

回滚上一版引入的闪退。

### 上一版发生了什么

v7.28 试了一种新思路：不再自己补 `onReward`，只触发 `onVideoComplete`，让广告 SDK 自己判断发不发奖励。结果一关广告就闪退：

```
FATAL EXCEPTION: pool-48-thread-1
Process: sca.thtrg.ef
java.lang.NullPointerException: ... Object.toString() on a null object reference
    at com.anythink.unitybridge.videoad.VideoHelper$1$13.run(VideoHelper.java:271)
```

原因：TopOn 的 `VideoHelper` 在「视频看完」的后续流程里，要对一个 `ATAdInfo` 调 `toString()`。这个对象只有广告真实播放、SDK 状态机走完后才会生成有效值，客户端伪造不出来，所以拿到的是 null，直接 NPE。

### 这版做了什么

回滚 v7.28 的改动，回到 v7.27 的手动补发逻辑。功能上等于 v7.27，只是修复了闪退。

### 顺带把结论说清楚

这几版（7.26 → 7.28）一直在查「跳过广告奖励不到账」，现在结论明确：

- 优量汇（GDT）源的激励视频，奖励需要服务端确认观看记录，客户端无法伪造
- 手动补 `onReward`，参数是空 Map / 空壳 ATAdInfo，适配器不传递
- 只触发 `onVideoComplete`，SDK 内部 NPE 闪退
- 快手（KS）源只认客户端回调，所以之前能到账

所以这个游戏（TopOn 聚合 + 优量汇源）**跳过广告拿不到奖励是平台机制决定的，不是模块 bug**。快手源的游戏、以及其它只校验客户端回调的游戏，模块仍然有效。

### 说明

签名不变，可以直接覆盖安装 v7.28。
