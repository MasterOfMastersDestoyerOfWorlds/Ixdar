package ixdar.annotations.automation;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
public interface AutomationRoute {
    JsonObject endpointHandler(HttpExchange exchange) throws Exception;
    
}
