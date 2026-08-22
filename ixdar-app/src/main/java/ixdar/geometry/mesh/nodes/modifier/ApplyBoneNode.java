package ixdar.geometry.mesh.nodes.modifier;

import java.util.HashMap;
import java.util.List;

import org.joml.Matrix4f;

import java.util.Map;
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
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Rotates each vertex weighted for the named bone around {@code pivot} by {@code rotation} (Euler
 * XYZ radians), scaled by the vertex weight from the slot {@link SetBoneWeightNode} wrote.
 *
 * <p>Chain these tip-to-root, and give each bone a weight covering its own segment and all
 * descendant segments.
 */
@MeshNodeAnnotation(id = "apply_bone")
public class ApplyBoneNode implements MeshNode {
    public static final String BONE = "bone";
    public static final float NUM_1e_7 = 1e-7f;
    public static final float NUM_0 = 0f;
    public static final int NUM_4 = 4;

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort BONE_NAME = new InputPort("bone_name", PortType.STRING, BONE);
    public static final InputPort ROTATION = new InputPort("rotation", PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    public static final InputPort PIVOT = new InputPort("pivot", PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

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
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output. Vertices with nonzero weight for `bone_name` are rotated around `pivot`.",
                BONE_NAME.name, "Name of the bone whose weights were written upstream by set_bone_weight.",
                ROTATION.name, "Euler rotation (radians) applied around the pivot.",
                PIVOT.name, "World-space point around which rotation is applied. Typically the bone's joint position."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput(GEOMETRY.name, Object.class));
        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput(GEOMETRY.name, base);
            return;
        }

        Object nameObj = FieldBroadcast.getInputOrDefault(ctx, BONE_NAME.name, BONE_NAME.defaultValue);
        String boneName = nameObj instanceof String s ? s : BONE;

        Object rotObj = FieldBroadcast.getInputOrDefault(ctx, ROTATION.name, ROTATION.defaultValue);
        Vector3f rot = vec3(rotObj);

        Object pivObj = FieldBroadcast.getInputOrDefault(ctx, PIVOT.name, PIVOT.defaultValue);
        Vector3f pivot = vec3(pivObj);

        String slotKey = SetBoneWeightNode.BONE_WEIGHT_PREFIX + boneName;
        Object weightSlot = base.slots().get(slotKey);
        if (!(weightSlot instanceof float[] weights)) {
            ctx.setOutput(GEOMETRY.name, base);
            return;
        }

        // Skip if rotation is effectively zero
        if (Math.abs(rot.x) < NUM_1e_7 && Math.abs(rot.y) < NUM_1e_7 && Math.abs(rot.z) < NUM_1e_7) {
            ctx.setOutput(GEOMETRY.name, base);
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
            ctx.setOutput(GEOMETRY.name, base);
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
        ctx.setOutput(GEOMETRY.name, base.withMesh(out));
    }

    private static Vector3f vec3(Object obj) {
        if (obj instanceof Vector3Value v) return new Vector3f(v.x(), v.y(), v.z());
        if (obj instanceof Vector3f v) return new Vector3f(v);
        return new Vector3f();
    }
}
