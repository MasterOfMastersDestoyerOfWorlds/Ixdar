package ixdar.nodegraph.nodes.curve;

import java.util.List;

import org.joml.Vector3f;

import java.util.Map;

import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.field.FloatCurveKernel;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.nodes.modifier.SolidifyMeshNode;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.nodegraph.field.FieldBroadcast;

/**
 * Bi-rail loft: sweeps a profile cross-section between two rail curves to build a quad mesh.
 *
 * <p>Rails are resampled to {@code x_resolution} stations by arc length and the profile to
 * {@code y_resolution} points, so the two inputs need not share a sample count.
 */
@MeshNodeAnnotation(id = "bi_rail_loft", category = NodeCategory.CURVE_OPERATIONS)
public class BiRailLoftNode implements MeshNode {
    public static final int DEFAULT_X_RESOLUTION = 32;
    public static final int DEFAULT_Y_RESOLUTION = 16;
    public static final int MAX_RESOLUTION = 512;
    public static final float DEFAULT_THICKNESS = 0.001f;
    public static final float MIN_LENGTH = 1e-10f;
    public static final float MIN_TANGENT_LENGTH_SQUARED = 1e-20f;
    public static final float FALLBACK_RAIL_SPACING = 1e-5f;
    public static final float PARALLEL_LENGTH_SQUARED_THRESHOLD = 1e-8f;

