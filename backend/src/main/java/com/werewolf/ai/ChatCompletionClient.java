package com.werewolf.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.werewolf.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的 /chat/completions 客户端。
 *
 * <p>只依赖 HTTP，因此可以无缝切换：
 * <ul>
 *   <li>DeepSeek：{@code https://api.deepseek.com} + {@code deepseek-chat}</li>
 *   <li>OpenAI：{@code https://api.openai.com/v1} + {@code gpt-4o-mini}</li>
 *   <li>本地 Ollama：{@code http://localhost:11434/v1} + {@code qwen2.5:7b}</li>
 * </ul>
 */
@Component
public class ChatCompletionClient {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionClient.class);

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public ChatCompletionClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
        var timeout=Duration.ofSeconds(Math.max(1,Math.min(60,properties.getTimeoutSeconds())));
        factory.setConnectTimeout(timeout);factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(factory)
                .baseUrl(properties.getBaseUrl())
                .build();
    }

    /**
     * 发起一次对话补全。
     *
     * @return 模型返回的文本；任何异常都会向上抛出，由 {@link LlmAgent} 兜底
     */
    public String complete(String systemPrompt, String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("temperature", properties.getTemperature());
        body.put("max_tokens", properties.getMaxTokens());
        body.put("stream", false);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        // 部分服务（含 DeepSeek）支持 JSON 模式，可显著降低解析失败率
        body.put("response_format", Map.of("type", "json_object"));

        String response = restClient.post()
                .uri(properties.endpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + properties.getApiKey())
                .body(body)
                .retrieve()
                .body(String.class);

        return extractContent(response);
    }

    private String extractContent(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).path("message");
                JsonNode content = message.path("content");
                if (content.isTextual()) {
                    return content.asText();
                }
                // 少数实现（如 Ollama 的部分模型）会返回 reasoning_content
                JsonNode reasoning = message.path("reasoning_content");
                if (reasoning.isTextual()) {
                    return reasoning.asText();
                }
            }
            log.warn("LLM 响应结构异常（内容未记录）");
            return null;
        } catch (Exception ex) {
            log.warn("LLM 响应解析失败: {}", ex.getClass().getSimpleName());
            return null;
        }
    }

    /** 仅供测试/健康检查使用。 */
    public Duration timeout() {
        return Duration.ofSeconds(properties.getTimeoutSeconds());
    }
}
