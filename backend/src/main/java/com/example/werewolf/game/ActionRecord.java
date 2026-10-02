package com.example.werewolf.game;

import com.example.werewolf.agent.ActionType;
import com.example.werewolf.agent.AgentMetrics;

/** 实验行动记录；不保存 AgentContext、请求全文或模型思考内容，也不会反馈给 Agent。 */
public record ActionRecord(int sequence, int day, GamePhase phase, String playerId,
                           ActionType action, String targetPlayerId, String status,
                           double decisionMillis, AgentMetrics metrics,
                           java.util.List<com.example.werewolf.agent.DecisionDiagnostic> diagnostics) {
    public ActionRecord {
        diagnostics = diagnostics == null ? java.util.List.of() : java.util.List.copyOf(diagnostics);
    }
    /** 老存档未保存诊断，读取时保留为空，不推测历史错误原因。 */
    public ActionRecord(int sequence, int day, GamePhase phase, String playerId, ActionType action,
                        String targetPlayerId, String status, double decisionMillis, AgentMetrics metrics) {
        this(sequence, day, phase, playerId, action, targetPlayerId, status, decisionMillis, metrics, java.util.List.of());
    }
}
