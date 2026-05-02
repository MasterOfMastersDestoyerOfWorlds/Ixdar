package ixdar.platform.automation.endpoints.mesh.skeleton;

import java.io.File;
import java.io.IOException;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

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
    @Override
    public JsonObject endpointHandler(HttpExchange exchange) throws IOException {
        try {
            JsonObject body = readBodyJson(exchange);
            String dslName = body.has("dsl") ? body.get("dsl").getAsString() : "";
            String referencePath = body.has("reference") ? body.get("reference").getAsString() : "";
            int resolution = body.has("resolution") ? body.get("resolution").getAsInt() : 128;
            float epsilon = body.has("epsilon") ? body.get("epsilon").getAsFloat() : 0;
            if (dslName.isEmpty() || referencePath.isEmpty()) {
                return writeError(exchange, 400, "Provide 'dsl' name and 'reference' OBJ path");
            }
            try {
                // Resolve DSL file — try multiple locations
                String dslFileName = dslName;
                if (!dslFileName.endsWith(".dsl"))
                    dslFileName += ".dsl";
                java.nio.file.Path dslPath = null;
                for (String base : new String[] {
                        "src/main/resources/dsl",
                        "ixdar-app/src/main/resources/dsl",
                }) {
                    java.nio.file.Path candidate = java.nio.file.Path.of(
                            System.getProperty("user.dir"),
                            base,
                            dslFileName);
                    if (java.nio.file.Files.exists(candidate)) {
                        dslPath = candidate;
                        break;
                    }
                }
                if (dslPath == null) {
                    JsonObject err = new JsonObject();
                    err.addProperty("ok", false);
                    err.addProperty(
                            "error",
                            "DSL file not found: " +
                                    dslFileName +
                                    " (searched from " +
                                    System.getProperty("user.dir") +
                                    ")");
                    return err;
                }

                // Resolve reference mesh
                File refFile = new File(referencePath);
                if (!refFile.isAbsolute())
                    refFile = new File(
                            System.getProperty("user.dir"),
                            referencePath);
                if (!refFile.exists()) {
                    JsonObject err = new JsonObject();
                    err.addProperty("ok", false);
                    err.addProperty(
                            "error",
                            "Reference mesh not found: " + refFile.getAbsolutePath());
                    return err;
                }

                String source = java.nio.file.Files.readString(dslPath);
                List<PythonParser.ParsedNode> parsed = new PythonParser(
                        new PythonLexer(source)).parseGraph();

                SkeletonSensitivityAnalyzer.SensitivityResult sensResult = SkeletonSensitivityAnalyzer
                        .analyze(
                                parsed,
                                refFile.getAbsolutePath(),
                                resolution,
                                epsilon);
                JsonObject result = new JsonObject();
                result.addProperty("ok", true);
                result.addProperty("dsl", dslFileName);
                result.addProperty("reference", refFile.getAbsolutePath());
                result.addProperty("resolution", resolution);
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
                            .getOrDefault(param.overrideKey(), 0f);
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
                err.addProperty("ok", false);
                err.addProperty("error", e.getMessage());
                return err;
            }
        } catch (Exception e) {
            return writeError(exchange, 500, e.getMessage());
        }
    }
}