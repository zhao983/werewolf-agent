package com.example.werewolf.agent;

/** 决策接口；实现类不能直接读写游戏完整状态。 */
public interface Agent {
    AgentResponse act(AgentContext context);
    /** 规则型 Agent 默认没有模型调用；统计仅供用户实验分析。 */
    default AgentMetrics lastMetrics() { return AgentMetrics.empty(); }
    /** 诊断只供用户查看；规则 Agent 没有模型请求诊断。 */
    default java.util.List<DecisionDiagnostic> lastDiagnostics() { return java.util.List.of(); }
    /** 同一行动持续失败时暂停进一步请求；规则 Agent 不需要该保护。 */
    default boolean retryBlocked() { return false; }
    /** 用户明确恢复重试，仅解除请求保护，不执行或跳过游戏行动。 */
    default void allowRetry() { }
}
