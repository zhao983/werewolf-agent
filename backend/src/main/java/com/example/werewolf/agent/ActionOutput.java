package com.example.werewolf.agent;

import com.example.werewolf.agent.DecisionDiagnostic.*;
import java.util.*;

/** 输出问题只使用安全的固定说明；本地修正仅影响私有摘要，不替换目标或公开发言。 */
public final class ActionOutput {
    private ActionOutput() { }
    public static final class Problem extends IllegalArgumentException {
        private final OutputIssue issue;
        public Problem(OutputIssue issue, String hint) { super(hint); this.issue = issue; }
        public OutputIssue issue() { return issue; }
    }
    public record Repaired(AgentResponse response, List<LocalRepair> repairs) { }
    public static Repaired repairSummary(AgentResponse response) {
        List<LocalRepair> repairs = new ArrayList<>();
        String reason = response.reasoning();
        if (reason == null) { reason = ""; repairs.add(LocalRepair.REASONING_DEFAULTED); }
        reason = reason.strip();
        if (reason.length() > 80) {
            // 避免切断 UTF-16 代理对；模型选择的行动、目标和公开发言保持原样。
            int end = Character.isHighSurrogate(reason.charAt(79)) ? 79 : 80;
            reason = reason.substring(0, end); repairs.add(LocalRepair.REASONING_TRIMMED);
        }
        return new Repaired(new AgentResponse(reason, response.speech(), response.action(), response.targetPlayerId()), List.copyOf(repairs));
    }
}
