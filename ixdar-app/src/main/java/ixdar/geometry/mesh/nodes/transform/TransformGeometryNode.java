package ixdar.geometry.mesh.nodes.transform;

import java.util.List;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Value;
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Transforms geometry by applying scale, rotation (Euler XYZ radians), and translation.
 * Order: Scale → Rotate → Translate (standard SRT).
 */
@MeshNodeAnnotation(id = "transform_geometry")
public class TransformGeometryNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort TRANSLATION = new InputPort("translation", PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    private static final InputPort ROTATION = new InputPort("rotation", PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    private static final InputPort SCALE = new InputPort("scale", PortType.VECTOR3, new Vector3Value(1f, 1f, 1f));
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

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
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        Vector3Value trans = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, "translation", TRANSLATION.defaultValue()),
                new Vector3Value(0f, 0f, 0f));
        Vector3Value rot = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, "rotation", ROTATION.defaultValue()),
                new Vector3Value(0f, 0f, 0f));
        Vector3Value sc = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, "scale", SCALE.defaultValue()),
                new Vector3Value(1f, 1f, 1f));

        boolean hasTranslation = trans.x() != 0f || trans.y() != 0f || trans.z() != 0f;
        boolean hasRotation = rot.x() != 0f || rot.y() != 0f || rot.z() != 0f;
        boolean hasScale = sc.x() != 1f || sc.y() != 1f || sc.z() != 1f;

        if (!hasTranslation && !hasRotation && !hasScale) {
            ctx.setOutput("geometry", base);
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
        if (mesh != null && mesh.vertexCount() > 0) {
            int n = mesh.vertexCount();
            HalfEdgeMesh out = new HalfEdgeMesh();
            java.util.HashMap<Integer, Integer> idMap = new java.util.HashMap<>();

            for (int i = 0; i < n; i++) {
                int vid = mesh.vertexIdAt(i);
                mesh.vertexPosition(vid, tmp);
                mat.transformPosition(tmp);
                int nid = out.addVertex(tmp);
                idMap.put(vid, nid);
            }

            for (int fi = 0; fi < mesh.faceCount(); fi++) {
                int fid = mesh.faceIdAt(fi);
                int fc = mesh.faceVertexCount(fid);
                int[] nv = new int[fc];
                for (int k = 0; k < fc; k++) {
                    int ov = mesh.faceVertexAt(fid, k);
                    nv[k] = idMap.get(ov);
                }
                out.addFace(nv);
            }
            out.computeNormals();
            result = result.withMesh(out);
        }

        // Transform curve geometry if present in slots
        Object curveObj = base.slots().get("_curve");
        if (curveObj instanceof CurveGeometry cg) {
            float[] srcPos = cg.positions();
            float[] dstPos = new float[srcPos.length];
            for (int ci = 0; ci < srcPos.length / 3; ci++) {
                tmp.set(srcPos[ci * 3], srcPos[ci * 3 + 1], srcPos[ci * 3 + 2]);
                mat.transformPosition(tmp);
                dstPos[ci * 3] = tmp.x;
                dstPos[ci * 3 + 1] = tmp.y;
                dstPos[ci * 3 + 2] = tmp.z;
            }
            result = result.withSlot("_curve", new CurveGeometry(dstPos, cg.curveOffsets()));
        }

        ctx.setOutput("geometry", result);
    }
}
