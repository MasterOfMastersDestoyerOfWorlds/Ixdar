package ixdar.platform.automation.endpoints.ui;

import com.google.gson.JsonObject;

import org.joml.Vector3f;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteDocBuilder;
import ixdar.annotations.automation.RouteParamType;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "ui/frame", method = APIMethod.POST)
public class Frame extends AutomationEndpoint implements AutomationRoute {
    public static final String SELECTION = "selection";
    public static final String BOUNDS = "bounds";
    public static final String REGION = "region";
    public static final String RING = "ring";
    public static final String POINT = "point";
    public static final String RADIUS = "radius";
    public static final String PADDING = "padding";
    public static final String AZIMUTH = "azimuth";
    public static final String ELEVATION = "elevation";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String DISTANCE = "distance";
    public static final String MATCHED_VERTICES = "matchedVertices";

    /**
     * {@code POST /ui/frame}: point the orbit camera at a selection and pull back far enough
     * that it fills the view, so a named part can be photographed without guessing angles.
     *
     * @param body the selection as {@link #selectionExpression} reads it, plus {@code padding}
     *             and optional {@code azimuth} and {@code elevation}; the current angles are kept
     *             otherwise
     * @throws Exception when the render thread reports a failure
     * @return the resolved bounds, the framed centre and radius, and the orbit that was applied
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws Exception {
        String selection = selectionExpression(body);
        float padding = body.has(PADDING) ? body.get(PADDING).getAsFloat()
                : OrbitMouseTrap.DEFAULT_FRAME_PADDING;
        boolean hasAzimuth = body.has(AZIMUTH) && !body.get(AZIMUTH).isJsonNull();
        boolean hasElevation = body.has(ELEVATION) && !body.get(ELEVATION).isJsonNull();
        float requestedAzimuth = hasAzimuth ? body.get(AZIMUTH).getAsFloat() : 0f;
        float requestedElevation = hasElevation ? body.get(ELEVATION).getAsFloat() : 0f;

        JsonObject result = runtime.runOnMainThread(() -> {
            JsonObject framed = new JsonObject();
            if (!(runtime.canvas instanceof MeshNodeViewerScene viewer)) {
                framed.addProperty(OK, false);
                framed.addProperty(ERROR, "MeshNodeViewerScene is not active");
                return framed;
            }
            SelectionBounds selected = new SelectionBounds();
            if (selection == null || !selected.resolve(viewer, selection)) {
                framed.addProperty(OK, false);
                framed.addProperty(ERROR, selection == null ? "a point needs a radius"
                        : selected.error);
                return framed;
            }
            Vector3f center = new Vector3f();
            float radius = selected.sphere(center);
            OrbitMouseTrap orbit = viewer.getOrbitMouse();
            orbit.setOrbit(hasAzimuth ? requestedAzimuth : orbit.getAzimuth(),
                    hasElevation ? requestedElevation : orbit.getElevation(), orbit.getDistance());
            float distance = orbit.frame(center, radius, padding, viewer.camera.aspectRatio());

            framed.addProperty(OK, true);
            framed.addProperty(SELECTION, selected.name.isEmpty() ? selected.kind
                    : selected.kind + ":" + selected.name);
            framed.addProperty(MATCHED_VERTICES, selected.matchedVertices);
            framed.add("boundsMin", runtime.vector3Array(selected.minimum));
            framed.add("boundsMax", runtime.vector3Array(selected.maximum));
            framed.add("center", runtime.vector3Array(center));
            framed.addProperty(RADIUS, radius);
            framed.addProperty(PADDING, padding);
            framed.addProperty(AZIMUTH, orbit.getAzimuth());
            framed.addProperty(ELEVATION, orbit.getElevation());
            framed.addProperty(DISTANCE, orbit.getDistance());
            framed.addProperty("requestedDistance", distance);
            return framed;
        });
        runtime.runOnMainThread(JsonObject::new);
        return result;
    }

    /**
     * The {@link SelectionBounds} expression a framing request names: explicit {@code bounds}
     * first, then {@code point} with {@code radius}, a {@code region}, a {@code ring}, and
     * last the {@code selection}, the whole mesh when none is given.
     *
     * @param body the request
     * @return the expression, or {@code null} for a point given without a radius
     */
    public static String selectionExpression(JsonObject body) {
        if (present(body, BOUNDS)) {
            return SelectionBounds.BOUNDS + ":" + body.get(BOUNDS).getAsString();
        }
        if (present(body, POINT)) {
            return present(body, RADIUS) ? SelectionBounds.POINT + ":"
                    + body.get(POINT).getAsString() + "," + body.get(RADIUS).getAsString() : null;
        }
        if (present(body, REGION)) {
            return SelectionBounds.REGION + ":" + body.get(REGION).getAsString();
        }
        if (present(body, RING)) {
            return SelectionBounds.RING + ":" + body.get(RING).getAsString();
        }
        return present(body, SELECTION) ? body.get(SELECTION).getAsString() : "";
    }