    public static final InputPort RAIL_A = new InputPort("rail_a", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort RAIL_B = new InputPort("rail_b", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort PROFILE = new InputPort("profile", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort PROFILE_B = new InputPort("profile_b", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort X_RESOLUTION = new InputPort("x_resolution", PortType.INT, 32, 2f, 512f);
    public static final InputPort Y_RESOLUTION = new InputPort("y_resolution", PortType.INT, 16, 2f, 512f);
    public static final InputPort BLEND_CLOSURE = new InputPort("blend_closure", PortType.CLOSURE, null);
    public static final InputPort DEPTH_SCALE = new InputPort("depth_scale", PortType.FLOAT, 1f, -100f, 100f);
    public static final InputPort ISO_CURVE_T = new InputPort("iso_curve_t", PortType.FLOAT, -1f, -1f, 1f);
    public static final InputPort THICKNESS = new InputPort("thickness", PortType.FLOAT, 0.001f, 0f, 10f);
    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);
    public static final OutputPort ISO_CURVE = new OutputPort("iso_curve", PortType.GEOMETRY_BUNDLE);
    public static final OutputPort BOUNDARY_A = new OutputPort("boundary_a", PortType.GEOMETRY_BUNDLE);
    public static final OutputPort BOUNDARY_B = new OutputPort("boundary_b", PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Generates a surface by sweeping profile curves between two rail curves with optional profile blending, depth scaling, and solidification via thickness.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.ofEntries(
                Map.entry(RAIL_A.name, "First rail curve defining the surface boundary along one side."),
                Map.entry(RAIL_B.name, "Second rail curve defining the opposing boundary."),
                Map.entry(PROFILE.name, "Primary cross-section curve swept between rails."),
                Map.entry(PROFILE_B.name, "Optional second cross-section. When set, the surface blends from `profile` at V=0 to `profile_b` at V=1."),
                Map.entry(X_RESOLUTION.name, "Samples along the U direction (along the rails). Higher = smoother sweep."),
                Map.entry(Y_RESOLUTION.name, "Samples along the V direction (across the profile). Higher = smoother cross-section."),
                Map.entry(BLEND_CLOSURE.name, "Optional float closure controlling profile-to-profile_b blend as a function of V."),
                Map.entry(DEPTH_SCALE.name, "Multiplier on profile depth. 1 = as-authored; 0 = flat sheet."),
                Map.entry(ISO_CURVE_T.name, "If ≥ 0, also output an iso-curve at this U parameter. -1 = disabled."),
                Map.entry(THICKNESS.name, "Solidify thickness for closed shells. 0 = open surface. Typical 0.001."),
                Map.entry(GEOMETRY.name, "Generated surface (possibly solidified)."),
                Map.entry(ISO_CURVE.name, "U-isocurve at iso_curve_t (empty if disabled)."),
                Map.entry(BOUNDARY_A.name, "Ordered boundary curve on the rail_a side of the surface (for bridging)."),
                Map.entry(BOUNDARY_B.name, "Ordered boundary curve on the rail_b side.")
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(RAIL_A, RAIL_B, PROFILE, PROFILE_B, X_RESOLUTION, Y_RESOLUTION, BLEND_CLOSURE, DEPTH_SCALE, ISO_CURVE_T, THICKNESS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY, ISO_CURVE, BOUNDARY_A, BOUNDARY_B);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle railAGb = ctx.getInput(RAIL_A.name, GeometryBundle.class);
        GeometryBundle railBGb = ctx.getInput(RAIL_B.name, GeometryBundle.class);
        GeometryBundle profileGb = ctx.getInput(PROFILE.name, GeometryBundle.class);
        GeometryBundle profileBGb = ctx.getInput(PROFILE_B.name, GeometryBundle.class);

        if (railAGb == null || railBGb == null || profileGb == null) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }

        CurveGeometry railACg = extractCurve(railAGb);
        CurveGeometry railBCg = extractCurve(railBGb);
        CurveGeometry profileCg = extractCurve(profileGb);

        if (railACg == null || railBCg == null || profileCg == null) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }

        // Optional second profile for blending
        CurveGeometry profileBCg = (profileBGb != null) ? extractCurve(profileBGb) : null;

        int xRes = FieldBroadcast.intAt(
                FieldBroadcast.getInputOrDefault(ctx, X_RESOLUTION.name, X_RESOLUTION.defaultValue), 0, DEFAULT_X_RESOLUTION);
        int yRes = FieldBroadcast.intAt(
                FieldBroadcast.getInputOrDefault(ctx, Y_RESOLUTION.name, Y_RESOLUTION.defaultValue), 0, DEFAULT_Y_RESOLUTION);
        xRes = Math.max(2, Math.min(MAX_RESOLUTION, xRes));
        yRes = Math.max(2, Math.min(MAX_RESOLUTION, yRes));

        boolean closedRails = railACg.closed[0];
        if (closedRails != railBCg.closed[0]) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }
        float[] railA = railACg.resampled(xRes).curvePositions(0);
        float[] railB = railBCg.resampled(xRes).curvePositions(0);

        float[] profileA = profileCg.pointCount(0) == yRes ? profileCg.curvePositions(0)
                : profileCg.resampled(yRes).curvePositions(0);
        float[] profileAU = new float[yRes];
        float[] profileAV = new float[yRes];
        normalizeProfile(profileA, yRes, profileAU, profileAV);

        // Extract and normalize profile B (or reuse A if not provided)
        float[] profileBU;
        float[] profileBV;
        if (profileBCg != null) {
            float[] profileB = profileBCg.pointCount(0) == yRes ? profileBCg.curvePositions(0)
                    : profileBCg.resampled(yRes).curvePositions(0);
            profileBU = new float[yRes];
            profileBV = new float[yRes];
            normalizeProfile(profileB, yRes, profileBU, profileBV);
        } else {
            profileBU = profileAU;
            profileBV = profileAV;
        }

        // Optional blend closure for per-station blending along the rail
        FloatCurveKernel blendKernel = ctx.getInput(BLEND_CLOSURE.name, FloatCurveKernel.class);

        float depthScale = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, DEPTH_SCALE.name, DEPTH_SCALE.defaultValue), 0, 1f);

