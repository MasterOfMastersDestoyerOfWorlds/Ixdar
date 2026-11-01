package ixdar.automation;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.graphics.image.PixelImage;
import ixdar.graphics.image.PngWriter;
import ixdar.platform.Platforms;

public class AutomationEndpoint {
    public static final Gson GSON = new Gson();

    public static final String OK = "ok";

    public static final String ERROR = "error";

    public AutomationRuntime runtime;

    /**
     * Start a successful route response; callers append their fields after {@code ok}.
     *
     * @return {@code {"ok": true}}
     */
    public static JsonObject success() {
        JsonObject response = new JsonObject();
        response.addProperty(OK, true);
        return response;
    }

    /**
     * Build the failed route response every endpoint answers with; callers may append fields after
     * {@code error}.
     *
     * @param message what went wrong
     * @return {@code {"ok": false, "error": message}}
     */
    public static JsonObject failure(String message) {
        JsonObject response = new JsonObject();
        response.addProperty(OK, false);
        response.addProperty(ERROR, message);
        return response;
    }

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
     * Resolve a path a CLI flag names as every path resolves ({@link MeshLoader#resolve}: the
     * checkout, then the module and its resources) and check it exists.
     *
     * @param path absolute, relative to the checkout, or a resource name ({@code dsl/skull3/outlines.svg})
     * @return the existing file, absolute, or {@code null} when {@code path} is blank or names nothing
     */
    public static File resolvePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Path found = MeshLoader.resolve(path);
        return Files.exists(found) ? found.toFile() : null;
    }

    /**
     * The file a required CLI flag names, resolved as {@link #resolvePath} does.
     *
     * @param flag the flag, for the message ({@code --template})
     * @param path the flag's value
     * @throws FileNotFoundException naming the flag and path when it names nothing
     * @return the existing file, absolute
     */
    public static Path existingFile(String flag, String path) throws FileNotFoundException {
        File file = resolvePath(path);
        if (file == null) {
            throw new FileNotFoundException(flag + " " + path + " names no file (relative paths start at the "
                    + "checkout " + MeshLoader.checkoutRoot() + ")");
        }
        return file.toPath();
    }
}
