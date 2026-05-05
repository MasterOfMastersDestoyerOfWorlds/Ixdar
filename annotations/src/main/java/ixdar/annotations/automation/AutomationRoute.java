package ixdar.annotations.automation;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
/**
 * Implemented by classes annotated with {@link AutomationRouteAnnotation};
 * dispatched by {@code AutomationApiServer} when a matching HTTP request
 * arrives.
 */
public interface AutomationRoute {
    /**
     * Handle a single request and produce its JSON response.
     *
     * @param body parsed JSON request body (empty object for GET / no payload)
     * @throws Exception any failure; surfaces as a 500 response
     * @return JSON object to serialize back to the client
     */
    JsonObject endpointHandler(JsonObject body) throws Exception;

}
