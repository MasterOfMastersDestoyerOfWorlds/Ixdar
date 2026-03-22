package ixdar.platform.automation;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class AutomationApiServer {
    private static final Gson GSON = new Gson();

    private final AutomationRuntime runtime;
    private final int port;
    private final HttpServer server;
    private final ExecutorService executor;

    public AutomationApiServer(AutomationRuntime runtime, int port) throws IOException {
        this.runtime = runtime;
        this.port = port;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.executor = Executors.newCachedThreadPool();
        this.server.setExecutor(executor);
        routes();
    }

    public int port() {
        return port;
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void routes() {
        server.createContext("/health", exchange -> {
            if (!requireMethod(exchange, "GET")) {
                return;
            }
            writeJson(exchange, runtime.health());
        });
        server.createContext("/ui/state", exchange -> {
            if (!requireMethod(exchange, "GET")) {
                return;
            }
            writeJson(exchange, runtime.uiState());
        });
        server.createContext("/ui/mesh/fingerprint", exchange -> {
            if (!requireMethod(exchange, "GET")) {
                return;
            }
            writeJson(exchange, runtime.meshFingerprint());
        });
        server.createContext("/ui/screenshot", this::screenshotHandler);
        server.createContext("/input/click", this::clickHandler);
        server.createContext("/input/hover", this::hoverHandler);
        server.createContext("/input/hover/clear", this::hoverClearHandler);
        server.createContext("/input/scroll", this::scrollHandler);
        server.createContext("/input/key", this::keyHandler);
        server.createContext("/input/type", this::typeHandler);
        server.createContext("/shutdown", this::shutdownHandler);
        server.createContext("/record/start", this::recordStartHandler);
        server.createContext("/record/stop", this::recordStopHandler);
        server.createContext("/record/status", exchange -> {
            if (!requireMethod(exchange, "GET")) {
                return;
            }
            writeJson(exchange, runtime.recorder().status());
        });
        server.createContext("/replay/start", this::replayStartHandler);
        server.createContext("/replay/status", this::replayStatusHandler);
        server.createContext("/replay/pause", this::replayPauseHandler);
        server.createContext("/replay/resume", this::replayResumeHandler);
        server.createContext("/replay/cancel", this::replayCancelHandler);
    }

    private void screenshotHandler(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        JsonObject body = readBodyJson(exchange);
        String outputPath = body.has("path") ? body.get("path").getAsString() : "";
        boolean inline = body.has("inline") && body.get("inline").getAsBoolean();
        try {
            writeJson(exchange, runtime.captureScreenshot(outputPath, inline));
        } catch (Exception e) {
            writeError(exchange, 500, e.getMessage());
        }
    }

    private void clickHandler(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            JsonObject body = readBodyJson(exchange);
            float x = body.has("x") ? body.get("x").getAsFloat() : 0f;
            float y = body.has("y") ? body.get("y").getAsFloat() : 0f;
            boolean normalized = body.has("normalized") && body.get("normalized").getAsBoolean();
            int button = body.has("button") ? body.get("button").getAsInt() : 0;
            writeJson(exchange, runtime.injectClick(x, y, normalized, button));
        } catch (Exception e) {
            writeError(exchange, 500, e.getMessage());
        }
    }

    private void scrollHandler(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            JsonObject body = readBodyJson(exchange);
            double delta = body.has("delta") ? body.get("delta").getAsDouble() : 0;
            writeJson(exchange, runtime.injectScroll(delta));
        } catch (Exception e) {
            writeError(exchange, 500, e.getMessage());
        }
    }

    private void hoverHandler(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            JsonObject body = readBodyJson(exchange);
            float x = body.has("x") ? body.get("x").getAsFloat() : 0f;
            float y = body.has("y") ? body.get("y").getAsFloat() : 0f;
            boolean normalized = body.has("normalized") && body.get("normalized").getAsBoolean();
            boolean persistent = !body.has("persistent") || body.get("persistent").getAsBoolean();
            writeJson(exchange, runtime.injectHover(x, y, normalized, persistent));
        } catch (Exception e) {
            writeError(exchange, 500, e.getMessage());
        }
    }

    private void hoverClearHandler(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        writeJson(exchange, runtime.clearHoverLock());
    }

    private void keyHandler(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            JsonObject body = readBodyJson(exchange);
            int key = body.has("key") ? body.get("key").getAsInt() : 0;
            int action = body.has("action") ? body.get("action").getAsInt() : 1;
            int mods = body.has("mods") ? body.get("mods").getAsInt() : 0;
            int scancode = body.has("scancode") ? body.get("scancode").getAsInt() : 0;
            writeJson(exchange, runtime.injectKey(key, action, mods, scancode));
        } catch (Exception e) {
            writeError(exchange, 500, e.getMessage());
        }
    }

    private void typeHandler(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            JsonObject body = readBodyJson(exchange);
            String text = body.has("text") ? body.get("text").getAsString() : "";
            writeJson(exchange, runtime.injectType(text));
        } catch (Exception e) {
            writeError(exchange, 500, e.getMessage());
        }
    }

    private void recordStartHandler(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        runtime.recorder().start();
        JsonObject result = runtime.recorder().status();
        result.addProperty("ok", true);
        writeJson(exchange, result);
    }

    private void recordStopHandler(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        JsonObject body = readBodyJson(exchange);
        String path = body.has("path") ? body.get("path").getAsString() : "";
        try {
            writeJson(exchange, runtime.recorder().stop(path));
        } catch (Exception e) {
            writeError(exchange, 500, e.getMessage());
        }
    }

    private void replayStartHandler(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            JsonObject body = readBodyJson(exchange);
            String file = body.has("file") ? body.get("file").getAsString() : "";
            String mode = body.has("mode") ? body.get("mode").getAsString() : "abstract";
            AutomationReplayEngine.ReplayMode replayMode = "raw".equalsIgnoreCase(mode) ? AutomationReplayEngine.ReplayMode.RAW
                    : AutomationReplayEngine.ReplayMode.ABSTRACT;
            boolean started = runtime.replayEngine().startReplay(file, replayMode);
            JsonObject result = new JsonObject();
            result.addProperty("ok", started);
            result.addProperty("mode", replayMode.name().toLowerCase());
            writeJson(exchange, result);
        } catch (Exception e) {
            writeError(exchange, 500, e.getMessage());
        }
    }

    private void replayStatusHandler(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        JsonObject result = new JsonObject();
        result.addProperty("replaying", runtime.replayEngine().isReplaying());
        result.addProperty("status", runtime.replayEngine().getLastReplayStatus());
        result.addProperty("file", runtime.replayEngine().getLastReplayFile());
        result.addProperty("paused", runtime.replayEngine().isPaused());
        writeJson(exchange, result);
    }

    private void replayPauseHandler(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        JsonObject result = new JsonObject();
        runtime.replayEngine().pause();
        result.addProperty("ok", true);
        result.addProperty("paused", true);
        writeJson(exchange, result);
    }

    private void replayResumeHandler(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        JsonObject result = new JsonObject();
        runtime.replayEngine().resume();
        result.addProperty("ok", true);
        result.addProperty("paused", runtime.replayEngine().isPaused());
        writeJson(exchange, result);
    }

    private void replayCancelHandler(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        JsonObject result = new JsonObject();
        runtime.replayEngine().cancel();
        result.addProperty("ok", true);
        writeJson(exchange, result);
    }

    private void shutdownHandler(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        writeJson(exchange, runtime.requestShutdown());
    }

    private JsonObject readBodyJson(HttpExchange exchange) throws IOException {
        InputStream bodyStream = exchange.getRequestBody();
        if (bodyStream == null) {
            return new JsonObject();
        }
        String body = new String(bodyStream.readAllBytes(), StandardCharsets.UTF_8);
        if (body.isBlank()) {
            return new JsonObject();
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private void writeError(HttpExchange exchange, int statusCode, String message) throws IOException {
        JsonObject error = new JsonObject();
        error.addProperty("ok", false);
        error.addProperty("error", message == null ? "" : message);
        writeJson(exchange, statusCode, error);
    }

    private void writeJson(HttpExchange exchange, JsonObject payload) throws IOException {
        writeJson(exchange, 200, payload);
    }

    private void writeJson(HttpExchange exchange, int code, JsonObject payload) throws IOException {
        byte[] response = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private boolean requireMethod(HttpExchange exchange, String expected) throws IOException {
        if (expected.equalsIgnoreCase(exchange.getRequestMethod())) {
            return true;
        }
        writeError(exchange, 405, "Method not allowed; expected " + expected);
        return false;
    }
}
