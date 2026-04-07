package ixdar.geometry.mesh.nodes.curve;

import java.util.List;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
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
    private static final InputPort X_RESOLUTION = new InputPort("x_resolution", PortType.INT, 32);
    private static final InputPort Y_RESOLUTION = new InputPort("y_resolution", PortType.INT, 16);
    private static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(RAIL_A, RAIL_B, PROFILE, X_RESOLUTION, Y_RESOLUTION);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle railAGb = GeometryBundles.bundlePart(ctx.getInput("rail_a", Object.class));
        GeometryBundle railBGb = GeometryBundles.bundlePart(ctx.getInput("rail_b", Object.class));
        GeometryBundle profileGb = GeometryBundles.bundlePart(ctx.getInput("profile", Object.class));

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

        int xRes = FieldBroadcast.intAt(
                FieldBroadcast.getInputOrDefault(ctx, "x_resolution", X_RESOLUTION.defaultValue()), 0, 32);
        int yRes = FieldBroadcast.intAt(
                FieldBroadcast.getInputOrDefault(ctx, "y_resolution", Y_RESOLUTION.defaultValue()), 0, 16);
        xRes = Math.max(2, Math.min(512, xRes));
        yRes = Math.max(2, Math.min(512, yRes));

        // Resample rails to xRes uniform points
        float[] railA = resampleToUniform(railACg, xRes);
        float[] railB = resampleToUniform(railBCg, xRes);

        // Extract profile points (use first polyline, normalized to unit scale)
        float[] profile = extractProfilePoints(profileCg);
        int profileN = profile.length / 3;

        // Resample profile if different from yRes
        if (profileN != yRes) {
            profile = resampleArray(profile, profileN, yRes);
            profileN = yRes;
        }

        // Normalize profile to [0,1] range in local 2D space
        // Profile XY defines the cross-section shape
        float[] profileU = new float[profileN]; // local U (across)
        float[] profileV = new float[profileN]; // local V (up)
        normalizeProfile(profile, profileN, profileU, profileV);

        // Build loft mesh
        HalfEdgeMesh mesh = buildLoftMesh(railA, railB, xRes, profileU, profileV, profileN);
        mesh.computeNormals();

        ctx.setOutput("geometry", GeometryBundle.empty().withMesh(mesh));
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
     * Centers the profile and scales so the extent maps to [-0.5, 0.5].
     * This ensures the profile fills the gap between the two rails.
     */
    private static void normalizeProfile(float[] profile, int n, float[] outU, float[] outV) {
        float cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            cx += profile[i * 3];
            cy += profile[i * 3 + 1];
        }
        cx /= n;
        cy /= n;

        // Find max extent for normalization
        float maxExt = 0;
        for (int i = 0; i < n; i++) {
            float dx = Math.abs(profile[i * 3] - cx);
            float dy = Math.abs(profile[i * 3 + 1] - cy);
            maxExt = Math.max(maxExt, Math.max(dx, dy));
        }
        float scale = maxExt > 1e-10f ? 0.5f / maxExt : 1f;

        for (int i = 0; i < n; i++) {
            outU[i] = (profile[i * 3] - cx) * scale;
            outV[i] = (profile[i * 3 + 1] - cy) * scale;
        }
    }

    /**
     * Build the loft mesh by placing profile cross-sections at each station along the rails.
     */
    private static HalfEdgeMesh buildLoftMesh(float[] railA, float[] railB,
                                               int xRes, float[] profileU, float[] profileV, int yRes) {
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
            up.set(tangent).cross(across).normalize();

            // Center point between rails
            float cx = (pA.x + pB.x) * 0.5f;
            float cy = (pA.y + pB.y) * 0.5f;
            float cz = (pA.z + pB.z) * 0.5f;

            // Place profile at this station
            for (int yi = 0; yi < yRes; yi++) {
                float u = profileU[yi] * railSpacing; // scale profile across to match rail spacing
                float v = profileV[yi] * railSpacing; // scale profile up proportionally

                float px = cx + across.x * u + up.x * v;
                float py = cy + across.y * u + up.y * v;
                float pz = cz + across.z * u + up.z * v;
                vid[xi][yi] = mesh.addVertex(px, py, pz);
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
