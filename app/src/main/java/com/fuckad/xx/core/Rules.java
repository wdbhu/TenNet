package com.fuckad.xx.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 内置的 SDK 规则表。
 *
 * <p>每条规则描述一个广告 SDK 的奖励回调接口：</p>
 * <ul>
 *     <li>{@code targetDesc}：接口的 JVM 描述符，dex 扫描时用它找实现类；</li>
 *     <li>{@code rewardNames}：真正发奖励的方法名，hook 后保证参数“看起来是完成态”；</li>
 *     <li>{@code triggerNames}：广告结束/跳过/关闭类方法，hook 后先放行再补发奖励；</li>
 *     <li>{@code deliverOnTrigger}：是否需要在触发方法里主动补发奖励。</li>
 * </ul>
 */
public final class Rules {

    public static final class Rule {

        public final String sdk;
        public final String targetDesc;
        public final String[] rewardNames;
        public final String[] triggerNames;
        public final boolean deliverOnTrigger;

        public Rule(String sdk,
             String targetDesc,
             String[] rewardNames,
             String[] triggerNames,
             boolean deliverOnTrigger) {
            this.sdk = sdk;
            this.targetDesc = targetDesc;
            this.rewardNames = rewardNames == null ? new String[0] : rewardNames;
            this.triggerNames = triggerNames == null ? new String[0] : triggerNames;
            this.deliverOnTrigger = deliverOnTrigger;
        }

        public boolean isReward(String methodName) {
            return contains(rewardNames, methodName);
        }

        public boolean isTrigger(String methodName) {
            return contains(triggerNames, methodName);
        }

        public String primaryRewardName() {
            return rewardNames.length > 0 ? rewardNames[0] : null;
        }

        private static boolean contains(String[] names, String name) {
            if (names == null || name == null) return false;
            for (String n : names) {
                if (name.equals(n)) return true;
            }
            return false;
        }

        @Override
        public String toString() {
            return sdk;
        }
    }

    private static final List<Rule> ALL;

