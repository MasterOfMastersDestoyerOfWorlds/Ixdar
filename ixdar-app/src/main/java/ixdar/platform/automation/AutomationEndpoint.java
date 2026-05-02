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
    
    protected AutomationRuntime runtime;
    private static final Gson GSON = new Gson();
    
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

    public JsonObject writeJson(HttpExchange exchange, JsonObject payload)
        throws IOException {
        return writeJson(exchange, 200, payload);
    }

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
    
    public float normalizeX(float x) {
        int w = Math.max(1, Platforms.get().getWindowWidth());
        return x / w;
    }

    public float normalizeY(float y) {
        int h = Math.max(1, Platforms.get().getWindowHeight());
        return y / h;
    }

    public float denormalizeX(float x) {
        return x * Platforms.get().getWindowWidth();
    }

    public float denormalizeY(float y) {
        return y * Platforms.get().getWindowHeight();
    }

    public byte[] imageBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        return baos.toByteArray();
    }

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

    public File resolvePath(String path) {
        if (path == null || path.isBlank())
            return null;
        File f = new File(path);
        if (!f.isAbsolute())
            f = new File(System.getProperty("user.dir"), path);
        return f.exists() ? f : null;
    }
}
