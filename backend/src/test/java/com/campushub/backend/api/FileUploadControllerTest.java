package com.campushub.backend.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campushub.backend.BackendApplication;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.auth.service.AuthApplicationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * 文件上传安全攻击型测试：覆盖认证、content-aware 校验、空文件、超限、路径穿越等边界。
 */
@SpringBootTest(classes = BackendApplication.class, properties = {
    "app.demo-data.enabled=false",
    "app.auth.allowed-email-domains=nju.edu.cn,smail.nju.edu.cn",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration",
    "spring.datasource.url=jdbc:h2:mem:campushub_upload;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:schema.sql,classpath:schema-demand.sql,classpath:schema-response.sql,classpath:schema-order.sql,classpath:schema-review.sql,classpath:schema-notification.sql,classpath:schema-recommendation.sql,classpath:schema-asset.sql",
    "spring.datasource.hikari.connection-timeout=3000",
    "app.upload.dir=target/test-uploads-img",
    "app.upload.max-file-size-bytes=2048"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class FileUploadControllerTest {

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

    private static final byte[] VALID_JPG = new byte[] {
        (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01
    };

    private static final byte[] VALID_WEBP = new byte[] {
        'R', 'I', 'F', 'F', 0x00, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P', 0x00, 0x00
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthApplicationService authApplicationService;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private com.campushub.backend.upload.repository.UploadedAssetRepository uploadedAssetRepository;

    @BeforeEach
    void seedUsers() {
        LocalDateTime now = LocalDateTime.now();
        if (userRepository.findByStudentId("upload-user").isEmpty()) {
            userRepository.save(new User(
                null, "upload-user@smail.nju.edu.cn", "upload-user",
                TEST_PASSWORD_ENCODER.encode("Password123"),
                "upload-user", null,
                UserRole.USER, UserStatus.ACTIVE,
                100, new BigDecimal("100.00"), BigDecimal.ZERO,
                now, now
            ));
        }
        if (userRepository.findByStudentId("upload-admin").isEmpty()) {
            userRepository.save(new User(
                null, "upload-admin@campushub.local", "upload-admin",
                TEST_PASSWORD_ENCODER.encode("Admin1234"),
                "upload-admin", null,
                UserRole.ADMIN, UserStatus.ACTIVE,
                100, BigDecimal.ZERO, BigDecimal.ZERO,
                now, now
            ));
        }
    }

    @AfterAll
    static void cleanUploadDir() throws Exception {
        Path dir = Path.of("target/test-uploads-img");
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) { }
                    });
            }
        }
    }

    @Test
    void guestUploadReturnsUnauthorized() throws Exception {
        mockMvc.perform(multipart("/api/v1/upload/images")
                .file(pngFile("pic.png")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void userCanUploadValidPng() throws Exception {
        String token = login("upload-user", "Password123");
        mockMvc.perform(multipart("/api/v1/upload/images")
                .file(pngFile("pic.png"))
                .header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.uploaded").value(1))
            .andExpect(jsonPath("$.data.urls[0]").isNotEmpty());
    }

    @Test
    void adminCanUploadValidJpg() throws Exception {
        String token = login("upload-admin", "Admin1234");
        mockMvc.perform(multipart("/api/v1/upload/images")
                .file(jpgFile("photo.jpg"))
                .header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.uploaded").value(1));
    }

    @Test
    void webpUploadSucceeds() throws Exception {
        String token = login("upload-user", "Password123");
        mockMvc.perform(multipart("/api/v1/upload/images")
                .file(webpFile("shot.webp"))
                .header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.uploaded").value(1));
    }

    @Test
    void emptyFileRejectedWithExplicitError() throws Exception {
        String token = login("upload-user", "Password123");
        MockMultipartFile empty = new MockMultipartFile("files", "empty.png", "image/png", new byte[0]);
        mockMvc.perform(multipart("/api/v1/upload/images")
                .file(empty)
                .header("Authorization", bearer(token)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void oversizedFileRejectedByControllerLimit() throws Exception {
        String token = login("upload-user", "Password123");
        byte[] big = new byte[3000]; // > 2048 (configured controller limit)
        java.util.Arrays.fill(big, (byte) 'x');
        // prepend valid png magic so it only fails on size, not content
        System.arraycopy(VALID_PNG, 0, big, 0, Math.min(VALID_PNG.length, big.length));
        MockMultipartFile file = new MockMultipartFile("files", "big.png", "image/png", big);
        mockMvc.perform(multipart("/api/v1/upload/images")
                .file(file)
                .header("Authorization", bearer(token)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void tooManyFilesRejected() throws Exception {
        String token = login("upload-user", "Password123");
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/v1/upload/images");
        for (int i = 0; i < 7; i++) {
            builder = builder.file(pngFile("pic" + i + ".png"));
        }
        mockMvc.perform(builder.header("Authorization", bearer(token)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void wrongExtensionRejected() throws Exception {
        String token = login("upload-user", "Password123");
        MockMultipartFile file = new MockMultipartFile("files", "note.txt", "text/plain", VALID_PNG);
        mockMvc.perform(multipart("/api/v1/upload/images")
                .file(file)
                .header("Authorization", bearer(token)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void fakeExtensionRejected() throws Exception {
        // extension .jpg but actual bytes are PNG — content-aware validation must catch this
        String token = login("upload-user", "Password123");
        MockMultipartFile file = new MockMultipartFile("files", "fake.jpg", "image/jpeg", VALID_PNG);
        mockMvc.perform(multipart("/api/v1/upload/images")
                .file(file)
                .header("Authorization", bearer(token)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void wrongMimeTypeRejected() throws Exception {
        // valid jpg bytes but content-type text/plain — must be rejected by content-type gate
        String token = login("upload-user", "Password123");
        MockMultipartFile file = new MockMultipartFile("files", "photo.jpg", "text/plain", VALID_JPG);
        mockMvc.perform(multipart("/api/v1/upload/images")
                .file(file)
                .header("Authorization", bearer(token)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void invalidImageBytesRejected() throws Exception {
        // .jpg extension + image/jpeg content-type but random bytes (no jpg magic) — magic-number gate
        String token = login("upload-user", "Password123");
        byte[] junk = "not-an-image-at-all-but-long-enough".getBytes();
        MockMultipartFile file = new MockMultipartFile("files", "broken.jpg", "image/jpeg", junk);
        mockMvc.perform(multipart("/api/v1/upload/images")
                .file(file)
                .header("Authorization", bearer(token)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void pathTraversalFilenameDoesNotEscapeStorage() throws Exception {
        // user-controlled filename must not be trusted as storage path; server generates UUID name
        String token = login("upload-user", "Password123");
        MockMultipartFile file = new MockMultipartFile(
            "files", "../../etc/passwd.jpg", "image/jpeg", VALID_JPG);
        MvcResult result = mockMvc.perform(multipart("/api/v1/upload/images")
                .file(file)
                .header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).at("/data");
        String url = data.at("/urls/0").asText();
        // stored url must not contain traversal segments
        if (url.contains("..")) {
            throw new AssertionError("stored url must not contain path traversal: " + url);
        }
    }

    @Test
    void partialSuccessReturnsUploadedAndErrors() throws Exception {
        String token = login("upload-user", "Password123");
        // one valid png + one invalid (txt)
        List<MockMultipartFile> files = new ArrayList<>();
        files.add(pngFile("good.png"));
        files.add(new MockMultipartFile("files", "bad.txt", "text/plain", VALID_PNG));
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/v1/upload/images");
        for (MockMultipartFile f : files) {
            builder = builder.file(f);
        }
        mockMvc.perform(builder.header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.uploaded").value(1))
            .andExpect(jsonPath("$.data.failed").value(1));
    }

    @Test
    void uploadAssetInsertFailureRemovesUrlFromResult() throws Exception {
        String token = login("upload-user", "Password123");
        when(uploadedAssetRepository.insert(any(com.campushub.backend.upload.repository.entity.UploadedAssetEntity.class))).thenThrow(new RuntimeException("DB insert failed"));

        mockMvc.perform(multipart("/api/v1/upload/images")
                .file(pngFile("pic.png"))
                .header("Authorization", bearer(token)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(1002));

        reset(uploadedAssetRepository);
    }

    @Test
    void partialSuccessWhenInsertFailsForSecondFile() throws Exception {
        String token = login("upload-user", "Password123");
        when(uploadedAssetRepository.insert(any(com.campushub.backend.upload.repository.entity.UploadedAssetEntity.class))).thenReturn(1).thenThrow(new RuntimeException("DB insert failed"));

        MvcResult result = mockMvc.perform(multipart("/api/v1/upload/images")
                .file(pngFile("pic1.png"))
                .file(pngFile("pic2.png"))
                .header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.uploaded").value(1))
            .andExpect(jsonPath("$.data.failed").value(1))
            .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        org.junit.jupiter.api.Assertions.assertEquals(1, data.get("urls").size());

        reset(uploadedAssetRepository);
    }

    private MockMultipartFile pngFile(String name) {
        return new MockMultipartFile("files", name, "image/png", VALID_PNG);
    }

    private MockMultipartFile jpgFile(String name) {
        return new MockMultipartFile("files", name, "image/jpeg", VALID_JPG);
    }

    private MockMultipartFile webpFile(String name) {
        return new MockMultipartFile("files", name, "image/webp", VALID_WEBP);
    }

    private String login(String loginId, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("loginId", loginId, "password", password))))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
