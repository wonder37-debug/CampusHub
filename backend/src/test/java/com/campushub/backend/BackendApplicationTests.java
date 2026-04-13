package com.campushub.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// 告诉 Spring Boot 在跑测试的时候，忽略数据库连接的自动配置
@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
class BackendApplicationTests {

    @Test
    void contextLoads() {
    }

}
