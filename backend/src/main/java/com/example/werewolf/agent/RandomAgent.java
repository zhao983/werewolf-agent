package com.example.werewolf.agent;

import java.util.List;
import java.util.Objects;
import java.util.Random;

/** 只从当前合法动作和目标中随机选择，用作规则验证基线。 */
public final class RandomAgent implements Agent {
    private final Random random;

    public RandomAgent(Random random) { this.random = Objects.requireNonNull(random); }

    @Override
    public AgentResponse act(AgentContext context) {
        if (context.availableActions().contains(ActionType.SPEAK)) {
            return AgentResponse.explained("随机基线使用固定发言模板。",
                    context.playerId() + "：我会观察大家的发言和投票。", ActionType.SPEAK, null);
        }
        List<ActionType> choices = context.availableActions();
        ActionType action = choices.get(random.nextInt(choices.size()));
        List<String> targets = context.legalTargets().getOrDefault(action, List.of());
        String target = targets.isEmpty() ? null : targets.get(random.nextInt(targets.size()));
        return AgentResponse.explained("从当前合法动作和目标中随机选择。", null, action, target);
    }
}
