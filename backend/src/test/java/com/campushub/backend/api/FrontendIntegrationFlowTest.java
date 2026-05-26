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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {
    "app.auth.allowed-email-domains=nju.edu.cn,smail.nju.edu.cn",
    "spring.datasource.url=jdbc:h2:mem:campushub_integration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:schema.sql",
    "spring.datasource.hikari.connection-timeout=3000"
})
@AutoConfigureMockMvc
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class FrontendIntegrationFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthApplicationService authApplicationService;

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
            .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.proofSubmitted").value(true))
            .andExpect(jsonPath("$.data.proofImageCount").value(2));

        updateOrder(publisher.token(), orderId, "COMPLETED", "confirm", null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        mockMvc.perform(post("/api/v1/orders/{orderId}/reviews", orderId)
                .header("Authorization", bearer(publisher.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("rating", 5, "comment", "Great help"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.orderId").value(orderId))
            .andExpect(jsonPath("$.data.targetId").value(accepter.userId()))
            .andExpect(jsonPath("$.data.rating").value(5));
    }

    @Test
    void shouldRejectUnauthenticatedProtectedApi() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(1001));
    }

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

    @Test
    void shouldRejectNonCampusEmailVerificationRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", "outsider@gmail.com", "studentId", "20261234"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(1002));
    }

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
        body.put("reward", BigDecimal.ZERO);
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

    private void approveDemand(String adminToken, Long demandId) throws Exception {
        mockMvc.perform(post("/api/v1/admin/demands/{demandId}/review", demandId)
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("action", "approve"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

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
