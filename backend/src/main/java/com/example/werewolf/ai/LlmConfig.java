package com.example.werewolf.ai;

/** 单次模型连接参数；API Key 不进入 GameView 或事件日志。 */
public record LlmConfig(String baseUrl, String apiKey, String model, double temperature, int maxTokens) {
    public LlmConfig {
        if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank())
            throw new IllegalArgumentException("Base URL and model are required");
        if (Double.isNaN(temperature) || temperature < 0 || temperature > 2 || maxTokens < 1 || maxTokens > 8192)
            throw new IllegalArgumentException("Invalid model parameters");
        java.net.URI uri = java.net.URI.create(baseUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Base URL must be an HTTP(S) service root");
        // 带密钥的远程 HTTP 会明文传输 Authorization；本机模型服务仍允许使用 HTTP。
        String host = uri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
        if ("http".equals(uri.getScheme()) && apiKey != null && !apiKey.isBlank() && !loopback)
            throw new IllegalArgumentException("带 API Key 的远程模型服务必须使用 HTTPS");
    }
}
