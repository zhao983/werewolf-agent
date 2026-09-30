package com.example.werewolf.ai;

/** 兼容模型接口的最小抽象，便于本地测试替换真实 HTTP 调用。 */
public interface LlmClient {
    String chat(LlmConfig config, String prompt);
}
