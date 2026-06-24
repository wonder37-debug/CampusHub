package com.campushub.backend.api;

import com.campushub.backend.common.api.ApiResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
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

@RestController
@RequestMapping("/api/v1")
public class FileUploadController {

    private final Path uploadRoot;

    /** Allowed image extensions */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    /** Max single file size: 5 MB */
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    /** Max files per upload request */
    private static final int MAX_FILES_PER_REQUEST = 6;

    public FileUploadController(@Value("${app.upload.dir:uploads}") String uploadDir) {
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadRoot);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create upload directory: " + this.uploadRoot, e);
        }
    }

    /**
     * Upload single or multiple image files.
     * Returns a list of accessible URLs.
     */
    @PostMapping("/upload/images")
    public ApiResponse<Map<String, Object>> uploadImages(@RequestParam("files") List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请选择至少一张图片");
        }
        if (files.size() > MAX_FILES_PER_REQUEST) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "单次最多上传 " + MAX_FILES_PER_REQUEST + " 张图片");
        }

        List<String> urls = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }

            // Validate file size
            if (file.getSize() > MAX_FILE_SIZE) {
                errors.add(file.getOriginalFilename() + " 超过 5MB 限制");
                continue;
            }

            // Validate extension
            String originalFilename = file.getOriginalFilename();
            String extension = getExtension(originalFilename);
            if (extension == null || !ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
                errors.add(originalFilename + " 格式不支持，仅支持 jpg/png/webp");
                continue;
            }

            try {
                String storedFilename = generateStoredFilename(extension);
                Path dateDir = getDateDir();
                Files.createDirectories(dateDir);
                Path targetPath = dateDir.resolve(storedFilename);
                Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

                // Build accessible URL: /api/v1/uploads/YYYY/MM/filename
                String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
                urls.add("/api/v1/uploads/" + datePath + "/" + storedFilename);
            } catch (IOException e) {
                errors.add(originalFilename + " 上传失败: " + e.getMessage());
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
     */
    @GetMapping("/uploads/{year}/{month}/{filename}")
    public ResponseEntity<Resource> serveFile(
            @PathVariable String year,
            @PathVariable String month,
            @PathVariable String filename) {
        try {
            Path filePath = uploadRoot.resolve(year).resolve(month).resolve(filename).normalize();
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
        return UUID.randomUUID().toString().replace("-", "") + "." + extension.toLowerCase();
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
}
