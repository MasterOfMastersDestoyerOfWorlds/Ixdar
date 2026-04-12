package ixdar.geometry.mesh.nodes.primitives;

import java.util.List;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.HalfEdgeMesh;

/**
 * Closes the open end of a {@link DualRadialSegmentNode} tube with a watertight
 * all-quad cap suitable for Catmull-Clark subdivision.
 * <p>
 * Generates concentric quad rings spiraling inward from the boundary ring to
 * a small innermost ring. Each ring has the same vertex count as the boundary,
 * preserving all-quad topology. The innermost ring uses a small non-zero radius
 * (5% of the boundary) to avoid degenerate geometry.
 * <p>
 * Intended as the terminal node in a segment chain:
 * {@code tip = segment_cap(geometry=seg_dist.geometry, cap_rings=2)}
 */
@MeshNodeAnnotation(id = "segment_cap")
public class SegmentCapNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort SEGMENTS = new InputPort("segments", PortType.INT, 12);
    private static final InputPort CAP_RINGS = new InputPort("cap_rings", PortType.INT, 2);
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, SEGMENTS, CAP_RINGS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        HalfEdgeMesh mesh = base.mesh() instanceof HalfEdgeMesh h ? h : null;
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput("geometry", base);
            return;
        }

        int segments = Math.max(3, intInput(ctx, "segments", 12));
        int capRings = Math.max(1, intInput(ctx, "cap_rings", 2));

        int totalVerts = mesh.vertexCount();
        if (totalVerts < segments) {
            ctx.setOutput("geometry", base);
            return;
        }

        // Find boundary ring at max Y
        Vector3f pos = new Vector3f();
        float maxY = Float.NEGATIVE_INFINITY;
        for (int v = 0; v < totalVerts; v++) {
            mesh.vertexPosition(v, pos);
            if (pos.y > maxY) maxY = pos.y;
        }

        int[] outerRing = new int[segments * 2]; // oversize buffer
        float[] ringAngles = new float[outerRing.length];
        int found = 0;
        float tolerance = 0.001f;

        for (int v = 0; v < totalVerts && found < outerRing.length; v++) {
            mesh.vertexPosition(v, pos);
            if (Math.abs(pos.y - maxY) < tolerance) {
                outerRing[found] = v;
                found++;
            }
        }

        if (found < 3) {
            ctx.setOutput("geometry", base);
            return;
        }

        // Compute centroid of outer ring
        Vector3f center = new Vector3f();
        for (int i = 0; i < found; i++) {
            mesh.vertexPosition(outerRing[i], pos);
            center.add(pos);
        }
        center.div(found);

        // Sort by angle for consistent winding
        for (int i = 0; i < found; i++) {
            mesh.vertexPosition(outerRing[i], pos);
            ringAngles[i] = (float) Math.atan2(pos.z - center.z, pos.x - center.x);
        }
        sortByAngle(outerRing, ringAngles, found);

        // Compute per-vertex displacement from center (for scaling inner rings)
        float[][] displacements = new float[found][2]; // dx, dz from center
        for (int i = 0; i < found; i++) {
            mesh.vertexPosition(outerRing[i], pos);
            displacements[i][0] = pos.x - center.x;
            displacements[i][1] = pos.z - center.z;
        }

        // Build concentric quad rings spiraling inward
        int[] prevRing = new int[found];
        System.arraycopy(outerRing, 0, prevRing, 0, found);

        for (int ring = 1; ring <= capRings; ring++) {
            float t = (float) ring / capRings;
            // Smoothstep easing for nicer distribution
            float scale = 1.0f - t;
            if (ring == capRings) {
                scale = 0.05f; // innermost ring: tiny but non-zero to avoid degeneracy
            }

            int[] currentRing = new int[found];
            for (int s = 0; s < found; s++) {
                float x = center.x + displacements[s][0] * scale;
                float z = center.z + displacements[s][1] * scale;
                currentRing[s] = mesh.addVertex(x, maxY, z);
            }

            // Quad faces between prevRing and currentRing
            for (int s = 0; s < found; s++) {
                int ns = (s + 1) % found;
                // Wind outward (+Y normal)
                mesh.addFace(prevRing[s], prevRing[ns], currentRing[ns], currentRing[s]);
            }

            prevRing = currentRing;
        }

        // Close the innermost ring with a triangle fan to a center vertex.
        // CC subdivision handles triangles by splitting each into 3 quads.
        int centerVert = mesh.addVertex(center.x, maxY, center.z);
        for (int s = 0; s < found; s++) {
            int ns = (s + 1) % found;
            mesh.addFace(prevRing[s], prevRing[ns], centerVert);
        }

        mesh.computeNormals();
        ctx.setOutput("geometry", base.withMesh(mesh));
    }

    private static void sortByAngle(int[] ids, float[] angles, int count) {
        for (int i = 1; i < count; i++) {
            float keyAngle = angles[i];
            int keyId = ids[i];
            int j = i - 1;
            while (j >= 0 && angles[j] > keyAngle) {
                angles[j + 1] = angles[j];
                ids[j + 1] = ids[j];
                j--;
            }
            angles[j + 1] = keyAngle;
            ids[j + 1] = keyId;
        }
    }

    private static int intInput(NodeContext ctx, String name, int def) {
        Number n = ctx.getInput(name, Number.class);
        return n != null ? n.intValue() : def;
    }
}
