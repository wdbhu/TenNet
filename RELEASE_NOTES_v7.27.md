## TenNet v7.27 (727)

接着「跳过广告奖励不到账」继续查，这次是补发挑错了方法。

### 现象

同一个游戏，快手源的广告能到账，优量汇（GDT）的不行。

到账那次（快手）：

```
00:00:19.355  onVideoPlayStart
00:00:20.855  补发 onRewardVerify  [KS]     ← 到账
```

没到账那次（GDT）：

```
00:01:42.457  onRewardedVideoAdPlayStart
00:01:43.912  补发 onReward()  [GDT]         ← 没到账
```

### 原因

`GDTATRewardedVideoAdapter$b` 这个类里有两个同名方法：

```
onReward()              ← 无参
onReward(java.util.Map) ← 有参
```

补发时遍历同名重载挑第一个能用的，而 `getDeclaredMethods()` 的顺序不确定，这次挑中了无参的 `onReward()`。

无参版本在适配器里通常只是个通知壳、或者只打点，真正把奖励往聚合层和 App 传的是带 `Map` 的那个。所以日志写着「已补发奖励」，游戏那边什么都没收到。

快手那边 `onRewardVerify()` 只有一个重载，所以一直正常。

### 改动

补发前把同名重载按参数个数降序排一遍，优先挑参数最多的：

```java
Collections.sort(ordered, new Comparator<Method>() {
    @Override
    public int compare(Method a, Method b) {
        return b.getParameterCount() - a.getParameterCount();
    }
});
```

参数多的信息全，更可能是真实业务入口；无参的多半只是通知。

另外把「已补发奖励」那行日志改成打完整签名，以前只打方法名：

```
已补发奖励 com.anythink.network.gdt.GDTATRewardedVideoAdapter$b#onReward(java.util.Map,) -> ... [GDT]
```

下次挑没挑对，一眼就能看出来。

### 还想再说一句广告时长的事

日志里每轮广告都是 2.5 秒就被关掉了：

```
00:01:42.412  PortraitADActivity 出现，关闭计时 2500ms
00:01:44.915  自动关闭广告页
```

正常激励视频 15 到 30 秒。2.5 秒关掉的话，就算方法挑对了，游戏侧要是校验观看时长仍然会拒发。

所以如果这版还是不到账，可以试试把关闭延迟改成 15000ms 以上，让广告自然播完再关，排除时长这个变量。

### 说明

签名不变，可以直接覆盖安装 v7.26。
