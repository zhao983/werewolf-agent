package com.example.werewolf.ai;

import java.util.List;
import java.util.Map;

/** 兼容模型接口的最小抽象，便于本地测试替换真实 HTTP 调用。 */
public interface LlmClient {
    String chat(LlmConfig config, String prompt);

    /** 保持最小接口和测试替身兼容；不支持 usage 的接口返回未知用量。 */
    default ChatResult chatWithUsage(LlmConfig config, String prompt) {
        return new ChatResult(chat(config, prompt), null, null, null);
    }

    /** 工具调用与普通文本分开返回，避免把工具名称当作可执行代码。 */
    default ChatResult chatWithTools(LlmConfig config, String prompt, List<Map<String, Object>> tools) {
        throw new IllegalStateException("当前模型客户端不支持工具调用，请选择 JSON 模式");
    }

    record ToolCall(String type, String name, String arguments) { }
    record ChatResult(String content, Long promptTokens, Long completionTokens, Long totalTokens, String error,
                      List<ToolCall> toolCalls, ResponseMetadata metadata) {
        public ChatResult {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
        public ChatResult(String content, Long promptTokens, Long completionTokens, Long totalTokens, String error,
                          List<ToolCall> toolCalls) {
            this(content, promptTokens, completionTokens, totalTokens, error, toolCalls, null);
        }
        public ChatResult(String content, Long promptTokens, Long completionTokens, Long totalTokens, String error) {
            this(content, promptTokens, completionTokens, totalTokens, error, List.of());
        }
        public ChatResult(String content, Long promptTokens, Long completionTokens, Long totalTokens) {
            this(content, promptTokens, completionTokens, totalTokens, null, List.of());
        }
    }
    /** 仅保留推理用量数字，不读取或保存服务端 reasoning_content。 */
    record ResponseMetadata(Integer httpStatus, com.example.werewolf.agent.DecisionDiagnostic.FinishReason finishReason,
                            Long reasoningTokens) {
        public ResponseMetadata(Integer httpStatus, com.example.werewolf.agent.DecisionDiagnostic.FinishReason finishReason) {
            this(httpStatus, finishReason, null);
        }
    }
}
