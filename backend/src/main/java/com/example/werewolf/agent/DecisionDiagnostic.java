package com.example.werewolf.agent;

/** 每次模型请求的安全诊断；只存枚举与数值，不保存原始回复、提示词、地址或密钥。 */
public record DecisionDiagnostic(int attempt, Code code, Integer httpStatus, FinishReason finishReason,
                                 Integer toolCallCount, boolean outputExceededLimit, long requestMillis) {
    public enum Code { SUCCESS, TIMEOUT, HTTP_ERROR, NETWORK_ERROR, RESPONSE_ERROR, INTERRUPTED,
        TOKEN_LIMIT, EMPTY_CONTENT, INVALID_ACTION, INCONSISTENT_ACTION, CLIENT_ERROR }
    public enum FinishReason { STOP, LENGTH, TOOL_CALLS, CONTENT_FILTER, FUNCTION_CALL, UNKNOWN }
    public static FinishReason finish(String value) {
        if (value == null) return null;
        try { return FinishReason.valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return FinishReason.UNKNOWN; }
    }
}
