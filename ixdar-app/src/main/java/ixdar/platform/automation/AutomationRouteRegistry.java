package ixdar.platform.automation;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ixdar.platform.automation.endpoints.AutomationRuntime;

/**
 * Registers methods annotated with {@link AutomationRoute} as HTTP handlers. A
 * handler method must return {@link JsonObject} and accept either () or
 * (JsonObject body). GET routes call the no-arg form; POST routes read and
 * parse the request body first.
 */
final class AutomationRouteRegistry {

    private AutomationRouteRegistry() {
    }

    static void registerAll(HttpServer server, AutomationRuntime runtime) {
        for (Method m : AutomationRuntime.class.getDeclaredMethods()) {
            AutomationRoute route = m.getAnnotation(AutomationRoute.class);
            if (route == null)
                continue;
            if (!JsonObject.class.isAssignableFrom(m.getReturnType())) {
                throw new IllegalStateException(
                        "@AutomationRoute method " + m.getName() + " must return JsonObject");
            }
            m.setAccessible(true);
            server.createContext(route.path(), exchange -> handle(exchange, runtime, m, route));
        }
    }

    private static void handle(HttpExchange exchange, AutomationRuntime runtime, Method m, AutomationRoute route)
            throws IOException {
        String want = route.method().toUpperCase();
        String got = exchange.getRequestMethod().toUpperCase();
        if (!want.equals(got)) {
            writeError(exchange, 405, "Method not allowed; expected " + want);
            return;
        }
        try {
            Object result;
            if (m.getParameterCount() == 0) {
                result = m.invoke(runtime);
            } else if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == JsonObject.class) {
                JsonObject body = readBodyJson(exchange);
                result = m.invoke(runtime, body);
            } else {
                writeError(exchange, 500,
                        "@AutomationRoute method " + m.getName() + " has unsupported signature");
                return;
            }
            writeJson(exchange, (JsonObject) result);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            writeError(exchange, 500, cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private static JsonObject readBodyJson(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0)
                return new JsonObject();
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
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
