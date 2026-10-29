package com.project.migration.infrastructure.report;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

/**
 * Failure screenshots as small inline JPEGs, so {@code dashboard.html} needs no other file. One embedder per report:
 * it stops embedding once the page would grow past the budget; the caller then links the file instead.
 */
final class ScreenshotEmbedder {

    static final long DEFAULT_BUDGET_BYTES = 5L * 1024 * 1024;
    static final int MAX_WIDTH = 640;
    private static final float QUALITY = 0.7f;

    private long remaining;

    ScreenshotEmbedder(long budgetBytes) {
        this.remaining = budgetBytes;
    }

    /** {@code data:} URI of the shrunken image, empty if it is unreadable or does not fit the budget. */
    Optional<String> embed(Path file) {
        try {
            BufferedImage source = ImageIO.read(Files.newInputStream(file));
            if (source == null) {
                return Optional.empty();
            }
            byte[] jpeg = encode(shrink(source));
            long encodedSize = jpeg.length * 4L / 3 + 32;
            if (encodedSize > remaining) {
                return Optional.empty();
            }
            remaining -= encodedSize;
            return Optional.of("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static BufferedImage shrink(BufferedImage source) {
        int width = Math.min(MAX_WIDTH, source.getWidth());
        int height = Math.max(1, Math.round(source.getHeight() * (width / (float) source.getWidth())));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, width, height, java.awt.Color.WHITE, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(QUALITY);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
