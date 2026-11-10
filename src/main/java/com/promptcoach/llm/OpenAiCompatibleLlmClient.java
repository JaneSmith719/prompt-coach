package com.promptcoach.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptcoach.config.CoachProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 调用任意 OpenAI 兼容协议的对话接口。
 *
 * <p>只依赖 JDK 自带的 HttpClient 和 Jackson，不引第三方 HTTP 客户端，
 * 这样换服务商（DeepSeek / OpenAI / 通义 / Kimi）只改配置：
 * base-url 指向 {@code /v1}，model 换成对方的模型名即可。</p>
 */
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final CoachProperties.Llm config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleLlmClient(CoachProperties properties, ObjectMapper objectMapper) {
        this.config = properties.getLlm();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public boolean isAvailable() {
        return config.isUsable();
    }

    @Override
    public String model() {
        return config.getModel();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (!isAvailable()) {
            throw new LlmException("未配置 AI_API_KEY，无法调用大模型");
        }

        Map<String, Object> payload = Map.of(
                "model", config.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                "temperature", 0.6,
                "stream", false);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(endpoint()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + config.getApiKey().trim())
                    .timeout(Duration.ofSeconds(Math.max(5, config.getTimeoutSeconds())))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
        } catch (IOException e) {
            throw new LlmException("请求体序列化失败", e);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("调用被中断", e);
        } catch (IOException e) {
            throw new LlmException("网络异常：" + e.getMessage(), e);
        }

        if (response.statusCode() / 100 != 2) {
            log.warn("模型接口返回 HTTP {}：{}", response.statusCode(), abbreviate(response.body()));
            throw new LlmException("模型接口返回 HTTP " + response.statusCode());
        }

        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new LlmException("模型返回内容为空");
            }
            return stripCodeFence(content.asText().trim());
        } catch (IOException e) {
            throw new LlmException("响应解析失败", e);
        }
    }

    /** 兼容用户把完整地址填进来的情况，避免拼成 .../chat/completions/chat/completions */
    private String endpoint() {
        String base = config.getBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
    }

    /** 模型偶尔会把答案包在 ``` 里，去掉更省事 */
    private String stripCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        String withoutFirstLine = text.replaceFirst("^```[a-zA-Z]*\\R?", "");
        int lastFence = withoutFirstLine.lastIndexOf("```");
        return lastFence >= 0 ? withoutFirstLine.substring(0, lastFence).trim() : withoutFirstLine.trim();
    }

    private String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "…";
    }
}