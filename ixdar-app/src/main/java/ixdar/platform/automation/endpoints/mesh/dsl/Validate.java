package ixdar.platform.automation.endpoints.mesh.dsl;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.geometry.mesh.documentation.ValidateDsl;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(path = "/mesh/dsl/validate", method = APIMethod.POST)
public class Validate extends AutomationEndpoint implements AutomationRoute {

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String dslSource = body.has("dsl") ? body.get("dsl").getAsString() : "";
        String exportPath = body.has("export")
                ? body.get("export").getAsString()
                : null;

        if (dslSource.isEmpty()) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", "Missing required field: dsl (DSL source text)");
            return err;
        }

        String skillDir = System.getProperty("user.home") + "/.ix/voyage/skills";
        java.util.Map<String, Object> result = ValidateDsl.validate(
                dslSource,
                skillDir,
                exportPath);
        return GSON.toJsonTree(result).getAsJsonObject();
    }
}