    /**
     * Whether a request carries a non-blank value under a key.
     *
     * @param body the request
     * @param key  the parameter name
     * @return true when the value is present and not blank
     */
    private static boolean present(JsonObject body, String key) {
        return body.has(key) && !body.get(key).isJsonNull()
                && !body.get(key).getAsString().isBlank();
    }

    /**
     * Document the selection and padding parameters every framing route takes, in the order
     * {@link #selectionExpression} reads them.
     *
     * @param builder the route's doc under construction
     * @return {@code builder}, with the parameters added
     */
    public static RouteDocBuilder selectionParams(RouteDocBuilder builder) {
        return builder
                .param(SELECTION, RouteParamType.STRING, false, SelectionBounds.MESH,
                        "What to frame: mesh, overlay, tag:NAME, edge-mark:LABEL, ring:LABEL, "
                                + "region:N[,M] or region:selected, or a bare name.",
                        "tag:cranium")
                .param(BOUNDS, RouteParamType.STRING, false, "",
                        "Explicit box as minX,minY,minZ,maxX,maxY,maxZ; overrides every other "
                                + "selection.",
                        "-1,-1,-1,1,1,1")
                .param(POINT, RouteParamType.STRING, false, "",
                        "A point x,y,z to frame with --radius around it.", "0.1,0.4,0")
                .param(RADIUS, RouteParamType.FLOAT, false, "",
                        "Radius of the sphere framed around --point.", "0.05")
                .param(REGION, RouteParamType.STRING, false, "",
                        "Ring regions by the numbers the regions command reports, comma-separated, "
                                + "or 'selected' for the region tool's selection.",
                        "3")
                .param(RING, RouteParamType.STRING, false, "",
                        "A ring by its label in the ring-tool scene: a graph ring or 'ring #N'.",
                        "ring2")
                .param(PADDING, RouteParamType.FLOAT, false,
                        String.valueOf(OrbitMouseTrap.DEFAULT_FRAME_PADDING),
                        "Margin around the selection as a fraction of its radius.", "0.25");
    }

    @Override
    public RouteDoc describe() {
        return selectionParams(RouteDoc.builder()
                .commandName("frame")
                .description("Fit the camera to a selection, a region, a ring, a point and radius "
                        + "or an explicit box, filling the view with it."))
                .param(AZIMUTH, RouteParamType.FLOAT, false, "",
                        "Orbit azimuth in radians to view from; omitted keeps the current angle.", "1.5708")
                .param(ELEVATION, RouteParamType.FLOAT, false, "",
                        "Orbit elevation in radians to view from, +-1.5708 for a true top or "
                                + "bottom view; omitted keeps the current angle.",
                        "0.6")
                .responseHint("{ok, selection, matchedVertices, boundsMin, boundsMax, center, radius, padding, "
                        + "azimuth, elevation, distance, requestedDistance, error?}")
                .build();
    }
}
