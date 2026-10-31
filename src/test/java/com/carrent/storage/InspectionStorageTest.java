package com.carrent.storage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import com.carrent.common.exception.BusinessException;
import com.carrent.common.exception.NotFoundException;
import java.nio.file.Files;
import java.util.zip.CRC32;
import java.nio.ByteBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionStorageTest {
    @TempDir Path root;

    @Test
    void storesValidImageUsingAPrivateGeneratedKeyAndCanReadAndDeleteIt() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var storage = new InspectionStorageService(root.toString());
        var stored = storage.store(new MockMultipartFile("images", "../../client.png", "image/png", bytes.toByteArray()));
        assertThat(stored.storageKey()).matches("[a-f0-9-]{36}\\.png");
        assertThat(stored.mediaType()).isEqualTo("image/png");
        assertThat(storage.read(stored.storageKey()).getContentAsByteArray()).isEqualTo(bytes.toByteArray());
        storage.delete(stored.storageKey());
        assertThat(root.resolve(stored.storageKey())).doesNotExist();
    }

    @Test void rejectsMissingEmptyOrOversizedFilesWithoutCreatingFiles() throws Exception {
        var storage = new InspectionStorageService(root.toString());
        assertThatThrownBy(() -> storage.store(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.store(new MockMultipartFile("images", new byte[0])))
                .isInstanceOf(BusinessException.class);
        var bytes = image("png");
        var oversized = java.util.Arrays.copyOf(bytes, 5 * 1024 * 1024 + 1);
        assertThatThrownBy(() -> storage.store(new MockMultipartFile("images", "a.png", "image/png", oversized)))
                .isInstanceOf(BusinessException.class);
        try (var files = Files.list(root)) { assertThat(files.count()).isZero(); }
    }

    @Test void rejectsLargeDimensionsBeforeDecodingPixelData() throws Exception {
        byte[] bytes = image("png");
        ByteBuffer.wrap(bytes).putInt(16, 10000).putInt(20, 10000);
        var crc = new CRC32(); crc.update(bytes, 12, 17);
        ByteBuffer.wrap(bytes).putInt(29, (int) crc.getValue());
        assertThatThrownBy(() -> new InspectionStorageService(root.toString()).store(
                new MockMultipartFile("images", "a.png", "image/png", bytes)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("20 triệu");
    }

    @Test void acceptsJpegAndTheExactByteLimitBasedOnDecodedContent() throws Exception {
        var storage = new InspectionStorageService(root.toString());
        var jpeg = storage.store(new MockMultipartFile("images", "wrong.txt", "text/plain", image("jpeg")));
        assertThat(jpeg.mediaType()).isEqualTo("image/jpeg");
        assertThat(jpeg.storageKey()).endsWith(".jpg");
        var padded = java.util.Arrays.copyOf(image("png"), 5 * 1024 * 1024);
        assertThat(storage.store(new MockMultipartFile("images", padded)).sizeBytes()).isEqualTo(5242880);
    }

    @Test void rejectsFakeAndTruncatedContent() throws Exception {
        var storage = new InspectionStorageService(root.toString());
        for (byte[] bytes : new byte[][]{"<script>alert(1)</script>".getBytes(),
                java.util.Arrays.copyOf(image("png"), 40), image("gif")}) {
            assertThatThrownBy(() -> storage.store(new MockMultipartFile("images", "a.png", "image/png", bytes)))
                    .isInstanceOf(BusinessException.class);
        }
        try (var files = Files.list(root)) { assertThat(files.count()).isZero(); }
    }

    @ParameterizedTest @ValueSource(strings={"../secret.png", "..\\secret.png", "/tmp/a.png", "", "client.png"})
    void rejectsNonServerKeys(String key) {
        var storage = new InspectionStorageService(root.toString());
        assertThatThrownBy(() -> storage.read(key)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(NotFoundException.class);
    }

    private static byte[] image(String format) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }
}
