package com.example.werewolf.ai;

/** JSON 保留旧接口兼容性；工具模式按服务支持程度选择是否要求严格参数。 */
public enum DecisionMode {
    JSON, TOOLS, TOOLS_STRICT;

    public boolean usesTools() { return this != JSON; }
}
