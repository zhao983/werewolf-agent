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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import com.example.werewolf.agent.DecisionDiagnostic;
import com.example.werewolf.agent.DecisionDiagnostic.Code;

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
        return request(config, prompt, null);
    }

    @Override
    public ChatResult chatWithTools(LlmConfig config, String prompt, List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) throw new IllegalArgumentException("当前阶段没有可调用工具");
        return request(config, prompt, tools);
    }

    private ChatResult request(LlmConfig config, String prompt, List<Map<String, Object>> tools) {
        Integer receivedStatus = null;
        try {
            String url = config.baseUrl().replaceAll("/+$", "") + "/chat/completions";
            Map<String, Object> payload = new LinkedHashMap<>(Map.of(
                    "model", config.model(), "temperature", config.temperature(),
                    config.tokenLimitParameter().field(), config.maxTokens(), "messages", List.of(Map.of("role", "user", "content", prompt))));
            if (tools != null) {
                payload.put("tools", tools);
                payload.put("tool_choice", "required");
                payload.put("parallel_tool_calls", false);
            }
            String body = mapper.writeValueAsString(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                    .header("Content-Type", "application/json");
            if (config.apiKey() != null && !config.apiKey().isBlank())
                builder.header("Authorization", "Bearer " + config.apiKey());
            HttpResponse<String> response = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            receivedStatus = response.statusCode();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String hint = tools != null && (response.statusCode() == 400 || response.statusCode() == 422)
                        ? "；请确认服务支持 tools/tool_choice 及所选模式；可改用普通工具或 JSON 模式后新建对局" : "";
                // 不回显远端错误正文，避免密钥或私有提示被第三方服务带回页面。
                throw new ModelRequestException(Code.HTTP_ERROR, response.statusCode(), "Model API returned HTTP " + response.statusCode() + hint);
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode choice = root.path("choices").path(0);
            JsonNode content = choice.path("message").path("content");
            JsonNode usage = root.path("usage");
            List<ToolCall> calls = new ArrayList<>();
            if (tools != null) {
                JsonNode raw = choice.path("message").path("tool_calls");
                if (raw.isArray()) {
                    for (JsonNode call : raw) {
                        JsonNode function = call.path("function");
                        calls.add(new ToolCall(text(call.path("type")), text(function.path("name")), text(function.path("arguments"))));
                    }
                } else if (!raw.isMissingNode() && !raw.isNull()) {
                    calls.add(new ToolCall(null, null, null)); // 交给 Agent 记录非法回复并纠正。
                }
            }
            String problem = null;
            // 推理模型可能耗尽输出额度而没有生成最终消息，此时给出可操作的错误原因。
            if ("length".equals(choice.path("finish_reason").asText()))
                problem = "模型输出达到 Max Tokens 上限，请在 AI 设置中调高 Max Tokens";
            else if (tools == null && (!content.isTextual() || content.asText().isBlank()))
                problem = "模型接口未返回可读取的消息内容";
            // 工具回复允许 content=null；缺失工具调用属于非法回复，由 Agent 最多纠正一次。
            // 空回复同样可能已经消耗 token，因此把 usage 与安全错误一起交给统计层。
            return new ChatResult(content.isTextual() ? content.asText() : null, tokenCount(usage.path("prompt_tokens")),
                    tokenCount(usage.path("completion_tokens")), tokenCount(usage.path("total_tokens")), problem, calls,
                    new ResponseMetadata(response.statusCode(), DecisionDiagnostic.finish(text(choice.path("finish_reason")))));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ModelRequestException(Code.TIMEOUT, null, "模型请求超过 " + config.requestTimeoutSeconds() + " 秒，请检查服务或调整 AI 设置中的请求超时后新建对局");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelRequestException(Code.INTERRUPTED, null, "模型请求被中断");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ModelRequestException(Code.RESPONSE_ERROR, receivedStatus, "模型接口响应不是有效 JSON");
        } catch (java.io.IOException e) {
            throw new ModelRequestException(Code.NETWORK_ERROR, null, "模型连接失败，请检查服务地址与网络");
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new ModelRequestException(Code.CLIENT_ERROR, null, "模型请求未能完成，请检查连接设置");
        }
    }

    private String text(JsonNode node) { return node.isTextual() ? node.asText() : null; }

    /** 兼容接口可能没有 usage；缺失和非法数值不作为零消耗处理。 */
    private Long tokenCount(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0
                ? node.longValue() : null;
    }
}