        float isoCurveT = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, ISO_CURVE_T.name, ISO_CURVE_T.defaultValue), 0, -1f);

        // Build loft mesh with two-profile blending
        // If iso_curve_t >= 0, also extract the iso-parameter curve at that profile fraction
        float[] isoCurvePositions = (isoCurveT >= 0f && isoCurveT <= 1f) ? new float[xRes * 3] : null;
        int isoRow = (isoCurvePositions != null) ? Math.round(isoCurveT * (yRes - 1)) : -1;

        // Always extract boundary curves (yi=0 → boundary_a, yi=yRes-1 → boundary_b)
        float[] boundaryAPositions = new float[xRes * 3];
        float[] boundaryBPositions = new float[xRes * 3];

        float thickness = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, THICKNESS.name, THICKNESS.defaultValue), 0, DEFAULT_THICKNESS);

        HalfEdgeMesh mesh = buildLoftMesh(railA, railB, xRes, closedRails,
                profileAU, profileAV, profileBU, profileBV, yRes, blendKernel, depthScale,
                isoRow, isoCurvePositions, boundaryAPositions, boundaryBPositions);
        mesh.computeNormals();

        // Solidify the open loft surface to make it watertight
        MeshTopology finalMesh;
        if (thickness > 0f) {
            finalMesh = SolidifyMeshNode.solidify(mesh, thickness);
        } else {
            finalMesh = mesh;
        }

        GeometryBundle outBundle = GeometryBundle.empty().withMesh(finalMesh);
        ctx.setOutput(GEOMETRY.name, outBundle);

        // Output iso-curve if requested
        if (isoCurvePositions != null) {
            ctx.setOutput(ISO_CURVE.name, GeometryBundle.empty().withSlot(CurveGeometry.SLOT,
                    CurveGeometry.singlePolyline(isoCurvePositions, closedRails)));
        } else {
            ctx.setOutput(ISO_CURVE.name, GeometryBundle.empty());
        }

        // Output boundary curves (always available — these are the actual surface edges)
        ctx.setOutput(BOUNDARY_A.name, GeometryBundle.empty().withSlot(CurveGeometry.SLOT,
                CurveGeometry.singlePolyline(boundaryAPositions, closedRails)));
        ctx.setOutput(BOUNDARY_B.name, GeometryBundle.empty().withSlot(CurveGeometry.SLOT,
                CurveGeometry.singlePolyline(boundaryBPositions, closedRails)));
    }

    private static CurveGeometry extractCurve(GeometryBundle gb) {
        Object raw = gb.slots().get(CurveGeometry.SLOT);
        if (raw instanceof CurveGeometry cg && cg.pointCount() >= 2) {
            return cg;
        }
        return null;
    }

    /**
     * Normalizes the profile to local 2D coordinates (U across, V up), centred and scaled so U
     * fills [-0.5, 0.5]. V takes the same scale factor, so a profile taller than it is wide keeps
     * its aspect ratio and extends past the rail spacing.
     */
    private static void normalizeProfile(float[] profile, int n, float[] outU, float[] outV) {
        float cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            cx += profile[i * 3];
            cy += profile[i * 3 + 1];
        }
        cx /= n;
        cy /= n;

        // Scale based on U (across) extent only, so cross-section fills the rail gap
        float maxU = 0;
        for (int i = 0; i < n; i++) {
            maxU = Math.max(maxU, Math.abs(profile[i * 3] - cx));
        }
        float scale = maxU > MIN_LENGTH ? 0.5f / maxU : 1f;

        for (int i = 0; i < n; i++) {
            outU[i] = (profile[i * 3] - cx) * scale;
            outV[i] = (profile[i * 3 + 1] - cy) * scale;
        }
    }

    /**
     * Build the loft mesh by placing profile cross-sections at each station along the rails.
     * When two profiles are provided, linearly interpolates between them across the cross-section:
     * profile A dominates near the start of the cross-section, profile B near the end. Closed
     * rails join the last station back to the first.
     */
    private static HalfEdgeMesh buildLoftMesh(float[] railA, float[] railB, int xRes, boolean closed,
                                               float[] profileAU, float[] profileAV,
                                               float[] profileBU, float[] profileBV, int yRes,
                                               FloatCurveKernel blendKernel, float depthScale,
                                               int isoRow, float[] isoCurveOut,
                                               float[] boundaryAOut, float[] boundaryBOut) {
        int spans = closed ? xRes : xRes - 1;
        HalfEdgeMesh mesh = new HalfEdgeMesh(xRes * yRes, 0, spans * (yRes - 1), 0);

        Vector3f pA = new Vector3f();
        Vector3f pB = new Vector3f();
        Vector3f tangent = new Vector3f();
        Vector3f across = new Vector3f(); // rail A → rail B direction
        Vector3f up = new Vector3f();     // tangent × across

        int[][] vid = new int[xRes][yRes];

        for (int xi = 0; xi < xRes; xi++) {
            // Rail positions at this station
            pA.set(railA[xi * 3], railA[xi * 3 + 1], railA[xi * 3 + 2]);
            pB.set(railB[xi * 3], railB[xi * 3 + 1], railB[xi * 3 + 2]);

            // Tangent: forward direction along rails
            int next = (xi + 1) % xRes;
            if (closed || xi < xRes - 1) {
                tangent.set(
                        (railA[next * 3] + railB[next * 3]) * 0.5f -
                                (railA[xi * 3] + railB[xi * 3]) * 0.5f,
                        (railA[next * 3 + 1] + railB[next * 3 + 1]) * 0.5f -
                                (railA[xi * 3 + 1] + railB[xi * 3 + 1]) * 0.5f,
                        (railA[next * 3 + 2] + railB[next * 3 + 2]) * 0.5f -
                                (railA[xi * 3 + 2] + railB[xi * 3 + 2]) * 0.5f
                );
            } else {
                tangent.set(
                        (railA[xi * 3] + railB[xi * 3]) * 0.5f -
                                (railA[(xi - 1) * 3] + railB[(xi - 1) * 3]) * 0.5f,
                        (railA[xi * 3 + 1] + railB[xi * 3 + 1]) * 0.5f -
                                (railA[(xi - 1) * 3 + 1] + railB[(xi - 1) * 3 + 1]) * 0.5f,
                        (railA[xi * 3 + 2] + railB[xi * 3 + 2]) * 0.5f -
                                (railA[(xi - 1) * 3 + 2] + railB[(xi - 1) * 3 + 2]) * 0.5f
                );
            }
            if (tangent.lengthSquared() < MIN_TANGENT_LENGTH_SQUARED) tangent.set(0, 0, 1);
            tangent.normalize();

            // Across: direction from rail A to rail B
            across.set(pB).sub(pA);
            float railSpacing = across.length();
            if (railSpacing < MIN_LENGTH) railSpacing = FALLBACK_RAIL_SPACING;
            across.normalize();

            // Up: perpendicular to tangent and across
            up.set(tangent).cross(across);
            if (up.lengthSquared() < PARALLEL_LENGTH_SQUARED_THRESHOLD) {
                // Tangent nearly parallel to across — use world-up fallback
                // Pick the axis least aligned with across as the reference direction
                float ax = Math.abs(across.x), ay = Math.abs(across.y), az = Math.abs(across.z);
                if (ay <= ax && ay <= az) {
                    up.set(0, 1, 0);
                } else if (ax <= az) {
                    up.set(1, 0, 0);
                } else {
                    up.set(0, 0, 1);
                }
                // Orthogonalize: up = up - (up·across)*across, then normalize
                float dot = up.dot(across);
                up.x -= dot * across.x;
                up.y -= dot * across.y;
                up.z -= dot * across.z;
            }
            up.normalize();

            // Center point between rails
            float cx = (pA.x + pB.x) * 0.5f;
            float cy = (pA.y + pB.y) * 0.5f;
            float cz = (pA.z + pB.z) * 0.5f;

            // Place profile at this station, blending between profile A and B.
            // When blendKernel is provided, blend varies per-station (along rail, xi direction).
            // When null, blend varies per-point (across cross-section, yi direction).
            float stationBlend = (blendKernel != null)
                    ? blendKernel.evaluate((xRes > 1) ? (float) xi / (xRes - 1) : 0.5f)
                    : Float.NaN; // sentinel: use per-yi blend

            for (int yi = 0; yi < yRes; yi++) {
                float blend = Float.isNaN(stationBlend)
                        ? ((yRes > 1) ? (float) yi / (yRes - 1) : 0.5f)
                        : stationBlend;
                float u = (profileAU[yi] * (1f - blend) + profileBU[yi] * blend) * railSpacing;
                float v = (profileAV[yi] * (1f - blend) + profileBV[yi] * blend) * railSpacing * depthScale;

                float px = cx + across.x * u + up.x * v;
                float py = cy + across.y * u + up.y * v;
                float pz = cz + across.z * u + up.z * v;
                vid[xi][yi] = mesh.addVertex(px, py, pz);

                // Record iso-curve point if this is the target row
                if (isoCurveOut != null && yi == isoRow) {
                    isoCurveOut[xi * 3] = px;
                    isoCurveOut[xi * 3 + 1] = py;
                    isoCurveOut[xi * 3 + 2] = pz;
                }
                // Record boundary curves (first and last profile rows)
                if (yi == 0 && boundaryAOut != null) {
                    boundaryAOut[xi * 3] = px;
                    boundaryAOut[xi * 3 + 1] = py;
                    boundaryAOut[xi * 3 + 2] = pz;
                }
                if (yi == yRes - 1 && boundaryBOut != null) {
                    boundaryBOut[xi * 3] = px;
                    boundaryBOut[xi * 3 + 1] = py;
                    boundaryBOut[xi * 3 + 2] = pz;
                }
            }
        }

        // Connect quads
        for (int xi = 0; xi < spans; xi++) {
            int next = (xi + 1) % xRes;
            for (int yi = 0; yi < yRes - 1; yi++) {
                mesh.addFace(vid[xi][yi], vid[xi][yi + 1], vid[next][yi + 1], vid[next][yi]);
            }
        }

        return mesh;
    }
}
