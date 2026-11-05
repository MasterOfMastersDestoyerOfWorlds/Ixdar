package ixdar.nodegraph.nodes.curve;

import java.util.Arrays;
import java.util.List;

import org.joml.Vector3f;

import java.util.Map;

import ixdar.geometry.grid2d.PolylineResample;

import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.nodegraph.field.FieldBroadcast;

/**
 * Ruled bi-rail loft: the first polyline in {@code rail_a} and {@code rail_b} are resampled to
 * {@code u_segments} points by arc length, then meshed with {@code v_segments} steps of linear
 * interpolation between corresponding pairs (uniform across the span). Both rails must match
 * open vs closed topology.
 */
@MeshNodeAnnotation(id = "birail_loft", category = NodeCategory.CURVE_OPERATIONS)
public class BirailLoftMeshNode implements MeshNode {
    public static final int DEFAULT_U_SEGMENTS = 16;
    public static final int DEFAULT_V_SEGMENTS = 8;
    public static final float MIN_CLOSED_EPSILON = 1e-5f;
    public static final float CLOSED_EPSILON_PER_LENGTH = 1e-4f;
    public static final float MIN_ARC_LENGTH = 1e-20f;

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
        GeometryBundle ga = ctx.getInput(RAIL_A.name, GeometryBundle.class);
        GeometryBundle gb = ctx.getInput(RAIL_B.name, GeometryBundle.class);
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

        int uSeg = readInt(ctx, U_SEGMENTS.name, DEFAULT_U_SEGMENTS);
        int vSeg = readInt(ctx, V_SEGMENTS.name, DEFAULT_V_SEGMENTS);
        uSeg = Math.max(2, uSeg);
        vSeg = Math.max(2, vSeg);

        float[] railA = railPoints(cga);
        float[] railB = railPoints(cgb);
        if (railA == null || railB == null) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }
        boolean closed = isClosed(railA);
        if (closed != isClosed(railB)) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }

        float[] aRes = PolylineResample.uniform(railA, 0, railA.length / 3, 3, closed, uSeg);
        float[] bRes = PolylineResample.uniform(railB, 0, railB.length / 3, 3, closed, uSeg);

        HalfEdgeMesh mesh = new HalfEdgeMesh();
        int[][] vid = new int[uSeg][vSeg];
        for (int i = 0; i < uSeg; i++) {
            for (int j = 0; j < vSeg; j++) {
                float t = j / (float) (vSeg - 1);
                float x = aRes[i * 3] + t * (bRes[i * 3] - aRes[i * 3]);
                float y = aRes[i * 3 + 1] + t * (bRes[i * 3 + 1] - aRes[i * 3 + 1]);
                float z = aRes[i * 3 + 2] + t * (bRes[i * 3 + 2] - aRes[i * 3 + 2]);
                vid[i][j] = mesh.addVertex(x, y, z);
            }
        }

        if (closed) {
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

    /**
     * A rail's first polyline, packed xyz.
     *
     * @param cg the rail curve
     * @return its points, or null when it has fewer than two, a non-finite one, or no extent
     */
    private static float[] railPoints(CurveGeometry cg) {
        int off0 = cg.curveOffsets()[0];
        int n = cg.curveOffsets()[1] - off0;
        if (n < 2) {
            return null;
        }
        float[] points = Arrays.copyOfRange(cg.positions(), off0 * 3, (off0 + n) * 3);
        for (float coordinate : points) {
            if (!Float.isFinite(coordinate)) {
                return null;
            }
        }
        return boundingDiagonal(points) < MIN_ARC_LENGTH ? null : points;
    }

    /**
     * Whether a rail closes: its ends meet within a ten-thousandth of its bounding diagonal.
     *
     * @param points the rail, packed xyz
     * @return true for a loop
     */
    private static boolean isClosed(float[] points) {
        float eps = Math.max(MIN_CLOSED_EPSILON, boundingDiagonal(points) * CLOSED_EPSILON_PER_LENGTH);
        int last = points.length - 3;
        return new Vector3f(points[0], points[1], points[2]).distance(points[last], points[last + 1],
                points[last + 2]) < eps;
    }

    private static float boundingDiagonal(float[] points) {
        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);
        for (int at = 0; at < points.length; at += 3) {
            min.min(new Vector3f(points[at], points[at + 1], points[at + 2]));
            max.max(new Vector3f(points[at], points[at + 1], points[at + 2]));
        }
        return max.sub(min).length();
    }
}
