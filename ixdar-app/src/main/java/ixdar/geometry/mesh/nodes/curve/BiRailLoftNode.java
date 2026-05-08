package ixdar.geometry.mesh.nodes.curve;

import java.util.List;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.geometry.mesh.curve.FloatCurveKernel;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.ArrayMeshEngine;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Bi-rail loft: generates a surface mesh by sweeping profile curves between two rail curves.
 * <p>
 * Algorithm:
 * <ol>
 *   <li>Resample both rails to xResolution uniform points</li>
 *   <li>Resample profile to yResolution uniform points</li>
 *   <li>For each station along the rails, compute a local frame (tangent, normal, binormal)</li>
 *   <li>Interpolate position between rail A and rail B</li>
 *   <li>Place profile cross-section at each station, scaled to match rail spacing</li>
 *   <li>Connect adjacent cross-sections into a quad mesh</li>
 * </ol>
 */
@MeshNodeAnnotation(id = "bi_rail_loft")
public class BiRailLoftNode implements MeshNode {
    public static final String RAIL_A_2 = "rail_a";
    public static final String RAIL_B_2 = "rail_b";
    public static final String PROFILE_2 = "profile";
    public static final String PROFILE_B_2 = "profile_b";
    public static final String X_RESOLUTION_2 = "x_resolution";
    public static final String Y_RESOLUTION_2 = "y_resolution";
    public static final String BLEND_CLOSURE_2 = "blend_closure";
    public static final String DEPTH_SCALE_2 = "depth_scale";
    public static final String ISO_CURVE_T_2 = "iso_curve_t";
    public static final String THICKNESS_2 = "thickness";
    public static final String GEOMETRY_2 = "geometry";
    public static final String ISO_CURVE_2 = "iso_curve";
    public static final String BOUNDARY_A_2 = "boundary_a";
    public static final String BOUNDARY_B_2 = "boundary_b";
    public static final String CURVE = "_curve";
    public static final int NUM_32 = 32;
    public static final int NUM_16 = 16;
    public static final int NUM_512 = 512;
    public static final int NUM_3 = 3;
    public static final float NUM_1 = 1f;
    public static final float NUM_0 = 0f;
    public static final float NUM_0_001 = 0.001f;
    public static final float NUM_1e_10 = 1e-10f;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_1e_20 = 1e-20f;
    public static final float NUM_1e_5 = 1e-5f;
    public static final float NUM_1e_8 = 1e-8f;

