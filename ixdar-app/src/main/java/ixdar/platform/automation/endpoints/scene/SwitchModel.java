package ixdar.platform.automation.endpoints.scene;

import java.io.IOException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.model.ModelChoice;
import ixdar.scenes.model.ModelLoadLedger;
import ixdar.scenes.model.ModelScene;

/**
 * Switches the active {@link ModelScene} to a named model through the same request the terminal's
 * {@code ml} command makes, then holds the request open until the scene has loaded and recomputed,
 * so one live scene can serve a sweep over many meshes.
 */
@AutomationRouteAnnotation(path = "/scene/model", method = APIMethod.POST)
public class SwitchModel extends AutomationEndpoint implements AutomationRoute {

    /** Request key: the model display name, or part of a listed model's name or path. */
    public static final String NAME = "name";

    /** Response key: whether the model loaded. */
    public static final String OK = "ok";

    /** Response key: {@link #LOADED} or {@link #FAILED}. */
    public static final String OUTCOME = "outcome";

    public static final String LOADED = "loaded";

    public static final String FAILED = "failed";

    /** Response key: why the switch failed. */
    public static final String ERROR = "error";

    /** Response key, and the CLI command name: the display name of the matched model. */
    public static final String MODEL = "model";

    /** Response key: the loader path of the matched model. */
    public static final String PATH = "path";

    /** Response key: wall time of the load and recompute. */
    public static final String SECONDS = "seconds";

    /** Response key: the models a name can match, returned when it matched none. */
    public static final String AVAILABLE = "available";

    /** Longest a request waits for the load and recompute before answering failed. */
    public static final long WAIT_SECONDS = 900L;

    private static final long MILLIS_PER_SECOND = 1000L;

    /**
     * {@code POST /scene/model}: resolve {@code name} against the active model scene's models,
     * request that load on the render thread, and answer once it has finished.
     *
     * @param body JSON body with {@code name}
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {ok, outcome, model, path, seconds}}, with {@code error} when the outcome is
     *         failed
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String name = body.has(NAME) && !body.get(NAME).isJsonNull()
                ? body.get(NAME).getAsString().trim()
                : "";
        if (name.isEmpty()) {
            return failure("a model name is required, e.g. bolt");
        }
        ModelScene[] requestedOn = new ModelScene[1];
        int[] serial = new int[1];
        JsonObject result;
        try {
            result = runtime.runOnMainThread(() -> {
                if (!(runtime.canvas instanceof ModelScene)) {
                    return failure("the active scene is not a model scene");
                }
                ModelScene scene = (ModelScene) runtime.canvas;
                ModelChoice match = scene.requestModel(name);
                if (match == null) {
                    JsonObject miss = failure("no model matching: " + name);
                    JsonArray available = new JsonArray();
                    for (ModelChoice choice : scene.availableModels()) {
                        available.add(choice.displayName);
                    }
                    miss.add(AVAILABLE, available);
                    return miss;
                }
                requestedOn[0] = scene;
                serial[0] = scene.modelLoads.requestedSerial;
                JsonObject accepted = new JsonObject();
                accepted.addProperty(OK, true);
                accepted.addProperty(OUTCOME, LOADED);
                accepted.addProperty(MODEL, match.displayName);
                accepted.addProperty(PATH, match.path);
                return accepted;
            });
        } catch (Exception requestFailure) {
            return failure(String.valueOf(requestFailure.getMessage()));
        }
        if (requestedOn[0] == null) {
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
        return result;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName(MODEL)
                .description("Switch the active model scene to a named model and recompute, as the"
                        + " terminal command ml does, returning once it has loaded or failed, with"
                        + " the failure message and the seconds it took.")
                .positional(NAME, RouteParamType.STRING,
                        "Model display name, or part of the name or path of a listed model.", "bolt")
                .responseHint("{ok, outcome: loaded|failed, model, path, seconds, error}")
                .waitSeconds(WAIT_SECONDS)
                .build();
    }

    /**
     * Build the failed envelope this route answers with before any load ran.
     *
     * @param message what went wrong
     * @return {@code {"ok": false, "outcome": "failed", "error": message}}
     */
    private static JsonObject failure(String message) {
        JsonObject error = new JsonObject();
        error.addProperty(OK, false);
        error.addProperty(OUTCOME, FAILED);
        error.addProperty(ERROR, message);
        return error;
    }
}
