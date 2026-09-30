package com.example.werewolf.ai;

/** 兼容模型接口的最小抽象，便于本地测试替换真实 HTTP 调用。 */
public interface LlmClient {
    String chat(LlmConfig config, String prompt);

    /** 保持最小接口和测试替身兼容；不支持 usage 的接口返回未知用量。 */
    default ChatResult chatWithUsage(LlmConfig config, String prompt) {
        return new ChatResult(chat(config, prompt), null, null, null);
    }

    record ChatResult(String content, Long promptTokens, Long completionTokens, Long totalTokens, String error) {
        public ChatResult(String content, Long promptTokens, Long completionTokens, Long totalTokens) {
            this(content, promptTokens, completionTokens, totalTokens, null);
        }
    }
}
