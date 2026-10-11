package com.carrent.storage;

import com.carrent.common.exception.BusinessException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class StorageService {
    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private static final Path ROOT = Path.of("uploads").toAbsolutePath().normalize();
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final long MAX_SIZE = 5L * 1024 * 1024;

    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Vui lòng chọn ảnh để tải lên.");
        }
        String name = file.getOriginalFilename();
        String extension = name == null || !name.contains(".") ? "" : name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension)) {
            throw new BusinessException("Ảnh phải có định dạng JPG, JPEG, PNG hoặc WEBP.");
        }
        if (file.getContentType() == null || !file.getContentType().startsWith("image/")) {
            throw new BusinessException("Tệp tải lên phải là ảnh.");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException("Ảnh không được vượt quá 5 MB.");
        }
        // shortcut: metadata checks cannot prove image bytes are valid; add decoding if untrusted image processing is introduced.
    }

    public String store(MultipartFile file) {
        validate(file);
        String extension = file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        Path destination = ROOT.resolve(UUID.randomUUID() + "." + extension);
        try {
            Files.createDirectories(ROOT);
            try (var input = file.getInputStream()) {
                Files.copy(input, destination);
            }
            return "/uploads/" + destination.getFileName();
        } catch (IOException ex) {
            log.error("Could not store upload", ex);
            try {
                Files.deleteIfExists(destination);
            } catch (IOException cleanup) {
                log.error("Could not remove partial upload {}", destination, cleanup);
            }
            throw new BusinessException("Không thể lưu ảnh. Vui lòng thử lại.");
        }
    }

    public void delete(String webPath) {
        if (webPath == null || !webPath.startsWith("/uploads/")) {
            throw new BusinessException("Đường dẫn ảnh không hợp lệ.");
        }
        String name = webPath.substring("/uploads/".length());
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
            throw new BusinessException("Đường dẫn ảnh không hợp lệ.");
        }
        Path destination = ROOT.resolve(name).normalize();
        if (!destination.getParent().equals(ROOT)) {
            throw new BusinessException("Đường dẫn ảnh không hợp lệ.");
        }
        try {
            Files.deleteIfExists(destination);
        } catch (IOException ex) {
            log.error("Could not delete upload {}", destination, ex);
            throw new BusinessException("Không thể xóa ảnh. Vui lòng thử lại.");
        }
    }
}
