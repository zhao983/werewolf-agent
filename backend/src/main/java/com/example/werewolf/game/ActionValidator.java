package com.example.werewolf.game;

import com.example.werewolf.agent.ActionType;
import com.example.werewolf.agent.AgentContext;
import com.example.werewolf.agent.AgentResponse;

/** 对所有 Agent（包括 LLM）的结构化输出进行统一合法性检查。 */
public final class ActionValidator {
    private ActionValidator() { }

    public static void validate(AgentContext context, AgentResponse response) {
        if (response == null || response.action() == null || !context.availableActions().contains(response.action())) {
            throw new IllegalArgumentException("Action is not available in " + context.phase());
        }
        ActionType action = response.action();
        if (action == ActionType.SPEAK) {
            if (response.speech() == null || response.speech().isBlank() || response.targetPlayerId() != null)
                throw new IllegalArgumentException("Speech must have text and no target");
        } else if (action == ActionType.PASS) {
            if (response.targetPlayerId() != null) throw new IllegalArgumentException("PASS has no target");
        } else if (!context.legalTargets().getOrDefault(action, java.util.List.of()).contains(response.targetPlayerId())) {
            throw new IllegalArgumentException("Illegal " + action + " target: " + response.targetPlayerId());
        }
    }
}
