package ixdar.nodegraph.nodes.primitives;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.EdgeKey;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

@MeshNodeAnnotation(id = "icosphere", category = NodeCategory.PRIMITIVES)
public class IcosphereMeshNode implements MeshNode {
    public static final InputPort RADIUS = new InputPort("radius", PortType.FLOAT, 1.0f, 0.001f, 100f);
    public static final InputPort SUBDIVISIONS = new InputPort("subdivisions", PortType.INT, 0, (float) 0, (float) 6);
    public static final OutputPort MESH = new OutputPort("mesh", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(RADIUS, SUBDIVISIONS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH);
    }

    @Override
    public String description() {
        return "Generates a triangle-based sphere by subdividing an icosahedron, controlled by radius and subdivision level (0 = 20-face icosahedron, each level quadruples face count).";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                RADIUS.name, "Distance from center to surface. icosphere(radius=r) has extent 2r on each axis (vertices at ±r). For a reference of extent <X,Y,Z>, start with radius=1 and apply transform_geometry(scale=<X/2, Y/2, Z/2>).",
                SUBDIVISIONS.name, "Number of recursive quadrisections. 0 = 20 triangle faces; each level quadruples face count. Caps at 6.",
                MESH.name, "Triangle-based sphere, manifold, centered at origin."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        float radius = ctx.getInput(RADIUS.name, Number.class) != null ? ctx.getInput(RADIUS.name, Number.class).floatValue()
                : 1.0f;

        Number subInput = ctx.getInput(SUBDIVISIONS.name, Number.class);
        int subdivisions = subInput == null ? 0 : subInput.intValue();

        float horizontalOffset = (float) Math.PI / 180 * 72;
        float elevation = (float) Math.atan(1.0 / 2.0);
        PackedPoints positions = new PackedPoints();
        positions.append(0f, radius, 0f);
        for (int ring = 0; ring < 2; ring++) {
            for (int i = 0; i < 5; i++) {
                float hAngle = i * horizontalOffset + ring * (horizontalOffset / 2);
                positions.append((float) (radius * Math.cos(elevation) * Math.cos(hAngle)),
                        (float) (radius * (ring == 0 ? Math.sin(elevation) : -Math.sin(elevation))),
                        (float) (radius * Math.cos(elevation) * Math.sin(hAngle)));
            }
        }
        positions.append(0f, -radius, 0f);
        int[] indices = icosahedronFaceIndices();

        for (int s = 0; s < subdivisions; s++) {
            Map<Long, Integer> edgeMidpoint = new HashMap<>();
            int[] out = new int[indices.length * 4];
            int o = 0;
            for (int t = 0; t < indices.length; t += 3) {
                int v0 = indices[t];
                int v1 = indices[t + 1];
                int v2 = indices[t + 2];
                int m1 = midpointOnSphere(v0, v1, positions, radius, edgeMidpoint);
                int m2 = midpointOnSphere(v1, v2, positions, radius, edgeMidpoint);
                int m3 = midpointOnSphere(v2, v0, positions, radius, edgeMidpoint);
                for (int corner : new int[] { v0, m1, m3, v1, m2, m1, v2, m3, m2, m1, m2, m3 }) {
                    out[o++] = corner;
                }
            }
            indices = out;
        }

        HalfEdgeMesh mesh = new HalfEdgeMesh();
        for (int i = 0; i < positions.size(); i++) {
            mesh.addVertex(positions.at(i));
        }
        for (int t = 0; t < indices.length; t += 3) {
            mesh.addFace(indices[t], indices[t + 1], indices[t + 2]);
        }

        mesh.computeNormals();
        ctx.setOutput(MESH.name, GeometryBundle.ofMesh(mesh));
    }

    private static int[] icosahedronFaceIndices() {
        int topPole = 0;
        int bottomPole = 11;
        int[] topRingVertices = { 1, 2, 3, 4, 5 };
        int[] bottomRingVertices = { 6, 7, 8, 9, 10 };
        int[] faces = new int[60];
        int f = 0;
        for (int i = 0; i < 5; i++) {
            int next = (i + 1) % 5;
            faces[f++] = topPole;
            faces[f++] = topRingVertices[next];
            faces[f++] = topRingVertices[i];

            faces[f++] = topRingVertices[i];
            faces[f++] = topRingVertices[next];
            faces[f++] = bottomRingVertices[i];

            faces[f++] = bottomRingVertices[i];
            faces[f++] = topRingVertices[next];
            faces[f++] = bottomRingVertices[next];

            faces[f++] = bottomPole;
            faces[f++] = bottomRingVertices[i];
            faces[f++] = bottomRingVertices[next];
        }
        return faces;
    }

    private static int midpointOnSphere(int a, int b, PackedPoints positions, float radius,
            Map<Long, Integer> edgeMidpoint) {
        long key = EdgeKey.undirected(a, b);
        Integer existing = edgeMidpoint.get(key);
        if (existing != null) {
            return existing;
        }
        Vector3f mid = positions.at(a).add(positions.at(b)).mul(0.5f);
        // Divided per component (JOML's div multiplies by a reciprocal) and summed in this order,
        // not by length(), so a radius-1 and a radius-2 sphere keep their exactly shared points.
        float len = (float) Math.sqrt(mid.x * mid.x + mid.y * mid.y + mid.z * mid.z);
        int idx = positions.append(mid.div(len, len, len).mul(radius));
        edgeMidpoint.put(key, idx);
        return idx;
    }
}
