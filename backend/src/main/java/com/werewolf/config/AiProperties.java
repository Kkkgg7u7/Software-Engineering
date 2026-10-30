package com.werewolf.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI NPC 配置（werewolf.ai）。只依赖 OpenAI 兼容的 /chat/completions 协议，
 * 因此 DeepSeek / 通义千问 / 本地 Ollama 都能直接换 base-url 使用。
 */
@ConfigurationProperties(prefix = "werewolf.ai")
public class AiProperties {

    /** 总开关；false 时全部使用规则 AI（无外部依赖，答辩现场断网也能玩）。 */
    private boolean enabled = true;

    private String baseUrl = "https://api.deepseek.com";

    private String path = "/chat/completions";

    /** 留空则直接走规则 AI，不会发起外部请求。 */
    private String apiKey = "";

    private String model = "deepseek-chat";

    private double temperature = 0.9;

    private int maxTokens = 400;

    private int timeoutSeconds = 12;

    /** 单局调用上限，防止费用失控。 */
    private int maxCallsPerGame = 120;

    private long minThinkMillis = 900;

    private long maxThinkMillis = 2600;

    /** 是否具备真实调用能力。 */
    public boolean isLlmUsable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public String endpoint() {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String p = path.startsWith("/") ? path : "/" + path;
        return base + p;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxCallsPerGame() {
        return maxCallsPerGame;
    }

    public void setMaxCallsPerGame(int maxCallsPerGame) {
        this.maxCallsPerGame = maxCallsPerGame;
    }

    public long getMinThinkMillis() {
        return minThinkMillis;
    }

    public void setMinThinkMillis(long minThinkMillis) {
        this.minThinkMillis = minThinkMillis;
    }

    public long getMaxThinkMillis() {
        return maxThinkMillis;
    }

    public void setMaxThinkMillis(long maxThinkMillis) {
        this.maxThinkMillis = maxThinkMillis;
    }
}
