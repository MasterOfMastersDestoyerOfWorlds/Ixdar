package ixdar.platform.automation;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.imageio.ImageIO;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;

import ixdar.platform.Platforms;
import ixdar.platform.automation.endpoints.AutomationRuntime;

public class AutomationEndpoint {
    public static final int NUM_200 = 200;
    public static final Gson GSON = new Gson();
    
    protected AutomationRuntime runtime;
    
    /**
     * Read the request body as a UTF-8 JSON object.
     *
     * @param exchange the HTTP exchange whose request body should be consumed
     * @throws IOException if reading the body stream fails
     * @return parsed object, or an empty object when the body is missing or blank
     */
    public JsonObject readBodyJson(HttpExchange exchange) throws IOException {
        InputStream bodyStream = exchange.getRequestBody();
        if (bodyStream == null) {
            return new JsonObject();
        }
        String body = new String(
            bodyStream.readAllBytes(),
            StandardCharsets.UTF_8
        );
        if (body.isBlank()) {
            return new JsonObject();
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    /**
     * Write a JSON error envelope ({@code {"ok": false, "error": message}}) and close
     * the exchange.
     *
     * @param exchange the HTTP exchange to respond on
     * @param statusCode HTTP status code to send
     * @param message human-readable error message; null is rendered as the empty string
     * @throws IOException if writing the response fails
     * @return the error payload that was sent
     */
    public JsonObject writeError(
        HttpExchange exchange,
        int statusCode,
        String message
    ) throws IOException {
        JsonObject error = new JsonObject();
        error.addProperty("ok", false);
        error.addProperty("error", message == null ? "" : message);
        return writeJson(exchange, statusCode, error);
    }

    /**
     * Convenience overload that writes {@code payload} with HTTP 200.
     *
     * @param exchange the HTTP exchange to respond on
     * @param payload JSON body to serialize
     * @throws IOException if writing the response fails
     * @return the same {@code payload} that was sent
     */
    public JsonObject writeJson(HttpExchange exchange, JsonObject payload)
        throws IOException {
        return writeJson(exchange, NUM_200, payload);
    }

    /**
     * Serialize {@code payload} as UTF-8 JSON, send it with the given status code, and
     * close the exchange.
     *
     * @param exchange the HTTP exchange to respond on
     * @param code HTTP status code to send
     * @param payload JSON body to serialize
     * @throws IOException if writing the response fails
     * @return the same {@code payload} that was sent
     */
    public JsonObject writeJson(HttpExchange exchange, int code, JsonObject payload)
        throws IOException {
        byte[] response = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange
            .getResponseHeaders()
            .set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
        return payload;
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
     * Encode the given image as PNG bytes via {@link ImageIO}.
     *
     * @param image source image
     * @throws IOException if PNG encoding fails
     * @return PNG-encoded byte array
     */
    public byte[] imageBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        return baos.toByteArray();
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
