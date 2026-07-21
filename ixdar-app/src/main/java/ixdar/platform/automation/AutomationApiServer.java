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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class AutomationApiServer {
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String APPLICATION_JSON = "application/json";

    /** Leading separator required by {@code HttpServer.createContext} on every route path. */
    public static final String PATH_SEPARATOR = "/";
    public static final int NUM_405 = 405;
    public static final int NUM_500 = 500;
    public static final int NUM_200 = 200;

    private final AutomationRuntime runtime;
    private final int port;
    private final HttpServer server;
    private final ExecutorService executor;

    /**
     * Bind a new HTTP server to {@code 127.0.0.1:port}, install a cached-thread
     * executor, and register every route discovered via {@link AutomationRouteMap}.
     *
     * @param runtime shared editor state passed to each route handler
     * @param port loopback TCP port to listen on
     * @throws IOException if the socket cannot be bound
     */
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

    /**
     * The loopback port this server is bound to.
     *
     * @return port number passed to the constructor
     */
    public int port() {
        return port;
    }

    /**
     * Start the underlying {@link HttpServer} and begin accepting requests.
     */
    public void start() {
        server.start();
    }

    /**
     * Stop the HTTP server immediately and shut down the request executor.
     */
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
        Map<String, Map<String, AutomationRoute>> routesByPath = new HashMap<>();
        for (Supplier<? extends AutomationRoute> route : AutomationRouteMap.MAP.values()) {
            AutomationRoute automationRoute = route.get();
            AutomationRouteAnnotation ann = automationRoute.getClass().getAnnotation(AutomationRouteAnnotation.class);
            if (ann == null) {
                continue;
            }
            if (automationRoute instanceof AutomationEndpoint endpoint) {
                endpoint.runtime = runtime;
            }
            String path = ann.path();
            if (!path.startsWith(PATH_SEPARATOR)) {
                path = PATH_SEPARATOR + path;
            }
            String method = ann.method().name().toUpperCase();
            AutomationRoute previous = routesByPath.computeIfAbsent(path, key -> new HashMap<>())
                    .put(method, automationRoute);
            if (previous != null) {
                throw new IllegalStateException("Duplicate automation route for " + method + " " + path
                        + " claimed by " + previous.getClass().getName() + " and "
                        + automationRoute.getClass().getName() + "; two routes share one (path, method) slot.");
            }
        }
        for (Map.Entry<String, Map<String, AutomationRoute>> entry : routesByPath.entrySet()) {
            Map<String, AutomationRoute> byMethod = entry.getValue();
            server.createContext(entry.getKey(), exchange -> handle(exchange, byMethod));
        }
    }

    private static void handle(HttpExchange exchange, Map<String, AutomationRoute> byMethod)
            throws IOException {
        AutomationRoute route = byMethod.get(exchange.getRequestMethod().toUpperCase());
        if (route == null) {
            writeError(exchange, NUM_405, "Method not allowed; expected one of " + byMethod.keySet());
            return;
        }
        try {
            JsonObject body = readBodyJson(exchange);
            JsonObject result = route.endpointHandler(body);
            writeJson(exchange, result);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            writeError(exchange, NUM_500, cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private static void writeJson(HttpExchange exchange, JsonObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(CONTENT_TYPE, APPLICATION_JSON);
        exchange.sendResponseHeaders(NUM_200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private static void writeError(HttpExchange exchange, int status, String msg) throws IOException {
        JsonObject err = new JsonObject();
        err.addProperty("error", msg);
        byte[] bytes = err.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(CONTENT_TYPE, APPLICATION_JSON);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }
}
