package com.campushub.backend.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Spring AI 配置入口。
 *
 * <p>关键设计：
 * <ul>
 *   <li>ChatClient 注入为可选依赖（{@code required = false}）。</li>
 *   <li>当 {@code spring.ai.openai.api-key} 未配置（占位符 {@code not-configured} 或空）时，
 *       {@link #chatClient} 返回 {@code null}，应用仍能启动。</li>
 *   <li>调用 AI 接口时由 {@code AiDemandApplicationServiceImpl} 检测到 ChatClient 为 null，
 *       返回"AI 服务尚未配置"的业务错误。</li>
 *   <li>通过 {@link RestClientCustomizer} 为 Spring AI OpenAI 使用的 {@link org.springframework.web.client.RestClient}
 *       设置 connect/read timeout（10s/60s），实际生效在底层 HTTP client。</li>
 * </ul>
 * 这样保证"未配置 AI_API_KEY 项目仍能正常启动"的安全要求。
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    static final String PLACEHOLDER_API_KEY = "not-configured";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    @Bean
    public ChatClient chatClient(
        @Autowired(required = false) ChatClient.Builder chatClientBuilder,
        @Value("${spring.ai.openai.api-key:}") String apiKey
    ) {
        if (!isRealApiKey(apiKey)) {
            log.warn("AI_API_KEY 未配置（占位符或空），ChatClient 跳过初始化。调用 AI 接口将返回业务错误。");
            return null;
        }
        if (chatClientBuilder == null) {
            log.warn("AI ChatClient.Builder 不可用，ChatClient 跳过初始化。");
            return null;
        }
        return chatClientBuilder.build();
    }

    /**
     * 为 Spring AI OpenAI ChatModel 使用的 RestClient 设置 HTTP 超时。
     *
     * <p>{@code spring.ai.openai.connect-timeout-ms} / {@code read-timeout-ms} 在 Spring AI 1.0
     * 不生效（{@code OpenAiConnectionProperties} 无对应字段），必须通过 RestClient.Builder 的
     * requestFactory 设置。本 bean 会被 Spring AI 自动配置注入的 RestClient.Builder 应用。
     */
    @Bean
    public RestClientCustomizer aiRestClientCustomizer() {
        return restClientBuilder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
            factory.setReadTimeout(READ_TIMEOUT_MS);
            restClientBuilder.requestFactory(factory);
        };
    }

    private boolean isRealApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        return !PLACEHOLDER_API_KEY.equals(apiKey);
    }
}
