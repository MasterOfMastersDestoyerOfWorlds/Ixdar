package ixdar.automation;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;

import com.google.gson.Gson;

import ixdar.graphics.image.PixelImage;
import ixdar.graphics.image.PngWriter;
import ixdar.platform.Platforms;

public class AutomationEndpoint {
    public static final Gson GSON = new Gson();

    protected AutomationRuntime runtime;

    /**
     * Convert a pixel x-coordinate into a normalized {@code [0, 1]} fraction of the
     * current window width. Window width is clamped to at least 1 to avoid division
     * by zero.
     *
     * @param x pixel x-coordinate
     * @return {@code x / windowWidth}
     */
    public float normalizeX(float x) {
        int w = Math.max(1, Platforms.get().getWindowWidth());
        return x / w;
    }

    /**
     * Convert a pixel y-coordinate into a normalized {@code [0, 1]} fraction of the
     * current window height. Window height is clamped to at least 1 to avoid division
     * by zero.
     *
     * @param y pixel y-coordinate
     * @return {@code y / windowHeight}
     */
    public float normalizeY(float y) {
        int h = Math.max(1, Platforms.get().getWindowHeight());
        return y / h;
    }

    /**
     * Convert a normalized x-coordinate back to pixels using the current window width.
     *
     * @param x normalized x in {@code [0, 1]}
     * @return {@code x * windowWidth}
     */
    public float denormalizeX(float x) {
        return x * Platforms.get().getWindowWidth();
    }

    /**
     * Convert a normalized y-coordinate back to pixels using the current window height.
     *
     * @param y normalized y in {@code [0, 1]}
     * @return {@code y * windowHeight}
     */
    public float denormalizeY(float y) {
        return y * Platforms.get().getWindowHeight();
    }

    /**
     * Encode the given image as PNG bytes via {@link PngWriter}.
     *
     * @param image source image
     * @throws IOException if PNG encoding fails
     * @return PNG-encoded byte array
     */
    public byte[] imageBytes(PixelImage image) throws IOException {
        return PngWriter.toBytes(image);
    }

    /**
     * Compute the lowercase hexadecimal SHA-256 digest of {@code bytes}.
     *
     * @param bytes data to hash
     * @throws Exception if the SHA-256 algorithm is unavailable
     * @return 64-character lowercase hex digest
     */
    public String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte b : hash) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    // ==================== Patch decomposition + segmentation (annotation-driven)
    // ====================

    /**
     * Resolve a user-supplied path against the current working directory and verify
     * that it exists.
     *
     * @param path filesystem path; absolute paths are used as-is, relative paths are
     *             resolved against {@code user.dir}
     * @return existing {@link File}, or {@code null} when {@code path} is blank or
     *         the resolved file does not exist
     */
    public File resolvePath(String path) {
        if (path == null || path.isBlank())
            return null;
        File f = new File(path);
        if (!f.isAbsolute())
            f = new File(System.getProperty("user.dir"), path);
        return f.exists() ? f : null;
    }
}
