package com.example.werewolf.agent;

/** 决策接口；实现类不能直接读写游戏完整状态。 */
public interface Agent {
    AgentResponse act(AgentContext context);
    /** 规则型 Agent 默认没有模型调用；统计仅供用户实验分析。 */
    default AgentMetrics lastMetrics() { return AgentMetrics.empty(); }
    /** 诊断只供用户查看；规则 Agent 没有模型请求诊断。 */
    default java.util.List<DecisionDiagnostic> lastDiagnostics() { return java.util.List.of(); }
}
