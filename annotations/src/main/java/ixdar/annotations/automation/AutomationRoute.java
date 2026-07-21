package ixdar.annotations.automation;

import com.google.gson.JsonObject;
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

    /**
     * Machine-readable documentation for this route: description, JSON body parameters, and response
     * shape. Serialized into the automation routes manifest and rendered by the CLI. The default
     * returns {@link RouteDoc#empty()} so un-migrated routes still export.
     *
     * @return this route's documentation; never {@code null}
     */
    default RouteDoc describe() {
        return RouteDoc.empty();
    }
}
