package ixdar.platform.automation;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ixdar.platform.automation.endpoints.AutomationRuntime;
import ixdar.scenes.mesh.MeshNodeViewerScene;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
            0
        );
        this.executor = Executors.newCachedThreadPool();
        this.server.setExecutor(executor);
        AutomationRouteRegistry.registerAll(server, runtime);
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

    private JsonObject readBodyJson(HttpExchange exchange) throws IOException {
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

    private void writeJson(HttpExchange exchange, int code, JsonObject payload)
        throws IOException {
        byte[] response = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange
            .getResponseHeaders()
            .set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
