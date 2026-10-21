package ixdar.nodegraph.nodes.transform;
import java.util.Objects;
import java.util.List;

import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.Vector3Value;
import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.nodegraph.field.FieldBroadcast;
import ixdar.nodegraph.nodes.patch.AssignBezierHandlesNode;

/**
 * Transforms geometry by applying scale, rotation (Euler XYZ radians), and translation.
 * Order: Scale → Rotate → Translate (standard SRT).
 */
@MeshNodeAnnotation(id = "transform_geometry", category = NodeCategory.TRANSFORM)
public class TransformGeometryNode implements MeshNode {
    public static final int VECTOR_STRIDE = 3;

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort TRANSLATION = new InputPort("translation", PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    public static final InputPort ROTATION = new InputPort("rotation", PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    public static final InputPort SCALE = new InputPort("scale", PortType.VECTOR3, new Vector3Value(1f, 1f, 1f));
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, TRANSLATION, ROTATION, SCALE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Applies translation, rotation, and scale to mesh vertices and curve geometry using standard SRT order.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output geometry bundle. Vertex positions and curve control points are transformed; bezier handle slots are scaled and rotated in place.",
                TRANSLATION.name, "World-space offset added after scale+rotate. translation=<X,Y,Z> shifts every vertex by those units.",
                ROTATION.name, "Euler XYZ in RADIANS (not degrees). Applied after scale, before translate. <0,0,0> = identity.",
                SCALE.name, "Per-axis multiplier applied first. scale=<X,Y,Z> stretches extent by those factors componentwise: output extent = input extent × scale. Pass 1.0 to leave an axis untouched, not 0.5. To map a unit cube (extent 1) onto a reference with extent <Ex,Ey,Ez>, use scale=<Ex,Ey,Ez>."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        Vector3Value trans = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, TRANSLATION.name, TRANSLATION.defaultValue),
                new Vector3Value(0f, 0f, 0f));
        Vector3Value rot = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, ROTATION.name, ROTATION.defaultValue),
                new Vector3Value(0f, 0f, 0f));
        Vector3Value sc = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, SCALE.name, SCALE.defaultValue),
                new Vector3Value(1f, 1f, 1f));

        boolean hasTranslation = trans.x() != 0f || trans.y() != 0f || trans.z() != 0f;
        boolean hasRotation = rot.x() != 0f || rot.y() != 0f || rot.z() != 0f;
        boolean hasScale = sc.x() != 1f || sc.y() != 1f || sc.z() != 1f;

        if (!hasTranslation && !hasRotation && !hasScale) {
            ctx.setOutput(GEOMETRY.name, base);
            return;
        }

        // Build SRT matrix: Translation * Rotation * Scale
        Matrix4f mat = new Matrix4f();
        mat.translation(trans.x(), trans.y(), trans.z());
        if (hasRotation) {
            Quaternionf q = new Quaternionf().rotationXYZ(rot.x(), rot.y(), rot.z());
            mat.rotate(q);
        }
        if (hasScale) {
            mat.scale(sc.x(), sc.y(), sc.z());
        }

        Vector3f tmp = new Vector3f();
        GeometryBundle result = base;

        // Transform mesh vertices if present
        MeshTopology mesh = base.mesh();
        HalfEdgeMesh out = null;
        int[] activeVertexOfId = null;
        if (mesh != null && mesh.vertexCount() > 0) {
            int n = mesh.vertexCount();
            int fc = mesh.faceCount();

            // Output vertex i is the input's active vertex i; faces keep their corners.
            float[] newPositions = new float[n * VECTOR_STRIDE];
            activeVertexOfId = new int[mesh.vertexIdBound()];
            for (int activeVertex = 0; activeVertex < n; activeVertex++) {
                int vertexId = mesh.vertexIdAt(activeVertex);
                activeVertexOfId[vertexId] = activeVertex;
                mesh.vertexPosition(vertexId, tmp);
                mat.transformPosition(tmp);
                newPositions[activeVertex * VECTOR_STRIDE] = tmp.x;
                newPositions[activeVertex * VECTOR_STRIDE + 1] = tmp.y;
                newPositions[activeVertex * VECTOR_STRIDE + 2] = tmp.z;
            }
            int[] faceSizes = new int[fc];
            int cornerCount = 0;
            for (int fi = 0; fi < fc; fi++) {
                faceSizes[fi] = mesh.faceVertexCount(mesh.faceIdAt(fi));
                cornerCount += faceSizes[fi];
            }
            int[] faceCorners = new int[cornerCount];
            int cursor = 0;
            for (int fi = 0; fi < fc; fi++) {
                int faceId = mesh.faceIdAt(fi);
                for (int k = 0; k < faceSizes[fi]; k++) {
                    faceCorners[cursor++] = activeVertexOfId[mesh.faceVertexAt(faceId, k)];
                }
            }
            out = HalfEdgeMeshOps.bulkAllocateMixed(newPositions, faceSizes, faceCorners);
            out.computeNormals();
            result = result.withMesh(out);
        }

        // Transform bezier handle offset vectors if present. Handles are offset
        // (direction) vectors, so translation has no effect; only rotation/scale
        // need to be applied. Previously these were pass-through, which distorted
        // curves under non-uniform scale.
        if (hasRotation || hasScale) {
            result = transformHandleSlot(result, AssignBezierHandlesNode.SLOT_HANDLES_START, mat, tmp);
            result = transformHandleSlot(result, AssignBezierHandlesNode.SLOT_HANDLES_END, mat, tmp);
        }

        // Handles are keyed by edge id, start at the tail of the edge's half-edge; the rebuilt
        // mesh numbers and orients its edges afresh, so each pair moves to the output edge
        // joining the same vertices, swapped when that edge runs the other way.
        if (out != null && result.slots().get(AssignBezierHandlesNode.SLOT_HANDLES_START) instanceof float[] start
                && result.slots().get(AssignBezierHandlesNode.SLOT_HANDLES_END) instanceof float[] end) {
            float[] outStart = new float[out.edgeIdBound() * VECTOR_STRIDE];
            float[] outEnd = new float[outStart.length];
            for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
                int edgeId = mesh.edgeIdAt(activeEdge);
                if ((edgeId + 1) * VECTOR_STRIDE > Math.min(start.length, end.length)) {
                    continue;
                }
                int halfEdge = mesh.edgeHalfEdge(edgeId);
                int tail = activeVertexOfId[mesh.halfEdgeVertex(halfEdge)];
                int outEdge = out.edgeBetween(tail, activeVertexOfId[mesh.halfEdgeEndVertex(halfEdge)]);
                boolean sameWay = out.halfEdgeVertex(out.edgeHalfEdge(outEdge)) == tail;
                System.arraycopy(sameWay ? start : end, edgeId * VECTOR_STRIDE, outStart,
                        outEdge * VECTOR_STRIDE, VECTOR_STRIDE);
                System.arraycopy(sameWay ? end : start, edgeId * VECTOR_STRIDE, outEnd,
                        outEdge * VECTOR_STRIDE, VECTOR_STRIDE);
            }
            result = result.withSlot(AssignBezierHandlesNode.SLOT_HANDLES_START, outStart)
                    .withSlot(AssignBezierHandlesNode.SLOT_HANDLES_END, outEnd);
        }

        // Transform curve geometry if present in slots
        Object curveObj = base.slots().get(CurveGeometry.SLOT);
        if (curveObj instanceof CurveGeometry cg) {
            float[] srcPos = cg.positions();
            float[] dstPos = new float[srcPos.length];
            for (int ci = 0; ci < srcPos.length / VECTOR_STRIDE; ci++) {
                tmp.set(srcPos[ci * VECTOR_STRIDE], srcPos[ci * VECTOR_STRIDE + 1], srcPos[ci * VECTOR_STRIDE + 2]);
                mat.transformPosition(tmp);
                dstPos[ci * VECTOR_STRIDE] = tmp.x;
                dstPos[ci * VECTOR_STRIDE + 1] = tmp.y;
                dstPos[ci * VECTOR_STRIDE + 2] = tmp.z;
            }
            result = result.withSlot(CurveGeometry.SLOT, new CurveGeometry(dstPos, cg.curveOffsets()));
        }

        ctx.setOutput(GEOMETRY.name, result);
    }

    /**
     * If {@code bundle} has a float[] slot named {@code slotName}, applies the
     * linear part of {@code mat} to each 3-float vector in place (on a copy) and
     * returns a new bundle with the transformed slot. Otherwise returns the
     * bundle unchanged.
     */
    private static GeometryBundle transformHandleSlot(GeometryBundle bundle, String slotName,
            Matrix4f mat, Vector3f tmp) {
        Object o = bundle.slots().get(slotName);
        if (!(o instanceof float[] src)) {
            return bundle;
        }
        float[] dst = new float[src.length];
        int n = src.length / VECTOR_STRIDE;
        for (int i = 0; i < n; i++) {
            int b = i * VECTOR_STRIDE;
            tmp.set(src[b], src[b + 1], src[b + 2]);
            mat.transformDirection(tmp);
            dst[b] = tmp.x;
            dst[b + 1] = tmp.y;
            dst[b + 2] = tmp.z;
        }
        return bundle.withSlot(slotName, dst);
    }
}
