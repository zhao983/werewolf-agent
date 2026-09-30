package com.example.werewolf.agent;

/** 决策接口；实现类不能直接读写游戏完整状态。 */
public interface Agent {
    AgentResponse act(AgentContext context);
}
