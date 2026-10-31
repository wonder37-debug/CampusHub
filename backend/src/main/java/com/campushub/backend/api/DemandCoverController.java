package com.campushub.backend.api;

import com.campushub.backend.demand.cover.DemandCoverRenderer;
import com.campushub.backend.demand.domain.CampusZone;
import com.campushub.backend.demand.domain.DemandCategory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 需求卡片兜底封面接口。
 *
 * <p>接口契约：
 * <pre>
 * GET /api/v1/covers/{category}/{seed}.jpg?zone=XIANLIN&amp;loc=仙林校区 · 杜厦图书馆研讨间
 * </pre>
 *
 * <p>关键约束：
 * <ul>
 *   <li>{@code category} 无法识别时按「其他」出图，不返回 4xx/5xx。</li>
 *   <li>{@code zone}/{@code loc} 均可省略；解析失败一律降级为 {@code null}。</li>
 *   <li>{@code loc} 先按 code point 截断到 40 字，再参与渲染与缓存 key，避免被构造出无限多缓存文件。</li>
 *   <li>缓存文件名包含 {@code (zone, location)} 的内容哈希：地点改了 URL 就换图，不会命中旧缓存。</li>
 *   <li>URL 已带内容哈希，因此可以安全地下发 7 天强缓存。</li>
 *   <li>公开接口可被任意 seed/loc 组合命中，因此磁盘缓存有文件数上限（{@code app.cover.cache.max-files}），
 *       超出后按最后修改时间淘汰旧文件，避免被构造请求写满磁盘。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class DemandCoverController {

    /** 地点原文参与渲染与哈希前的长度上限（code point）。 */
    private static final int LOCATION_MAX_CODE_POINTS = 40;

    /** 磁盘缓存文件数默认上限，约对应 512 × 45KB ≈ 23MB。 */
    private static final int DEFAULT_MAX_CACHE_FILES = 512;

    private static final CacheControl COVER_CACHE_CONTROL =
        CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic().immutable();

    private final Path cacheRoot;
    private final int maxCacheFiles;

    public DemandCoverController(
            @Value("${app.upload.dir:uploads}") String uploadDir,
            @Value("${app.cover.cache.max-files:" + DEFAULT_MAX_CACHE_FILES + "}") int maxCacheFiles) {
        this.cacheRoot = Paths.get(uploadDir).toAbsolutePath().normalize().resolve("covers");
        // 上限至少为 1：配置成 0 或负数时只降级为“每次重绘”，不能变成非法状态
        this.maxCacheFiles = Math.max(1, maxCacheFiles);
        try {
            Files.createDirectories(this.cacheRoot);
        } catch (IOException e) {
            // 目录创建失败不能拖垮应用启动：真正写缓存时会再次尝试，失败也不影响响应
            this.cacheRoot.toFile().mkdirs();
        }
    }

    @GetMapping("/covers/{category}/{seed}.jpg")
    public ResponseEntity<byte[]> loadCover(
            @PathVariable String category,
            @PathVariable String seed,
            @RequestParam(value = "zone", required = false) String zone,
            @RequestParam(value = "loc", required = false) String location) {

        DemandCategory resolvedCategory = resolveCategory(category);
        CampusZone resolvedZone = resolveZone(zone);
        String normalizedLocation = DemandCoverRenderer.truncateCodePoints(location, LOCATION_MAX_CODE_POINTS);
        long resolvedSeed = resolveSeed(seed);

        Path cachedFile = cacheRoot.resolve(fileName(resolvedCategory, resolvedSeed, contentHash(resolvedZone, normalizedLocation)));
        byte[] bytes = readIfPresent(cachedFile);
        if (bytes == null) {
            bytes = DemandCoverRenderer.render(resolvedCategory, resolvedZone, normalizedLocation, resolvedSeed);
            persist(cachedFile, bytes);
        }

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(DemandCoverRenderer.CONTENT_TYPE))
            .cacheControl(COVER_CACHE_CONTROL)
            .body(bytes);
    }

    private DemandCategory resolveCategory(String category) {
        DemandCategory resolved = DemandCategory.fromValue(category);
        return resolved == null ? DemandCategory.OTHER : resolved;
    }

    /** 校区接受枚举名（大小写不敏感）与中文短名，非法值降级为 null。 */
    private CampusZone resolveZone(String zone) {
        if (zone == null || zone.isBlank()) {
            return null;
        }
        String normalized = zone.trim();
        for (CampusZone candidate : CampusZone.values()) {
            if (candidate.name().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        return switch (normalized) {
            case "鼓楼", "鼓楼校区" -> CampusZone.GULOU;
            case "仙林", "仙林校区" -> CampusZone.XIANLIN;
            case "苏州", "苏州校区" -> CampusZone.SUZHOU;
            default -> null;
        };
    }

    /**
     * seed 只用于渲染的确定性播种与缓存文件名，必须是纯数字：
     * 直接把路径变量拼进文件名会带来路径穿越风险，因此非法输入改走稳定哈希。
     */
    private long resolveSeed(String seed) {
        if (seed == null || seed.isBlank()) {
            return 0L;
        }
        String trimmed = seed.trim();
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return Math.floorMod((long) trimmed.hashCode(), 1_000_000L);
        }
    }

    /** 缓存文件名：{category}-{seed}-{内容哈希8位}.jpg，内容变化必然换文件名。 */
    private String fileName(DemandCategory category, long seed, String contentHash) {
        return category.name().toLowerCase(Locale.ROOT) + "-" + seed + "-" + contentHash + "." + DemandCoverRenderer.FILE_EXTENSION;
    }

    /** (zone, location) 规范化后取 SHA-256 前 8 位十六进制，跨机器稳定。 */
    private String contentHash(CampusZone zone, String location) {
        String canonical = (zone == null ? "" : zone.name()) + "|" + (location == null ? "" : location);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                builder.append(String.format("%02x", digest[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.format("%08x", canonical.hashCode() & 0xFFFFFFFFL);
        }
    }

    private byte[] readIfPresent(Path file) {
        try {
            if (!Files.isReadable(file)) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            return bytes.length == 0 ? null : bytes;
        } catch (IOException e) {
            return null;
        }
    }

    /** 临时文件 → 原子移动，避免并发请求读到半个文件。 */
    private void persist(Path target, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        Path temp = null;
        try {
            Files.createDirectories(cacheRoot);
            temp = Files.createTempFile(cacheRoot, "cover-", ".tmp");
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            trimCache();
        } catch (IOException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 清理失败不影响本次响应
                }
            }
        }
    }

    /**
     * 把缓存文件数收敛到上限内：按最后修改时间从旧到新淘汰。
     *
     * <p>并发场景下可能有多个请求同时裁剪，最终文件数略多于或略少于上限都无害；
     * 删除失败的条目留到下一轮处理。
     */
    private void trimCache() {
        String suffix = "." + DemandCoverRenderer.FILE_EXTENSION;
        List<Path> entries = new ArrayList<>();
        try (var files = Files.list(cacheRoot)) {
            files.filter(path -> path.getFileName().toString().endsWith(suffix)).forEach(entries::add);
        } catch (IOException e) {
            return;
        }

        int overflow = entries.size() - maxCacheFiles;
        if (overflow <= 0) {
            return;
        }
        entries.sort(Comparator.comparingLong(DemandCoverController::lastModifiedMillis));
        for (int index = 0; index < overflow; index++) {
            try {
                Files.deleteIfExists(entries.get(index));
            } catch (IOException ignored) {
                // 删除失败不影响本次响应
            }
        }
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }
}
