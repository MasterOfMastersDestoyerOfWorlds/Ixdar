package ixdar.geometry.mesh.nodes.geometry;

import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.ModeConstraint;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Boolean (CSG) operations on two meshes: union, difference, or intersect.
 * <p>
 * Uses ray-casting (odd-crossing rule) to classify each face's centroid as
 * inside or outside the other solid, then selects faces based on the operation.
 * <p>
 * This is a face-classification approach — it does not split triangles at
 * intersection boundaries. Results are clean when one mesh fully contains
 * the overlapping region of the other (e.g., subtracting a sphere from a cube).
 */
@MeshNodeAnnotation(id = "mesh_boolean")
public class MeshBooleanNode implements MeshNode {

    private static final InputPort MESH_A = new InputPort("mesh_a", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort MESH_B = new InputPort("mesh_b", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort OPERATION = new InputPort("operation", PortType.STRING, "DIFFERENCE",
            new ModeConstraint("DIFFERENCE", List.of("UNION", "DIFFERENCE", "INTERSECT"), Map.of()));
    private static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(MESH_A, MESH_B, OPERATION);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public String description() {
        return "Performs CSG boolean operations (union, difference, or intersect) on two meshes using ray-cast face classification.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "mesh_a", "First operand (typically the base mesh).",
                "mesh_b", "Second operand (typically the tool mesh).",
                "operation", "CSG op: UNION (A ∪ B), DIFFERENCE (A − B), INTERSECT (A ∩ B).",
                "geometry", "Result as a geometry bundle."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle gbA = GeometryBundles.bundlePart(ctx.getInput("mesh_a", Object.class));
        GeometryBundle gbB = GeometryBundles.bundlePart(ctx.getInput("mesh_b", Object.class));

        if (gbA == null || gbA.mesh() == null || gbA.mesh().vertexCount() == 0) {
            ctx.setOutput("geometry", gbB != null ? gbB : GeometryBundle.empty());
            return;
        }
        if (gbB == null || gbB.mesh() == null || gbB.mesh().vertexCount() == 0) {
            ctx.setOutput("geometry", gbA);
            return;
        }

        Object modeObj = FieldBroadcast.getInputOrDefault(ctx, "operation", OPERATION.defaultValue());
        String mode = modeObj instanceof String s ? s : "DIFFERENCE";

        MeshTopology meshA = gbA.mesh();
        MeshTopology meshB = gbB.mesh();

        // Extract triangle soups from both meshes
        TriangleSoup soupA = TriangleSoup.from(meshA);
        TriangleSoup soupB = TriangleSoup.from(meshB);

        HalfEdgeMesh result = new HalfEdgeMesh();

        switch (mode.toUpperCase()) {
            case "UNION" -> {
                // Keep A faces outside B + B faces outside A
                addClassifiedFaces(result, soupA, soupB, false, false);
                addClassifiedFaces(result, soupB, soupA, false, false);
            }
            case "DIFFERENCE" -> {
                // Keep A faces outside B + B faces inside A (flipped)
                addClassifiedFaces(result, soupA, soupB, false, false);
                addClassifiedFaces(result, soupB, soupA, true, true);
            }
            case "INTERSECT" -> {
                // Keep A faces inside B + B faces inside A
                addClassifiedFaces(result, soupA, soupB, true, false);
                addClassifiedFaces(result, soupB, soupA, true, false);
            }
        }

        result.computeNormals();
        ctx.setOutput("geometry", gbA.withMesh(result));
    }

    /**
     * Adds faces from {@code source} to {@code out} based on inside/outside classification
     * against {@code classifier}.
     *
     * @param keepInside if true, keep faces whose centroid is inside classifier; otherwise keep outside
     * @param flip       if true, reverse winding order of added faces
     */
    private static void addClassifiedFaces(HalfEdgeMesh out, TriangleSoup source,
                                            TriangleSoup classifier, boolean keepInside, boolean flip) {
        Vector3f centroid = new Vector3f();

        for (int ti = 0; ti < source.triCount; ti++) {
            // Compute triangle centroid
            int b = ti * 9;
            centroid.set(
                    (source.positions[b] + source.positions[b + 3] + source.positions[b + 6]) / 3f,
                    (source.positions[b + 1] + source.positions[b + 4] + source.positions[b + 7]) / 3f,
                    (source.positions[b + 2] + source.positions[b + 5] + source.positions[b + 8]) / 3f
            );

            boolean inside = isInsideMesh(centroid, classifier);
            if (inside != keepInside) continue;

            // Add triangle vertices
            int v0 = out.addVertex(source.positions[b], source.positions[b + 1], source.positions[b + 2]);
            int v1 = out.addVertex(source.positions[b + 3], source.positions[b + 4], source.positions[b + 5]);
            int v2 = out.addVertex(source.positions[b + 6], source.positions[b + 7], source.positions[b + 8]);

            if (flip) {
                out.addFace(v0, v2, v1);
            } else {
                out.addFace(v0, v1, v2);
            }
        }
    }

    /**
     * Tests if a point is inside a closed triangle mesh using ray casting (odd-crossing rule).
     * Casts a ray in +X direction and counts intersections with the mesh triangles.
     */
    private static boolean isInsideMesh(Vector3f point, TriangleSoup mesh) {
        int crossings = 0;
        // Cast ray in +X direction from point
        float ox = point.x, oy = point.y, oz = point.z;

        for (int ti = 0; ti < mesh.triCount; ti++) {
            int b = ti * 9;
            if (rayTriangleIntersectX(ox, oy, oz,
                    mesh.positions[b], mesh.positions[b + 1], mesh.positions[b + 2],
                    mesh.positions[b + 3], mesh.positions[b + 4], mesh.positions[b + 5],
                    mesh.positions[b + 6], mesh.positions[b + 7], mesh.positions[b + 8])) {
                crossings++;
            }
        }
        return (crossings & 1) == 1; // Odd = inside
    }

    /**
     * Möller–Trumbore ray-triangle intersection for a ray in +X direction.
     * Ray origin: (ox, oy, oz), direction: (1, 0, 0).
     * Returns true if the ray intersects the triangle at t > 0.
     */
    private static boolean rayTriangleIntersectX(
            float ox, float oy, float oz,
            float v0x, float v0y, float v0z,
            float v1x, float v1y, float v1z,
            float v2x, float v2y, float v2z) {

        // Edge vectors
        float e1x = v1x - v0x, e1y = v1y - v0y, e1z = v1z - v0z;
        float e2x = v2x - v0x, e2y = v2y - v0y, e2z = v2z - v0z;

        // h = dir × e2 = (1,0,0) × e2
        float hx = 0f;
        float hy = -e2z;
        float hz = e2y;

        float a = e1x * hx + e1y * hy + e1z * hz; // dot(e1, h)
        if (a > -1e-8f && a < 1e-8f) return false; // Ray parallel to triangle

        float f = 1f / a;
        float sx = ox - v0x, sy = oy - v0y, sz = oz - v0z;

        float u = f * (sx * hx + sy * hy + sz * hz);
        if (u < 0f || u > 1f) return false;

        // q = s × e1
        float qx = sy * e1z - sz * e1y;
        float qy = sz * e1x - sx * e1z;
        float qz = sx * e1y - sy * e1x;

        float v = f * qx; // f * dot(dir, q) = f * (1*qx + 0*qy + 0*qz)
        if (v < 0f || u + v > 1f) return false;

        float t = f * (e2x * qx + e2y * qy + e2z * qz);
        return t > 1e-6f; // Intersection at positive t
    }

    /**
     * Flattened triangle soup extracted from a MeshTopology.
     * All faces are triangulated (fan from first vertex for n-gons).
     * Positions stored as packed [x0,y0,z0, x1,y1,z1, x2,y2,z2, ...] per triangle.
     */
    private static final class TriangleSoup {
        final float[] positions;
        final int triCount;

        private TriangleSoup(float[] positions, int triCount) {
            this.positions = positions;
            this.triCount = triCount;
        }

        static TriangleSoup from(MeshTopology mesh) {
            // Count total triangles (fan triangulation: n-gon → n-2 triangles)
            int totalTris = 0;
            for (int fi = 0; fi < mesh.faceCount(); fi++) {
                int fid = mesh.faceIdAt(fi);
                int fc = mesh.faceVertexCount(fid);
                if (fc >= 3) totalTris += fc - 2;
            }

            float[] pos = new float[totalTris * 9];
            Vector3f v = new Vector3f();
            int idx = 0;

            for (int fi = 0; fi < mesh.faceCount(); fi++) {
                int fid = mesh.faceIdAt(fi);
                int fc = mesh.faceVertexCount(fid);
                if (fc < 3) continue;

                // Cache first vertex
                mesh.vertexPosition(mesh.faceVertexAt(fid, 0), v);
                float v0x = v.x, v0y = v.y, v0z = v.z;

                // Fan triangulation from vertex 0
                mesh.vertexPosition(mesh.faceVertexAt(fid, 1), v);
                float prevX = v.x, prevY = v.y, prevZ = v.z;

                for (int k = 2; k < fc; k++) {
                    mesh.vertexPosition(mesh.faceVertexAt(fid, k), v);
                    pos[idx++] = v0x; pos[idx++] = v0y; pos[idx++] = v0z;
                    pos[idx++] = prevX; pos[idx++] = prevY; pos[idx++] = prevZ;
                    pos[idx++] = v.x; pos[idx++] = v.y; pos[idx++] = v.z;
                    prevX = v.x; prevY = v.y; prevZ = v.z;
                }
            }

            return new TriangleSoup(pos, totalTris);
        }
    }
}
