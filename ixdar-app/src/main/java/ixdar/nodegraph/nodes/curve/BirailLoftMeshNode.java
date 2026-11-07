package ixdar.nodegraph.nodes.curve;

import java.util.List;

import org.joml.Vector3f;

import java.util.Map;

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
 * interpolation between corresponding pairs (uniform across the span). Both rails must be closed,
 * or both open.
 */
@MeshNodeAnnotation(id = "birail_loft", category = NodeCategory.CURVE_OPERATIONS)
public class BirailLoftMeshNode implements MeshNode {
    public static final int DEFAULT_U_SEGMENTS = 16;
    public static final int DEFAULT_V_SEGMENTS = 8;
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

        boolean closed = cga.closed[0];
        if (!isUsableRail(cga) || !isUsableRail(cgb) || closed != cgb.closed[0]) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }

        float[] aRes = cga.resampled(uSeg).curvePositions(0);
        float[] bRes = cgb.resampled(uSeg).curvePositions(0);

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
     * Whether a rail's first polyline can be lofted: at least two points, all finite, with extent.
     *
     * @param cg the rail curve
     * @return false when it has fewer than two points, a non-finite one, or no extent
     */
    private static boolean isUsableRail(CurveGeometry cg) {
        if (cg.pointCount(0) < 2) {
            return false;
        }
        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);
        float[] points = cg.curvePositions(0);
        for (int at = 0; at < points.length; at += 3) {
            if (!Float.isFinite(points[at]) || !Float.isFinite(points[at + 1]) || !Float.isFinite(points[at + 2])) {
                return false;
            }
            min.min(new Vector3f(points[at], points[at + 1], points[at + 2]));
            max.max(new Vector3f(points[at], points[at + 1], points[at + 2]));
        }
        return max.sub(min).length() >= MIN_ARC_LENGTH;
    }
}
