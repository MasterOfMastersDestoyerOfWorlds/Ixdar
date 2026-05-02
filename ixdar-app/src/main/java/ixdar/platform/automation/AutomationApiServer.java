package ixdar.platform.automation;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.AutomationRouteRegistry;
import ixdar.platform.automation.endpoints.AutomationRuntime;
import ixdar.scenes.mesh.MeshNodeViewerScene;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class AutomationApiServer {

    private final AutomationRuntime runtime;
    private final int port;
    private final HttpServer server;
    private final ExecutorService executor;

    public AutomationApiServer(AutomationRuntime runtime, int port)
            throws IOException {
        this.runtime = runtime;
        this.port = port;
        this.server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", port),
                0);
        this.executor = Executors.newCachedThreadPool();
        this.server.setExecutor(executor);
        registerAll(server, runtime);
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

    private static JsonObject readBodyJson(HttpExchange exchange) throws IOException {
        InputStream bodyStream = exchange.getRequestBody();
        if (bodyStream == null) {
            return new JsonObject();
        }
        String body = new String(
                bodyStream.readAllBytes(),
                StandardCharsets.UTF_8);
        if (body.isBlank()) {
            return new JsonObject();
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    static void registerAll(HttpServer server, AutomationRuntime runtime) {
        for (Supplier<? extends AutomationRoute> route : AutomationRouteMap.MAP.values()) {
            AutomationRoute automationRoute = route.get();
            AutomationRouteAnnotation ann = automationRoute.getClass().getAnnotation(AutomationRouteAnnotation.class);
            if (ann == null) {
                continue;
            }
            String path = ann.path();
            APIMethod method = ann.method();
            server.createContext(path, exchange -> handle(exchange, runtime, automationRoute, method));
        }
    }

    private static void handle(HttpExchange exchange, AutomationRuntime runtime, AutomationRoute route, APIMethod method)
            throws IOException {
        String want = method.name().toUpperCase();
        String got = exchange.getRequestMethod().toUpperCase();
        if (!want.equals(got)) {
            writeError(exchange, 405, "Method not allowed; expected " + want);
            return;
        }
        try {
            JsonObject body = readBodyJson(exchange);
            JsonObject result = route.endpointHandler(body);
            writeJson(exchange, result);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            writeError(exchange, 500, cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private static void writeJson(HttpExchange exchange, JsonObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private static void writeError(HttpExchange exchange, int status, String msg) throws IOException {
        JsonObject err = new JsonObject();
        err.addProperty("error", msg);
        byte[] bytes = err.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }
}
