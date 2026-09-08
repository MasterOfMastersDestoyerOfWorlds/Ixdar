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
import java.net.BindException;
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
    /** The only interface the automation server is ever exposed on. */
    public static final String LOOPBACK_HOST = "127.0.0.1";
    public static final int NUM_405 = 405;
    public static final int NUM_500 = 500;
    public static final int NUM_200 = 200;

    private final AutomationRuntime runtime;
    private final int port;
    private final HttpServer server;
    private final ExecutorService executor;

    /**
     * Bind a new HTTP server on loopback and register every route discovered via
     * {@link AutomationRouteMap}. A taken port falls back to a free one, so a second
     * scene never fails to start; {@link #port()} reports what was bound.
     *
     * @param runtime shared editor state passed to each route handler
     * @param requestedPort preferred loopback TCP port, or 0 to let the OS choose
     * @throws IOException if no socket can be bound at all
     */
    public AutomationApiServer(AutomationRuntime runtime, int requestedPort)
            throws IOException {
        this.runtime = runtime;
        HttpServer bound;
        try {
            bound = HttpServer.create(new InetSocketAddress(LOOPBACK_HOST, requestedPort), 0);
        } catch (BindException portTaken) {
            bound = HttpServer.create(new InetSocketAddress(LOOPBACK_HOST, 0), 0);
        }
        this.server = bound;
        this.port = bound.getAddress().getPort();
        this.executor = Executors.newCachedThreadPool(AutomationApiServer::newDaemonThread);
        this.server.setExecutor(executor);
        registerAll(server, runtime);
    }

    /**
     * A daemon worker for the request executor.
     *
     * @param work the executor's task
     * @return a daemon thread that will run it
     */
    private static Thread newDaemonThread(Runnable work) {
        Thread worker = new Thread(work, "automation-http");
        worker.setDaemon(true);
        return worker;
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