    private static final InputPort RAIL_A = new InputPort(RAIL_A_2, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort RAIL_B = new InputPort(RAIL_B_2, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort PROFILE = new InputPort(PROFILE_2, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort PROFILE_B = new InputPort(PROFILE_B_2, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort X_RESOLUTION = new InputPort(X_RESOLUTION_2, PortType.INT, 32, 2f, 512f);
    private static final InputPort Y_RESOLUTION = new InputPort(Y_RESOLUTION_2, PortType.INT, 16, 2f, 512f);
    private static final InputPort BLEND_CLOSURE = new InputPort(BLEND_CLOSURE_2, PortType.CLOSURE, null);
    private static final InputPort DEPTH_SCALE = new InputPort(DEPTH_SCALE_2, PortType.FLOAT, 1f, -100f, 100f);
    private static final InputPort ISO_CURVE_T = new InputPort(ISO_CURVE_T_2, PortType.FLOAT, -1f, -1f, 1f);
    private static final InputPort THICKNESS = new InputPort(THICKNESS_2, PortType.FLOAT, 0.001f, 0f, 10f);
    private static final OutputPort GEOMETRY = new OutputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE);
    private static final OutputPort ISO_CURVE = new OutputPort(ISO_CURVE_2, PortType.GEOMETRY_BUNDLE);
    private static final OutputPort BOUNDARY_A = new OutputPort(BOUNDARY_A_2, PortType.GEOMETRY_BUNDLE);
    private static final OutputPort BOUNDARY_B = new OutputPort(BOUNDARY_B_2, PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Generates a surface by sweeping profile curves between two rail curves with optional profile blending, depth scaling, and solidification via thickness.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.ofEntries(
                java.util.Map.entry(RAIL_A_2, "First rail curve defining the surface boundary along one side."),
                java.util.Map.entry(RAIL_B_2, "Second rail curve defining the opposing boundary."),
                java.util.Map.entry(PROFILE_2, "Primary cross-section curve swept between rails."),
                java.util.Map.entry(PROFILE_B_2, "Optional second cross-section. When set, the surface blends from `profile` at V=0 to `profile_b` at V=1."),
                java.util.Map.entry(X_RESOLUTION_2, "Samples along the U direction (along the rails). Higher = smoother sweep."),
                java.util.Map.entry(Y_RESOLUTION_2, "Samples along the V direction (across the profile). Higher = smoother cross-section."),
                java.util.Map.entry(BLEND_CLOSURE_2, "Optional float closure controlling profile-to-profile_b blend as a function of V."),
                java.util.Map.entry(DEPTH_SCALE_2, "Multiplier on profile depth. 1 = as-authored; 0 = flat sheet."),
                java.util.Map.entry(ISO_CURVE_T_2, "If ≥ 0, also output an iso-curve at this U parameter. -1 = disabled."),
                java.util.Map.entry(THICKNESS_2, "Solidify thickness for closed shells. 0 = open surface. Typical 0.001."),
                java.util.Map.entry(GEOMETRY_2, "Generated surface (possibly solidified)."),
                java.util.Map.entry(ISO_CURVE_2, "U-isocurve at iso_curve_t (empty if disabled)."),
                java.util.Map.entry(BOUNDARY_A_2, "Ordered boundary curve on the rail_a side of the surface (for bridging)."),
                java.util.Map.entry(BOUNDARY_B_2, "Ordered boundary curve on the rail_b side.")
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
        GeometryBundle railAGb = GeometryBundles.bundlePart(ctx.getInput(RAIL_A_2, Object.class));
        GeometryBundle railBGb = GeometryBundles.bundlePart(ctx.getInput(RAIL_B_2, Object.class));
        GeometryBundle profileGb = GeometryBundles.bundlePart(ctx.getInput(PROFILE_2, Object.class));
        GeometryBundle profileBGb = GeometryBundles.bundlePart(ctx.getInput(PROFILE_B_2, Object.class));

        if (railAGb == null || railBGb == null || profileGb == null) {
            ctx.setOutput(GEOMETRY_2, GeometryBundle.empty());
            return;
        }

        CurveGeometry railACg = extractCurve(railAGb);
        CurveGeometry railBCg = extractCurve(railBGb);
        CurveGeometry profileCg = extractCurve(profileGb);

        if (railACg == null || railBCg == null || profileCg == null) {
            ctx.setOutput(GEOMETRY_2, GeometryBundle.empty());
            return;
        }

        // Optional second profile for blending
        CurveGeometry profileBCg = (profileBGb != null) ? extractCurve(profileBGb) : null;

        int xRes = FieldBroadcast.intAt(
                FieldBroadcast.getInputOrDefault(ctx, X_RESOLUTION_2, X_RESOLUTION.defaultValue()), 0, NUM_32);
        int yRes = FieldBroadcast.intAt(
                FieldBroadcast.getInputOrDefault(ctx, Y_RESOLUTION_2, Y_RESOLUTION.defaultValue()), 0, NUM_16);
        xRes = Math.max(2, Math.min(NUM_512, xRes));
        yRes = Math.max(2, Math.min(NUM_512, yRes));

        // Resample rails to xRes uniform points
        float[] railA = resampleToUniform(railACg, xRes);
        float[] railB = resampleToUniform(railBCg, xRes);

        // Extract and normalize profile A
        float[] profileA = extractProfilePoints(profileCg);
        int profileAN = profileA.length / NUM_3;
        if (profileAN != yRes) {
            profileA = resampleArray(profileA, profileAN, yRes);
        }
        float[] profileAU = new float[yRes];
        float[] profileAV = new float[yRes];
        normalizeProfile(profileA, yRes, profileAU, profileAV);

        // Extract and normalize profile B (or reuse A if not provided)
        float[] profileBU;
        float[] profileBV;
        if (profileBCg != null) {
            float[] profileB = extractProfilePoints(profileBCg);
            int profileBN = profileB.length / NUM_3;
            if (profileBN != yRes) {
                profileB = resampleArray(profileB, profileBN, yRes);
            }
            profileBU = new float[yRes];
            profileBV = new float[yRes];
            normalizeProfile(profileB, yRes, profileBU, profileBV);
        } else {
            profileBU = profileAU;
            profileBV = profileAV;
        }

        // Optional blend closure for per-station blending along the rail
        Object closureObj = ctx.getInput(BLEND_CLOSURE_2, Object.class);
        FloatCurveKernel blendKernel = (closureObj instanceof FloatCurveKernel k) ? k : null;

        float depthScale = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, DEPTH_SCALE_2, DEPTH_SCALE.defaultValue()), 0, NUM_1);

        float isoCurveT = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, ISO_CURVE_T_2, ISO_CURVE_T.defaultValue()), 0, -NUM_1);

        // Build loft mesh with two-profile blending
        // If iso_curve_t >= 0, also extract the iso-parameter curve at that profile fraction
        float[] isoCurvePositions = (isoCurveT >= NUM_0 && isoCurveT <= NUM_1) ? new float[xRes * NUM_3] : null;
        int isoRow = (isoCurvePositions != null) ? Math.round(isoCurveT * (yRes - 1)) : -1;

        // Always extract boundary curves (yi=0 → boundary_a, yi=yRes-1 → boundary_b)
        float[] boundaryAPositions = new float[xRes * NUM_3];
        float[] boundaryBPositions = new float[xRes * NUM_3];

        float thickness = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, THICKNESS_2, THICKNESS.defaultValue()), 0, NUM_0_001);

        HalfEdgeMesh mesh = buildLoftMesh(railA, railB, xRes,
                profileAU, profileAV, profileBU, profileBV, yRes, blendKernel, depthScale,
                isoRow, isoCurvePositions, boundaryAPositions, boundaryBPositions);
        mesh.computeNormals();

        // Solidify the open loft surface to make it watertight
        MeshTopology finalMesh;
        if (thickness > NUM_0) {
            finalMesh = ArrayMeshEngine.solidifyUniformMeshTopology(mesh, thickness);
        } else {
            finalMesh = mesh;
        }

        GeometryBundle outBundle = GeometryBundle.empty().withMesh(finalMesh);
        ctx.setOutput(GEOMETRY_2, outBundle);

        // Output iso-curve if requested
        if (isoCurvePositions != null) {
            ctx.setOutput(ISO_CURVE_2, makeClosedCurveBundle(isoCurvePositions));
        } else {
            ctx.setOutput(ISO_CURVE_2, GeometryBundle.empty());
        }

        // Output boundary curves (always available — these are the actual surface edges)
        ctx.setOutput(BOUNDARY_A_2, makeClosedCurveBundle(boundaryAPositions));
        ctx.setOutput(BOUNDARY_B_2, makeClosedCurveBundle(boundaryBPositions));
    }

    /** Close a polyline loop and wrap in a GeometryBundle with _curve slot. */
    private static GeometryBundle makeClosedCurveBundle(float[] positions) {
        float[] closed = new float[positions.length + NUM_3];
        System.arraycopy(positions, 0, closed, 0, positions.length);
        closed[closed.length - NUM_3] = positions[0];
        closed[closed.length - 2] = positions[1];
        closed[closed.length - 1] = positions[2];
        CurveGeometry curve = CurveGeometry.singlePolyline(closed);
        return GeometryBundle.empty().withSlot(CURVE, curve);
    }

    private static CurveGeometry extractCurve(GeometryBundle gb) {
        Object raw = gb.slots().get(CURVE);
        if (raw instanceof CurveGeometry cg && cg.pointCount() >= 2) {
            return cg;
        }
        return null;
    }

    /**
     * Resample a CurveGeometry's first polyline to n uniformly spaced points.
     */
    private static float[] resampleToUniform(CurveGeometry cg, int n) {
        float[] pos = cg.positions();
        int off0 = cg.curveOffsets()[0];
        int off1 = cg.curveOffsets()[1];
        int nPts = off1 - off0;

        // Compute arc lengths
        float[] arcLen = new float[nPts];
        arcLen[0] = 0;
        for (int i = 1; i < nPts; i++) {
            int b0 = NUM_3 * (off0 + i - 1);
            int b1 = NUM_3 * (off0 + i);
            float dx = pos[b1] - pos[b0];
            float dy = pos[b1 + 1] - pos[b0 + 1];
            float dz = pos[b1 + 2] - pos[b0 + 2];
            arcLen[i] = arcLen[i - 1] + (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        float totalLen = arcLen[nPts - 1];

        float[] result = new float[n * NUM_3];
        int seg = 0;
        for (int i = 0; i < n; i++) {
            float targetLen = (n == 1) ? 0 : totalLen * i / (n - 1);
            while (seg < nPts - 2 && arcLen[seg + 1] < targetLen) seg++;

            float segLen = arcLen[seg + 1] - arcLen[seg];
            float t = segLen < NUM_1e_10 ? NUM_0 : (targetLen - arcLen[seg]) / segLen;

            int b0 = NUM_3 * (off0 + seg);
            int b1 = NUM_3 * (off0 + seg + 1);
            result[i * NUM_3] = pos[b0] + t * (pos[b1] - pos[b0]);
            result[i * NUM_3 + 1] = pos[b0 + 1] + t * (pos[b1 + 1] - pos[b0 + 1]);
            result[i * NUM_3 + 2] = pos[b0 + 2] + t * (pos[b1 + 2] - pos[b0 + 2]);
        }
        return result;
    }

    private static float[] extractProfilePoints(CurveGeometry cg) {
        float[] pos = cg.positions();
        int off0 = cg.curveOffsets()[0];
        int off1 = cg.curveOffsets()[1];
        int nPts = off1 - off0;
        float[] out = new float[nPts * NUM_3];
        System.arraycopy(pos, off0 * NUM_3, out, 0, nPts * NUM_3);
        return out;
    }

    /**
     * Resample a flat XYZ array from srcN points to dstN points by arc-length interpolation.
     */
    private static float[] resampleArray(float[] src, int srcN, int dstN) {
        float[] arcLen = new float[srcN];
        arcLen[0] = 0;
        for (int i = 1; i < srcN; i++) {
            float dx = src[i * NUM_3] - src[(i - 1) * NUM_3];
            float dy = src[i * NUM_3 + 1] - src[(i - 1) * NUM_3 + 1];
            float dz = src[i * NUM_3 + 2] - src[(i - 1) * NUM_3 + 2];
            arcLen[i] = arcLen[i - 1] + (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        float total = arcLen[srcN - 1];

        float[] result = new float[dstN * NUM_3];
        int seg = 0;
        for (int i = 0; i < dstN; i++) {
            float target = (dstN == 1) ? 0 : total * i / (dstN - 1);
            while (seg < srcN - 2 && arcLen[seg + 1] < target) seg++;

            float segLen = arcLen[seg + 1] - arcLen[seg];
            float t = segLen < NUM_1e_10 ? NUM_0 : (target - arcLen[seg]) / segLen;

            result[i * NUM_3] = src[seg * NUM_3] + t * (src[(seg + 1) * NUM_3] - src[seg * NUM_3]);
            result[i * NUM_3 + 1] = src[seg * NUM_3 + 1] + t * (src[(seg + 1) * NUM_3 + 1] - src[seg * NUM_3 + 1]);
            result[i * NUM_3 + 2] = src[seg * NUM_3 + 2] + t * (src[(seg + 1) * NUM_3 + 2] - src[seg * NUM_3 + 2]);
        }
        return result;
    }

    /**
     * Normalize profile to local 2D coordinates (U=across, V=up).
     * Centers the profile and scales U (across) to fill [-0.5, 0.5] so the
     * cross-section spans the gap between rails. V (up/depth) is scaled by
     * the same factor, preserving aspect ratio — this allows the collar
     * to extend beyond the rail spacing when the profile is taller than wide.
     */
    private static void normalizeProfile(float[] profile, int n, float[] outU, float[] outV) {
        float cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            cx += profile[i * NUM_3];
            cy += profile[i * NUM_3 + 1];
        }
        cx /= n;
        cy /= n;

        // Scale based on U (across) extent only, so cross-section fills the rail gap
        float maxU = 0;
        for (int i = 0; i < n; i++) {
            maxU = Math.max(maxU, Math.abs(profile[i * NUM_3] - cx));
        }
        float scale = maxU > NUM_1e_10 ? NUM_0_5 / maxU : NUM_1;

        for (int i = 0; i < n; i++) {
            outU[i] = (profile[i * NUM_3] - cx) * scale;
            outV[i] = (profile[i * NUM_3 + 1] - cy) * scale;
        }
    }

    /**
     * Build the loft mesh by placing profile cross-sections at each station along the rails.
     * When two profiles are provided, linearly interpolates between them across the cross-section:
     * profile A dominates near the start of the cross-section, profile B near the end.
     */
    private static HalfEdgeMesh buildLoftMesh(float[] railA, float[] railB, int xRes,
                                               float[] profileAU, float[] profileAV,
                                               float[] profileBU, float[] profileBV, int yRes,
                                               FloatCurveKernel blendKernel, float depthScale,
                                               int isoRow, float[] isoCurveOut,
                                               float[] boundaryAOut, float[] boundaryBOut) {
        HalfEdgeMesh mesh = new HalfEdgeMesh(xRes * yRes, 0, (xRes - 1) * (yRes - 1), 0);

        Vector3f pA = new Vector3f();
        Vector3f pB = new Vector3f();
        Vector3f tangent = new Vector3f();
        Vector3f across = new Vector3f(); // rail A → rail B direction
        Vector3f up = new Vector3f();     // tangent × across

        int[][] vid = new int[xRes][yRes];

        for (int xi = 0; xi < xRes; xi++) {
            // Rail positions at this station
            pA.set(railA[xi * NUM_3], railA[xi * NUM_3 + 1], railA[xi * NUM_3 + 2]);
            pB.set(railB[xi * NUM_3], railB[xi * NUM_3 + 1], railB[xi * NUM_3 + 2]);

            // Tangent: forward direction along rails
            if (xi < xRes - 1) {
                tangent.set(
                        (railA[(xi + 1) * NUM_3] + railB[(xi + 1) * NUM_3]) * NUM_0_5 -
                                (railA[xi * NUM_3] + railB[xi * NUM_3]) * NUM_0_5,
                        (railA[(xi + 1) * NUM_3 + 1] + railB[(xi + 1) * NUM_3 + 1]) * NUM_0_5 -
                                (railA[xi * NUM_3 + 1] + railB[xi * NUM_3 + 1]) * NUM_0_5,
                        (railA[(xi + 1) * NUM_3 + 2] + railB[(xi + 1) * NUM_3 + 2]) * NUM_0_5 -
                                (railA[xi * NUM_3 + 2] + railB[xi * NUM_3 + 2]) * NUM_0_5
                );
            } else {
                tangent.set(
                        (railA[xi * NUM_3] + railB[xi * NUM_3]) * NUM_0_5 -
                                (railA[(xi - 1) * NUM_3] + railB[(xi - 1) * NUM_3]) * NUM_0_5,
                        (railA[xi * NUM_3 + 1] + railB[xi * NUM_3 + 1]) * NUM_0_5 -
                                (railA[(xi - 1) * NUM_3 + 1] + railB[(xi - 1) * NUM_3 + 1]) * NUM_0_5,
                        (railA[xi * NUM_3 + 2] + railB[xi * NUM_3 + 2]) * NUM_0_5 -
                                (railA[(xi - 1) * NUM_3 + 2] + railB[(xi - 1) * NUM_3 + 2]) * NUM_0_5
                );
            }
            if (tangent.lengthSquared() < NUM_1e_20) tangent.set(0, 0, 1);
            tangent.normalize();

            // Across: direction from rail A to rail B
            across.set(pB).sub(pA);
            float railSpacing = across.length();
            if (railSpacing < NUM_1e_10) railSpacing = NUM_1e_5;
            across.normalize();

            // Up: perpendicular to tangent and across
            up.set(tangent).cross(across);
            if (up.lengthSquared() < NUM_1e_8) {
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
            float cx = (pA.x + pB.x) * NUM_0_5;
            float cy = (pA.y + pB.y) * NUM_0_5;
            float cz = (pA.z + pB.z) * NUM_0_5;

            // Place profile at this station, blending between profile A and B.
            // When blendKernel is provided, blend varies per-station (along rail, xi direction).
            // When null, blend varies per-point (across cross-section, yi direction).
            float stationBlend = (blendKernel != null)
                    ? blendKernel.evaluate((xRes > 1) ? (float) xi / (xRes - 1) : NUM_0_5)
                    : Float.NaN; // sentinel: use per-yi blend

            for (int yi = 0; yi < yRes; yi++) {
                float blend = Float.isNaN(stationBlend)
                        ? ((yRes > 1) ? (float) yi / (yRes - 1) : NUM_0_5)
                        : stationBlend;
                float u = (profileAU[yi] * (NUM_1 - blend) + profileBU[yi] * blend) * railSpacing;
                float v = (profileAV[yi] * (NUM_1 - blend) + profileBV[yi] * blend) * railSpacing * depthScale;

                float px = cx + across.x * u + up.x * v;
                float py = cy + across.y * u + up.y * v;
                float pz = cz + across.z * u + up.z * v;
                vid[xi][yi] = mesh.addVertex(px, py, pz);

                // Record iso-curve point if this is the target row
                if (isoCurveOut != null && yi == isoRow) {
                    isoCurveOut[xi * NUM_3] = px;
                    isoCurveOut[xi * NUM_3 + 1] = py;
                    isoCurveOut[xi * NUM_3 + 2] = pz;
                }
                // Record boundary curves (first and last profile rows)
                if (yi == 0 && boundaryAOut != null) {
                    boundaryAOut[xi * NUM_3] = px;
                    boundaryAOut[xi * NUM_3 + 1] = py;
                    boundaryAOut[xi * NUM_3 + 2] = pz;
                }
                if (yi == yRes - 1 && boundaryBOut != null) {
                    boundaryBOut[xi * NUM_3] = px;
                    boundaryBOut[xi * NUM_3 + 1] = py;
                    boundaryBOut[xi * NUM_3 + 2] = pz;
                }
            }
        }

        // Connect quads
        for (int xi = 0; xi < xRes - 1; xi++) {
            for (int yi = 0; yi < yRes - 1; yi++) {
                mesh.addFace(vid[xi][yi], vid[xi][yi + 1], vid[xi + 1][yi + 1], vid[xi + 1][yi]);
            }
        }

        return mesh;
    }
}
