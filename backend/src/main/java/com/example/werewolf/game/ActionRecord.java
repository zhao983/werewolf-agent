package com.example.werewolf.game;

import com.example.werewolf.agent.ActionType;
import com.example.werewolf.agent.AgentMetrics;

/** 实验行动记录；不保存 AgentContext、请求全文或模型思考内容，也不会反馈给 Agent。 */
public record ActionRecord(int sequence, int day, GamePhase phase, String playerId,
                           ActionType action, String targetPlayerId, String status,
                           double decisionMillis, AgentMetrics metrics) { }
