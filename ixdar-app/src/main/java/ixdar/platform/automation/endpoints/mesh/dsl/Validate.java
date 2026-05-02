package ixdar.platform.automation.endpoints.mesh.dsl;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import ixdar.annotations.automation.APIMethod;
import ixdar.geometry.mesh.documentation.ValidateDsl;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import java.io.IOException;

@AutomationRouteAnnotation(path = "/mesh/dsl/validate", method = APIMethod.POST)
public class Validate extends AutomationEndpoint implements AutomationRoute {

    @Override
    public JsonObject endpointHandler(HttpExchange exchange) throws IOException {
        JsonObject body = readBodyJson(exchange);
        String dslSource = body.has("dsl") ? body.get("dsl").getAsString() : "";
        String exportPath = body.has("export")
                ? body.get("export").getAsString()
                : null;

        if (dslSource.isEmpty()) {
            return writeError(
                    exchange,
                    400,
                    "Missing required field: dsl (DSL source text)");
        }

        String skillDir = System.getProperty("user.home") + "/.ix/voyage/skills";
        java.util.Map<String, Object> result = ValidateDsl.validate(
                dslSource,
                skillDir,
                exportPath);
        return writeJson(exchange, GSON.toJsonTree(result).getAsJsonObject());
    }
}
