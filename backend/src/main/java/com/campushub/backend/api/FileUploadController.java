package com.campushub.backend.api;

import com.campushub.backend.common.api.ApiResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.common.security.CurrentUser;
import com.campushub.backend.common.security.RequestUserExtractor;
import com.campushub.backend.upload.repository.UploadedAssetRepository;
import com.campushub.backend.upload.repository.entity.UploadedAssetEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 图片上传与静态访问。
 *
 * <p>安全语义：
 * <ul>
 *   <li>{@code POST /api/v1/upload/images} —— 仅已登录（USER/ADMIN）用户可调用，游客返回 401。
 *       鉴权复用 {@link RequestUserExtractor}，不引入新认证体系。</li>
 *   <li>文件校验三层：扩展名 + content-type + 文件头魔数签名（非完整图片解码），避免仅凭扩展名信任用户输入。</li>
 *   <li>存储文件名由服务端生成（UUID），禁止使用用户原始文件名作为存储路径。</li>
 *   <li>{@code GET /api/v1/uploads/{year}/{month}/{filename}} —— 公开匿名访问。
 *       需求图片本身设计为公开资源（列表/详情展示），无需鉴权；路径穿越由
 *       {@code normalize() + startsWith(uploadRoot)} 拦截。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class FileUploadController {

    private final Path uploadRoot;

    /** Allowed image extensions */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    /** Allowed image content-types, must align with ALLOWED_EXTENSIONS */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    /** Max files per upload request */
    private static final int MAX_FILES_PER_REQUEST = 6;

    private final long maxFileSize;
    private final RequestUserExtractor requestUserExtractor;
    private final UploadedAssetRepository uploadedAssetRepository;

    public FileUploadController(
        @Value("${app.upload.dir:uploads}") String uploadDir,
        @Value("${app.upload.max-file-size-bytes:10485760}") long maxFileSize,
        RequestUserExtractor requestUserExtractor,
        UploadedAssetRepository uploadedAssetRepository
    ) {
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.maxFileSize = maxFileSize;
        this.requestUserExtractor = requestUserExtractor;
        this.uploadedAssetRepository = uploadedAssetRepository;
        try {
            Files.createDirectories(this.uploadRoot);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create upload directory: " + this.uploadRoot, e);
        }
    }

    /**
     * Upload single or multiple image files.
     * Returns a list of accessible URLs.
     *
     * <p>只有已登录用户可以上传；游客请求会被 {@link RequestUserExtractor#requireCurrentUser}
     * 拒绝并返回 401。不要通过前端隐藏上传按钮代替后端权限控制。</li>
     */
    @PostMapping("/upload/images")
    public ApiResponse<Map<String, Object>> uploadImages(
        HttpServletRequest request,
        @RequestParam("files") List<MultipartFile> files
    ) {
        // 强制认证：游客 -> 401，USER/ADMIN -> allowed
        var currentUser = requestUserExtractor.requireCurrentUser(request);

        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请选择至少一张图片");
        }
        if (files.size() > MAX_FILES_PER_REQUEST) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "单次最多上传 " + MAX_FILES_PER_REQUEST + " 张图片");
        }

        List<String> urls = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (MultipartFile file : files) {
            String originalFilename = file.getOriginalFilename();

            // 空文件：明确报错而非静默跳过，保证部分成功语义清晰
            if (file.isEmpty()) {
                errors.add((originalFilename == null ? "文件" : originalFilename) + " 为空文件");
                continue;
            }

            // Validate file size
            if (file.getSize() > maxFileSize) {
                errors.add((originalFilename == null ? "文件" : originalFilename) + " 超过 " + (maxFileSize / (1024 * 1024)) + "MB 限制");
                continue;
            }

            // Validate extension
            String extension = getExtension(originalFilename);
            if (extension == null || !ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
                errors.add((originalFilename == null ? "文件" : originalFilename) + " 格式不支持，仅支持 jpg/png/webp");
                continue;
            }
            String normalizedExtension = extension.toLowerCase();

            // Validate content-type
            String contentType = file.getContentType();
            if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
                errors.add((originalFilename == null ? "文件" : originalFilename) + " 的内容类型不被支持");
                continue;
            }

            try {
                // 魔数校验只需前 12 字节，独立流读取头部，避免全量读入内存
                byte[] header;
                try (var headerStream = file.getInputStream()) {
                    header = headerStream.readNBytes(12);
                }
                if (!isValidImageContent(header, normalizedExtension)) {
                    errors.add((originalFilename == null ? "文件" : originalFilename) + " 不是有效的图片文件");
                    continue;
                }

                String storedFilename = generateStoredFilename(normalizedExtension);
                LocalDate today = LocalDate.now();
                Path dateDir = uploadRoot
                        .resolve(String.valueOf(today.getYear()))
                        .resolve(String.format("%02d", today.getMonthValue()));
                Files.createDirectories(dateDir);
                Path targetPath = dateDir.resolve(storedFilename).normalize();
                // 路径穿越兜底：最终写入路径必须仍在 uploadRoot 之下
                if (!targetPath.startsWith(uploadRoot)) {
                    errors.add((originalFilename == null ? "文件" : originalFilename) + " 存储路径非法");
                    continue;
                }
                // 流式写入，try-with-resources 确保 InputStream 关闭，避免异常/高并发下资源泄漏
                try (var in = file.getInputStream()) {
                    Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }

                // Build accessible URL: /api/v1/uploads/YYYY/MM/filename（与存储路径用同一个 today，避免日期切换不一致）
                String datePath = today.format(DateTimeFormatter.ofPattern("yyyy/MM"));
                String urlPath = "/api/v1/uploads/" + datePath + "/" + storedFilename;
                urls.add(urlPath);
                try {
                    uploadedAssetRepository.insert(new UploadedAssetEntity(storedFilename, urlPath, currentUser.userId()));
                } catch (RuntimeException e) {
                    // 上传记录保存失败时清理已写入的文件，避免产生无记录的孤立资源
                    Files.deleteIfExists(targetPath);
                    errors.add((originalFilename == null ? "文件" : originalFilename) + " 上传记录保存失败");
                    continue;
                }
            } catch (IOException e) {
                errors.add((originalFilename == null ? "文件" : originalFilename) + " 上传失败: " + e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("urls", urls);
        result.put("uploaded", urls.size());
        result.put("failed", errors.size());
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }

        if (urls.isEmpty() && !errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, String.join("; ", errors));
        }

        return ApiResponse.success(result);
    }

    /**
     * Serve uploaded files via HTTP for local static storage mapping.
     *
     * <p>公开匿名访问：需求图片设计上本身就是公开资源（首页列表、需求详情、订单详情均需展示），
     * 故无需鉴权。路径穿越由 {@code normalize() + startsWith(uploadRoot)} 拦截。</p>
     */
    @GetMapping("/uploads/{year}/{month}/{filename}")
    public ResponseEntity<Resource> serveFile(
            @PathVariable String year,
            @PathVariable String month,
            @PathVariable String filename) {
        try {
            Path filePath = uploadRoot.resolve(year).resolve(month).resolve(filename).normalize();
            if (!filePath.startsWith(uploadRoot)) {
                return ResponseEntity.notFound().build();
            }
            Resource resource = new UrlResource(filePath.toUri());

            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.notFound().build();
            }

            // Determine content type
            String contentType = determineContentType(filename);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                    .body(resource);
        } catch (MalformedURLException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    private Path getDateDir() {
        LocalDate today = LocalDate.now();
        return uploadRoot
                .resolve(String.valueOf(today.getYear()))
                .resolve(String.format("%02d", today.getMonthValue()));
    }

    private String generateStoredFilename(String extension) {
        return UUID.randomUUID().toString().replace("-", "") + "." + extension;
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return null;
        }
        return filename.substring(filename.lastIndexOf('.') + 1);
    }

    private String determineContentType(String filename) {
        String ext = getExtension(filename);
        if (ext == null) return "application/octet-stream";
        return switch (ext.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }

    /**
     * 文件头魔数签名校验（content-aware），避免仅凭扩展名信任用户文件名。
     * 注意：这是文件头签名校验，不是完整图片解码；能拦截伪造扩展名与伪装内容，
     * 但不保证图片完整可正常渲染。jpg/jpeg 校验 SOI(FF D8 FF)，png 校验 8 字节签名，
     * webp 校验 RIFF + WEBP 标识。
     */
    private boolean isValidImageContent(byte[] content, String extension) {
        if (content == null || content.length < 12) {
            return false;
        }
        return switch (extension) {
            case "jpg", "jpeg" ->
                (content[0] & 0xFF) == 0xFF
                && (content[1] & 0xFF) == 0xD8
                && (content[2] & 0xFF) == 0xFF;
            case "png" ->
                (content[0] & 0xFF) == 0x89
                && (content[1] & 0xFF) == 0x50
                && (content[2] & 0xFF) == 0x4E
                && (content[3] & 0xFF) == 0x47
                && (content[4] & 0xFF) == 0x0D
                && (content[5] & 0xFF) == 0x0A
                && (content[6] & 0xFF) == 0x1A
                && (content[7] & 0xFF) == 0x0A;
            case "webp" ->
                // RIFF....WEBP
                content[0] == 'R' && content[1] == 'I' && content[2] == 'F' && content[3] == 'F'
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P';
            default -> false;
        };
    }
}
