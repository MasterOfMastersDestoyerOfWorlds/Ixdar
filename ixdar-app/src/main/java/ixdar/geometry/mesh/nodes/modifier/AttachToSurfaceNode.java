package ixdar.geometry.mesh.nodes.modifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Quaternionf;
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
import ixdar.geometry.mesh.nodes.data.TagGeometryNode;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Creates an attachment hole on a mesh surface using spherical coordinates.
 * <p>
 * Finds the face nearest to the direction specified by (theta, phi) from the
 * mesh centroid, insets it, and removes the inner face to create a boundary
 * loop suitable for bridging. Outputs the attachment position, surface normal,
 * and Euler rotation for aligning a +Y-oriented tube.
 * <p>
 * If {@code radius > 0}, all faces whose centroid falls within that distance
 * of the hit point are selected, creating a larger hole.
 */
@MeshNodeAnnotation(id = "attach_to_surface")
public class AttachToSurfaceNode implements MeshNode {
    public static final String GEOMETRY_2 = "geometry";
    public static final String THETA_2 = "theta";
    public static final String PHI_2 = "phi";
    public static final String RADIUS_2 = "radius";
    public static final String INSET_2 = "inset";
    public static final String TWIST_2 = "twist";
    public static final String TAG_2 = "tag";
    public static final String ATTACH = "attach";
    public static final String ATTACH_POSITION = "attach_position";
    public static final String ATTACH_NORMAL = "attach_normal";
    public static final String ATTACH_ROTATION = "attach_rotation";
    public static final float NUM_0 = 0f;
    public static final float NUM_1_5707963 = 1.5707963f;
    public static final float NUM_0_01 = 0.01f;
    public static final float NUM_0_12 = 0.12f;
    public static final float NUM_0_99 = 0.99f;
    public static final float NUM_1e_12 = 1e-12f;
    public static final int NUM_3 = 3;
    public static final float NUM_1e_8 = 1e-8f;
    public static final float NUM_1e_6 = 1e-6f;

    private static final InputPort GEOMETRY = new InputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort THETA = new InputPort(THETA_2, PortType.FLOAT, 0.0f, -6.2832f, 6.2832f);
    private static final InputPort PHI = new InputPort(PHI_2, PortType.FLOAT, 1.5707963f, 0f, 3.1416f);
    private static final InputPort RADIUS = new InputPort(RADIUS_2, PortType.FLOAT, 0.0f, 0f, 10f);
    private static final InputPort INSET = new InputPort(INSET_2, PortType.FLOAT, 0.12f, 0f, 1f);
    private static final InputPort TWIST = new InputPort(TWIST_2, PortType.FLOAT, 0.0f, -6.2832f, 6.2832f);
    private static final InputPort TAG = new InputPort(TAG_2, PortType.STRING, ATTACH);

