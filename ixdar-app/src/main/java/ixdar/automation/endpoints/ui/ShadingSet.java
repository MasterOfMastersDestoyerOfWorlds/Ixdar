package ixdar.automation.endpoints.ui;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.scenes.model.ModelScene;

@AutomationRouteAnnotation(path = "ui/shading", method = APIMethod.POST)
public class ShadingSet extends AutomationEndpoint implements AutomationRoute {
    public static final String SHADING = "shading";
    public static final String HEADLIGHT = "headlight";
    public static final String LOOKS = "looks";

    /**
     * Pick a model scene's surface look and leave it on, or with no parameters report it.
     *
     * @param body JSON body with optional {@code shading} and {@code headlight}
     * @return {@code {ok, shading, shown, looks, headlight, palette}}, or an error when no model
     *         scene is active or it registered no look by that name
     * @throws Exception when the render thread fails the call
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws Exception {
        // The CLI sends an omitted mode as an empty string and an omitted flag as false.
        String name = body.has(SHADING) ? body.get(SHADING).getAsString() : "";
        boolean headlight = body.has(HEADLIGHT) && body.get(HEADLIGHT).getAsBoolean();
        return runtime.runOnMainThread(() -> {
            if (!(runtime.canvas instanceof ModelScene scene)) {
                return failure("no model scene is active");
            }
            if (!name.isBlank() || headlight) {
                try {
                    scene.setLook(name.isBlank() ? scene.looks.picked.id : name, headlight);
                } catch (IllegalArgumentException unknown) {
                    return failure(unknown.getMessage());
                }
            }
            JsonObject result = success();
            addLook(result, scene);
            result.addProperty("palette", scene.palette.name);
            return result;
        });
    }

    /**
     * Report a scene's looks: the picked one, the one drawn (a tool's override, when set), every
     * registered name and the headlight.
     *
     * @param out   the reply to add them to
     * @param scene the scene
     */
    public static void addLook(JsonObject out, ModelScene scene) {
        out.addProperty(SHADING, scene.looks.picked == null ? "" : scene.looks.picked.id);
        out.addProperty("shown", scene.looks.shown() == null ? "" : scene.looks.shown().id);
        out.addProperty(LOOKS, scene.looks.names());
        out.addProperty(HEADLIGHT, scene.headlight);
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName(SHADING)
                .description("Pick the scene's surface look (any look the scene registered, with an "
                        + "optional headlight) and leave it on; with no arguments, report it.")
                .optionalPositional(SHADING, RouteParamType.STRING,
                        "a registered look: every model scene has default, clay (light clay on a "
                                + "light background) and curvature (concave dark, convex light); the "
                                + "ring scene adds regions and parts; the reply lists the scene's own. "
                                + "Omitted keeps the current one.",
                        "curvature")
                .param(HEADLIGHT, RouteParamType.BOOL, false, "false",
                        "Clay and curvature key light from the camera; naming a look without it "
                                + "turns the headlight off.",
                        "true")
                .responseHint("{ok, shading, shown, looks, headlight, palette}")
                .build();
    }
}
