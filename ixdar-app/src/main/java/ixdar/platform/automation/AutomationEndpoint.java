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
     * TODO: document {@code readBodyJson}.
     *
     * @param exchange TODO: describe
     * @throws IOException TODO: describe
     * @return TODO: describe
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
     * TODO: document {@code writeError}.
     *
     * @param exchange TODO: describe
     * @param statusCode TODO: describe
     * @param message TODO: describe
     * @throws IOException TODO: describe
     * @return TODO: describe
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
     * TODO: document {@code writeJson}.
     *
     * @param exchange TODO: describe
     * @param payload TODO: describe
     * @throws IOException TODO: describe
     * @return TODO: describe
     */
    public JsonObject writeJson(HttpExchange exchange, JsonObject payload)
        throws IOException {
        return writeJson(exchange, NUM_200, payload);
    }

    /**
     * TODO: document {@code writeJson}.
     *
     * @param exchange TODO: describe
     * @param code TODO: describe
     * @param payload TODO: describe
     * @throws IOException TODO: describe
     * @return TODO: describe
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
     * TODO: document {@code normalizeX}.
     *
     * @param x TODO: describe
     * @return TODO: describe
     */
    public float normalizeX(float x) {
        int w = Math.max(1, Platforms.get().getWindowWidth());
        return x / w;
    }

    /**
     * TODO: document {@code normalizeY}.
     *
     * @param y TODO: describe
     * @return TODO: describe
     */
    public float normalizeY(float y) {
        int h = Math.max(1, Platforms.get().getWindowHeight());
        return y / h;
    }

    /**
     * TODO: document {@code denormalizeX}.
     *
     * @param x TODO: describe
     * @return TODO: describe
     */
    public float denormalizeX(float x) {
        return x * Platforms.get().getWindowWidth();
    }

    /**
     * TODO: document {@code denormalizeY}.
     *
     * @param y TODO: describe
     * @return TODO: describe
     */
    public float denormalizeY(float y) {
        return y * Platforms.get().getWindowHeight();
    }

    /**
     * TODO: document {@code imageBytes}.
     *
     * @param image TODO: describe
     * @throws IOException TODO: describe
     * @return TODO: describe
     */
    public byte[] imageBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        return baos.toByteArray();
    }

    /**
     * TODO: document {@code sha256}.
     *
     * @param bytes TODO: describe
     * @throws Exception TODO: describe
     * @return TODO: describe
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
     * TODO: document {@code resolvePath}.
     *
     * @param path TODO: describe
     * @return TODO: describe
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
