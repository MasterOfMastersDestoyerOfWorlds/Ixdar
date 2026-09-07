package ixdar.platform.automation.endpoints.mesh;

import java.io.IOException;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.geometry.mesh.quadlayout.solver.SingularSystemDiagnosis;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.QuadLayoutScene;

/**
 * Serves the seamless solver's singular-system diagnosis and, on request, marks the offending
 * vertices in the scene so they can be found by eye.
 */
@AutomationRouteAnnotation(path = "/mesh/seamless/diagnosis", method = APIMethod.POST)
public class SeamlessDiagnosis extends AutomationEndpoint implements AutomationRoute {

    /** Request flag asking for the offending vertices to be marked in the scene. */
    public static final String HIGHLIGHT = "highlight";

    /** Response key: whether the request itself succeeded. */
    public static final String OK = "ok";

    /** Response key: why the request failed. */
    public static final String ERROR = "error";

    /** Response key: whether the last seamless solve hit a singular system at all. */
    public static final String SINGULAR = "singular";

    /** Coordinates per vertex position. */
    private static final int POSITION_COMPONENTS = 3;

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        boolean highlight = body.has(HIGHLIGHT) && body.get(HIGHLIGHT).getAsBoolean();
        try {
            return runtime.runOnMainThread(() -> {
                JsonObject result = new JsonObject();
                if (!(runtime.canvas instanceof QuadLayoutScene)) {
                    result.addProperty(OK, false);
                    result.addProperty(ERROR, "QuadLayoutScene is not active");
                    return result;
                }
                QuadLayoutScene scene = (QuadLayoutScene) runtime.canvas;
                if (scene.engine == null || scene.engine.seamless == null) {
                    result.addProperty(OK, false);
                    result.addProperty(ERROR, "The seamless stage has not run yet");
                    return result;
                }
                SingularSystemDiagnosis diagnosis = scene.engine.seamless.singularDiagnosis;
                result.addProperty(OK, true);
                result.addProperty(SINGULAR, diagnosis != null);
                if (diagnosis == null) {
                    return result;
                }
                describeDiagnosis(result, diagnosis);
                result.addProperty("highlighted", highlight);
                if (highlight) {
                    scene.quadRuntime.setDiagnostic(List.of(),
                            List.of(offendingVertexPositions(diagnosis)), List.of());
                }
                return result;
            });
        } catch (Exception failure) {
            JsonObject error = new JsonObject();
            error.addProperty(OK, false);
            error.addProperty(ERROR, failure.getMessage() == null ? "" : failure.getMessage());
            return error;
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Report the seamless solver's singular-system diagnosis: how the"
                        + " singularity was classified, the null vector's support and the mesh"
                        + " vertices it sits on.")
                .param(HIGHLIGHT, RouteParamType.BOOL, false, "false",
                        "Also mark the offending vertices in the quad-layout scene.", "true")
                .responseHint("{ok, singular, classification, classificationCode, pivotIndex,"
                        + " dimension, appliedDiagonalShift, nullVectorEnergy, supportSize,"
                        + " distinctChartCount, degenerateVertexCount, logLine, support[],"
                        + " highlighted}")
                .build();
    }

    /**
     * Write the diagnosis's scalars and its per-support-entry rows into the response.
     *
     * @param result    response object to fill
     * @param diagnosis the diagnosis to describe
     */
    private static void describeDiagnosis(JsonObject result, SingularSystemDiagnosis diagnosis) {
        result.addProperty("classification",
                SingularSystemDiagnosis.classificationName(diagnosis.classification));
        result.addProperty("classificationCode", diagnosis.classification);
        result.addProperty("pivotIndex", diagnosis.pivotIndex);
        result.addProperty("dimension", diagnosis.dimension);
        result.addProperty("appliedDiagonalShift", diagnosis.appliedDiagonalShift);
        result.addProperty("nullVectorEnergy", diagnosis.nullVectorEnergy);
        result.addProperty("supportSize", diagnosis.supportSize);
        result.addProperty("distinctChartCount", diagnosis.distinctChartCount);
        result.addProperty("degenerateVertexCount", diagnosis.degenerateVertexCount);
        result.addProperty("logLine", diagnosis.logLine());
        JsonArray support = new JsonArray();
        for (int entry = 0; entry < diagnosis.supportDof.length; entry++) {
            JsonObject row = new JsonObject();
            row.addProperty("dof", diagnosis.supportDof[entry]);
            row.addProperty("weight", diagnosis.supportWeight[entry]);
            row.addProperty("chartId", diagnosis.supportChartId[entry]);
            row.addProperty("vertexId", diagnosis.supportVertexId[entry]);
            row.addProperty("x", diagnosis.supportVertexPosition[entry * POSITION_COMPONENTS]);
            row.addProperty("y", diagnosis.supportVertexPosition[entry * POSITION_COMPONENTS + 1]);
            row.addProperty("z", diagnosis.supportVertexPosition[entry * POSITION_COMPONENTS + 2]);
            support.add(row);
        }
        result.add("support", support);
    }

    /**
     * The recorded support's vertex positions as one flat world-space xyz array, the form the
     * runtime's diagnostic marker groups take.
     *
     * @param diagnosis the diagnosis whose support to mark
     * @return flat {@code x, y, z} triples, one per recorded support entry
     */
    private static float[] offendingVertexPositions(SingularSystemDiagnosis diagnosis) {
        float[] positions = new float[diagnosis.supportDof.length * POSITION_COMPONENTS];
        for (int entry = 0; entry < positions.length; entry++) {
            positions[entry] = (float) diagnosis.supportVertexPosition[entry];
        }
        return positions;
    }
}
