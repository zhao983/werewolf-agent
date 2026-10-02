package com.example.werewolf.game;

import com.example.werewolf.agent.ActionType;
import com.example.werewolf.agent.AgentContext;
import com.example.werewolf.agent.AgentResponse;
import com.example.werewolf.agent.ActionOutput.Problem;
import com.example.werewolf.agent.DecisionDiagnostic.OutputIssue;

/** 对所有 Agent（包括 LLM）的结构化输出进行统一合法性检查。 */
public final class ActionValidator {
    private ActionValidator() { }

    public static void validate(AgentContext context, AgentResponse response) {
        if (response == null || response.action() == null || !context.availableActions().contains(response.action())) {
            throw new Problem(OutputIssue.ACTION_NOT_ALLOWED, "动作不属于本阶段允许的行动");
        }
        ActionType action = response.action();
        if (action == ActionType.SPEAK) {
            if (response.speech() == null || response.speech().isBlank())
                throw new Problem(OutputIssue.EMPTY_SPEECH, "公开发言必须是非空文本");
            if (response.targetPlayerId() != null) throw new Problem(OutputIssue.UNEXPECTED_TARGET, "发言不能携带目标");
            if (response.speech().length() > 500) throw new Problem(OutputIssue.SPEECH_TOO_LONG, "公开发言超过500字符；请缩短 speech");
        } else if (action == ActionType.PASS) {
            if (response.targetPlayerId() != null) throw new Problem(OutputIssue.UNEXPECTED_TARGET, "PASS 不能携带目标");
        } else if (!context.legalTargets().getOrDefault(action, java.util.List.of()).contains(response.targetPlayerId())) {
            throw new Problem(OutputIssue.ILLEGAL_TARGET, "目标不在本次合法玩家列表；请从 legalTargets 复制玩家编号");
        }
    }
}
