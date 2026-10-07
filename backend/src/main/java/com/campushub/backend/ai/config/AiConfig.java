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
     * <p><b>注意：这是 application-wide 配置，不是"仅 AI 生效"。</b>
     * Spring AI 1.0 的 OpenAiChatAutoConfiguration 使用自动配置的 {@code RestClient.Builder}
     * （通过 {@code ObjectProvider<RestClient.Builder>} 注入），而 Spring Boot 的
     * {@code RestClientAutoConfiguration} 会把所有 {@link RestClientCustomizer} bean 应用到
     * 该 Builder。因此本 bean 会影响项目中所有使用 RestClient 的地方。
     *
     * <p>当前 CampusHub 除 Spring AI 外没有其他 RestClient 用法（已确认 backend/src 下无其他
     * RestClient 引用），所以 10s connect / 60s read 的 timeout 应用到全局是安全的。
     * 若未来引入其他第三方 HTTP 调用且需要不同 timeout，应改为：
     * <ul>
     *   <li>自定义 OpenAiApi / OpenAiChatModel 并传入独立 RestClient.Builder；或</li>
     *   <li>用 {@code @Qualifier} 区分多个 RestClient.Builder。</li>
     * </ul>
     * 当前不引入这些复杂度，因为 Spring AI 1.0 只能通过 application-wide RestClientCustomizer
     * 可靠注入 timeout（{@code OpenAiConnectionProperties} 在 1.0 无 connectTimeout/readTimeout 字段）。
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