    static {
        List<Rule> list = new ArrayList<>(12);

        // 穿山甲
        list.add(new Rule("CSJ",
                "Lcom/bytedance/sdk/openadsdk/TTRewardVideoAd$RewardAdInteractionListener;",
                new String[]{"onRewardArrived", "onRewardVerify"},
                new String[]{"onSkippedVideo", "onVideoComplete", "onAdClose", "onAdShow"},
                true));

        // TapAD / TapTap 聚合（Dirichlet）外层奖励监听：游戏真正拿奖励靠这一层
        list.add(new Rule("TAPS",
                "Lcom/tapsdk/tapad/group/ads/DirichletRewardVideoAd$RewardAdInteractionListener;",
                new String[]{"onRewardVerify", "onReward", "onRewardArrived"},
                new String[]{"onAdClose", "onAdShow"},
                true));

        // 穿山甲信息流视频奖励
        list.add(new Rule("CSJ-Feed",
                "Lcom/bytedance/sdk/openadsdk/TTFeedAd$VideoRewardListener;",
                new String[]{"onFeedRewardCountDown"},
                new String[0],
                false));

        // 优量汇
        list.add(new Rule("GDT",
                "Lcom/qq/e/ads/rewardvideo/RewardVideoADListener;",
                new String[]{"onReward"},
                new String[]{"onVideoComplete", "onADClose", "onADShow"},
                true));

        // 优量汇新版奖励监听
        list.add(new Rule("GDT-Reward",
                "Lcom/qq/e/comm/listeners/ADRewardListener;",
                new String[]{"onReward"},
                new String[0],
                false));

        // 快手
        list.add(new Rule("KS",
                "Lcom/kwad/sdk/api/KsRewardVideoAd$RewardAdInteractionListener;",
                new String[]{"onRewardVerify", "onRewardStepVerify", "onExtraRewardVerify"},
                new String[]{"onVideoSkipToEnd", "onVideoPlayEnd", "onPageDismiss", "onVideoPlayStart"},
                true));

        // 穿山甲聚合 GroMore
        list.add(new Rule("GM",
                "Lcom/bytedance/msdk/api/v2/ad/reward/GMRewardedAdListener;",
                new String[]{"onRewardVerify"},
                new String[]{"onSkippedVideo", "onVideoComplete", "onRewardedAdClosed"},
                true));

        // 小米 MIMO
        list.add(new Rule("MIMO",
                "Lcom/miui/zeus/mimo/sdk/RewardVideoAd$RewardVideoInteractionListener;",
                new String[]{"onReward"},
                new String[]{"onVideoSkip", "onVideoComplete", "onAdDismissed"},
                true));

        // 阿里 Tanx
        list.add(new Rule("TANX",
                "Lcom/alimm/tanx/core/ad/ad/reward/ITanxRewardVideoInteractionListener;",
                new String[]{"onRewardArrived"},
                new String[]{"onSkippedVideo", "onVideoComplete", "onAdClose"},
                true));

        // Tanx 激励视频模板
        list.add(new Rule("TANX-Express",
                "Lcom/alimm/tanx/ui/ad/express/reward/ITanxRewardExpressAd$OnRewardAdListener;",
                new String[]{"onRewardArrived"},
                new String[0],
                false));

        // AdMob
        list.add(new Rule("ADMOB",
                "Lcom/google/android/gms/ads/OnUserEarnedRewardListener;",
                new String[]{"onUserEarnedReward"},
                new String[0],
                false));

        // AdMob 旧版
        list.add(new Rule("ADMOB-Legacy",
                "Lcom/google/android/gms/ads/rewarded/RewardedAdCallback;",
                new String[]{"onUserEarnedReward"},
                new String[]{"onRewardedAdClosed"},
                true));

        // TopOn / AnyThink 聚合（App 自己实现的顶层回调）
        list.add(new Rule("TOPN",
                "Lcom/anythink/rewardvideo/api/ATRewardVideoListener;",
                new String[]{"onReward"},
                new String[]{"onRewardedVideoAdClosed", "onRewardedVideoAdPlayEnd", "onRewardedVideoAdPlayStart"},
                true));

        // Sigmob
        list.add(new Rule("SIGMOB",
                "Lcom/sigmob/windad/rewardVideo/WindRewardVideoAdListener;",
                new String[]{"onRewardAdRewarded"},
                new String[]{"onRewardAdClosed", "onRewardAdPlayEnd", "onRewardAdPlayStart"},
                true));

        // 百度联盟
        list.add(new Rule("BAIDU",
                "Lcom/baidu/mobads/sdk/api/RewardVideoAd$RewardVideoAdListener;",
                new String[]{"onRewardVerify"},
                new String[]{"onAdShow"},
                true));

        // SmartDigimkt
        list.add(new Rule("SDM",
                "Lcom/smartdigimkt/sdk/api/format/SDMRewardVideoListener;",
                new String[]{"onAdReward"},
                new String[]{"onAdClose", "onAdPlayEnd", "onAdPlayStart"},
                true));

        // 部分 cocos 游戏自带聚合层（liquid union）：onRewardVerify 只做埋点，
        // 真正发奖在 onVideoComplete，所以把它作为 trigger 触发补发。
        list.add(new Rule("LIQUID",
                "Lcom/liquid/union/sdk/UnionRewardVideoAd$UnionRewardAdInteractionListener;",
                new String[]{"onVideoComplete"},
                new String[]{"onRewardVerify", "onAdClose"},
                true));

        // Unity Ads
        list.add(new Rule("UNITY",
                "Lcom/unity3d/ads/IUnityAdsShowListener;",
                new String[]{"onUnityAdsShowComplete"},
                new String[]{"onUnityAdsShowStart"},
                false));

        // 汇川（noah/adn）SDK 内部奖励点：HcRewardedAdn 实现 view/a，
        // 关页后补发 onReward(0) 会顺着 SDK 自己的链路回调到聚合层和 App。
        list.add(new Rule("汇川",
                "Lcom/noah/adn/huichuan/view/a;",
                new String[]{"onReward"},
                new String[]{"onVideoComplete", "onAdClose"},
                true));

        // 汇川公开 API 的奖励监听（App 直接接 SDK 时用这一层）
        list.add(new Rule("汇川-API",
                "Lcom/noah/api/RewardedVideoAd$AdListener;",
                new String[]{"onRewarded"},
                new String[]{"onVideoEnd", "onAdClosed"},
                true));

        // 趣头条广告 SDK 的 App 层奖励回调（CAdVideo* 系列都走这里）
        list.add(new Rule("趣头条",
                "Lcom/coohua/base/sdk/ad/callback/RewardVideoAdListener;",
                new String[]{"onReward"},
                new String[]{"onVideoComplete", "onAdClose", "onSkipped"},
                true));

        // 趣头条锁屏/插屏内的激励回调
        list.add(new Rule("趣头条-锁屏",
                "Lcom/coohua/adbase/innerdiylock/listener/RewardAdInteractionListener;",
                new String[]{"onRewardArrived"},
                new String[]{"onVideoComplete", "onAdClose", "onSkippedVideo"},
                true));

        ALL = Collections.unmodifiableList(list);
    }

    private Rules() {
    }

    public static List<Rule> all() {
        return ALL;
    }

    /** 所有规则都启用（SDK 开关已移除）。 */
    public static List<Rule> enabled() {
        return ALL;
    }
}
