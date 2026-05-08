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
    public static final String GEOMETRY_2 = "geometry";
    public static final String BONE_NAME_2 = "bone_name";
    public static final String BONE = "bone";
    public static final String ROTATION_2 = "rotation";
    public static final String PIVOT_2 = "pivot";
    public static final float NUM_1e_7 = 1e-7f;
    public static final float NUM_0 = 0f;
    public static final int NUM_4 = 4;

    private static final InputPort GEOMETRY = new InputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort BONE_NAME = new InputPort(BONE_NAME_2, PortType.STRING, BONE);
    private static final InputPort ROTATION = new InputPort(ROTATION_2, PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    private static final InputPort PIVOT = new InputPort(PIVOT_2, PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    private static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE);

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
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                GEOMETRY_2, "Input/output. Vertices with nonzero weight for `bone_name` are rotated around `pivot`.",
                BONE_NAME_2, "Name of the bone whose weights were written upstream by set_bone_weight.",
                ROTATION_2, "Euler rotation (radians) applied around the pivot.",
                PIVOT_2, "World-space point around which rotation is applied. Typically the bone's joint position."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput(GEOMETRY_2, Object.class));
        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput(GEOMETRY_2, base);
            return;
        }

        Object nameObj = FieldBroadcast.getInputOrDefault(ctx, BONE_NAME_2, BONE_NAME.defaultValue());
        String boneName = nameObj instanceof String s ? s : BONE;

        Object rotObj = FieldBroadcast.getInputOrDefault(ctx, ROTATION_2, ROTATION.defaultValue());
        Vector3f rot = vec3(rotObj);

        Object pivObj = FieldBroadcast.getInputOrDefault(ctx, PIVOT_2, PIVOT.defaultValue());
        Vector3f pivot = vec3(pivObj);

        String slotKey = SetBoneWeightNode.BONE_WEIGHT_PREFIX + boneName;
        Object weightSlot = base.slots().get(slotKey);
        if (!(weightSlot instanceof float[] weights)) {
            ctx.setOutput(GEOMETRY_2, base);
            return;
        }

        // Skip if rotation is effectively zero
        if (Math.abs(rot.x) < NUM_1e_7 && Math.abs(rot.y) < NUM_1e_7 && Math.abs(rot.z) < NUM_1e_7) {
            ctx.setOutput(GEOMETRY_2, base);
            return;
        }

        // Check if any vertex actually has weight > 0
        int n = mesh.vertexCount();
        boolean anyWeighted = false;
        for (int i = 0; i < n && !anyWeighted; i++) {
            int vid = mesh.vertexIdAt(i);
            if (vid < weights.length && weights[vid] > NUM_0) anyWeighted = true;
        }
        if (!anyWeighted) {
            ctx.setOutput(GEOMETRY_2, base);
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
        HalfEdgeMesh out = new HalfEdgeMesh(n, 0, mesh.faceCount(), mesh.faceCount() * NUM_4 * 2);
        HashMap<Integer, Integer> idMap = new HashMap<>(n * 2);

        for (int i = 0; i < n; i++) {
            int vid = mesh.vertexIdAt(i);
            mesh.vertexPosition(vid, pos);

            float w = (vid < weights.length) ? weights[vid] : NUM_0;
            if (w > NUM_0) {
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
        ctx.setOutput(GEOMETRY_2, base.withMesh(out));
    }

    private static Vector3f vec3(Object obj) {
        if (obj instanceof Vector3Value v) return new Vector3f(v.x(), v.y(), v.z());
        if (obj instanceof Vector3f v) return new Vector3f(v);
        return new Vector3f();
    }
}
