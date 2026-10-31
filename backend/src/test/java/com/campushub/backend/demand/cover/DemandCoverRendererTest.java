package com.campushub.backend.demand.cover;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campushub.backend.demand.domain.CampusZone;
import com.campushub.backend.demand.domain.DemandCategory;

import java.io.ByteArrayInputStream;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 封面渲染器的纯单元测试：不启动 Spring 上下文，只校验文案规则与输出契约。
 */
class DemandCoverRendererTest {

    @Test
    @DisplayName("副标题按真实数据去掉 location 里重复的校区前缀")
    void buildSubtitleStripsDuplicatedZonePrefix() {
        assertEquals("仙林 · 菜鸟驿站（三栋楼下）",
            DemandCoverRenderer.buildSubtitle(CampusZone.XIANLIN, "仙林校区 · 菜鸟驿站（三栋楼下）"));
        assertEquals("鼓楼 · 行政楼 302",
            DemandCoverRenderer.buildSubtitle(CampusZone.GULOU, "鼓楼校区 · 行政楼 302"));
        assertEquals("苏州 · 体育馆 2 号场",
            DemandCoverRenderer.buildSubtitle(CampusZone.SUZHOU, "苏州校区 · 体育馆 2 号场"));
        assertEquals("鼓楼 · 图书馆",
            DemandCoverRenderer.buildSubtitle(CampusZone.GULOU, "图书馆"));
    }

    @Test
    @DisplayName("线上地点只显示「线上」，不拼校区")
    void buildSubtitleKeepsOnlineOnly() {
        assertEquals("线上", DemandCoverRenderer.buildSubtitle(CampusZone.XIANLIN, "线上"));
        assertEquals("线上", DemandCoverRenderer.buildSubtitle(null, " 线上 "));
    }

    @Test
    @DisplayName("无分隔符但带「XX校区」前缀时也要剥掉前缀")
    void buildSubtitleStripsPrefixWithoutSeparator() {
        assertEquals("仙林 · 菜鸟驿站", DemandCoverRenderer.buildSubtitle(CampusZone.XIANLIN, "仙林校区菜鸟驿站"));
        assertEquals("鼓楼", DemandCoverRenderer.buildSubtitle(CampusZone.GULOU, "鼓楼校区"));
    }

    @Test
    @DisplayName("多段地点只剥掉开头的校区标记，不丢中间细节")
    void buildSubtitleKeepsDetailAfterCampusSegment() {
        assertEquals("鼓楼 · 行政楼 · 302",
            DemandCoverRenderer.buildSubtitle(CampusZone.GULOU, "鼓楼校区 · 行政楼 · 302"));
        assertEquals("仙林 · 五栋宿舍路口",
            DemandCoverRenderer.buildSubtitle(CampusZone.XIANLIN, "仙林 · 五栋宿舍路口"));
        assertEquals("仙林 · 三栋楼下 · 菜鸟驿站",
            DemandCoverRenderer.buildSubtitle(CampusZone.XIANLIN, "三栋楼下 · 菜鸟驿站"));
    }

    @Test
    @DisplayName("圆环播种用稳定的枚举名，而不是身份哈希")
    void ringSeedUsesStableZoneName() {
        long expected = 42L * 0x9E3779B97F4A7C15L
            + DemandCategory.HELP.ordinal() * 7919L
            + Objects.hash("GULOU", "图书馆");

        assertEquals(expected, DemandCoverRenderer.ringSeed(DemandCategory.HELP, CampusZone.GULOU, "图书馆", 42L));
    }

    @Test
    @DisplayName("空值与未知校区降级为「校园」，不抛异常")
    void buildSubtitleFallsBackForMissingInput() {
        assertEquals("校园", DemandCoverRenderer.buildSubtitle(null, null));
        assertEquals("校园", DemandCoverRenderer.buildSubtitle(null, "   "));
        assertEquals("仙林", DemandCoverRenderer.buildSubtitle(CampusZone.XIANLIN, ""));
        assertEquals("仙林", DemandCoverRenderer.buildSubtitle(CampusZone.XIANLIN, "仙林"));
    }

    @Test
    @DisplayName("副标题超过 18 个字符时按 code point 截断并加省略号")
    void buildSubtitleTruncatesByCodePoint() {
        String longPlace = "一二三四五六七八九十一二三四五六七八九十";
        String subtitle = DemandCoverRenderer.buildSubtitle(CampusZone.GULOU, longPlace);

        assertEquals(18, subtitle.codePointCount(0, subtitle.length()));
        assertTrue(subtitle.endsWith("…"));

        String withEmoji = "😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀";
        String emojiSubtitle = DemandCoverRenderer.buildSubtitle(CampusZone.GULOU, withEmoji);
        assertEquals(18, emojiSubtitle.codePointCount(0, emojiSubtitle.length()));
    }

    @Test
    @DisplayName("渲染输出为 800×600 的 JPEG，且体积不超过 60 KB")
    void renderProducesJpegWithinBudget() throws Exception {
        byte[] bytes = DemandCoverRenderer.render(
            DemandCategory.STUDY_TUTORING, CampusZone.XIANLIN, "仙林校区 · 杜厦图书馆研讨间", 3L);

        assertTrue(bytes.length > 0);
        assertEquals((byte) 0xFF, bytes[0]);
        assertEquals((byte) 0xD8, bytes[1]);
        assertTrue(bytes.length <= 60 * 1024, "封面体积应 <= 60KB，实际 " + bytes.length + "B");

        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(image);
        assertEquals(DemandCoverRenderer.WIDTH, image.getWidth());
        assertEquals(DemandCoverRenderer.HEIGHT, image.getHeight());
    }

    @Test
    @DisplayName("同一入参重复渲染得到逐字节一致的结果")
    void renderIsDeterministic() {
        byte[] first = DemandCoverRenderer.render(DemandCategory.HELP, CampusZone.GULOU, "图书馆", 42L);
        byte[] second = DemandCoverRenderer.render(DemandCategory.HELP, CampusZone.GULOU, "图书馆", 42L);

        assertArrayEquals(first, second);
    }

    @Test
    @DisplayName("分类、校区、地点为 null 时仍能正常出图")
    void renderToleratesNullInputs() {
        byte[] bytes = assertDoesNotThrow(() -> DemandCoverRenderer.render(null, null, null, 0L));

        assertTrue(bytes.length > 0);
        assertEquals((byte) 0xFF, bytes[0]);
    }

    @Test
    @DisplayName("七个分类都能出图且互不相同")
    void renderAllCategories() {
        for (DemandCategory category : DemandCategory.values()) {
            byte[] bytes = DemandCoverRenderer.render(category, CampusZone.XIANLIN, "图书馆", 1L);
            assertTrue(bytes.length > 0, category + " 未出图");
        }
    }
}
