package com.project.migration.domain.model.media;

import com.project.migration.domain.model.Checks;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * Binary image (screenshot) passed to a vision LLM or returned by an MCP server.
 * The byte array is defensively copied on the way in and out.
 */
public record ImageAttachment(byte[] data, String mimeType) {

    public static final String PNG = "image/png";

    public ImageAttachment {
        Checks.notNull(data, "data");
        if (data.length == 0) {
            throw new IllegalArgumentException("data must not be empty");
        }
        Checks.notBlank(mimeType, "mimeType");
        data = data.clone();
    }

    public static ImageAttachment png(byte[] data) {
        return new ImageAttachment(data, PNG);
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    public int sizeBytes() {
        return data.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ImageAttachment that
                && mimeType.equals(that.mimeType)
                && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(data) + mimeType.hashCode();
    }

    @Override
    public String toString() {
        String head = HexFormat.of().formatHex(data, 0, Math.min(8, data.length));
        return "ImageAttachment[mimeType=" + mimeType + ", size=" + data.length + "B, head=" + head + "]";
    }
}
