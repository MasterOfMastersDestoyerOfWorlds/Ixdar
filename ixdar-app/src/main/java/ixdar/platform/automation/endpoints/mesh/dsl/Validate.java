package ixdar.platform.automation.endpoints.mesh.dsl;

import java.io.File;
import java.io.IOException;

import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.util.Map;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.geometry.mesh.documentation.ValidateDsl;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.automation.AutomationPortFile;

@AutomationRouteAnnotation(path = "/mesh/dsl/validate", method = APIMethod.POST)
public class Validate extends AutomationEndpoint implements AutomationRoute {
    public static final String DSL = "dsl";
    public static final String EXPORT = "export";
    /** Longest {@code dsl} value still worth testing as a filename. */
    public static final int MAX_PATH_LENGTH = 4096;

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String dslSource = body.has(DSL) ? body.get(DSL).getAsString() : "";
        String exportPath = body.has(EXPORT)
                ? body.get(EXPORT).getAsString()
                : null;

        if (dslSource.isEmpty()) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", "Missing required field: dsl (DSL source text or file path)");
            return err;
        }

        File sourceFile = dslFile(dslSource);
        if (sourceFile != null) {
            dslSource = Files.readString(sourceFile.toPath());
        }

        String skillDir = System.getProperty("user.home") + "/.ix/voyage/skills";
        Map<String, Object> result = ValidateDsl.validate(
                dslSource,
                skillDir,
                exportPath);
        JsonObject json = GSON.toJsonTree(result).getAsJsonObject();
        if (sourceFile != null) {
            json.addProperty("dslPath", sourceFile.getAbsolutePath());
        }
        return json;
    }

    /**
     * Interpret the {@code dsl} value as a filename when one exists, so callers can pass
     * either source text or a path. A relative path is tried against the working directory
     * and then the checkout root, so both spellings a caller might type resolve.
     *
     * @param dslValue the request's {@code dsl} field
     * @return the existing file it names, or {@code null} when it is source text
     */
    private static File dslFile(String dslValue) {
        if (dslValue.indexOf('\n') >= 0 || dslValue.length() > MAX_PATH_LENGTH) {
            return null;
        }
        File named = new File(dslValue);
        if (named.isAbsolute()) {
            return named.isFile() ? named : null;
        }
        File fromWorkingDirectory = new File(System.getProperty("user.dir"), dslValue);
        if (fromWorkingDirectory.isFile()) {
            return fromWorkingDirectory;
        }
        File fromCheckout = AutomationPortFile.checkoutRoot().resolve(dslValue).toFile();
        return fromCheckout.isFile() ? fromCheckout : null;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Validate DSL source text, or the contents of a .dsl file path, against the skill schema.")
                .param(DSL, RouteParamType.STRING, true, "",
                        "DSL source text, or a path to a .dsl file; relative paths resolve against the scene's working directory.",
                        "ixdar-app/src/main/resources/dsl/skull.dsl")
                .param(EXPORT, RouteParamType.STRING, false, "",
                        "Optional path to export the probed output mesh as OBJ.", "~/probe.obj")
                .responseHint("{valid, nodeCount, errors:[...], warnings:[...], meshProbe:{...}, dslPath?}")
                .build();
    }
}
