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

    private static final InputPort RAIL_A = new InputPort("rail_a", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort RAIL_B = new InputPort("rail_b", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort PROFILE = new InputPort("profile", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort PROFILE_B = new InputPort("profile_b", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort X_RESOLUTION = new InputPort("x_resolution", PortType.INT, 32, 2f, 512f);
    private static final InputPort Y_RESOLUTION = new InputPort("y_resolution", PortType.INT, 16, 2f, 512f);
    private static final InputPort BLEND_CLOSURE = new InputPort("blend_closure", PortType.CLOSURE, null);
    private static final InputPort DEPTH_SCALE = new InputPort("depth_scale", PortType.FLOAT, 1f, -100f, 100f);
    private static final InputPort ISO_CURVE_T = new InputPort("iso_curve_t", PortType.FLOAT, -1f, -1f, 1f);
    private static final InputPort THICKNESS = new InputPort("thickness", PortType.FLOAT, 0.001f, 0f, 10f);
    private static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);
    private static final OutputPort ISO_CURVE = new OutputPort("iso_curve", PortType.GEOMETRY_BUNDLE);
    private static final OutputPort BOUNDARY_A = new OutputPort("boundary_a", PortType.GEOMETRY_BUNDLE);
    private static final OutputPort BOUNDARY_B = new OutputPort("boundary_b", PortType.GEOMETRY_BUNDLE);

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
        GeometryBundle railAGb = GeometryBundles.bundlePart(ctx.getInput("rail_a", Object.class));
        GeometryBundle railBGb = GeometryBundles.bundlePart(ctx.getInput("rail_b", Object.class));
        GeometryBundle profileGb = GeometryBundles.bundlePart(ctx.getInput("profile", Object.class));
        GeometryBundle profileBGb = GeometryBundles.bundlePart(ctx.getInput("profile_b", Object.class));

        if (railAGb == null || railBGb == null || profileGb == null) {
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }

        CurveGeometry railACg = extractCurve(railAGb);
        CurveGeometry railBCg = extractCurve(railBGb);
        CurveGeometry profileCg = extractCurve(profileGb);

        if (railACg == null || railBCg == null || profileCg == null) {
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }

        // Optional second profile for blending
        CurveGeometry profileBCg = (profileBGb != null) ? extractCurve(profileBGb) : null;

        int xRes = FieldBroadcast.intAt(
                FieldBroadcast.getInputOrDefault(ctx, "x_resolution", X_RESOLUTION.defaultValue()), 0, 32);
        int yRes = FieldBroadcast.intAt(
                FieldBroadcast.getInputOrDefault(ctx, "y_resolution", Y_RESOLUTION.defaultValue()), 0, 16);
        xRes = Math.max(2, Math.min(512, xRes));
        yRes = Math.max(2, Math.min(512, yRes));

        // Resample rails to xRes uniform points
        float[] railA = resampleToUniform(railACg, xRes);
        float[] railB = resampleToUniform(railBCg, xRes);

        // Extract and normalize profile A
        float[] profileA = extractProfilePoints(profileCg);
        int profileAN = profileA.length / 3;
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
            int profileBN = profileB.length / 3;
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
        Object closureObj = ctx.getInput("blend_closure", Object.class);
        FloatCurveKernel blendKernel = (closureObj instanceof FloatCurveKernel k) ? k : null;

        float depthScale = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, "depth_scale", DEPTH_SCALE.defaultValue()), 0, 1f);

        float isoCurveT = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, "iso_curve_t", ISO_CURVE_T.defaultValue()), 0, -1f);

        // Build loft mesh with two-profile blending
        // If iso_curve_t >= 0, also extract the iso-parameter curve at that profile fraction
        float[] isoCurvePositions = (isoCurveT >= 0f && isoCurveT <= 1f) ? new float[xRes * 3] : null;
        int isoRow = (isoCurvePositions != null) ? Math.round(isoCurveT * (yRes - 1)) : -1;

        // Always extract boundary curves (yi=0 → boundary_a, yi=yRes-1 → boundary_b)
        float[] boundaryAPositions = new float[xRes * 3];
        float[] boundaryBPositions = new float[xRes * 3];

        float thickness = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, "thickness", THICKNESS.defaultValue()), 0, 0.001f);

        HalfEdgeMesh mesh = buildLoftMesh(railA, railB, xRes,
                profileAU, profileAV, profileBU, profileBV, yRes, blendKernel, depthScale,
                isoRow, isoCurvePositions, boundaryAPositions, boundaryBPositions);
        mesh.computeNormals();

        // Solidify the open loft surface to make it watertight
        MeshTopology finalMesh;
        if (thickness > 0f) {
            finalMesh = ArrayMeshEngine.solidifyUniformMeshTopology(mesh, thickness);
        } else {
            finalMesh = mesh;
        }

        GeometryBundle outBundle = GeometryBundle.empty().withMesh(finalMesh);
        ctx.setOutput("geometry", outBundle);

        // Output iso-curve if requested
        if (isoCurvePositions != null) {
            ctx.setOutput("iso_curve", makeClosedCurveBundle(isoCurvePositions));
        } else {
            ctx.setOutput("iso_curve", GeometryBundle.empty());
        }

        // Output boundary curves (always available — these are the actual surface edges)
        ctx.setOutput("boundary_a", makeClosedCurveBundle(boundaryAPositions));
        ctx.setOutput("boundary_b", makeClosedCurveBundle(boundaryBPositions));
    }

    /** Close a polyline loop and wrap in a GeometryBundle with _curve slot. */
    private static GeometryBundle makeClosedCurveBundle(float[] positions) {
        float[] closed = new float[positions.length + 3];
        System.arraycopy(positions, 0, closed, 0, positions.length);
        closed[closed.length - 3] = positions[0];
        closed[closed.length - 2] = positions[1];
        closed[closed.length - 1] = positions[2];
        CurveGeometry curve = CurveGeometry.singlePolyline(closed);
        return GeometryBundle.empty().withSlot("_curve", curve);
    }

    private static CurveGeometry extractCurve(GeometryBundle gb) {
        Object raw = gb.slots().get("_curve");
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
            int b0 = 3 * (off0 + i - 1);
            int b1 = 3 * (off0 + i);
            float dx = pos[b1] - pos[b0];
            float dy = pos[b1 + 1] - pos[b0 + 1];
            float dz = pos[b1 + 2] - pos[b0 + 2];
            arcLen[i] = arcLen[i - 1] + (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        float totalLen = arcLen[nPts - 1];

        float[] result = new float[n * 3];
        int seg = 0;
        for (int i = 0; i < n; i++) {
            float targetLen = (n == 1) ? 0 : totalLen * i / (n - 1);
            while (seg < nPts - 2 && arcLen[seg + 1] < targetLen) seg++;

            float segLen = arcLen[seg + 1] - arcLen[seg];
            float t = segLen < 1e-10f ? 0f : (targetLen - arcLen[seg]) / segLen;

            int b0 = 3 * (off0 + seg);
            int b1 = 3 * (off0 + seg + 1);
            result[i * 3] = pos[b0] + t * (pos[b1] - pos[b0]);
            result[i * 3 + 1] = pos[b0 + 1] + t * (pos[b1 + 1] - pos[b0 + 1]);
            result[i * 3 + 2] = pos[b0 + 2] + t * (pos[b1 + 2] - pos[b0 + 2]);
        }
        return result;
    }

    private static float[] extractProfilePoints(CurveGeometry cg) {
        float[] pos = cg.positions();
        int off0 = cg.curveOffsets()[0];
        int off1 = cg.curveOffsets()[1];
        int nPts = off1 - off0;
        float[] out = new float[nPts * 3];
        System.arraycopy(pos, off0 * 3, out, 0, nPts * 3);
        return out;
    }

    /**
     * Resample a flat XYZ array from srcN points to dstN points by arc-length interpolation.
     */
    private static float[] resampleArray(float[] src, int srcN, int dstN) {
        float[] arcLen = new float[srcN];
        arcLen[0] = 0;
        for (int i = 1; i < srcN; i++) {
            float dx = src[i * 3] - src[(i - 1) * 3];
            float dy = src[i * 3 + 1] - src[(i - 1) * 3 + 1];
            float dz = src[i * 3 + 2] - src[(i - 1) * 3 + 2];
            arcLen[i] = arcLen[i - 1] + (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        float total = arcLen[srcN - 1];

        float[] result = new float[dstN * 3];
        int seg = 0;
        for (int i = 0; i < dstN; i++) {
            float target = (dstN == 1) ? 0 : total * i / (dstN - 1);
            while (seg < srcN - 2 && arcLen[seg + 1] < target) seg++;

            float segLen = arcLen[seg + 1] - arcLen[seg];
            float t = segLen < 1e-10f ? 0f : (target - arcLen[seg]) / segLen;

            result[i * 3] = src[seg * 3] + t * (src[(seg + 1) * 3] - src[seg * 3]);
            result[i * 3 + 1] = src[seg * 3 + 1] + t * (src[(seg + 1) * 3 + 1] - src[seg * 3 + 1]);
            result[i * 3 + 2] = src[seg * 3 + 2] + t * (src[(seg + 1) * 3 + 2] - src[seg * 3 + 2]);
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
        float scale = maxU > 1e-10f ? 0.5f / maxU : 1f;

        for (int i = 0; i < n; i++) {
            outU[i] = (profile[i * 3] - cx) * scale;
            outV[i] = (profile[i * 3 + 1] - cy) * scale;
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
            pA.set(railA[xi * 3], railA[xi * 3 + 1], railA[xi * 3 + 2]);
            pB.set(railB[xi * 3], railB[xi * 3 + 1], railB[xi * 3 + 2]);

            // Tangent: forward direction along rails
            if (xi < xRes - 1) {
                tangent.set(
                        (railA[(xi + 1) * 3] + railB[(xi + 1) * 3]) * 0.5f -
                                (railA[xi * 3] + railB[xi * 3]) * 0.5f,
                        (railA[(xi + 1) * 3 + 1] + railB[(xi + 1) * 3 + 1]) * 0.5f -
                                (railA[xi * 3 + 1] + railB[xi * 3 + 1]) * 0.5f,
                        (railA[(xi + 1) * 3 + 2] + railB[(xi + 1) * 3 + 2]) * 0.5f -
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
            if (tangent.lengthSquared() < 1e-20f) tangent.set(0, 0, 1);
            tangent.normalize();

            // Across: direction from rail A to rail B
            across.set(pB).sub(pA);
            float railSpacing = across.length();
            if (railSpacing < 1e-10f) railSpacing = 1e-5f;
            across.normalize();

            // Up: perpendicular to tangent and across
            up.set(tangent).cross(across);
            if (up.lengthSquared() < 1e-8f) {
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
        for (int xi = 0; xi < xRes - 1; xi++) {
            for (int yi = 0; yi < yRes - 1; yi++) {
                mesh.addFace(vid[xi][yi], vid[xi][yi + 1], vid[xi + 1][yi + 1], vid[xi + 1][yi]);
            }
        }

        return mesh;
    }
}
