package ixdar.geometry.mesh.nodes.modifier;

import java.util.HashMap;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Value;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Rotates weighted vertices around a pivot point for forward-kinematics posing.
 * <p>
 * Reads the {@code _bone_weight_{bone_name}} float[] slot written by
 * {@link SetBoneWeightNode}. For each vertex with weight &gt; 0, rotates it
 * around {@code pivot} by {@code rotation} (Euler XYZ radians), interpolated
 * by the vertex weight.
 * <p>
 * Chain tip-to-root for FK: apply_bone(dist) → apply_bone(mid) → apply_bone(prox).
 * Each bone's weight should cover its segment AND all descendant segments.
 */
@MeshNodeAnnotation(id = "apply_bone")
public class ApplyBoneNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort BONE_NAME = new InputPort("bone_name", PortType.STRING, "bone");
    private static final InputPort ROTATION = new InputPort("rotation", PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    private static final InputPort PIVOT = new InputPort("pivot", PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, BONE_NAME, ROTATION, PIVOT);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Rotates weighted vertices around a pivot point for forward-kinematics posing, using bone weights from set_bone_weight. Chain tip-to-root for multi-bone rigs.";
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput("geometry", base);
            return;
        }

        Object nameObj = FieldBroadcast.getInputOrDefault(ctx, "bone_name", BONE_NAME.defaultValue());
        String boneName = nameObj instanceof String s ? s : "bone";

        Object rotObj = FieldBroadcast.getInputOrDefault(ctx, "rotation", ROTATION.defaultValue());
        Vector3f rot = vec3(rotObj);

        Object pivObj = FieldBroadcast.getInputOrDefault(ctx, "pivot", PIVOT.defaultValue());
        Vector3f pivot = vec3(pivObj);

        String slotKey = SetBoneWeightNode.BONE_WEIGHT_PREFIX + boneName;
        Object weightSlot = base.slots().get(slotKey);
        if (!(weightSlot instanceof float[] weights)) {
            ctx.setOutput("geometry", base);
            return;
        }

        // Skip if rotation is effectively zero
        if (Math.abs(rot.x) < 1e-7f && Math.abs(rot.y) < 1e-7f && Math.abs(rot.z) < 1e-7f) {
            ctx.setOutput("geometry", base);
            return;
        }

        // Check if any vertex actually has weight > 0
        int n = mesh.vertexCount();
        boolean anyWeighted = false;
        for (int i = 0; i < n && !anyWeighted; i++) {
            int vid = mesh.vertexIdAt(i);
            if (vid < weights.length && weights[vid] > 0f) anyWeighted = true;
        }
        if (!anyWeighted) {
            ctx.setOutput("geometry", base);
            return;
        }

        // Build rotation matrix: translate to pivot → rotate XYZ → translate back
        Matrix4f boneMat = new Matrix4f()
                .translation(pivot)
                .rotateXYZ(rot.x, rot.y, rot.z)
                .translate(-pivot.x, -pivot.y, -pivot.z);

        Vector3f pos = new Vector3f();
        Vector3f transformed = new Vector3f();

        // Clone mesh with modified positions
        HalfEdgeMesh out = new HalfEdgeMesh(n, 0, mesh.faceCount(), mesh.faceCount() * 4 * 2);
        HashMap<Integer, Integer> idMap = new HashMap<>(n * 2);

        for (int i = 0; i < n; i++) {
            int vid = mesh.vertexIdAt(i);
            mesh.vertexPosition(vid, pos);

            float w = (vid < weights.length) ? weights[vid] : 0f;
            if (w > 0f) {
                boneMat.transformPosition(pos, transformed);
                pos.lerp(transformed, w);
            }

            int nid = out.addVertex(pos);
            idMap.put(vid, nid);
        }

        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            int fid = mesh.faceIdAt(fi);
            int fc = mesh.faceVertexCount(fid);
            int[] nv = new int[fc];
            for (int k = 0; k < fc; k++) {
                nv[k] = idMap.get(mesh.faceVertexAt(fid, k));
            }
            out.addFace(nv);
        }

        out.computeNormals();
        ctx.setOutput("geometry", base.withMesh(out));
    }

    private static Vector3f vec3(Object obj) {
        if (obj instanceof Vector3Value v) return new Vector3f(v.x(), v.y(), v.z());
        if (obj instanceof Vector3f v) return new Vector3f(v);
        return new Vector3f();
    }
}
