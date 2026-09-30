package com.example.werewolf.agent;

/** 决策结果：reasoning 仅内部使用，speech 才能发布到公共发言。 */
public record AgentResponse(String reasoning, String speech, ActionType action, String targetPlayerId) {
    public static AgentResponse action(ActionType action, String target) {
        return new AgentResponse(null, null, action, target);
    }
    public static AgentResponse speech(String text) {
        return new AgentResponse(null, text, ActionType.SPEAK, null);
    }
    /** 可供观战页面展示的一句决策说明；不应包含模型的隐藏推理过程。 */
    public static AgentResponse explained(String note, String speech, ActionType action, String target) {
        return new AgentResponse(note, speech, action, target);
    }
}
