package com.example.werewolf.agent;

import java.util.regex.Pattern;

/** 保守检查明确的编号/目标矛盾；不判断他人真实角色，允许角色伪装及公开策略性隐瞒。 */
public final class DecisionConsistency {
    private static final String PLAYER = "(?:player\\s*(\\d+)|(?:第)?(\\d+)\\s*号(?:玩家)?)";
    private DecisionConsistency() { }
    public static void validate(AgentContext context, AgentResponse response) {
        String reason = response.reasoning() == null ? "" : response.reasoning();
        String speech = response.speech() == null ? "" : response.speech();
        if (reason.length() > 80 || speech.length() > 500)
            throw new Problem("请将私有摘要限制在80字、公开发言限制在500字以内");
        // 仅检查以句首“我是 N 号”自我介绍的编号，引用他人的话不属于本人身份声明。
        var self = Pattern.compile("(?:^|[。！？\\n])\\s*我是\\s*" + PLAYER, Pattern.CASE_INSENSITIVE).matcher(speech);
        while (self.find()) if (!id(self, 1).equals(context.playerId()))
            throw new Problem("自我介绍编号与 playerId 不符；请使用本人编号，角色声明可按策略选择");
        // 检查私有摘要明确写出的实际投票，不改写工具目标，也不依据嫌疑叙述自动换票。
        if (response.action() == ActionType.VOTE) {
            var vote = Pattern.compile("(?:^|[，,。；;：:\\n])\\s*(?:我(?:将|会|决定)?|本轮|因此|所以|必须|选择)?\\s*(?:必须)?\\s*(?:投票(?:给|选择)?|投给|投)\\s*" + PLAYER + "(?!\\s*的)",
                    Pattern.CASE_INSENSITIVE).matcher(reason);
            while (vote.find()) if (!id(vote, 1).equals(response.targetPlayerId()))
                throw new Problem("私有摘要明确投票对象与 targetPlayerId 不一致；请重新选择一个合法目标并保持一致");
        }
        // 已死亡者不能回应新问题；回顾其过去发言和讨论其身份仍允许。
        var request = Pattern.compile("(?:^|[，,。；;！!\\n])\\s*(?:请|希望|要求|让)\\s*" + PLAYER +
                "\\s*(?:玩家)?\\s*(?:说明|解释|回应|发言|回答)", Pattern.CASE_INSENSITIVE).matcher(speech);
        while (request.find()) if (context.deadPlayerIds().contains(id(request, 1)))
            throw new Problem("发言要求已出局玩家回应；请依据 deadPlayerIds 向存活玩家提问");
        // 只限制私有摘要否认本人已执行行动；公开发言可以故意隐瞒查验或用药。
        boolean checked = context.ownActionHistory().stream().anyMatch(a -> a.action() == ActionType.CHECK);
        boolean medicated = context.ownActionHistory().stream().anyMatch(a -> a.action() == ActionType.SAVE || a.action() == ActionType.POISON);
        boolean firstNightMedicated = context.ownActionHistory().stream().anyMatch(a -> a.day() == 1 && (a.action() == ActionType.SAVE || a.action() == ActionType.POISON));
        if (checked && Pattern.compile("(?:^|[，。；])\\s*(?:首夜平安夜)?(?:我未验人|我没有进行验人|无查验信息|从未查验)(?:[，。；]|$)").matcher(reason).find()
                || medicated && Pattern.compile("(?:^|[，。；])\\s*(?:从未用药|我没有使用过药剂)(?:[，。；]|$)").matcher(reason).find()
                || firstNightMedicated && Pattern.compile("(?:^|[，。；])\\s*首夜(?:平安夜)?(?:且)?未用药(?:[，。；]|$)").matcher(reason).find())
            throw new Problem("私有摘要否认本人已完成的查验或用药；请核对 ownActionHistory 和 privateInformation");
        if (Pattern.compile("(?:虽然|其实)我是[^。！？]{0,12}(?:女巫|预言家|狼人)[^。！？]{0,12}(?:不能明说|不能说|不能暴露)").matcher(speech).find())
            throw new Problem("公开发言混入了标注为不能公开的内心旁白；请放入 reasoning，speech 仅写愿意公开的话");
    }
    private static String id(java.util.regex.Matcher matcher, int group) {
        String number = matcher.group(group) == null ? matcher.group(group + 1) : matcher.group(group);
        return "player" + Integer.parseInt(number);
    }
    public static final class Problem extends IllegalArgumentException {
        Problem(String message) { super(message); }
    }
}
