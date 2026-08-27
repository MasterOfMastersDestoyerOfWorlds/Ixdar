package ixdar.geometry.mesh.nodes.curve;

import java.util.List;

import org.joml.Vector3f;

import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Ruled bi-rail loft: the first polyline in {@code rail_a} and {@code rail_b} are resampled to
 * {@code u_segments} points by arc length, then meshed with {@code v_segments} steps of linear
 * interpolation between corresponding pairs (uniform across the span). Both rails must match
 * open vs closed topology.
 */
@MeshNodeAnnotation(id = "birail_loft")
public class BirailLoftMeshNode implements MeshNode {
    public static final int NUM_16 = 16;
    public static final int NUM_8 = 8;
    public static final int NUM_3 = 3;
    public static final float NUM_1e_5 = 1e-5f;
    public static final float NUM_1e_4 = 1e-4f;
    public static final float NUM_0 = 0f;
    public static final float NUM_1e_20 = 1e-20f;
    public static final float NUM_1e_8 = 1e-8f;

    public static final InputPort RAIL_A = new InputPort("rail_a", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort RAIL_B = new InputPort("rail_b", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort U_SEGMENTS = new InputPort("u_segments", PortType.INT, 16, 2f, 512f);
    public static final InputPort V_SEGMENTS = new InputPort("v_segments", PortType.INT, 8, 2f, 512f);
    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Creates a ruled surface by linearly interpolating between two rail curves resampled by arc length, with configurable U and V segment counts.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                RAIL_A.name, "First rail curve defining one surface boundary.",
                RAIL_B.name, "Second rail curve defining the opposing boundary.",
                U_SEGMENTS.name, "Samples along the rails. Higher = smoother sweep.",
                V_SEGMENTS.name, "Samples across (from rail_a to rail_b). Higher = smoother cross-section.",
                GEOMETRY.name, "Ruled surface as a geometry bundle."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(RAIL_A, RAIL_B, U_SEGMENTS, V_SEGMENTS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle ga = GeometryBundles.bundlePart(ctx.getInput(RAIL_A.name, Object.class));
        GeometryBundle gb = GeometryBundles.bundlePart(ctx.getInput(RAIL_B.name, Object.class));
        if (ga == null || gb == null) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }
        Object ca = ga.slots().get(CurveGeometry.SLOT);
        Object cb = gb.slots().get(CurveGeometry.SLOT);
        if (!(ca instanceof CurveGeometry cga) || !(cb instanceof CurveGeometry cgb)) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }

        int uSeg = readInt(ctx, U_SEGMENTS.name, NUM_16);
        int vSeg = readInt(ctx, V_SEGMENTS.name, NUM_8);
        uSeg = Math.max(2, uSeg);
        vSeg = Math.max(2, vSeg);

        Polyline pa = extractPolyline(cga);
        Polyline pb = extractPolyline(cgb);
        if (pa == null || pb == null) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }
        if (pa.closed != pb.closed) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }

        Vector3f[] aRes = resampleArcLength(pa.points, pa.closed, uSeg);
        Vector3f[] bRes = resampleArcLength(pb.points, pb.closed, uSeg);
        if (aRes == null || bRes == null) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }

        HalfEdgeMesh mesh = new HalfEdgeMesh();
        int[][] vid = new int[uSeg][vSeg];
        for (int i = 0; i < uSeg; i++) {
            Vector3f pA = aRes[i];
            Vector3f pB = bRes[i];
            for (int j = 0; j < vSeg; j++) {
                float t = j / (float) (vSeg - 1);
                float x = pA.x + t * (pB.x - pA.x);
                float y = pA.y + t * (pB.y - pA.y);
                float z = pA.z + t * (pB.z - pA.z);
                vid[i][j] = mesh.addVertex(x, y, z);
            }
        }

        if (pa.closed) {
            for (int i = 0; i < uSeg; i++) {
                int i1 = (i + 1) % uSeg;
                for (int j = 0; j < vSeg - 1; j++) {
                    mesh.addFace(vid[i][j], vid[i][j + 1], vid[i1][j + 1], vid[i1][j]);
                }
            }
        } else {
            for (int i = 0; i < uSeg - 1; i++) {
                for (int j = 0; j < vSeg - 1; j++) {
                    mesh.addFace(vid[i][j], vid[i][j + 1], vid[i + 1][j + 1], vid[i + 1][j]);
                }
            }
        }

        mesh.computeNormals();
        ctx.setOutput(GEOMETRY.name, ga.withMesh(mesh));
    }

    private static int readInt(NodeContext ctx, String name, int fallback) {
        Object v = FieldBroadcast.getInputOrDefault(ctx, name, fallback);
        if (v instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        return fallback;
    }

    private static Polyline extractPolyline(CurveGeometry cg) {
        int off0 = cg.curveOffsets()[0];
        int off1 = cg.curveOffsets()[1];
        int n = off1 - off0;
        if (n < 2) {
            return null;
        }
        float[] pos = cg.positions();
        Vector3f[] pts = new Vector3f[n];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int b = NUM_3 * (off0 + i);
            float x = pos[b];
            float y = pos[b + 1];
            float z = pos[b + 2];
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                return null;
            }
            pts[i] = new Vector3f(x, y, z);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        float diag = new Vector3f(maxX - minX, maxY - minY, maxZ - minZ).length();
        float eps = Math.max(NUM_1e_5, diag * NUM_1e_4);
        boolean closed = pts[0].distance(pts[n - 1]) < eps;
        return new Polyline(pts, closed);
    }

    private static Vector3f[] resampleArcLength(Vector3f[] src, boolean closed, int count) {
        if (count < 2 || src.length < 2) {
            return null;
        }
        int segCount = closed ? src.length : src.length - 1;
        float[] lens = new float[segCount];
        float total = NUM_0;
        int n = src.length;
        for (int i = 0; i < segCount; i++) {
            int j = closed ? (i + 1) % n : i + 1;
            float L = src[i].distance(src[j]);
            lens[i] = L;
            total += L;
        }
        if (total < NUM_1e_20) {
            return null;
        }

        Vector3f[] out = new Vector3f[count];
        for (int k = 0; k < count; k++) {
            float dist = closed ? (k / (float) count) * total : (k / (float) (count - 1)) * total;
            out[k] = pointAtArcLength(src, lens, total, dist, closed, n, segCount);
        }
        return out;
    }

    private static Vector3f pointAtArcLength(
            Vector3f[] src,
            float[] lens,
            float total,
            float dist,
            boolean closed,
            int n,
            int segCount) {
        if (dist <= NUM_0) {
            return new Vector3f(src[0]);
        }
        if (!closed && dist >= total - NUM_1e_8) {
            return new Vector3f(src[n - 1]);
        }
        if (closed) {
            dist = dist % total;
            if (dist < NUM_1e_8) {
                return new Vector3f(src[0]);
            }
        }
        float acc = NUM_0;
        for (int i = 0; i < segCount; i++) {
            float L = lens[i];
            if (acc + L >= dist - NUM_1e_8) {
                float t = L > NUM_1e_20 ? (dist - acc) / L : NUM_0;
                int i1 = closed ? (i + 1) % n : i + 1;
                return new Vector3f(src[i1]).sub(src[i]).mul(t).add(src[i]);
            }
            acc += L;
        }
        int last = closed ? 0 : n - 1;
        return new Vector3f(src[last]);
    }

    private static final class Polyline {
        final Vector3f[] points;
        final boolean closed;

        Polyline(Vector3f[] points, boolean closed) {
            this.points = points;
            this.closed = closed;
        }
    }
}
