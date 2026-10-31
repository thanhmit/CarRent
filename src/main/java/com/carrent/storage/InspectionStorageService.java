package com.carrent.storage;

import com.carrent.common.exception.BusinessException;
import com.carrent.common.exception.NotFoundException;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class InspectionStorageService {
    private final Path root;

    public InspectionStorageService(@Value("${carrent.inspection.storage-root:private-uploads/inspections}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public record StoredImage(String storageKey, String mediaType, int sizeBytes) {}

    public StoredImage store(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > 5L * 1024 * 1024) {
            throw new BusinessException("Vui lòng chọn ảnh từ 1 byte đến 5 MiB.");
        }
        Path destination = null;
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0 || bytes.length > 5 * 1024 * 1024) {
                throw new BusinessException("Vui lòng chọn ảnh từ 1 byte đến 5 MiB.");
            }
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new BusinessException("Tệp tải lên không phải ảnh JPEG hoặc PNG hợp lệ.");
                var reader = readers.next();
                String format;
                try {
                    reader.setInput(input, true, true);
                    format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!format.equals("png") && !format.equals("jpeg")) {
                        throw new BusinessException("Ảnh biên bản chỉ hỗ trợ JPEG và PNG.");
                    }
                    if ((long) reader.getWidth(0) * reader.getHeight(0) > 20_000_000) {
                        throw new BusinessException("Ảnh không được vượt quá 20 triệu pixel.");
                    }
                    if (reader.read(0) == null) throw new BusinessException("Không thể đọc nội dung ảnh.");
                } finally {
                    reader.dispose();
                }
                Files.createDirectories(root);
                String key = UUID.randomUUID() + (format.equals("png") ? ".png" : ".jpg");
                destination = root.resolve(key);
                Files.write(destination, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
                return new StoredImage(key, format.equals("png") ? "image/png" : "image/jpeg", Math.toIntExact(Files.size(destination)));
            }
        } catch (IOException | RuntimeException failure) {
            if (destination != null) {
                try { Files.deleteIfExists(destination); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            if (failure instanceof BusinessException business) throw business;
            throw new BusinessException("Không thể lưu ảnh biên bản. Vui lòng kiểm tra tệp và thử lại.");
        }
    }

    public Resource read(String storageKey) {
        Path file = resolve(storageKey);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new NotFoundException("Ảnh không tồn tại.");
        return new FileSystemResource(file);
    }

    public void delete(String storageKey) {
        try { Files.deleteIfExists(resolve(storageKey)); }
        catch (IOException failure) { throw new BusinessException("Không thể xóa ảnh biên bản."); }
    }

    private Path resolve(String key) {
        if (key == null || !key.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.(jpg|png)")) {
            throw new NotFoundException("Ảnh không tồn tại.");
        }
        return root.resolve(key);
    }
}
