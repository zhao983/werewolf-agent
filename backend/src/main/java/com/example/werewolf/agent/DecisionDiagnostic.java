package com.example.werewolf.agent;

/** 每次模型请求的安全诊断；只存枚举与数值，不保存原始回复、提示词、地址或密钥。 */
public record DecisionDiagnostic(int attempt, Code code, Integer httpStatus, FinishReason finishReason,
                                 Integer toolCallCount, boolean outputExceededLimit, long requestMillis,
                                 OutputIssue issue, java.util.List<LocalRepair> repairs,
                                 Long completionTokens, Long reasoningTokens) {
    public DecisionDiagnostic { repairs = repairs == null ? java.util.List.of() : java.util.List.copyOf(repairs); }
    /** 旧记录和旧测试没有细分错误与用量字段。 */
    public DecisionDiagnostic(int attempt, Code code, Integer httpStatus, FinishReason finishReason,
                              Integer toolCallCount, boolean outputExceededLimit, long requestMillis) {
        this(attempt, code, httpStatus, finishReason, toolCallCount, outputExceededLimit, requestMillis, null, java.util.List.of(), null, null);
    }
    public enum Code { SUCCESS, TIMEOUT, HTTP_ERROR, NETWORK_ERROR, RESPONSE_ERROR, INTERRUPTED,
        TOKEN_LIMIT, EMPTY_CONTENT, INVALID_ACTION, INCONSISTENT_ACTION, CLIENT_ERROR }
    public enum FinishReason { STOP, LENGTH, TOOL_CALLS, CONTENT_FILTER, FUNCTION_CALL, UNKNOWN }
    /** 固定枚举避免把远端原始参数、私有提示或密钥写入诊断。 */
    public enum OutputIssue { MISSING_TOOL, MULTIPLE_TOOLS, UNKNOWN_TOOL, TOOL_TYPE, ARGUMENTS_TOO_LARGE,
        JSON_SYNTAX, JSON_OBJECT, MISSING_FIELD, UNEXPECTED_FIELD, FIELD_TYPE,
        UNKNOWN_ACTION, ACTION_NOT_ALLOWED, ILLEGAL_TARGET, UNEXPECTED_TARGET, EMPTY_SPEECH, SPEECH_TOO_LONG,
        SELF_ID, VOTE_TARGET, DEAD_PLAYER, OWN_HISTORY, PRIVATE_ASIDE, NIGHT_FACT, WOLF_COUNT, WIN_RULE, WOLF_TARGET }
    public enum LocalRepair { REASONING_DEFAULTED, REASONING_TRIMMED }
    public static FinishReason finish(String value) {
        if (value == null) return null;
        try { return FinishReason.valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return FinishReason.UNKNOWN; }
    }
}
