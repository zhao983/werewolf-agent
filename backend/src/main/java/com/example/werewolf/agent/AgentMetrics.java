package com.example.werewolf.agent;

/** 一次决策的调用统计；未知的 token 用量不估算，只累加接口实际报告的部分。 */
public record AgentMetrics(int apiCalls, int apiFailures, int invalidReplies, int usageReportedCalls,
                           long promptTokens, long completionTokens, long totalTokens, long requestMillis) {
    public static AgentMetrics empty() { return new AgentMetrics(0, 0, 0, 0, 0, 0, 0, 0); }
}
