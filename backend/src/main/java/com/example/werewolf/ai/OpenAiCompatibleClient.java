package com.example.werewolf.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 由后端发送 Chat Completions 请求，避免浏览器直连模型服务泄露密钥。 */
public final class OpenAiCompatibleClient implements LlmClient {
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public OpenAiCompatibleClient(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public String chat(LlmConfig config, String prompt) {
        ChatResult result = chatWithUsage(config, prompt);
        if (result.error() != null) throw new IllegalStateException(result.error());
        return result.content();
    }

    @Override
    public ChatResult chatWithUsage(LlmConfig config, String prompt) {
        try {
            String url = config.baseUrl().replaceAll("/+$", "") + "/chat/completions";
            String body = mapper.writeValueAsString(Map.of(
                    "model", config.model(), "temperature", config.temperature(),
                    "max_tokens", config.maxTokens(), "messages", List.of(Map.of("role", "user", "content", prompt))));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json");
            if (config.apiKey() != null && !config.apiKey().isBlank())
                builder.header("Authorization", "Bearer " + config.apiKey());
            HttpResponse<String> response = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("Model API returned HTTP " + response.statusCode());
            JsonNode root = mapper.readTree(response.body());
            JsonNode choice = root.path("choices").path(0);
            JsonNode content = choice.path("message").path("content");
            JsonNode usage = root.path("usage");
            String problem = null;
            // 推理模型可能耗尽输出额度而没有生成最终消息，此时给出可操作的错误原因。
            if (!content.isTextual() || content.asText().isBlank()) {
                if ("length".equals(choice.path("finish_reason").asText()))
                    problem = "模型输出达到 Max Tokens 上限，请在 AI 设置中调高 Max Tokens";
                else problem = "模型接口未返回可读取的消息内容";
            }
            // 空回复同样可能已经消耗 token，因此把 usage 与安全错误一起交给统计层。
            return new ChatResult(content.isTextual() ? content.asText() : null, tokenCount(usage.path("prompt_tokens")),
                    tokenCount(usage.path("completion_tokens")), tokenCount(usage.path("total_tokens")), problem);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Model request interrupted", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Model request failed: " + e.getClass().getSimpleName(), e);
        }
    }

    /** 兼容接口可能没有 usage；缺失和非法数值不作为零消耗处理。 */
    private Long tokenCount(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0
                ? node.longValue() : null;
    }
}
