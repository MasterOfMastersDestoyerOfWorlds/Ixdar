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
import ixdar.geometry.mesh.data.representation.PackedPoints;
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
        CurveGeometry railACg = extractCurve(ctx.getInput(RAIL_A.name, GeometryBundle.class));
        CurveGeometry railBCg = extractCurve(ctx.getInput(RAIL_B.name, GeometryBundle.class));
        CurveGeometry profileCg = extractCurve(ctx.getInput(PROFILE.name, GeometryBundle.class));
        CurveGeometry profileBCg = extractCurve(ctx.getInput(PROFILE_B.name, GeometryBundle.class));
        if (railACg == null || railBCg == null || profileCg == null) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }

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
        PackedPoints railA = railACg.resampled(xRes).curvePositions(0);
        PackedPoints railB = railBCg.resampled(xRes).curvePositions(0);

        PackedPoints profileA = profileCg.pointCount(0) == yRes ? profileCg.curvePositions(0)
                : profileCg.resampled(yRes).curvePositions(0);
        float[] profileAU = new float[yRes];
        float[] profileAV = new float[yRes];
        normalizeProfile(profileA, yRes, profileAU, profileAV);

        float[] profileBU;
        float[] profileBV;
        if (profileBCg != null) {
            PackedPoints profileB = profileBCg.pointCount(0) == yRes ? profileBCg.curvePositions(0)
                    : profileBCg.resampled(yRes).curvePositions(0);
            profileBU = new float[yRes];
            profileBV = new float[yRes];
            normalizeProfile(profileB, yRes, profileBU, profileBV);
        } else {
            profileBU = profileAU;
            profileBV = profileAV;
        }

        FloatCurveKernel blendKernel = ctx.getInput(BLEND_CLOSURE.name, FloatCurveKernel.class);
        float depthScale = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, DEPTH_SCALE.name, DEPTH_SCALE.defaultValue), 0, 1f);
        float isoCurveT = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, ISO_CURVE_T.name, ISO_CURVE_T.defaultValue), 0, -1f);
        PackedPoints isoCurvePositions = (isoCurveT >= 0f && isoCurveT <= 1f) ? new PackedPoints(xRes) : null;
        int isoRow = (isoCurvePositions != null) ? Math.round(isoCurveT * (yRes - 1)) : -1;
        PackedPoints boundaryAPositions = new PackedPoints(xRes);
        PackedPoints boundaryBPositions = new PackedPoints(xRes);
        float thickness = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, THICKNESS.name, THICKNESS.defaultValue), 0, DEFAULT_THICKNESS);

        // Profile cross-sections placed at each rail station, blended from profile A at the start of
        // the cross-section to profile B at its end; closed rails join the last station to the first.
        int spans = closedRails ? xRes : xRes - 1;
        HalfEdgeMesh mesh = new HalfEdgeMesh(xRes * yRes, 0, spans * (yRes - 1), 0);
        Vector3f tangent = new Vector3f();
        Vector3f across = new Vector3f(); // rail A → rail B direction
        Vector3f up = new Vector3f();     // tangent × across
        int[][] vid = new int[xRes][yRes];
        for (int xi = 0; xi < xRes; xi++) {
            Vector3f pA = railA.at(xi);
            Vector3f pB = railB.at(xi);
            boolean forward = closedRails || xi < xRes - 1;
            int from = forward ? xi : xi - 1;
            int to = forward ? (xi + 1) % xRes : xi;
            tangent.set(railA.at(to).add(railB.at(to)).mul(0.5f)
                    .sub(railA.at(from).add(railB.at(from)).mul(0.5f)));
            if (tangent.lengthSquared() < MIN_TANGENT_LENGTH_SQUARED) tangent.set(0, 0, 1);
            tangent.normalize();

            across.set(pB).sub(pA);
            float railSpacing = across.length();
            if (railSpacing < MIN_LENGTH) railSpacing = FALLBACK_RAIL_SPACING;
            across.normalize();

            up.set(tangent).cross(across);
            if (up.lengthSquared() < PARALLEL_LENGTH_SQUARED_THRESHOLD) {
                MeshTopology.tangentFrame(across).getColumn(0, up);
            }
            up.normalize();

            Vector3f centre = new Vector3f(pA).add(pB).mul(0.5f);

            float stationBlend = (blendKernel != null)
                    ? blendKernel.evaluate((xRes > 1) ? (float) xi / (xRes - 1) : 0.5f)
                    : Float.NaN; // sentinel: use per-yi blend

            for (int yi = 0; yi < yRes; yi++) {
                float blend = Float.isNaN(stationBlend)
                        ? ((yRes > 1) ? (float) yi / (yRes - 1) : 0.5f)
                        : stationBlend;
                float u = (profileAU[yi] * (1f - blend) + profileBU[yi] * blend) * railSpacing;
                float v = (profileAV[yi] * (1f - blend) + profileBV[yi] * blend) * railSpacing * depthScale;

                Vector3f point = new Vector3f(centre).fma(u, across).fma(v, up);
                vid[xi][yi] = mesh.addVertex(point);

                if (isoCurvePositions != null && yi == isoRow) {
                    isoCurvePositions.set(xi, point);
                }
                if (yi == 0) {
                    boundaryAPositions.set(xi, point);
                }
                if (yi == yRes - 1) {
                    boundaryBPositions.set(xi, point);
                }
            }
        }

        for (int xi = 0; xi < spans; xi++) {
            int next = (xi + 1) % xRes;
            for (int yi = 0; yi < yRes - 1; yi++) {
                mesh.addFace(vid[xi][yi], vid[xi][yi + 1], vid[next][yi + 1], vid[next][yi]);
            }
        }
        mesh.computeNormals();

        MeshTopology finalMesh = thickness > 0f ? SolidifyMeshNode.solidify(mesh, thickness) : mesh;
        ctx.setOutput(GEOMETRY.name, GeometryBundle.empty().withMesh(finalMesh));

        if (isoCurvePositions != null) {
            ctx.setOutput(ISO_CURVE.name, GeometryBundle.empty().withSlot(CurveGeometry.SLOT,
                    CurveGeometry.singlePolyline(isoCurvePositions, closedRails)));
        } else {
            ctx.setOutput(ISO_CURVE.name, GeometryBundle.empty());
        }

        ctx.setOutput(BOUNDARY_A.name, GeometryBundle.empty().withSlot(CurveGeometry.SLOT,
                CurveGeometry.singlePolyline(boundaryAPositions, closedRails)));
        ctx.setOutput(BOUNDARY_B.name, GeometryBundle.empty().withSlot(CurveGeometry.SLOT,
                CurveGeometry.singlePolyline(boundaryBPositions, closedRails)));
    }

    private static CurveGeometry extractCurve(GeometryBundle gb) {
        Object raw = gb == null ? null : gb.slots().get(CurveGeometry.SLOT);
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
    private static void normalizeProfile(PackedPoints points, int n, float[] outU, float[] outV) {
        float cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            cx += points.get(i, 0);
            cy += points.get(i, 1);
        }
        cx /= n;
        cy /= n;

        float maxU = 0;
        for (int i = 0; i < n; i++) {
            maxU = Math.max(maxU, Math.abs(points.get(i, 0) - cx));
        }
        float scale = maxU > MIN_LENGTH ? 0.5f / maxU : 1f;

        for (int i = 0; i < n; i++) {
            outU[i] = (points.get(i, 0) - cx) * scale;
            outV[i] = (points.get(i, 1) - cy) * scale;
        }
    }
}
