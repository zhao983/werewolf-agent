package com.example.werewolf.agent;

import com.example.werewolf.player.Role;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 简单、可复现的非 LLM 基线策略；仅依赖 AgentContext。 */
public final class RuleAgent implements Agent {
    private static final Pattern WOLF_CHECK = Pattern.compile("(player\\d+) is WEREWOLF");

    @Override
    public AgentResponse act(AgentContext context) {
        if (context.availableActions().contains(ActionType.SPEAK))
            return AgentResponse.explained("规则基线使用固定发言模板。",
                    context.playerId() + "：我会结合已知信息做判断。", ActionType.SPEAK, null);
        if (context.availableActions().contains(ActionType.SAVE))
            return AgentResponse.explained("有解药且存在狼人目标，优先救人。",
                    null, ActionType.SAVE, context.attackedPlayerId());
        if (context.availableActions().contains(ActionType.CHECK)) {
            List<String> candidates = context.legalTargets().get(ActionType.CHECK);
            String target = candidates.stream().filter(id -> context.privateInformation().stream()
                    .noneMatch(info -> info.contains(id + " is "))).findFirst().orElse(candidates.get(0));
            return AgentResponse.explained("优先查验尚未查验的存活玩家。", null, ActionType.CHECK, target);
        }
        if (context.availableActions().contains(ActionType.KILL))
            return AgentResponse.explained("选择合法目标列表中的首位玩家。", null,
                    ActionType.KILL, context.legalTargets().get(ActionType.KILL).get(0));
        if (context.availableActions().contains(ActionType.VOTE)) {
            List<String> candidates = context.legalTargets().get(ActionType.VOTE);
            if (context.role() == Role.SEER) {
                for (String info : context.privateInformation()) {
                    Matcher found = WOLF_CHECK.matcher(info);
                    if (found.find() && candidates.contains(found.group(1)))
                        return AgentResponse.explained("投票给已查验出的狼人。", null, ActionType.VOTE, found.group(1));
                }
            }
            return AgentResponse.explained("没有已确认的狼人，选择合法目标列表中的首位玩家。",
                    null, ActionType.VOTE, candidates.get(0));
        }
        return AgentResponse.explained("当前没有更高优先级的规则动作。", null, ActionType.PASS, null);
    }
}
