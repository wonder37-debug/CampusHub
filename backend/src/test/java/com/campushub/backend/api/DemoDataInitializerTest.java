package com.campushub.backend.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campushub.backend.BackendApplication;
import com.campushub.backend.auth.repository.UserRepository;
import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * 验证 {@link DemoDataInitializer} 在 demo-data.enabled=true 时预置的 admin 账号初始余额为 100.00
 * （F15：预置账号余额一致，使演示账号可发布带悬赏需求）。
 *
 * <p>TEST001/TEST002 的余额由 {@code init_schema.sql}（MySQL/生产初始化脚本）保证，
 * 测试环境使用 {@code schema.sql} 系列建表脚本（不含预置账号 INSERT），故此处不覆盖。</p>
 */
@SpringBootTest(classes = BackendApplication.class, properties = {
    "app.demo-data.enabled=true",
    "app.auth.allowed-email-domains=nju.edu.cn,smail.nju.edu.cn",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration",
    "spring.datasource.url=jdbc:h2:mem:campushub_demo_init;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:schema.sql,classpath:schema-demand.sql,classpath:schema-response.sql,classpath:schema-order.sql,classpath:schema-review.sql,classpath:schema-notification.sql,classpath:schema-recommendation.sql",
    "spring.datasource.hikari.connection-timeout=3000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class DemoDataInitializerTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldSeedAdminWithInitialBalance100() {
        var admin = userRepository.findByStudentId("admin");
        assertTrue(admin.isPresent(), "DemoDataInitializer 应预置 admin 账号");
        BigDecimal balance = admin.get().getBalance();
        assertEquals(new BigDecimal("100.00"), balance, "admin 初始余额应为 100.00");
    }
}
