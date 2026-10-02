package com.example.werewolf.ai;

import com.example.werewolf.agent.DecisionDiagnostic.Code;

/** 请求失败的安全分类；不把第三方响应或异常原文写入实验记录。 */
public final class ModelRequestException extends IllegalStateException {
    private final Code code;
    private final Integer httpStatus;
    public ModelRequestException(Code code, Integer httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }
    public Code code() { return code; }
    public Integer httpStatus() { return httpStatus; }
}
