package ixdar.platform.automation.endpoints.ui;

import com.google.gson.JsonObject;

import org.joml.Vector3f;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.platform.Platforms;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "ui/frame", method = APIMethod.POST)
public class Frame extends AutomationEndpoint implements AutomationRoute {
    public static final String SELECTION = "selection";
    public static final String BOUNDS = "bounds";
    public static final String PADDING = "padding";
    public static final String AZIMUTH = "azimuth";
    public static final String ELEVATION = "elevation";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String DISTANCE = "distance";
    public static final String MATCHED_VERTICES = "matchedVertices";
    public static final float DEFAULT_PADDING = 0.15f;
    public static final float MINIMUM_RADIUS = 1e-4f;
    public static final float DISTANCE_FLOOR_FRACTION = 0.01f;
    public static final float DISTANCE_CEILING_MULTIPLE = 8f;
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;
    public static final int NUM_5 = 5;
    public static final int NUM_6 = 6;

    /**
     * {@code POST /ui/frame}: point the orbit camera at a bounding box and pull back far enough
     * that the box fills the view, so a named part can be photographed without guessing angles.
     *
     * @param body {@code selection} or explicit {@code bounds}, plus {@code padding} and optional
     *             {@code azimuth} and {@code elevation}; the current angles are kept otherwise
     * @throws Exception when the render thread reports a failure
     * @return the resolved bounds, the framed centre and radius, and the orbit that was applied
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws Exception {
        String selection = body.has(SELECTION) ? body.get(SELECTION).getAsString() : "";
        String explicitBounds = body.has(BOUNDS) ? body.get(BOUNDS).getAsString() : "";
        float padding = body.has(PADDING) ? body.get(PADDING).getAsFloat() : DEFAULT_PADDING;
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
            Vector3f minimum = new Vector3f();
            Vector3f maximum = new Vector3f();
            String resolvedSelection;
            if (!explicitBounds.isBlank()) {
                String[] fields = explicitBounds.trim().split("\\s*,\\s*");
                if (fields.length != NUM_6) {
                    framed.addProperty(OK, false);
                    framed.addProperty(ERROR, "bounds needs six numbers: minX,minY,minZ,maxX,maxY,maxZ");
                    return framed;
                }
                minimum.set(Float.parseFloat(fields[0]), Float.parseFloat(fields[1]), Float.parseFloat(fields[2]));
                maximum.set(Float.parseFloat(fields[NUM_3]), Float.parseFloat(fields[NUM_4]),
                        Float.parseFloat(fields[NUM_5]));
                resolvedSelection = BOUNDS;
                framed.addProperty(MATCHED_VERTICES, 0);
            } else {
                SelectionBounds selected = new SelectionBounds();
                if (!selected.resolve(viewer, selection)) {
                    framed.addProperty(OK, false);
                    framed.addProperty(ERROR, selected.error);
                    return framed;
                }
                minimum.set(selected.minimum);
                maximum.set(selected.maximum);
                resolvedSelection = selected.name.isEmpty()
                        ? selected.kind
                        : selected.kind + ":" + selected.name;
                framed.addProperty(MATCHED_VERTICES, selected.matchedVertices);
            }

            Vector3f center = new Vector3f(minimum).add(maximum).mul(0.5f);
            float radius = Math.max(MINIMUM_RADIUS, new Vector3f(maximum).sub(minimum).length() * 0.5f);
            float paddedRadius = radius * (1f + Math.max(0f, padding));
            float distance = fitDistance(viewer, paddedRadius);

            OrbitMouseTrap orbit = viewer.getOrbitMouse();
            float azimuth = hasAzimuth ? requestedAzimuth : orbit.getAzimuth();
            float elevation = hasElevation ? requestedElevation : orbit.getElevation();
            orbit.setDistanceBounds(Math.max(MINIMUM_RADIUS, distance * DISTANCE_FLOOR_FRACTION),
                    distance * DISTANCE_CEILING_MULTIPLE);
            orbit.setTarget(center);
            orbit.setOrbit(azimuth, elevation, distance);

            framed.addProperty(OK, true);
            framed.addProperty(SELECTION, resolvedSelection);
            framed.add("boundsMin", runtime.vector3Array(minimum));
            framed.add("boundsMax", runtime.vector3Array(maximum));
            framed.add("center", runtime.vector3Array(center));
            framed.addProperty("radius", radius);
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
     * Camera distance at which a sphere of the given radius just fills the smaller of the two
     * view angles, so the framed part fills the shot on any aspect ratio.
     *
     * @param viewer the active mesh viewer, whose camera carries the field of view in degrees
     * @param radius radius of the sphere to fit, already padded
     * @return the distance from the orbit target to place the camera at
     */
    private float fitDistance(MeshNodeViewerScene viewer, float radius) {
        int width = Math.max(1, Platforms.get().getFrameBufferWidth());
        int height = Math.max(1, Platforms.get().getFrameBufferHeight());
        float halfFieldOfViewY = (float) Math.toRadians(viewer.camera.fov) * 0.5f;
        float halfFieldOfViewX = (float) Math.atan(Math.tan(halfFieldOfViewY) * width / height);
        float narrowest = Math.min(halfFieldOfViewY, halfFieldOfViewX);
        return radius / (float) Math.sin(narrowest);
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("frame")
                .description("Fit the camera to a named selection or an explicit bounding box, filling the view with it.")
                .param(SELECTION, RouteParamType.STRING, false, "mesh",
                        "What to frame: mesh, overlay, tag:NAME, edge-mark:LABEL, patch:ID, or a bare name.",
                        "tag:cranium")
                .param(BOUNDS, RouteParamType.STRING, false, "",
                        "Explicit box as minX,minY,minZ,maxX,maxY,maxZ; overrides the selection.",
                        "-1,-1,-1,1,1,1")
                .param(PADDING, RouteParamType.FLOAT, false, String.valueOf(DEFAULT_PADDING),
                        "Margin around the box as a fraction of its radius.", "0.25")
                .param(AZIMUTH, RouteParamType.FLOAT, false, "",
                        "Orbit azimuth in radians to view from; omitted keeps the current angle.", "1.5708")
                .param(ELEVATION, RouteParamType.FLOAT, false, "",
                        "Orbit elevation in radians to view from; omitted keeps the current angle.", "0.6")
                .responseHint("{ok, selection, matchedVertices, boundsMin, boundsMax, center, radius, padding, "
                        + "azimuth, elevation, distance, requestedDistance, error?}")
                .build();
    }
}
