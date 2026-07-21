package ixdar.platform.automation.endpoints.mesh.dsl;

import java.io.IOException;

import com.google.gson.JsonObject;

import java.util.Map;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.geometry.mesh.documentation.ValidateDsl;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(path = "/mesh/dsl/validate", method = APIMethod.POST)
public class Validate extends AutomationEndpoint implements AutomationRoute {
    public static final String DSL = "dsl";
    public static final String EXPORT = "export";

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String dslSource = body.has(DSL) ? body.get(DSL).getAsString() : "";
        String exportPath = body.has(EXPORT)
                ? body.get(EXPORT).getAsString()
                : null;

        if (dslSource.isEmpty()) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", "Missing required field: dsl (DSL source text)");
            return err;
        }

        String skillDir = System.getProperty("user.home") + "/.ix/voyage/skills";
        Map<String, Object> result = ValidateDsl.validate(
                dslSource,
                skillDir,
                exportPath);
        return GSON.toJsonTree(result).getAsJsonObject();
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Validate DSL source text against the skill schema, optionally probing and exporting its output mesh.")
                .param(DSL, RouteParamType.STRING, true, "",
                        "DSL source text to validate.", "node loadMesh { path: \"a.obj\" }")
                .param(EXPORT, RouteParamType.STRING, false, "",
                        "Optional path to export the probed output mesh as OBJ.", "~/probe.obj")
                .responseHint("{valid, nodeCount, errors:[...], warnings:[...], meshProbe:{...}}")
                .build();
    }
}
