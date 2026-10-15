package com.campushub.backend.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 配置入口。
 *
 * <p>关键设计：
 * <ul>
 *   <li>ChatClient 注入为可选依赖（{@code required = false}）。</li>
 *   <li>当 {@code spring.ai.openai.api-key}、{@code spring.ai.openai.chat.options.model} 为空，
 *       或 {@code spring.ai.openai.base-url} 为占位符（{@code https://placeholder.invalid}）时，
 *       {@link #chatClient} 返回 {@code null}，应用仍能启动。</li>
 *   <li>调用 AI 接口时由 {@code AiDemandApplicationServiceImpl} 检测到 ChatClient 为 null，
 *       返回"AI 服务尚未配置"的业务错误，明确提示需要设置的环境变量。</li>
 *   <li><b>不强制替换 Spring Boot 的 HTTP client</b>：HTTP 超时通过
 *       {@code spring.http.client.connect-timeout} / {@code spring.http.client.read-timeout}
 *       （Spring Boot 3.5 HTTP client properties）配置，保留 Spring Boot 自动选择的 request factory，
 *       不再通过 {@code RestClientCustomizer} + {@code SimpleClientHttpRequestFactory} 强制覆盖。</li>
 * </ul>
 * 这样保证"未配置 AI_BASE_URL / AI_MODEL / AI_API_KEY 项目仍能正常启动"的安全要求。
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    /** base-url 占位符（RFC 2606 .invalid TLD），配置文件默认值；AiConfig 检测到后跳过 ChatClient 创建。 */
    static final String PLACEHOLDER_BASE_URL = "https://placeholder.invalid";

    /** api-key 占位符，配置文件默认值；AiConfig 检测到后跳过 ChatClient 创建。 */
    static final String PLACEHOLDER_API_KEY = "not-configured";

    @Bean
    public ChatClient chatClient(
        @Autowired(required = false) ChatClient.Builder chatClientBuilder,
        @Value("${spring.ai.openai.api-key:}") String apiKey,
        @Value("${spring.ai.openai.base-url:}") String baseUrl,
        @Value("${spring.ai.openai.chat.options.model:}") String model
    ) {
        if (!isConfigured(apiKey, baseUrl, model)) {
            log.warn("AI 配置不完整（AI_BASE_URL / AI_MODEL / AI_API_KEY 环境变量未全部设置），ChatClient 跳过初始化。调用 AI 接口将返回业务错误。");
            return null;
        }
        if (chatClientBuilder == null) {
            log.warn("AI ChatClient.Builder 不可用，ChatClient 跳过初始化。");
            return null;
        }
        return chatClientBuilder.build();
    }

    private boolean isConfigured(String apiKey, String baseUrl, String model) {
        return isRealApiKey(apiKey) && isRealBaseUrl(baseUrl) && isPresent(model);
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isRealApiKey(String apiKey) {
        return isPresent(apiKey) && !PLACEHOLDER_API_KEY.equals(apiKey);
    }

    private boolean isRealBaseUrl(String baseUrl) {
        return isPresent(baseUrl) && !PLACEHOLDER_BASE_URL.equals(baseUrl);
    }
}
