package com.campushub.backend.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 封面接口的 Web 层契约测试：只装配 MVC 切片，不需要数据库。
 */
@WebMvcTest(
    controllers = DemandCoverController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class},
    properties = "app.upload.dir=target/test-covers"
)
@AutoConfigureMockMvc(addFilters = false)
class DemandCoverControllerTest {

    private static final String COVER_BASE = "/api/v1/covers";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("带校区与地点时返回 800×600 JPEG，并下发 7 天强缓存")
    void servesJpegWithImmutableCacheHeader() throws Exception {
        MvcResult result = mockMvc.perform(get(COVER_BASE + "/STUDY_TUTORING/3.jpg")
                .param("zone", "XIANLIN")
                .param("loc", "仙林校区 · 杜厦图书馆研讨间"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/jpeg"))
            .andExpect(header().string("Cache-Control", "max-age=604800, public, immutable"))
            .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertTrue(body.length > 0);
        assertTrue((body[0] & 0xFF) == 0xFF && (body[1] & 0xFF) == 0xD8, "应为 JPEG 字节流");
    }

    @Test
    @DisplayName("同一 URL 重复请求命中磁盘缓存，返回完全一致的内容")
    void secondRequestHitsDiskCache() throws Exception {
        String url = COVER_BASE + "/HELP/6.jpg";
        byte[] first = mockMvc.perform(get(url).param("zone", "GULOU").param("loc", "图书馆"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        byte[] second = mockMvc.perform(get(url).param("zone", "GULOU").param("loc", "图书馆"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();

        assertArrayEquals(first, second);
    }

    @Test
    @DisplayName("只改地点参数就换图，缓存文件名必须含内容哈希")
    void changingLocationProducesDifferentImage() throws Exception {
        String url = COVER_BASE + "/EXPRESS/11.jpg";
        byte[] library = mockMvc.perform(get(url).param("zone", "XIANLIN").param("loc", "图书馆"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        byte[] gym = mockMvc.perform(get(url).param("zone", "XIANLIN").param("loc", "体育馆"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();

        assertFalse(java.util.Arrays.equals(library, gym), "地点变化后应当得到新图");
    }

    @Test
    @DisplayName("不带 query、未知分类、非法校区都只降级不报错")
    void degradesInsteadOfFailing() throws Exception {
        mockMvc.perform(get(COVER_BASE + "/EXPRESS/1.jpg"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/jpeg"));

        mockMvc.perform(get(COVER_BASE + "/UNKNOWN/1.jpg"))
            .andExpect(status().isOk());

        mockMvc.perform(get(COVER_BASE + "/EXPRESS/1.jpg").param("zone", "NOT_A_ZONE").param("loc", "x"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("超长 loc 被截断，缓存目录里不出现超长文件名")
    void truncatesOverlongLocation() throws Exception {
        String longLocation = "仙林校区".repeat(50);

        mockMvc.perform(get(COVER_BASE + "/OTHER/99.jpg").param("loc", longLocation))
            .andExpect(status().isOk());

        Path cacheDir = Path.of("target/test-covers/covers");
        try (var files = Files.list(cacheDir)) {
            List<String> names = files.map(file -> file.getFileName().toString()).toList();
            assertTrue(names.stream().anyMatch(name -> name.startsWith("other-99-")),
                "应写入 other-99-{hash}.jpg，实际: " + names);
            assertTrue(names.stream().allMatch(name -> name.length() <= 32),
                "缓存文件名不应随 loc 长度增长，实际: " + names);
        }
    }
}
