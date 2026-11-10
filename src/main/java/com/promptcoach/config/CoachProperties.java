package com.promptcoach.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全局配置项，全部支持环境变量覆盖，方便在 GitHub Actions / 服务器上部署。
 */
@ConfigurationProperties(prefix = "coach")
public class CoachProperties {

    /** 单次输入允许的最大字符数，防止超长文本打爆模型上下文 */
    private int maxInputChars = 4000;

    /** 内存中保留的历史记录条数 */
    private int historyLimit = 50;

    private final Llm llm = new Llm();

    public int getMaxInputChars() {
        return maxInputChars;
    }

    public void setMaxInputChars(int maxInputChars) {
        this.maxInputChars = maxInputChars;
    }

    public int getHistoryLimit() {
        return historyLimit;
    }

    public void setHistoryLimit(int historyLimit) {
        this.historyLimit = historyLimit;
    }

    public Llm getLlm() {
        return llm;
    }

    /**
     * OpenAI 兼容接口配置。DeepSeek、OpenAI、通义千问、Kimi 都支持这套协议，
     * 换服务商只需要改 base-url + model。
     */
    public static class Llm {

        /** 是否允许调用大模型，false 时永远走本地规则引擎 */
        private boolean enabled = true;

        /** 兼容接口地址，例如 https://api.deepseek.com/v1 */
        private String baseUrl = "https://api.deepseek.com/v1";

        /** API Key，为空则视为未配置，自动降级为本地模式 */
        private String apiKey = "";

        private String model = "deepseek-chat";

        private int timeoutSeconds = 40;

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

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        /** 配置齐全才认为可用 */
        public boolean isUsable() {
            return enabled && apiKey != null && !apiKey.isBlank()
                    && baseUrl != null && !baseUrl.isBlank()
                    && model != null && !model.isBlank();
        }
    }
}