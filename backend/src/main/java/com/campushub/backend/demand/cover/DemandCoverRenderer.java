package com.campushub.backend.demand.cover;

import com.campushub.backend.demand.domain.CampusZone;
import com.campushub.backend.demand.domain.DemandCategory;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * 需求卡片兜底封面渲染器：把「分类 + 校区 + 地点」直接画成一张带中文文字的 JPEG。
 *
 * <p>设计约定：
 * <ul>
 *   <li>纯 JDK 能力（Java2D + ImageIO），不引入任何第三方图形库，不调用外部服务。</li>
 *   <li>输出尺寸 {@link #WIDTH} × {@link #HEIGHT}（4:3），与卡片 {@code aspect-ratio: 4/3} 对齐。</li>
 *   <li>同一 {@code (category, zone, location, seed)} 组合在任何机器上渲染结果一致（确定性）。</li>
 *   <li>任何异常都降级为「无文字纯图形」封面，绝不把异常抛给接口层。</li>
 * </ul>
 *
 * <p>视觉结构：垂直 50% 处硬分割为上深下亮两段同色系色带，主标题（分类名）跨分割线居中，
 * 副标题（短校区 · 地点）居其下方；画面四角附近叠加被画布裁切的同心圆环装饰。
 */
public final class DemandCoverRenderer {

    static {
        // 离屏绘制不需要显示设备：装了桌面环境或残留 DISPLAY 的机器上，
        // Java2D 初始化可能尝试连接 X11 并抛 AWTError。
        System.setProperty("java.awt.headless", "true");
    }

    /** 画布宽度，与卡片 4:3 比例一致。 */
    public static final int WIDTH = 800;

    /** 画布高度。 */
    public static final int HEIGHT = 600;

    /** 响应内容类型。 */
    public static final String CONTENT_TYPE = "image/jpeg";

    /** 缓存文件扩展名（不含点）。 */
    public static final String FILE_EXTENSION = "jpg";

    private static final int SPLIT_Y = HEIGHT / 2;
    private static final float JPEG_QUALITY = 0.88f;

    private static final int TITLE_START_SIZE = 92;
    private static final int TITLE_MIN_SIZE = 40;
    private static final int TITLE_MAX_WIDTH = (int) (WIDTH * 0.75f);
    private static final int TITLE_BASELINE = 344;

    private static final int SUBTITLE_SIZE = 40;
    private static final int SUBTITLE_BASELINE = 414;
    private static final int SUBTITLE_ALPHA = 235;
    private static final int SUBTITLE_MAX_CODE_POINTS = 18;

    private static final int RING_GROUP_COUNT = 3;
    private static final float RING_STROKE_WIDTH = 2.8f;

    /**
     * 衬线中文字体候选：Linux(Noto) → 思源 → macOS → Windows。
     * 探测一次后固定下来，避免每次渲染都遍历。
     */
    private static final String[] SERIF_CANDIDATES = {
        "Noto Serif CJK SC",
        "Source Han Serif SC",
        "Songti SC",
        "SimSun",
        "STSong",
        "FangSong",
    };

    private static final String SERIF_FAMILY = detectSerifFamily();

    private DemandCoverRenderer() {
    }

    /**
     * 渲染封面。
     *
     * @param category 分类，{@code null} 按 {@link DemandCategory#OTHER} 处理
     * @param zone     校区，{@code null} 表示未知
     * @param location 地点原文，可以为 {@code null} 或空白
     * @param seed     稳定的种子（通常是需求 id），决定圆环装饰位置
     * @return JPEG 字节；即使内部失败也会返回可用的纯图形封面
     */
    public static byte[] render(DemandCategory category, CampusZone zone, String location, long seed) {
        DemandCategory safeCategory = category == null ? DemandCategory.OTHER : category;
        String title = safeCategory.getLabel();
        // 主机没有任何中文字形时，宁可不画文字，也不要缓存一张“豆腐块”封面（URL 带 immutable 强缓存，脏图会长期驻留）
        if (!hasGlyphCoverage(title)) {
            return renderFallback(safeCategory);
        }
        try {
            BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                applyHints(g);
                Palette palette = paletteOf(safeCategory);
                paintBase(g, palette);
                paintRings(g, ringRandom(safeCategory, zone, location, seed));
                paintTitle(g, title);
                paintSubtitle(g, buildSubtitle(zone, location));
            } finally {
                g.dispose();
            }
            return writeJpeg(image);
        } catch (RuntimeException | IOException ex) {
            return renderFallback(safeCategory);
        }
    }

    /**
     * 中文字形覆盖探测。
     *
     * <p>只探测主标题（固定中文分类名）：分类名都画不出来，说明主机没有任何中文字体，
     * 此时文字只会渲染为方块。副标题参差不参与探测——地点是用户自由输入，
     * 只要一个 emoji 不在字体覆盖范围内，整张封面就会丢掉分类标题，得不偿失。
     */
    private static boolean hasGlyphCoverage(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        Font probe = new Font(SERIF_FAMILY, Font.PLAIN, SUBTITLE_SIZE);
        return probe.canDisplayUpTo(text) == -1;
    }

    /**
     * 按「短校区 · 地点」规则生成副标题。
     *
     * <p>location 本身可能已经带校区前缀（如 {@code "仙林校区 · 菜鸟驿站（三栋楼下）"}），
     * 直接拼接会得到「仙林 · 仙林校区 · ...」这种重复文案，因此先做规范化再拼。
     */
    static String buildSubtitle(CampusZone zone, String location) {
        String shortZone = shortZoneOf(zone);
        String place = normalizePlace(zone, location);

        String subtitle;
        if (place.isEmpty()) {
            subtitle = shortZone;
        } else if ("线上".equals(place)) {
            subtitle = place;
        } else if (place.equals(shortZone)) {
            subtitle = shortZone;
        } else {
            subtitle = shortZone + " · " + place;
        }
        return truncateCodePoints(subtitle, SUBTITLE_MAX_CODE_POINTS);
    }

    /** 三选一的短校区名，未知或 null 用「校园」兜底。 */
    static String shortZoneOf(CampusZone zone) {
        if (zone == null) {
            return "校园";
        }
        return switch (zone) {
            case GULOU -> "鼓楼";
            case XIANLIN -> "仙林";
            case SUZHOU -> "苏州";
        };
    }

    /**
     * 地点规范化：只剥掉开头的校区标记，保留其余地点细节。
     *
     * <p>不能用「取最后一个 · 之后」：{@code "鼓楼校区 · 行政楼 · 302"} 会退化成「302」，
     * 丢掉楼名。这里的规则是首段是校区标记（等于短校区名或以「校区」结尾）时才剥掉，
     * 其余情况原样保留，宁可多显示也不丢信息。
     */
    private static String normalizePlace(CampusZone zone, String location) {
        if (location == null) {
            return "";
        }
        String trimmed = location.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int firstSeparator = trimmed.indexOf('·');
        if (firstSeparator >= 0) {
            String head = trimmed.substring(0, firstSeparator).trim();
            if (head.equals(shortZoneOf(zone)) || head.endsWith("校区")) {
                return trimmed.substring(firstSeparator + 1).trim();
            }
            return trimmed;
        }
        for (CampusZone candidate : CampusZone.values()) {
            String prefix = shortZoneOf(candidate) + "校区";
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return trimmed;
    }

    /** 按 code point 截断，避免把 emoji / 生僻字切成半个；控制器截断 loc 参数时复用。 */
    public static String truncateCodePoints(String value, int maxCodePoints) {
        if (value == null) {
            return "";
        }
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints - 1);
        return value.substring(0, end) + "…";
    }

    private static void applyHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // 中文在 800px 宽画布上不开文字抗锯齿会有明显锯齿
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    /** 上下双色带 + 50% 处硬分割，分割线用极淡高光收边。 */
    private static void paintBase(Graphics2D g, Palette palette) {
        Color upperStart = mix(palette.upper(), Color.WHITE, 0.06f);
        g.setPaint(new GradientPaint(0, 0, upperStart, WIDTH, SPLIT_Y, palette.upper()));
        g.fillRect(0, 0, WIDTH, SPLIT_Y);

        Color lowerEnd = mix(palette.lower(), Color.BLACK, 0.03f);
        g.setPaint(new GradientPaint(0, SPLIT_Y, palette.lower(), WIDTH, HEIGHT, lowerEnd));
        g.fillRect(0, SPLIT_Y, WIDTH, HEIGHT - SPLIT_Y);

        // alpha 必须极低，超过 30 会变成一条明显的白线
        g.setColor(new Color(255, 255, 255, 18));
        g.fillRect(0, SPLIT_Y - 1, WIDTH, 2);
    }

    /** 3 组同心圆环，圆心靠近四角、半径足够大以被画布裁切；圆环先于文字绘制。 */
    private static void paintRings(Graphics2D g, Random random) {
        Graphics2D rings = (Graphics2D) g.create();
        try {
            rings.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            rings.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            rings.setStroke(new BasicStroke(RING_STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            for (int group = 0; group < RING_GROUP_COUNT; group++) {
                double centerX = (random.nextBoolean()
                    ? between(random, 0.12, 0.34)
                    : between(random, 0.72, 0.96)) * WIDTH;
                double centerY = (random.nextBoolean()
                    ? between(random, 0.10, 0.30)
                    : between(random, 0.78, 1.02)) * HEIGHT;

                int ringCount = 4 + random.nextInt(3);
                double baseRadius = between(random, 150, 280);
                double spacing = between(random, 46, 72);
                rings.setColor(new Color(255, 255, 255, 26 + random.nextInt(15)));

                for (int index = 0; index < ringCount; index++) {
                    double radius = baseRadius + spacing * index;
                    rings.draw(new Ellipse2D.Double(centerX - radius, centerY - radius, radius * 2, radius * 2));
                }
            }
        } finally {
            rings.dispose();
        }
    }

    /** 主标题：92px 起按实测宽度自适应缩小，水平居中，基线跨过分割线。 */
    private static void paintTitle(Graphics2D g, String text) {
        FontRenderContext frc = g.getFontRenderContext();
        int size = TITLE_START_SIZE;
        Font font = new Font(SERIF_FAMILY, Font.BOLD, size);
        while (size > TITLE_MIN_SIZE && stringWidth(font, text, frc) > TITLE_MAX_WIDTH) {
            size -= 2;
            font = new Font(SERIF_FAMILY, Font.BOLD, size);
        }

        g.setFont(font);
        g.setColor(Color.WHITE);
        // getStringBounds 的 x 是相对基点的偏移，居中时要减掉
        Rectangle2D bounds = font.getStringBounds(text, frc);
        float x = (float) ((WIDTH - bounds.getWidth()) / 2 - bounds.getX());
        g.drawString(text, x, TITLE_BASELINE);
    }

    /** 副标题：40px，白色 0.92 不透明度，水平居中。 */
    private static void paintSubtitle(Graphics2D g, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        FontRenderContext frc = g.getFontRenderContext();
        Font font = new Font(SERIF_FAMILY, Font.PLAIN, SUBTITLE_SIZE);
        g.setFont(font);
        g.setColor(new Color(255, 255, 255, SUBTITLE_ALPHA));

        Rectangle2D bounds = font.getStringBounds(text, frc);
        float x = (float) ((WIDTH - bounds.getWidth()) / 2 - bounds.getX());
        g.drawString(text, x, SUBTITLE_BASELINE);
    }

    /**
     * 固定公式播种：同一输入在任何机器、任何时间都得到相同的圆环布局。
     * 不使用 currentTimeMillis / Math.random / HashMap 遍历顺序等不稳定输入。
     *
     * <p>校区必须用 {@code zone.name()}：Enum 的 hashCode 是身份哈希，
     * 具体取值不在 JVM 规范保证范围内（不同 JVM 实现/参数可能不同），
     * 直接哈希枚举实例会破坏跨机器、跨重启的确定性。
     */
    private static Random ringRandom(DemandCategory category, CampusZone zone, String location, long seed) {
        return new Random(ringSeed(category, zone, location, seed));
    }

    /** 圆环播种值，单独暴露给单元测试防止回退到不稳定的枚举身份哈希。 */
    static long ringSeed(DemandCategory category, CampusZone zone, String location, long seed) {
        return seed * 0x9E3779B97F4A7C15L
            + category.ordinal() * 7919L
            + Objects.hash(zone == null ? null : zone.name(), location);
    }

    private static Palette paletteOf(DemandCategory category) {
        return switch (category) {
            case EXPRESS -> new Palette(new Color(0x1E5FD1), new Color(0x2F7BCB));
            case ERRAND -> new Palette(new Color(0x7C3AED), new Color(0x9061F9));
            case STUDY_TUTORING -> new Palette(new Color(0x1F9D6B), new Color(0x35A87C));
            case SECOND_HAND -> new Palette(new Color(0xD2490C), new Color(0xF0661A));
            case TEAM_UP -> new Palette(new Color(0x0E7490), new Color(0x1197B8));
            case HELP -> new Palette(new Color(0xC2185B), new Color(0xE0357B));
            case OTHER -> new Palette(new Color(0x4B5C6B), new Color(0x64748B));
        };
    }

    private static Color mix(Color base, Color target, float ratio) {
        float keep = 1f - ratio;
        return new Color(
            Math.round(base.getRed() * keep + target.getRed() * ratio),
            Math.round(base.getGreen() * keep + target.getGreen() * ratio),
            Math.round(base.getBlue() * keep + target.getBlue() * ratio)
        );
    }

    private static double between(Random random, double min, double max) {
        return min + random.nextDouble() * (max - min);
    }

    private static double stringWidth(Font font, String text, FontRenderContext frc) {
        return font.getStringBounds(text, frc).getWidth();
    }

    /** 字体探测：必须比对 getFamily()，canDisplay() 对回退逻辑字体也会返回 true。 */
    private static String detectSerifFamily() {
        for (String name : SERIF_CANDIDATES) {
            Font probe = new Font(name, Font.PLAIN, 12);
            if (probe.getFamily().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                return name;
            }
        }
        // 逻辑字体兜底：一定可用，只是可能不是衬线，不因此抛异常
        return Font.SERIF;
    }

    private static byte[] writeJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(FILE_EXTENSION);
        if (!writers.hasNext()) {
            ImageIO.write(image, FILE_EXTENSION, out);
            return out.toByteArray();
        }

        ImageWriter writer = writers.next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(output);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** 极端情况下的兜底：只画色带，保证接口仍能返回一张可用的图。 */
    private static byte[] renderFallback(DemandCategory category) {
        try {
            BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                paintBase(g, paletteOf(category));
            } finally {
                g.dispose();
            }
            return writeJpeg(image);
        } catch (RuntimeException | IOException ignore) {
            return new byte[0];
        }
    }

    private record Palette(Color upper, Color lower) {
    }
}
