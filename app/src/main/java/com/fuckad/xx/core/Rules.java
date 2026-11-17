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

    /**
     * 默认的错误回调方法名。
     *
     * <p>广告加载/播放失败时 SDK 走的是这些方法，之前规则表里一个都没有，
     * 结果「播放失败」时日志里没有任何痕迹，无法区分是没填充、网络错还是审核拒绝。
     * 现在统一挂上，只记日志、不改行为。</p>
     */
    private static final String[] DEFAULT_ERROR_NAMES = {
            "onAdError",          // 通用
            "onADError",
            "onError",
            "onAdFailed",
            "onAdFail",
            "onAdFailedToLoad",
            "onAdLoadFailed",
            "onAdFailedToShow",
            "onLoadFailed",
            "onRenderFail",
            "onNoAd",             // 优量汇 / 广点通：无广告填充
            "onVideoError",
            "onMediaError",
            "onRewardFailed",
            "onRewardError",
            "onAdShowError",
            "onShowError",
            "onAdVideoError",
    };

    public static final class Rule {

        public final String sdk;
        public final String targetDesc;
        public final String[] rewardNames;
        public final String[] triggerNames;
        public final String[] errorNames;
        public final boolean deliverOnTrigger;

        /** 通用错误回调名（绝大多数 SDK 都适用）。 */
        public Rule(String sdk,
                    String targetDesc,
                    String[] rewardNames,
                    String[] triggerNames,
                    boolean deliverOnTrigger) {
            this(sdk, targetDesc, rewardNames, triggerNames, DEFAULT_ERROR_NAMES, deliverOnTrigger);
        }

        /** 需要额外/自定义错误回调名时用这个。 */
        public Rule(String sdk,
                    String targetDesc,
                    String[] rewardNames,
                    String[] triggerNames,
                    String[] errorNames,
                    boolean deliverOnTrigger) {
            this.sdk = sdk;
            this.targetDesc = targetDesc;
            this.rewardNames = rewardNames == null ? new String[0] : rewardNames;
            this.triggerNames = triggerNames == null ? new String[0] : triggerNames;
            this.errorNames = errorNames == null ? DEFAULT_ERROR_NAMES : errorNames;
            this.deliverOnTrigger = deliverOnTrigger;
        }

        public boolean isReward(String methodName) {
            return contains(rewardNames, methodName);
        }

        public boolean isTrigger(String methodName) {
            return contains(triggerNames, methodName);
        }

        /** 是否是错误/失败回调。命中后只记日志，不干预原有流程。 */
        public boolean isError(String methodName) {
            return contains(errorNames, methodName);
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

        // ===== 错误/加载结果回调 =====
        // 这些规则只负责把失败链路记进日志，rewardNames / triggerNames 都为空，
        // deliverOnTrigger=false，不会干预原有流程。
        // 加它们的原因：「播放失败」时模块完全无记录，无法区分没填充/网络错/审核拒绝。

        list.add(errorOnly("CSJ-加载",
                "Lcom/bytedance/sdk/openadsdk/TTAdNative$RewardVideoAdListener;",
                "onError"));
        list.add(errorOnly("CSJ-信息流加载",
                "Lcom/bytedance/sdk/openadsdk/TTAdNative$FeedAdListener;",
                "onError"));
        list.add(errorOnly("CSJ-插全屏加载",
                "Lcom/bytedance/sdk/openadsdk/TTAdNative$FullScreenVideoAdListener;",
                "onError"));

        list.add(errorOnly("GM-加载",
                "Lcom/bytedance/msdk/api/v2/ad/reward/GMRewardedAdLoadListener;",
                "onAdLoadFail", "onError"));
        list.add(errorOnly("GM-展示",
                "Lcom/bytedance/msdk/api/v2/ad/reward/GMRewardedAdShowListener;",
                "onAdShowFail"));

        // onADLoad 是「加载成功」回调，不能算错误；只用 onNoAd（无填充）
        list.add(errorOnly("GDT-加载",
                "Lcom/qq/e/ads/rewardvideo/RewardVideoADListener;",
                "onNoAd"));

        list.add(errorOnly("KS-加载",
                "Lcom/kwad/sdk/api/KsRewardVideoAd$RewardAdInteractionListener;",
                "onError", "onAdFailed"));

        list.add(errorOnly("BAIDU-加载",
                "Lcom/baidu/mobads/sdk/api/RewardVideoAd$RewardVideoAdListener;",
                "onAdFailed", "onError"));

        list.add(errorOnly("TOPN-加载",
                "Lcom/anythink/rewardvideo/api/ATRewardVideoListener;",
                "onRewardVideoAdLoadFail", "onRewardedVideoAdPlayFailed",
                "onRewardVideoAdPlayError"));

        list.add(errorOnly("SIGMOB-加载",
                "Lcom/sigmob/windad/rewardVideo/WindRewardVideoAdListener;",
                "onRewardAdLoadError", "onRewardAdPlayError"));

        list.add(errorOnly("MIMO-加载",
                "Lcom/miui/zeus/mimo/sdk/RewardVideoAd$RewardVideoLoadListener;",
                "onAdLoadFailed"));

        list.add(errorOnly("SDM-加载",
                "Lcom/smartdigimkt/sdk/api/format/SDMRewardVideoListener;",
                "onAdError", "onAdFailed", "onLoadFailed"));

        list.add(errorOnly("趣头条-加载",
                "Lcom/coohua/base/sdk/ad/callback/RewardVideoAdListener;",
                "onError", "onAdFailed"));

        list.add(errorOnly("TAPS-加载",
                "Lcom/tapsdk/tapad/group/ads/DirichletRewardVideoAd$RewardAdInteractionListener;",
                "onError", "onAdFailed"));

        list.add(errorOnly("汇川-加载",
                "Lcom/noah/api/RewardedVideoAd$AdListener;",
                "onAdError", "onError"));

        ALL = Collections.unmodifiableList(list);
    }

    /** 只挂错误回调的规则：不参与发奖，只记日志。 */
    private static Rule errorOnly(String sdk, String targetDesc, String... errorNames) {
        return new Rule(sdk, targetDesc, new String[0], new String[0], errorNames, false);
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
