package com.campushub.backend.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campushub.backend.BackendApplication;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.dto.EmailVerificationIssue;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.auth.service.AuthApplicationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP 集成层安全边界测试：覆盖权限/IDOR、状态机越级、重复幂等在 Controller→Service 链路上的表现。
 */
@SpringBootTest(classes = BackendApplication.class, properties = {
    "app.demo-data.enabled=false",
    "app.auth.allowed-email-domains=nju.edu.cn,smail.nju.edu.cn",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration",
    "spring.datasource.url=jdbc:h2:mem:campushub_security;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:schema.sql,classpath:schema-demand.sql,classpath:schema-response.sql,classpath:schema-order.sql,classpath:schema-review.sql,classpath:schema-notification.sql,classpath:schema-recommendation.sql,classpath:schema-asset.sql",
    "spring.datasource.hikari.connection-timeout=3000",
    "app.upload.dir=target/test-uploads-security"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class SecurityBoundaryIntegrationTest {

    private static final BCryptPasswordEncoder TEST_PASSWORD_ENCODER = new BCryptPasswordEncoder(4);

    private static final byte[] VALID_PNG = new byte[] {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54,
        0x78, (byte) 0x9C, 0x62, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4,
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthApplicationService authApplicationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.campushub.backend.upload.repository.UploadedAssetRepository uploadedAssetRepository;

    @Autowired
    private com.campushub.backend.order.repository.OrderRepository orderRepository;

    @AfterAll
    static void cleanUploadDir() throws Exception {
        Path dir = Path.of("target/test-uploads-security");
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) { }
                    });
            }
        }
    }

    @BeforeEach
    void seedAdmin() {
        if (userRepository.findByStudentId("secadmin").isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            userRepository.save(new User(
                null, "secadmin@campushub.local", "secadmin",
                TEST_PASSWORD_ENCODER.encode("Admin1234"),
                "secadmin", null,
                UserRole.ADMIN, UserStatus.ACTIVE,
                100, BigDecimal.ZERO, BigDecimal.ZERO,
                now, now
            ));
        }
    }

    @Test
    void guestAccessProtectedEndpointsReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(1001));
        mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminCannotAccessAdminEndpoints() throws Exception {
        TestUser user = registerAndLogin("sec-user");
        mockMvc.perform(get("/api/v1/admin/users")
                .header("Authorization", bearer(user.token())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(1004));
        mockMvc.perform(get("/api/v1/admin/dashboard")
                .header("Authorization", bearer(user.token())))
            .andExpect(status().isForbidden());
    }

    @Test
    void nonParticipantCannotCompleteOrder() throws Exception {
        TestUser publisher = registerAndLogin("sec-pub");
        TestUser accepter = registerAndLogin("sec-acc");
        TestUser outsider = registerAndLogin("sec-out");
        String adminToken = login("secadmin", "Admin1234");

        Long demandId = publishDemand(publisher.token(), "Secure delivery");
        approveDemand(adminToken, demandId);
        Long orderId = acceptDemand(accepter.token(), demandId);

        // outsider tries to complete → 403
        mockMvc.perform(put("/api/v1/orders/{orderId}", orderId)
                .header("Authorization", bearer(outsider.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("targetStatus", "IN_PROGRESS"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(1004));
    }

    @Test
    void completedOrderCannotBeCancelledOrRecompleted() throws Exception {
        TestUser publisher = registerAndLogin("sec-pub2");
        TestUser accepter = registerAndLogin("sec-acc2");
        String adminToken = login("secadmin", "Admin1234");

        Long demandId = publishDemand(publisher.token(), "Secure delivery two");
        approveDemand(adminToken, demandId);
        Long orderId = acceptDemand(accepter.token(), demandId);

        // provider starts + provider confirms completion
        updateOrder(accepter.token(), orderId, "IN_PROGRESS", "started", null)
            .andExpect(status().isOk());
        Long accepterUserId = userRepository.findByStudentId(accepter.studentId()).orElseThrow().getId();
        uploadedAssetRepository.insert(new com.campushub.backend.upload.repository.entity.UploadedAssetEntity("test.jpg", "/api/v1/uploads/2026/10/test.jpg", accepterUserId, true, null));
        updateOrder(accepter.token(), orderId, "COMPLETED", "done", java.util.List.of("/api/v1/uploads/2026/10/test.jpg"))
            .andExpect(status().isOk());
        // requester confirms completion → COMPLETED
        updateOrder(publisher.token(), orderId, "COMPLETED", "confirmed", null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        // COMPLETED -> CANCEL rejected (only accepted can be cancelled)
        updateOrder(publisher.token(), orderId, "CANCELLED", "late cancel", null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(1005));

        // COMPLETED -> COMPLETED rejected (duplicate completion)
        updateOrder(accepter.token(), orderId, "COMPLETED", "again", java.util.List.of("/api/v1/uploads/2026/10/test.jpg"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(1005));
    }

    @Test
    void expiredDemandCannotBeAccepted() throws Exception {
        TestUser publisher = registerAndLogin("sec-pub3");
        TestUser accepter = registerAndLogin("sec-acc3");
        String adminToken = login("secadmin", "Admin1234");

        // publish demand whose endTime is already in the past
        Long demandId = publishDemandWithEndTime(publisher.token(), LocalDateTime.now().minusMinutes(10));
        approveDemand(adminToken, demandId);

        // accepter attempts to accept expired demand → 409
        mockMvc.perform(post("/api/v1/demands/{demandId}/accept", demandId)
                .header("Authorization", bearer(accepter.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("note", "try expired"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(1005));
    }

    @Test
    void nonOwnerCannotUpdateOthersDemand() throws Exception {
        // IDOR 回归：用户 B 尝试修改用户 A 的 demand → 403（PERMISSION_DENIED）
        TestUser owner = registerAndLogin("idor-owner");
        TestUser intruder = registerAndLogin("idor-intruder");
        String adminToken = login("secadmin", "Admin1234");

        Long demandId = publishDemand(owner.token(), "IDOR target");
        approveDemand(adminToken, demandId);

        mockMvc.perform(put("/api/v1/demands/{demandId}", demandId)
                .header("Authorization", bearer(intruder.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "hijacked"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(1004));
    }

    @Test
    void proofImageAccessControlAllowsParticipantsAndAdminRejectsOutsiders() throws Exception {
        TestUser publisher = registerAndLogin("proof-pub");
        TestUser accepter = registerAndLogin("proof-acc");
        TestUser outsider = registerAndLogin("proof-out");
        String adminToken = login("secadmin", "Admin1234");

        Long demandId = publishDemand(publisher.token(), "Proof access test");
        approveDemand(adminToken, demandId);
        Long orderId = acceptDemand(accepter.token(), demandId);

        // ACCEPTED -> IN_PROGRESS
        updateOrder(accepter.token(), orderId, "IN_PROGRESS", "started", null)
            .andExpect(status().isOk());

        // 手动创建上传文件 + asset 记录（模拟 purpose=proof 上传，is_private=true）
        Long accepterUserId = userRepository.findByStudentId(accepter.studentId()).orElseThrow().getId();
        Path uploadDir = Path.of("target/test-uploads-security/2026/10");
        Files.createDirectories(uploadDir);
        String filename = "proof-sec-" + System.nanoTime() + ".png";
        Path filePath = uploadDir.resolve(filename);
        Files.write(filePath, VALID_PNG);
        String proofUrl = "/api/v1/uploads/2026/10/" + filename;
        uploadedAssetRepository.insert(new com.campushub.backend.upload.repository.entity.UploadedAssetEntity(
            filename, proofUrl, accepterUserId, true, null));

        // 提交完成凭证（会调用 markAsPrivateAndBindOrder 绑定 orderId）
        updateOrder(accepter.token(), orderId, "COMPLETED", "done", java.util.List.of(proofUrl))
            .andExpect(status().isOk());

        // 验证 asset 已绑定订单
        var asset = uploadedAssetRepository.findByUrlPath(proofUrl);
        org.junit.jupiter.api.Assertions.assertTrue(asset != null);
        org.junit.jupiter.api.Assertions.assertTrue(asset.getIsPrivate());
        org.junit.jupiter.api.Assertions.assertEquals(orderId, asset.getBoundOrderId());

        // 接单者可以访问（header token）
        mockMvc.perform(get(proofUrl).header("Authorization", bearer(accepter.token())))
            .andExpect(status().isOk());

        // 发布者可以访问
        mockMvc.perform(get(proofUrl).header("Authorization", bearer(publisher.token())))
            .andExpect(status().isOk());

        // 管理员可以访问
        mockMvc.perform(get(proofUrl).header("Authorization", bearer(adminToken)))
            .andExpect(status().isOk());

        // query parameter token 不再支持（JWT 不应出现在 URL 中）→ 401
        mockMvc.perform(get(proofUrl + "?token=" + accepter.token()))
            .andExpect(status().isUnauthorized());

        // 无关用户不能访问 → 403
        mockMvc.perform(get(proofUrl).header("Authorization", bearer(outsider.token())))
            .andExpect(status().isForbidden());

        // 未登录用户不能访问 → 401
        mockMvc.perform(get(proofUrl))
            .andExpect(status().isUnauthorized());

        // 直接访问旧公开路径（无 token）无法绕过权限 → 401
        mockMvc.perform(get(proofUrl))
            .andExpect(status().isUnauthorized());

        // 清理文件
        Files.deleteIfExists(filePath);
    }

    @Test
    void publicDemandImageRemainsAccessibleWithoutAuth() throws Exception {
        // 公开需求图片不受凭证权限控制影响，仍可匿名访问
        TestUser user = registerAndLogin("img-pub");
        Path uploadDir = Path.of("target/test-uploads-security/2026/10");
        Files.createDirectories(uploadDir);
        String filename = "public-sec-" + System.nanoTime() + ".png";
        Path filePath = uploadDir.resolve(filename);
        Files.write(filePath, VALID_PNG);
        String publicUrl = "/api/v1/uploads/2026/10/" + filename;
        Long userId = userRepository.findByStudentId(user.studentId()).orElseThrow().getId();
        uploadedAssetRepository.insert(new com.campushub.backend.upload.repository.entity.UploadedAssetEntity(
            filename, publicUrl, userId, false, null));

        // 公开图片匿名访问 → 200
        mockMvc.perform(get(publicUrl))
            .andExpect(status().isOk());

        Files.deleteIfExists(filePath);
    }

    // ---- helpers ----

    private record TestUser(String studentId, String token) {
    }

    private TestUser registerAndLogin(String prefix) throws Exception {
        String suffix = Long.toString(System.nanoTime());
        String email = prefix + "-" + suffix + "@smail.nju.edu.cn";
        String studentId = "S" + suffix.substring(Math.max(0, suffix.length() - 12));
        EmailVerificationIssue issue = authApplicationService.sendRegistrationCode(email, studentId);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                    "email", email,
                    "verificationCode", issue.verificationCode(),
                    "studentId", studentId,
                    "password", "Password123",
                    "nickname", prefix
                ))))
            .andExpect(status().isOk());

        String token = login(studentId, "Password123");
        return new TestUser(studentId, token);
    }

    private String login(String loginId, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("loginId", loginId, "password", password))))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/token").asText();
    }

    private Long publishDemand(String token, String title) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("description", "desc");
        body.put("category", "EXPRESS");
        body.put("campusZone", "XIANLIN");
        body.put("location", "station");
        body.put("startTime", LocalDateTime.now().plusHours(1).toString());
        body.put("endTime", LocalDateTime.now().plusHours(3).toString());
        body.put("reward", BigDecimal.ZERO);
        body.put("tags", java.util.List.of("tag"));
        body.put("anonymous", false);
        return postDemand(token, body);
    }

    private Long publishDemandWithEndTime(String token, LocalDateTime endTime) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Expired demand");
        body.put("description", "desc");
        body.put("category", "EXPRESS");
        body.put("campusZone", "XIANLIN");
        body.put("location", "station");
        // 不传 startTime（null），避免过去开始时间校验；endTime 为过去时间模拟过期需求
        body.put("endTime", endTime.toString());
        body.put("reward", BigDecimal.ZERO);
        body.put("tags", java.util.List.of("tag"));
        body.put("anonymous", false);
        return postDemand(token, body);
    }

    private Long postDemand(String token, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/demands")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        // admin approve will look up by id; return demand id
        return node.at("/data/id").asLong();
    }

    private void approveDemand(String adminToken, Long demandId) throws Exception {
        mockMvc.perform(post("/api/v1/admin/demands/{demandId}/review", demandId)
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("action", "approve", "reason", "ok"))))
            .andExpect(status().isOk());
    }

    private Long acceptDemand(String token, Long demandId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/demands/{demandId}/accept", demandId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("note", "I can handle it"))))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/orderId").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions updateOrder(
        String token, Long orderId, String targetStatus, String note, java.util.List<String> proofImageUrls
    ) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetStatus", targetStatus);
        body.put("note", note);
        if (proofImageUrls != null) {
            body.put("proofImageUrls", proofImageUrls);
        }
        return mockMvc.perform(put("/api/v1/orders/{orderId}", orderId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(body)));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
