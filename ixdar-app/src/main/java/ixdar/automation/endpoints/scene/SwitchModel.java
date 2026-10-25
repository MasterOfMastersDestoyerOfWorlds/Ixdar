package ixdar.automation.endpoints.scene;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.models.ModelCatalog;
import ixdar.models.ModelChoice;
import ixdar.models.ModelLoadLedger;
import ixdar.automation.AutomationEndpoint;
import ixdar.automation.AutomationPortFile;
import ixdar.scenes.model.ModelScene;

/**
 * Switches the active {@link ModelScene} to a named model through the same request the terminal's
 * {@code ml} command makes, then holds the request open until the scene has loaded and recomputed,
 * so one live scene can serve a sweep over many meshes.
 */
@AutomationRouteAnnotation(path = "/scene/model", method = APIMethod.POST)
public class SwitchModel extends AutomationEndpoint implements AutomationRoute {

    public static final String NAME = "name";

    public static final String OUTCOME = "outcome";

    public static final String LOADED = "loaded";

    public static final String FAILED = "failed";

    public static final String MODEL = "model";

    public static final String PATH = "path";

    public static final String SECONDS = "seconds";

    public static final String AVAILABLE = "available";

    public static final String SET = "set";

    public static final String OVERRIDES = "overrides";

    public static final long WAIT_SECONDS = 900L;

    private static final long MILLIS_PER_SECOND = 1000L;

    /**
     * {@code POST /scene/model}: resolve {@code name} against the active model scene's models, or
     * open the file at {@code path}, request that load on the render thread, and answer once it has
     * finished.
     *
     * @param body JSON body with {@code name} or {@code path}, and {@code set}
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {ok, outcome, model, path, seconds}}, with {@code error} when the outcome is
     *         failed
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String name = body.has(NAME) && !body.get(NAME).isJsonNull()
                ? body.get(NAME).getAsString().trim()
                : "";
        String file = body.has(PATH) && !body.get(PATH).isJsonNull()
                ? body.get(PATH).getAsString().trim()
                : "";
        ModelScene[] requestedOn = new ModelScene[1];
        int[] serial = new int[1];
        JsonObject result;
        try {
            result = name.isEmpty() == file.isEmpty()
                    ? failure("name a model, e.g. bolt, or give a --path to open, not both")
                    : runtime.runOnMainThread(() -> {
                if (!(runtime.canvas instanceof ModelScene)) {
                    return failure("the active scene is not a model scene");
                }
                ModelScene scene = (ModelScene) runtime.canvas;
                // A path opens the way -Dixdar.model does, a relative one from the checkout root
                // whatever directory the JVM runs in; the scene's own model, reopened by its path,
                // keeps its choice, so its overrides and save target stay the same.
                ModelChoice match = file.isEmpty() ? ModelCatalog.resolve(scene.availableModels(), name)
                        : ModelCatalog.launchChoice(
                                AutomationPortFile.checkoutRoot().resolve(file).toString(), null);
                if (!file.isEmpty() && !Files.exists(Path.of(match.path))) {
                    return failure("no file at " + match.path);
                }
                ModelChoice current = scene.currentModel();
                if (!file.isEmpty() && current != null && current.path != null
                        && Path.of(current.path).toAbsolutePath().normalize()
                                .equals(Path.of(match.path).normalize())) {
                    match = current;
                }
                if (match == null) {
                    JsonObject miss = failure("no model matching: " + name);
                    JsonArray available = new JsonArray();
                    for (ModelChoice choice : scene.availableModels()) {
                        available.add(choice.displayName);
                    }
                    miss.add(AVAILABLE, available);
                    return miss;
                }
                // Each set entry is checked against the graph the model runs; an empty value
                // clears its key.
                JsonArray assignments = body.has(SET) && body.get(SET).isJsonArray()
                        ? body.getAsJsonArray(SET) : new JsonArray();
                for (JsonElement entry : assignments) {
                    String assignment = entry.getAsString();
                    int equals = assignment.indexOf('=');
                    if (equals <= 0) {
                        return failure("--set " + assignment + ": expected path.port=value");
                    }
                    String value = assignment.substring(equals + 1);
                    try {
                        scene.setOverride(assignment.substring(0, equals).trim(),
                                value.isBlank() ? null : ModelScene.parseOverrideValue(value),
                                match);
                    } catch (IllegalArgumentException invalid) {
                        return failure(invalid.getMessage());
                    }
                }
                scene.requestModelLoad(match);
                requestedOn[0] = scene;
                serial[0] = scene.modelLoads.requestedSerial;
                JsonObject accepted = success();
                accepted.addProperty(OUTCOME, LOADED);
                accepted.addProperty(MODEL, match.displayName);
                accepted.addProperty(PATH, match.path);
                return accepted;
            });
        } catch (Exception requestFailure) {
            result = failure(String.valueOf(requestFailure.getMessage()));
            result.addProperty(OUTCOME, FAILED);
            return result;
        }
        if (requestedOn[0] == null) {
            result.addProperty(OUTCOME, FAILED);
            return result;
        }
        ModelLoadLedger loads = requestedOn[0].modelLoads;
        String error;
        if (!loads.awaitFinished(serial[0], WAIT_SECONDS * MILLIS_PER_SECOND)) {
            error = "still loading after " + WAIT_SECONDS + " s";
        } else if (loads.finishedSerial != serial[0]) {
            error = "replaced by a later model request before it was applied";
        } else {
            error = loads.finishedFailure;
            result.addProperty(SECONDS, loads.finishedSeconds);
        }
        result.addProperty(OK, error == null);
        result.addProperty(OUTCOME, error == null ? LOADED : FAILED);
        if (error != null) {
            result.addProperty(ERROR, error);
        }
        JsonObject overrides = new JsonObject();
        for (Map.Entry<String, Object> override
                : requestedOn[0].modelCollection.overrides.entrySet()) {
            overrides.addProperty(override.getKey(), String.valueOf(override.getValue()));
        }
        result.add(OVERRIDES, overrides);
        return result;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName(MODEL)
                .description("Switch the active model scene to a named model, or open a file by its path,"
                        + " and recompute, as the terminal command ml does, returning once it has loaded or"
                        + " failed, with the failure message and the seconds it took.")
                .optionalPositional(NAME, RouteParamType.STRING,
                        "Model display name, or part of the name or path of a listed model.", "bolt")
                .param(PATH, RouteParamType.STRING, false, "",
                        "A .dsl graph or mesh file to open by its path instead of a name, the scene's own "
                                + "model included, so a saved file can be reopened without a restart; "
                                + "a relative path is read from the checkout root.",
                        "tmp/IMG_4111.dsl")
                .param(SET, RouteParamType.STRING_LIST, false, "",
                        "Scene override path.port=value, kept across model switches; an empty value"
                                + " clears it.",
                        "layout/field.curvature_bias=-0.5")
                .responseHint("{ok, outcome: loaded|failed, model, path, seconds, error, overrides}")
                .waitSeconds(WAIT_SECONDS)
                .build();
    }
}
