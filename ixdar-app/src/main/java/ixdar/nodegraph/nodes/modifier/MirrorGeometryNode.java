package ixdar.nodegraph.nodes.modifier;

import java.util.Objects;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import ixdar.nodegraph.api.InputPort;

import org.joml.Vector4f;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.ops.MeshAppend;
import ixdar.geometry.mesh.data.ops.MeshMergeByDistance;
import ixdar.geometry.mesh.data.bundle.SlotCarry;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.nodegraph.nodes.patch.AssignBezierHandlesNode;

/**
 * Mirrors geometry across a symmetry plane, reverses face winding on the
 * mirrored copy, and welds seam vertices. Halves work for bilaterally
 * symmetric shapes.
 */
@MeshNodeAnnotation(id = "mirror_geometry", category = NodeCategory.MODIFIERS)
public class MirrorGeometryNode implements MeshNode {
    public static final String X = "X";
    public static final String Y = "Y";
    public static final String Z = "Z";

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort AXIS = new InputPort("axis", PortType.STRING, X);
    public static final InputPort MERGE_DISTANCE = new InputPort("merge_distance", PortType.FLOAT, 0.0001f, 0f, 1f);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, AXIS, MERGE_DISTANCE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Mirrors geometry across a symmetry plane (X, Y, or Z axis), reverses face winding on the copy, and welds seam vertices for bilateral symmetry.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output. Output contains original + mirrored copy. Bezier handles are reflected across the mirror plane.",
                AXIS.name, "Symmetry plane: X (mirror across YZ plane), Y (across XZ), Z (across XY). Default X.",
                MERGE_DISTANCE.name, "Weld threshold for seam vertices on the symmetry plane. 0 = no weld (two disjoint halves); typical 0.0001."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        String axis = ctx.getInput(AXIS.name, String.class);
        if (axis == null) axis = X;
        Number mdNum = ctx.getInput(MERGE_DISTANCE.name, Number.class);
        float md = mdNum == null ? 0.0001f : mdNum.floatValue();

        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput(GEOMETRY.name, base);
            return;
        }

        // Build mirror transform: negate the chosen axis
        Matrix4f mirror = new Matrix4f();
        switch (axis.toUpperCase()) {
            case Y -> mirror.scaling(1, -1, 1);
            case Z -> mirror.scaling(1, 1, -1);
            default -> mirror.scaling(-1, 1, 1);
        }

        // Create mirrored copy with reversed winding
        HalfEdgeMesh mirrored = new HalfEdgeMesh();
        Vector3f p = new Vector3f();
        Vector4f ph = new Vector4f();
        int n = mesh.vertexCount();
        HashMap<Integer, Integer> idMap = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int vid = mesh.vertexIdAt(i);
            mesh.vertexPosition(vid, p);
            ph.set(p.x, p.y, p.z, 1f);
            mirror.transform(ph);
            int nid = mirrored.addVertex(ph.x, ph.y, ph.z);
            idMap.put(vid, nid);
        }
        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            int fid = mesh.faceIdAt(fi);
            int fc = mesh.faceVertexCount(fid);
            int[] nv = new int[fc];
            // Reverse winding to maintain consistent normals after mirror
            for (int k = 0; k < fc; k++) {
                int ov = mesh.faceVertexAt(fid, fc - 1 - k);
                nv[k] = idMap.get(ov);
            }
            mirrored.addFace(nv);
        }

        // Join original + mirrored: the copy is the base again, as a second operand, so every
        // slot follows it, its corners matched backwards through the reversed winding.
        HalfEdgeMesh combined = new HalfEdgeMesh();
        Matrix4f identity = new Matrix4f();
        MeshAppend.append(combined, mesh, identity);
        MeshAppend.append(combined, mirrored, identity);
        int[] sourceVertex = new int[n * 2];
        int[] vertexOperand = new int[n * 2];
        for (int i = 0; i < n; i++) {
            sourceVertex[i] = mesh.vertexIdAt(i);
            sourceVertex[n + i] = sourceVertex[i];
            vertexOperand[n + i] = 1;
        }
        int faceCount = mesh.faceCount();
        int[] sourceFace = new int[faceCount * 2];
        int[] faceOperand = new int[faceCount * 2];
        for (int fi = 0; fi < faceCount; fi++) {
            sourceFace[fi] = mesh.faceIdAt(fi);
            sourceFace[faceCount + fi] = sourceFace[fi];
            faceOperand[faceCount + fi] = 1;
        }
        combined.recordSources(sourceVertex, null, sourceFace);
        combined.recordOperands(vertexOperand, faceOperand);
        combined.computeNormals();
        GeometryBundle doubled = SlotCarry.carry(List.of(base, base), GeometryBundle.ofMesh(combined));

        // A handle is a direction, so the mirrored half's handles reflect across the plane.
        if (doubled.slots().get(AssignBezierHandlesNode.SLOT_HANDLES_START) instanceof float[] start
                && doubled.slots().get(AssignBezierHandlesNode.SLOT_HANDLES_END) instanceof float[] end) {
            int axisIndex = switch (axis.toUpperCase()) {
                case Y -> 1;
                case Z -> 2;
                default -> 0;
            };
            for (int edge = 0; edge < combined.edgeCount(); edge++) {
                int edgeId = combined.edgeIdAt(edge);
                if (vertexOperand[combined.halfEdgeVertex(combined.edgeHalfEdge(edgeId))] == 1) {
                    start[edgeId * SlotCarry.HANDLE_COMPONENTS + axisIndex] *= -1f;
                    end[edgeId * SlotCarry.HANDLE_COMPONENTS + axisIndex] *= -1f;
                }
            }
        }

        // Weld seam vertices; the weld records its own provenance for the second carry.
        GeometryBundle out = md > 0f
                ? SlotCarry.carry(List.of(doubled), GeometryBundle.ofMesh(MeshMergeByDistance.merge(combined, md)))
                : doubled;
        ctx.setOutput(GEOMETRY.name, out);
    }

}
