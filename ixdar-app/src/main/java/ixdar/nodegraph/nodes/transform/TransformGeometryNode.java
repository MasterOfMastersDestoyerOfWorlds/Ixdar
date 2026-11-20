package ixdar.nodegraph.nodes.transform;
import java.util.Objects;
import java.util.List;

import java.util.Map;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import ixdar.geometry.mesh.data.representation.PackedPoints;
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

        Matrix4f mat = new Matrix4f();
        mat.translation(trans.x(), trans.y(), trans.z());
        if (hasRotation) {
            Quaternionf q = new Quaternionf().rotationXYZ(rot.x(), rot.y(), rot.z());
            mat.rotate(q);
        }
        if (hasScale) {
            mat.scale(sc.x(), sc.y(), sc.z());
        }

        GeometryBundle result = base;

        MeshTopology mesh = base.mesh();
        HalfEdgeMesh out = null;
        int[] activeVertexOfId = null;
        if (mesh != null && mesh.vertexCount() > 0) {
            int fc = mesh.faceCount();

            activeVertexOfId = new int[mesh.vertexIdBound()];
            PackedPoints newPositions = mesh.packedPositions(activeVertexOfId);
            newPositions.transformPositions(mat);
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
            out = HalfEdgeMeshOps.bulkAllocateMixed(newPositions,faceSizes, faceCorners);
            out.computeNormals();
            result = result.withMesh(out);
        }

        if (hasRotation || hasScale) {
            result = transformHandleSlot(result, AssignBezierHandlesNode.SLOT_HANDLES_START, mat);
            result = transformHandleSlot(result, AssignBezierHandlesNode.SLOT_HANDLES_END, mat);
        }

        if (out != null && result.slots().get(AssignBezierHandlesNode.SLOT_HANDLES_START) instanceof PackedPoints starts
                && result.slots().get(AssignBezierHandlesNode.SLOT_HANDLES_END) instanceof PackedPoints ends) {
            PackedPoints outStart = new PackedPoints(out.edgeIdBound());
            PackedPoints outEnd = new PackedPoints(out.edgeIdBound());
            for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
                int edgeId = mesh.edgeIdAt(activeEdge);
                if (edgeId >= Math.min(starts.size(), ends.size())) {
                    continue;
                }
                int halfEdge = mesh.edgeHalfEdge(edgeId);
                int tail = activeVertexOfId[mesh.halfEdgeVertex(halfEdge)];
                int outEdge = out.edgeBetween(tail, activeVertexOfId[mesh.halfEdgeEndVertex(halfEdge)]);
                boolean sameWay = out.halfEdgeVertex(out.edgeHalfEdge(outEdge)) == tail;
                outStart.set(outEdge, (sameWay ? starts : ends).at(edgeId));
                outEnd.set(outEdge, (sameWay ? ends : starts).at(edgeId));
            }
            result = result.withSlot(AssignBezierHandlesNode.SLOT_HANDLES_START, outStart)
                    .withSlot(AssignBezierHandlesNode.SLOT_HANDLES_END, outEnd);
        }

        Object curveObj = base.slots().get(CurveGeometry.SLOT);
        if (curveObj instanceof CurveGeometry cg) {
            PackedPoints dstPos = cg.positions.copy();
            dstPos.transformPositions(mat);
            result = result.withSlot(CurveGeometry.SLOT, cg.withPositions(dstPos));
        }

        ctx.setOutput(GEOMETRY.name, result);
    }

    /**
     * If {@code bundle} has a {@link PackedPoints} slot named {@code slotName}, applies the
     * linear part of {@code mat} to each vector of a copy and
     * returns a new bundle with the transformed slot. Otherwise returns the
     * bundle unchanged.
     */
    private static GeometryBundle transformHandleSlot(GeometryBundle bundle, String slotName,
            Matrix4f mat) {
        Object o = bundle.slots().get(slotName);
        if (!(o instanceof PackedPoints source)) {
            return bundle;
        }
        PackedPoints transformed = source.copy();
        transformed.transform(mat.get3x3(new Matrix3f()));
        return bundle.withSlot(slotName, transformed);
    }
}
