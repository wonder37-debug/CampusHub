package com.campushub.backend.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campushub.backend.auth.dto.EmailVerificationIssue;
import com.campushub.backend.auth.service.AuthApplicationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "app.auth.allowed-email-domains=nju.edu.cn,smail.nju.edu.cn")
@AutoConfigureMockMvc
class FrontendIntegrationFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthApplicationService authApplicationService;

    // 正常主流程：模拟前端完整调用链，从注册登录到发布需求、管理员审核、接单、完成订单和评价。
    @Test
    void shouldCompleteHappyPathAcrossFrontendApis() throws Exception {
        TestUser publisher = registerAndLogin("publisher-flow");
        TestUser accepter = registerAndLogin("accepter-flow");
        String adminToken = login("admin", "Admin1234").token();

        mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", bearer(publisher.token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.studentId").value(publisher.studentId()));

        Long demandId = publishDemand(publisher.token());

        mockMvc.perform(get("/api/v1/admin/demands/pending")
                .header("Authorization", bearer(adminToken))
                .param("q", "Pickup")
                .param("page", "1")
                .param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(post("/api/v1/admin/demands/{demandId}/review", demandId)
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("action", "approve", "reason", "ready for marketplace"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PENDING"));

        mockMvc.perform(get("/api/v1/demands")
                .header("Authorization", bearer(accepter.token()))
                .param("q", "Pickup")
                .param("sort", "TIME")
                .param("page", "1")
                .param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].id").value(demandId));

        mockMvc.perform(get("/api/v1/demands/{demandId}", demandId)
                .header("Authorization", bearer(accepter.token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PENDING"));

        Long orderId = acceptDemand(accepter.token(), demandId);

        mockMvc.perform(get("/api/v1/orders")
                .header("Authorization", bearer(accepter.token()))
                .param("page", "1")
                .param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].orderId").value(orderId));

        mockMvc.perform(get("/api/v1/orders/{orderId}", orderId)
                .header("Authorization", bearer(publisher.token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ACCEPTED"));

        updateOrder(accepter.token(), orderId, "IN_PROGRESS", "started", null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));

        updateOrder(accepter.token(), orderId, "COMPLETED", "delivered", 2)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("COMPLETED"))
            .andExpect(jsonPath("$.data.proofSubmitted").value(true))
            .andExpect(jsonPath("$.data.proofImageCount").value(2));

        mockMvc.perform(post("/api/v1/orders/{orderId}/reviews", orderId)
                .header("Authorization", bearer(publisher.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("rating", 5, "comment", "Great help"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.orderId").value(orderId))
            .andExpect(jsonPath("$.data.targetId").value(accepter.userId()))
            .andExpect(jsonPath("$.data.rating").value(5));
    }

    // 异常流程：未携带 Bearer token 访问订单列表，应被鉴权层拒绝。
    @Test
    void shouldRejectUnauthenticatedProtectedApi() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(1001));
    }

    // 异常流程：一个需求被接单后，其他用户再次接同一单，应触发业务冲突。
    @Test
    void shouldRejectDuplicateAcceptingDemand() throws Exception {
        TestUser publisher = registerAndLogin("publisher-duplicate");
        TestUser firstAccepter = registerAndLogin("first-accepter-duplicate");
        TestUser secondAccepter = registerAndLogin("second-accepter-duplicate");
        String adminToken = login("admin", "Admin1234").token();

        Long demandId = publishDemand(publisher.token());
        approveDemand(adminToken, demandId);

        acceptDemand(firstAccepter.token(), demandId);

        mockMvc.perform(post("/api/v1/demands/{demandId}/accept", demandId)
                .header("Authorization", bearer(secondAccepter.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("note", "I also want this order"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(1005));
    }

    // 异常流程：注册验证码接口只允许南大邮箱域名，非校园邮箱应被参数校验拒绝。
    @Test
    void shouldRejectNonCampusEmailVerificationRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", "outsider@gmail.com", "studentId", "20261234"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(1002));
    }

    // 异常流程：发布需求时传入非法参数，验证后端不会创建不合法需求。
    @Test
    void shouldRejectPublishingDemandWithInvalidParameters() throws Exception {
        TestUser publisher = registerAndLogin("publisher-invalid-demand");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("title", "No");
        body.put("description", "Invalid because title is too short and reward is negative");
        body.put("category", "EXPRESS");
        body.put("campusZone", "XIANLIN");
        body.put("location", "Package station");
        body.put("reward", new BigDecimal("-1.00"));
        body.put("tags", List.of("express"));
        body.put("anonymous", false);

        mockMvc.perform(post("/api/v1/demands")
                .header("Authorization", bearer(publisher.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(1002));
    }

    // 异常流程：需求发布者不能接自己的需求，验证接单权限控制。
    @Test
    void shouldRejectPublisherAcceptingOwnDemand() throws Exception {
        TestUser publisher = registerAndLogin("publisher-own-accept");
        String adminToken = login("admin", "Admin1234").token();
        Long demandId = publishDemand(publisher.token());
        approveDemand(adminToken, demandId);

        mockMvc.perform(post("/api/v1/demands/{demandId}/accept", demandId)
                .header("Authorization", bearer(publisher.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("note", "I should not accept my own demand"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(1004));
    }

    // 异常流程：订单详情只能由订单参与者或管理员查看，第三方用户应被拒绝。
    @Test
    void shouldRejectOutsiderViewingOrderDetail() throws Exception {
        TestUser publisher = registerAndLogin("publisher-outsider");
        TestUser accepter = registerAndLogin("accepter-outsider");
        TestUser outsider = registerAndLogin("outsider-order");
        String adminToken = login("admin", "Admin1234").token();

        Long demandId = publishDemand(publisher.token());
        approveDemand(adminToken, demandId);
        Long orderId = acceptDemand(accepter.token(), demandId);

        mockMvc.perform(get("/api/v1/orders/{orderId}", orderId)
                .header("Authorization", bearer(outsider.token())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(1004));
    }

    // 测试辅助方法：创建一个符合南大邮箱白名单的用户，完成验证码注册并登录，返回用户 token。
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
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.studentId").value(studentId));

        LoginSession session = login(studentId, "Password123");
        return new TestUser(session.userId(), studentId, session.token());
    }

    // 测试辅助方法：通过真实登录接口换取 token，后续请求统一使用 Bearer token 调用。
    private LoginSession login(String loginId, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("loginId", loginId, "password", password))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andReturn();
        JsonNode response = readJson(result);
        return new LoginSession(
            response.at("/data/token").asText(),
            response.at("/data/user/id").asLong()
        );
    }

    // 测试辅助方法：发布一条待审核需求，覆盖前端发布需求接口的请求体结构。
    private Long publishDemand(String token) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("title", "Pickup express package");
        body.put("description", "Please pick up a small package from the station");
        body.put("note", "Call me after pickup");
        body.put("category", "EXPRESS");
        body.put("campusZone", "XIANLIN");
        body.put("location", "Package station");
        body.put("startTime", LocalDateTime.now().plusHours(1).toString());
        body.put("endTime", LocalDateTime.now().plusHours(3).toString());
        body.put("reward", new BigDecimal("6.50"));
        body.put("tags", List.of("express", "pickup"));
        body.put("anonymous", false);

        MvcResult result = mockMvc.perform(post("/api/v1/demands")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("REVIEWING"))
            .andReturn();
        return readJson(result).at("/data/id").asLong();
    }

    // 测试辅助方法：调用接单接口，返回生成的订单 id。
    private Long acceptDemand(String token, Long demandId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/demands/{demandId}/accept", demandId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("note", "I can handle it"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
            .andReturn();
        return readJson(result).at("/data/orderId").asLong();
    }

    // 测试辅助方法：管理员审核通过需求，使需求从 REVIEWING 进入可接单的 PENDING 状态。
    private void approveDemand(String adminToken, Long demandId) throws Exception {
        mockMvc.perform(post("/api/v1/admin/demands/{demandId}/review", demandId)
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("action", "approve"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    // 测试辅助方法：更新订单状态，用于模拟开始执行、完成订单等前端操作。
    private ResultActions updateOrder(
        String token,
        Long orderId,
        String targetStatus,
        String note,
        Integer proofImageCount
    ) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("targetStatus", targetStatus);
        body.put("note", note);
        if (proofImageCount != null) {
            body.put("proofImageCount", proofImageCount);
        }
        return mockMvc.perform(put("/api/v1/orders/{orderId}", orderId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(body)));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return readJson(result.getResponse().getContentAsString());
    }

    private JsonNode readJson(String content) throws Exception {
        return objectMapper.readTree(content);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record TestUser(Long userId, String studentId, String token) {
    }

    private record LoginSession(String token, Long userId) {
    }
}
