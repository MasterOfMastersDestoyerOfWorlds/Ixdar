package ixdar.platform.automation.endpoints.mesh.skeleton;

import java.io.File;
import java.io.IOException;
import java.util.List;

import java.nio.file.Files;

import java.nio.file.Path;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.geometry.mesh.data.SkeletonSensitivityAnalyzer;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;
import ixdar.platform.automation.AutomationEndpoint;

/**
 * Compute skeleton sensitivity: Jacobian of joint positions w.r.t. DSL
 * parameters. Pure CPU — no GL context needed. May take 10-30 seconds depending
 * on parameter count.
 */
@AutomationRouteAnnotation(path = "/mesh/skeleton/sensitivity", method = APIMethod.POST)
public class Sensitivity extends AutomationEndpoint implements AutomationRoute {
    public static final String DSL = "dsl";
    public static final String REFERENCE = "reference";
    public static final String RESOLUTION = "resolution";
    public static final String EPSILON = "epsilon";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String DSL_2 = ".dsl";
    public static final String USER_DIR = "user.dir";
    public static final int NUM_128 = 128;
    public static final float NUM_0 = 0f;
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            String dslName = body.has(DSL) ? body.get(DSL).getAsString() : "";
            String referencePath = body.has(REFERENCE) ? body.get(REFERENCE).getAsString() : "";
            int resolution = body.has(RESOLUTION) ? body.get(RESOLUTION).getAsInt() : NUM_128;
            float epsilon = body.has(EPSILON) ? body.get(EPSILON).getAsFloat() : 0;
            if (dslName.isEmpty() || referencePath.isEmpty()) {
                JsonObject err = new JsonObject();
                err.addProperty(OK, false);
                err.addProperty(
                        ERROR,
                        "Provide 'dsl' name and 'reference' OBJ path");
                return err;
            }
            try {
                // Resolve DSL file — try multiple locations
                String dslFileName = dslName;
                if (!dslFileName.endsWith(DSL_2))
                    dslFileName += DSL_2;
                Path dslPath = null;
                for (String base : new String[] {
                        "src/main/resources/dsl",
                        "ixdar-app/src/main/resources/dsl",
                }) {
                    Path candidate = Path.of(
                            System.getProperty(USER_DIR),
                            base,
                            dslFileName);
                    if (Files.exists(candidate)) {
                        dslPath = candidate;
                        break;
                    }
                }
                if (dslPath == null) {
                    JsonObject err = new JsonObject();
                    err.addProperty(OK, false);
                    err.addProperty(
                            ERROR,
                            "DSL file not found: " +
                                    dslFileName +
                                    " (searched from " +
                                    System.getProperty(USER_DIR) +
                                    ")");
                    return err;
                }

                // Resolve reference mesh
                File refFile = new File(referencePath);
                if (!refFile.isAbsolute())
                    refFile = new File(
                            System.getProperty(USER_DIR),
                            referencePath);
                if (!refFile.exists()) {
                    JsonObject err = new JsonObject();
                    err.addProperty(OK, false);
                    err.addProperty(
                            ERROR,
                            "Reference mesh not found: " + refFile.getAbsolutePath());
                    return err;
                }

                String source = Files.readString(dslPath);
                List<PythonParser.ParsedNode> parsed = new PythonParser(
                        new PythonLexer(source)).parseGraph();

                SkeletonSensitivityAnalyzer.SensitivityResult sensResult = SkeletonSensitivityAnalyzer
                        .analyze(
                                parsed,
                                refFile.getAbsolutePath(),
                                resolution,
                                epsilon);
                JsonObject result = new JsonObject();
                result.addProperty(OK, true);
                result.addProperty(DSL, dslFileName);
                result.addProperty(REFERENCE, refFile.getAbsolutePath());
                result.addProperty(RESOLUTION, resolution);
                result.addProperty("baselineScore", sensResult.baselineScore());
                result.addProperty("projectedScore", sensResult.projectedScore());
                result.addProperty(
                        "parameterCount",
                        sensResult.parameters().size());
                result.addProperty("jointCount", sensResult.jointIndices().size());

                // Suggested deltas
                JsonObject deltas = new JsonObject();
                for (var entry : sensResult.suggestedDeltas().entrySet()) {
                    deltas.addProperty(entry.getKey(), entry.getValue());
                }
                result.add("suggestedDeltas", deltas);

                // Suggested new values
                JsonObject suggestedValues = new JsonObject();
                for (var param : sensResult.parameters()) {
                    float base = param.defaultValue();
                    float delta = sensResult
                            .suggestedDeltas()
                            .getOrDefault(param.overrideKey(), NUM_0);
                    suggestedValues.addProperty(param.overrideKey(), base + delta);
                }
                result.add("suggestedValues", suggestedValues);

                if (!sensResult.unstableParams().isEmpty()) {
                    JsonArray unstable = new JsonArray();
                    for (String p : sensResult.unstableParams())
                        unstable.add(p);
                    result.add("unstableParams", unstable);
                }

                return result;
            } catch (Exception e) {
                JsonObject err = new JsonObject();
                err.addProperty(OK, false);
                err.addProperty(ERROR, e.getMessage());
                return err;
            }
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            err.addProperty(ERROR, e.getMessage());
            return err;
        }
    }
}