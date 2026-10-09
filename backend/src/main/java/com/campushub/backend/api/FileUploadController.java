package com.campushub.backend.api;

import com.campushub.backend.common.api.ApiResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.common.security.CurrentUser;
import com.campushub.backend.common.security.RequestUserExtractor;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.upload.repository.UploadedAssetRepository;
import com.campushub.backend.upload.repository.entity.UploadedAssetEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
 *   <li>{@code GET /api/v1/uploads/{year}/{month}/{filename}} —— 公开图片匿名访问；
 *       私密图片（凭证等）要求服务端鉴权，仅上传者、订单参与者和管理员可读取。</li>
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
    private final OrderRepository orderRepository;

    public FileUploadController(
        @Value("${app.upload.dir:uploads}") String uploadDir,
        @Value("${app.upload.max-file-size-bytes:10485760}") long maxFileSize,
        RequestUserExtractor requestUserExtractor,
        UploadedAssetRepository uploadedAssetRepository,
        OrderRepository orderRepository
    ) {
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.maxFileSize = maxFileSize;
        this.requestUserExtractor = requestUserExtractor;
        this.uploadedAssetRepository = uploadedAssetRepository;
        this.orderRepository = orderRepository;
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
     * 拒绝并返回 401。不要通过前端隐藏上传按钮代替后端权限控制。</p>
     *
     * @param purpose 图片用途："demand"（默认，公开）或 "proof"（凭证，私密）
     */
    @PostMapping("/upload/images")
    public ApiResponse<Map<String, Object>> uploadImages(
        HttpServletRequest request,
        @RequestParam("files") List<MultipartFile> files,
        @RequestParam(value = "purpose", required = false, defaultValue = "demand") String purpose
    ) {
        var currentUser = requestUserExtractor.requireCurrentUser(request);

        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请选择至少一张图片");
        }
        if (files.size() > MAX_FILES_PER_REQUEST) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "单次最多上传 " + MAX_FILES_PER_REQUEST + " 张图片");
        }

        boolean isPrivate = "proof".equalsIgnoreCase(purpose);

        List<String> urls = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (MultipartFile file : files) {
            String originalFilename = file.getOriginalFilename();

            if (file.isEmpty()) {
                errors.add((originalFilename == null ? "文件" : originalFilename) + " 为空文件");
                continue;
            }

            if (file.getSize() > maxFileSize) {
                errors.add((originalFilename == null ? "文件" : originalFilename) + " 超过 " + (maxFileSize / (1024 * 1024)) + "MB 限制");
                continue;
            }

            String extension = getExtension(originalFilename);
            if (extension == null || !ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
                errors.add((originalFilename == null ? "文件" : originalFilename) + " 格式不支持，仅支持 jpg/png/webp");
                continue;
            }
            String normalizedExtension = extension.toLowerCase();

            String contentType = file.getContentType();
            if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
                errors.add((originalFilename == null ? "文件" : originalFilename) + " 的内容类型不被支持");
                continue;
            }

            try {
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
                if (!targetPath.startsWith(uploadRoot)) {
                    errors.add((originalFilename == null ? "文件" : originalFilename) + " 存储路径非法");
                    continue;
                }
                try (var in = file.getInputStream()) {
                    Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }

                String datePath = today.format(DateTimeFormatter.ofPattern("yyyy/MM"));
                String urlPath = "/api/v1/uploads/" + datePath + "/" + storedFilename;
                try {
                    uploadedAssetRepository.insert(new UploadedAssetEntity(storedFilename, urlPath, currentUser.userId(), isPrivate, null));
                    urls.add(urlPath);
                } catch (RuntimeException e) {
                    try {
                        Files.deleteIfExists(targetPath);
                    } catch (IOException deleteEx) {
                        // 文件清理失败不影响错误 URL 的返回（URL 未加入 urls）
                    }
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
     * <p>公开图片匿名访问（需求列表/详情等展示场景）；私密图片（凭证等）要求服务端鉴权，
     * 仅上传者、订单参与者（publisher/accepter）和管理员可读取。私密图片禁用公开缓存。
     * 鉴权仅接受 Authorization header，不接受 query parameter token，避免 JWT 泄露到 URL/日志。
     */
    @GetMapping("/uploads/{year}/{month}/{filename}")
    public ResponseEntity<Resource> serveFile(
            @PathVariable String year,
            @PathVariable String month,
            @PathVariable String filename,
            HttpServletRequest request) {
        try {
            Path filePath = uploadRoot.resolve(year).resolve(month).resolve(filename).normalize();
            if (!filePath.startsWith(uploadRoot)) {
                return ResponseEntity.notFound().build();
            }
            Resource resource = new UrlResource(filePath.toUri());

            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.notFound().build();
            }

            String contentType = determineContentType(filename);

            String urlPath = "/api/v1/uploads/" + year + "/" + month + "/" + filename;
            UploadedAssetEntity asset = uploadedAssetRepository.findByUrlPath(urlPath);

            if (asset != null && Boolean.TRUE.equals(asset.getIsPrivate())) {
                CurrentUser currentUser = requestUserExtractor.tryExtract(request);
                if (currentUser == null) {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
                }
                if (!canAccessPrivateAsset(currentUser, asset)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType))
                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                        .body(resource);
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                    .body(resource);
        } catch (MalformedURLException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    private boolean canAccessPrivateAsset(CurrentUser currentUser, UploadedAssetEntity asset) {
        if (currentUser.isAdmin()) {
            return true;
        }
        if (asset.getUploaderId() != null && asset.getUploaderId().equals(currentUser.userId())) {
            return true;
        }
        if (asset.getBoundOrderId() != null) {
            Order order = orderRepository.findById(asset.getBoundOrderId()).orElse(null);
            if (order != null && order.isParticipant(currentUser.userId())) {
                return true;
            }
        }
        return false;
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
                content[0] == 'R' && content[1] == 'I' && content[2] == 'F' && content[3] == 'F'
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P';
            default -> false;
        };
    }
}
