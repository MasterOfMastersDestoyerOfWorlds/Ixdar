package ixdar.automation.endpoints.ui;

import java.util.Locale;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.graphics.render.model.SurfaceShading;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "ui/shading", method = APIMethod.POST)
public class ShadingSet extends AutomationEndpoint implements AutomationRoute {
    public static final String SHADING = "shading";
    public static final String HEADLIGHT = "headlight";

    /**
     * Set the mesh viewer's surface look and leave it on, or with no parameters report it.
     *
     * @param body JSON body with optional {@code shading} and {@code headlight}
     * @return {@code {ok, shading, headlight, palette}}, or an error when no mesh viewer is active
     *         or the shading is unknown
     * @throws Exception when the render thread fails the call
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws Exception {
        // The CLI sends an omitted mode as an empty string and an omitted flag as false.
        String name = body.has(SHADING) ? body.get(SHADING).getAsString() : "";
        boolean headlight = body.has(HEADLIGHT) && body.get(HEADLIGHT).getAsBoolean();
        boolean setting = !name.isBlank() || headlight;
        SurfaceShading requested;
        try {
            requested = name.isBlank() ? null : SurfaceShading.named(name);
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }
        return runtime.runOnMainThread(() -> {
            if (!(runtime.canvas instanceof MeshNodeViewerScene mvs)) {
                return failure("MeshNodeViewerScene is not active");
            }
            if (setting) {
                mvs.setSurfaceLook(requested == null ? mvs.surfaceShading : requested, headlight);
            }
            JsonObject result = success();
            result.addProperty(SHADING, mvs.surfaceShading.name().toLowerCase(Locale.ROOT));
            result.addProperty(HEADLIGHT, mvs.headlight);
            result.addProperty("palette", mvs.palette.name);
            return result;
        });
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName(SHADING)
                .description("Set the mesh viewer's surface look (default, clay or curvature, with "
                        + "an optional headlight) and leave it on; with no arguments, report it.")
                .optionalPositional(SHADING, RouteParamType.STRING,
                        "default (the palette's look), clay (light clay on a light background) or "
                                + "curvature (clay darkened in concave, brightened on convex surface); "
                                + "omitted keeps the current one.",
                        "curvature")
                .param(HEADLIGHT, RouteParamType.BOOL, false, "false",
                        "Clay and curvature key light from the camera; naming a mode without it "
                                + "turns the headlight off.",
                        "true")
                .responseHint("{ok, shading, headlight, palette}")
                .build();
    }
}
