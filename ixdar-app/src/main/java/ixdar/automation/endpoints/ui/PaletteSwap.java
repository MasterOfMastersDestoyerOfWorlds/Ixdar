package ixdar.automation.endpoints.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.graphics.render.color.Palettes;
import ixdar.scenes.Scene;

/**
 * {@code POST /ui/palette}: step the scene to the next of every palette in
 * Palettes, or to one by name, so a script can screenshot every scene in every
 * look.
 */
@AutomationRouteAnnotation(path = "ui/paletteswap", method = APIMethod.POST)
public class PaletteSwap extends AutomationEndpoint implements AutomationRoute {
    public static final String NAME = "name";
    public static final String PALETTE = "palette";

    /**
     * Wear the named option, or the next one when no name is given.
     *
     * @param body {@code name}, optional
     * @throws Exception when the render thread reports a failure
     * @return the palette now worn and every option
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws Exception {
        String wanted = body.has(NAME) && !body.get(NAME).isJsonNull() ? body.get(NAME).getAsString() : "";
        return runtime.runOnMainThread(() -> {
            Scene scene = runtime.canvas;
            if (wanted.isBlank()) {
                scene.cyclePalette();
            } else {
                int tries = Palettes.OPTIONS.size();
                while (!scene.palette.name.equalsIgnoreCase(wanted) && tries-- > 0) {
                    scene.cyclePalette();
                }
                if (!scene.palette.name.equalsIgnoreCase(wanted)) {
                    return failure("no palette named " + wanted);
                }
            }
            JsonObject result = success();
            result.addProperty(PALETTE, scene.palette.name);
            JsonArray options = new JsonArray();
            Palettes.OPTIONS.forEach(option -> options.add(option.name));
            result.add("options", options);
            return result;
        });
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName(PALETTE)
                .description("Step the scene to its next colour palette, or to the one named.")
                .param(NAME, RouteParamType.STRING, false, "", "The palette to wear; omitted steps to the next.",
                        "Paper")
                .responseHint("{ok, palette, options, error?}")
                .build();
    }
}