    private static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE);
    private static final OutputPort POSITION_OUT = new OutputPort(ATTACH_POSITION, PortType.VECTOR3);
    private static final OutputPort NORMAL_OUT = new OutputPort(ATTACH_NORMAL, PortType.VECTOR3);
    private static final OutputPort ROTATION_OUT = new OutputPort(ATTACH_ROTATION, PortType.VECTOR3);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, THETA, PHI, RADIUS, INSET, TWIST, TAG);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT, POSITION_OUT, NORMAL_OUT, ROTATION_OUT);
    }

    @Override
    public String description() {
        return "Creates an attachment hole on a mesh surface at a spherical coordinate direction (theta, phi), outputting the position, normal, and rotation for aligning child geometry like limbs or tubes.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.ofEntries(
                java.util.Map.entry(GEOMETRY_2, "Input/output. A hole is cut at the attachment point on the parent surface; the opening boundary is tagged for bridge_edge_loops."),
                java.util.Map.entry(THETA_2, "Azimuthal angle (radians, around Y axis). 0 = +X, π/2 = +Z."),
                java.util.Map.entry(PHI_2, "Polar angle (radians, from +Y). 0 = top pole, π/2 = equator, π = bottom pole."),
                java.util.Map.entry(RADIUS_2, "Hole radius around the attachment point in world units."),
                java.util.Map.entry(INSET_2, "Inset distance from the cut boundary to the attachment ring. 0 = flush; positive = recessed."),
                java.util.Map.entry(TWIST_2, "Roll angle (radians) around the attachment normal. Rotates the attached child around its axis."),
                java.util.Map.entry(TAG_2, "String tag applied to the new boundary ring so downstream bridge_edge_loops / adaptive_bridge_loops can find it."),
                java.util.Map.entry(ATTACH_POSITION, "World-space position of the attachment point on the surface."),
                java.util.Map.entry(ATTACH_NORMAL, "Unit outward normal at the attachment point."),
                java.util.Map.entry(ATTACH_ROTATION, "Euler rotation (radians) that aligns +Y to the attach normal, plus twist.")
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput(GEOMETRY_2, Object.class));
        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.vertexCount() == 0) {
            setDefaults(ctx, base);
            return;
        }

        float theta = floatIn(ctx, THETA_2, NUM_0);
        float phi = floatIn(ctx, PHI_2, NUM_1_5707963);
        float radius = floatIn(ctx, RADIUS_2, NUM_0);
        float inset = Math.max(NUM_0_01, Math.min(floatIn(ctx, INSET_2, NUM_0_12), NUM_0_99));
        float twist = floatIn(ctx, TWIST_2, NUM_0);
        String tag = stringIn(ctx, TAG_2, ATTACH);

        int vertCount = mesh.vertexCount();
        int faceCount = mesh.faceCount();

        // Mesh centroid
        Vector3f centroid = new Vector3f();
        Vector3f tmp = new Vector3f();
        for (int i = 0; i < vertCount; i++) {
            mesh.vertexPosition(mesh.vertexIdAt(i), tmp);
            centroid.add(tmp);
        }
        centroid.div(vertCount);

        // Ray direction from spherical coords
        Vector3f dir = new Vector3f(
                (float) (Math.sin(phi) * Math.cos(theta)),
                (float) Math.cos(phi),
                (float) (Math.sin(phi) * Math.sin(theta))
        );
        if (dir.lengthSquared() < NUM_1e_12) dir.set(0, 1, 0);
        dir.normalize();

        // Compute face centroids
        float[][] fc = new float[faceCount][NUM_3];
        for (int fi = 0; fi < faceCount; fi++) {
            int fid = mesh.faceIdAt(fi);
            int nv = mesh.faceVertexCount(fid);
            float cx = 0, cy = 0, cz = 0;
            for (int k = 0; k < nv; k++) {
                mesh.vertexPosition(mesh.faceVertexAt(fid, k), tmp);
                cx += tmp.x;
                cy += tmp.y;
                cz += tmp.z;
            }
            fc[fi][0] = cx / nv;
            fc[fi][1] = cy / nv;
            fc[fi][2] = cz / nv;
        }

        // Find hit face: highest dot product of (faceCentroid - meshCentroid) with dir
        int hitFace = 0;
        float bestDot = Float.NEGATIVE_INFINITY;
        for (int fi = 0; fi < faceCount; fi++) {
            float dx = fc[fi][0] - centroid.x;
            float dy = fc[fi][1] - centroid.y;
            float dz = fc[fi][2] - centroid.z;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < NUM_1e_8) continue;
            float dot = (dx * dir.x + dy * dir.y + dz * dir.z) / len;
            if (dot > bestDot) {
                bestDot = dot;
                hitFace = fi;
            }
        }

        Vector3f attachPos = new Vector3f(fc[hitFace][0], fc[hitFace][1], fc[hitFace][2]);

        // Select faces within radius (or just the hit face)
        boolean[] selected = new boolean[faceCount];
        if (radius <= NUM_0) {
            selected[hitFace] = true;
        } else {
            for (int fi = 0; fi < faceCount; fi++) {
                float dx = fc[fi][0] - attachPos.x;
                float dy = fc[fi][1] - attachPos.y;
                float dz = fc[fi][2] - attachPos.z;
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= radius) {
                    selected[fi] = true;
                }
            }
        }

        // Compute face normal at hit face, ensuring it points outward (away from centroid)
        Vector3f attachNormal = faceNormal(mesh, mesh.faceIdAt(hitFace));
        Vector3f toCentroid = new Vector3f(attachPos).sub(centroid);
        if (attachNormal.dot(toCentroid) < 0) {
            attachNormal.negate();
        }

        // Build output mesh: clone verts, inset selected faces, omit inner faces
        HalfEdgeMesh out = new HalfEdgeMesh();
        Map<Integer, Integer> vertMap = new HashMap<>();
        for (int i = 0; i < vertCount; i++) {
            int vid = mesh.vertexIdAt(i);
            mesh.vertexPosition(vid, tmp);
            int nid = out.addVertex(tmp);
            vertMap.put(vid, nid);
        }

        List<Integer> boundaryVerts = new ArrayList<>();

        for (int fi = 0; fi < faceCount; fi++) {
            int fid = mesh.faceIdAt(fi);
            int nv = mesh.faceVertexCount(fid);

            if (!selected[fi]) {
                int[] vids = new int[nv];
                for (int k = 0; k < nv; k++) {
                    vids[k] = vertMap.get(mesh.faceVertexAt(fid, k));
                }
                out.addFace(vids);
            } else {
                // Create inset inner vertices
                float fcx = fc[fi][0], fcy = fc[fi][1], fcz = fc[fi][2];
                int[] inner = new int[nv];
                for (int k = 0; k < nv; k++) {
                    mesh.vertexPosition(mesh.faceVertexAt(fid, k), tmp);
                    float nx = tmp.x + (fcx - tmp.x) * inset;
                    float ny = tmp.y + (fcy - tmp.y) * inset;
                    float nz = tmp.z + (fcz - tmp.z) * inset;
                    inner[k] = out.addVertex(nx, ny, nz);
                    boundaryVerts.add(inner[k]);
                }
                // Side quads connecting outer boundary to inner boundary
                for (int k = 0; k < nv; k++) {
                    int next = (k + 1) % nv;
                    int oA = vertMap.get(mesh.faceVertexAt(fid, k));
                    int oB = vertMap.get(mesh.faceVertexAt(fid, next));
                    out.addFace(oA, oB, inner[next], inner[k]);
                }
                // Inner face is NOT added — creates the hole
            }
        }

        out.computeNormals();

        // Tag boundary vertices
        @SuppressWarnings("unchecked")
        Map<String, boolean[]> existingTags = (Map<String, boolean[]>) base.slots().get(TagGeometryNode.TAGS_SLOT);
        Map<String, boolean[]> tags = existingTags != null ? new HashMap<>(existingTags) : new HashMap<>();
        boolean[] tagMask = new boolean[out.vertexCount()];
        for (int vid : boundaryVerts) {
            if (vid < tagMask.length) tagMask[vid] = true;
        }
        tags.put(tag, tagMask);

        GeometryBundle result = base.withMesh(out).withSlot(TagGeometryNode.TAGS_SLOT, tags);

        // Compute rotation: align +Y to surface normal, with twist
        Vector3f rotation = alignRotation(attachNormal, twist);

        ctx.setOutput(GEOMETRY_2, result);
        ctx.setOutput(ATTACH_POSITION, new Vector3Value(attachPos.x, attachPos.y, attachPos.z));
        ctx.setOutput(ATTACH_NORMAL, new Vector3Value(attachNormal.x, attachNormal.y, attachNormal.z));
        ctx.setOutput(ATTACH_ROTATION, new Vector3Value(rotation.x, rotation.y, rotation.z));
    }

    private static Vector3f faceNormal(MeshTopology mesh, int fid) {
        Vector3f p0 = new Vector3f(), p1 = new Vector3f(), p2 = new Vector3f();
        mesh.vertexPosition(mesh.faceVertexAt(fid, 0), p0);
        mesh.vertexPosition(mesh.faceVertexAt(fid, 1), p1);
        mesh.vertexPosition(mesh.faceVertexAt(fid, 2), p2);
        Vector3f e1 = new Vector3f(p1).sub(p0);
        Vector3f e2 = new Vector3f(p2).sub(p0);
        Vector3f n = new Vector3f();
        e1.cross(e2, n);
        float len = n.length();
        return len > NUM_1e_8 ? n.div(len) : new Vector3f(0, 1, 0);
    }

    private static Vector3f alignRotation(Vector3f normal, float twist) {
        Quaternionf q = new Quaternionf().rotationTo(0, 1, 0, normal.x, normal.y, normal.z);
        if (Math.abs(twist) > NUM_1e_6) {
            Quaternionf tw = new Quaternionf().fromAxisAngleRad(normal.x, normal.y, normal.z, twist);
            tw.mul(q, q);
        }
        return q.getEulerAnglesXYZ(new Vector3f());
    }

    private void setDefaults(NodeContext ctx, GeometryBundle base) {
        ctx.setOutput(GEOMETRY_2, base);
        ctx.setOutput(ATTACH_POSITION, new Vector3Value(0, 0, 0));
        ctx.setOutput(ATTACH_NORMAL, new Vector3Value(0, 1, 0));
        ctx.setOutput(ATTACH_ROTATION, new Vector3Value(0, 0, 0));
    }

    private static float floatIn(NodeContext ctx, String name, float def) {
        Object obj = FieldBroadcast.getInputOrDefault(ctx, name, def);
        return FieldBroadcast.floatScalarOrDefault(obj, def);
    }

    private static String stringIn(NodeContext ctx, String name, String def) {
        Object obj = FieldBroadcast.getInputOrDefault(ctx, name, def);
        return obj instanceof String s ? s : def;
    }
}
