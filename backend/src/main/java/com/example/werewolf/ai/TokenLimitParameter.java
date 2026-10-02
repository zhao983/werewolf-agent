package com.example.werewolf.ai;

/** 兼容服务由用户选择支持的输出额度字段；每次只发送一种，不擅自自动切换。 */
public enum TokenLimitParameter {
    MAX_TOKENS("max_tokens"), MAX_COMPLETION_TOKENS("max_completion_tokens");
    private final String field;
    TokenLimitParameter(String field) { this.field = field; }
    public String field() { return field; }
}
