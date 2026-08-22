package ixdar.geometry.mesh.nodes.primitives;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

@MeshNodeAnnotation(id = "icosphere")
public class IcosphereMeshNode implements MeshNode {
    public static final int NUM_36 = 36;
    public static final int NUM_3 = 3;
    public static final int NUM_180 = 180;
    public static final int NUM_72 = 72;
    public static final double NUM_2_0 = 2.0;
    public static final float NUM_0 = 0f;
    public static final int NUM_5 = 5;
    public static final int NUM_11 = 11;
    public static final int NUM_4 = 4;
    public static final int NUM_6 = 6;
    public static final int NUM_7 = 7;
    public static final int NUM_8 = 8;
    public static final int NUM_9 = 9;
    public static final int NUM_10 = 10;
    public static final int NUM_60 = 60;
    public static final int NUM_32 = 32;
    public static final long NUM_0xffffffff = 0xffffffffL;
    public static final float NUM_0_5 = 0.5f;
    public static final InputPort RADIUS = new InputPort("radius", PortType.FLOAT, 1.0f, 0.001f, 100f);
    public static final InputPort SUBDIVISIONS = new InputPort("subdivisions", PortType.INT, 0, (float) 0, (float) 6);
    public static final OutputPort MESH = new OutputPort("mesh", PortType.MESH);

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

        ArrayList<Float> positions = new ArrayList<>(NUM_36);
        appendIcosahedronVertices(radius, positions);
        int[] indices = icosahedronFaceIndices();

        for (int s = 0; s < subdivisions; s++) {
            indices = subdivideTriangles(positions, indices, radius);
        }

        HalfEdgeMesh mesh = new HalfEdgeMesh();
        int vCount = positions.size() / NUM_3;
        for (int i = 0; i < vCount; i++) {
            mesh.addVertex(positions.get(NUM_3 * i), positions.get(NUM_3 * i + 1), positions.get(NUM_3 * i + 2));
        }
        for (int t = 0; t < indices.length; t += NUM_3) {
            mesh.addFace(indices[t], indices[t + 1], indices[t + 2]);
        }

        mesh.computeNormals();
        ctx.setOutput(MESH.name, mesh);
    }

    private static void appendIcosahedronVertices(float radius, ArrayList<Float> out) {
        float pi = (float) Math.PI;
        float horizontalOffset = pi / NUM_180 * NUM_72;
        float elevation = (float) Math.atan(1.0 / NUM_2_0);

        out.add(NUM_0);
        out.add(radius);
        out.add(NUM_0);

        for (int i = 0; i < NUM_5; i++) {
            float hAngle = i * horizontalOffset;
            float x = (float) (radius * Math.cos(elevation) * Math.cos(hAngle));
            float y = (float) (radius * Math.sin(elevation));
            float z = (float) (radius * Math.cos(elevation) * Math.sin(hAngle));
            out.add(x);
            out.add(y);
            out.add(z);
        }

        for (int i = 0; i < NUM_5; i++) {
            float hAngle = i * horizontalOffset + (horizontalOffset / 2);
            float x = (float) (radius * Math.cos(elevation) * Math.cos(hAngle));
            float y = (float) (radius * -Math.sin(elevation));
            float z = (float) (radius * Math.cos(elevation) * Math.sin(hAngle));
            out.add(x);
            out.add(y);
            out.add(z);
        }

        out.add(NUM_0);
        out.add(-radius);
        out.add(NUM_0);
    }

    private static int[] icosahedronFaceIndices() {
        int topPole = 0;
        int bottomPole = NUM_11;
        int[] topRingVertices = { 1, 2, NUM_3, NUM_4, NUM_5 };
        int[] bottomRingVertices = { NUM_6, NUM_7, NUM_8, NUM_9, NUM_10 };
        int[] faces = new int[NUM_60];
        int f = 0;
        for (int i = 0; i < NUM_5; i++) {
            int next = (i + 1) % NUM_5;
            faces[f++] = topPole;
            faces[f++] = topRingVertices[i];
            faces[f++] = topRingVertices[next];

            faces[f++] = topRingVertices[i];
            faces[f++] = bottomRingVertices[i];
            faces[f++] = topRingVertices[next];

            faces[f++] = bottomRingVertices[i];
            faces[f++] = bottomRingVertices[next];
            faces[f++] = topRingVertices[next];

            faces[f++] = bottomPole;
            faces[f++] = bottomRingVertices[next];
            faces[f++] = bottomRingVertices[i];
        }
        return faces;
    }

    private static int[] subdivideTriangles(ArrayList<Float> positions, int[] indices, float radius) {
        Map<Long, Integer> edgeMidpoint = new HashMap<>();
        int nTri = indices.length / NUM_3;
        int[] out = new int[nTri * NUM_4 * NUM_3];
        int o = 0;
        for (int t = 0; t < indices.length; t += NUM_3) {
            int v0 = indices[t];
            int v1 = indices[t + 1];
            int v2 = indices[t + 2];
            int m1 = midpointOnSphere(v0, v1, positions, radius, edgeMidpoint);
            int m2 = midpointOnSphere(v1, v2, positions, radius, edgeMidpoint);
            int m3 = midpointOnSphere(v2, v0, positions, radius, edgeMidpoint);
            out[o++] = v0;
            out[o++] = m1;
            out[o++] = m3;
            out[o++] = v1;
            out[o++] = m2;
            out[o++] = m1;
            out[o++] = v2;
            out[o++] = m3;
            out[o++] = m2;
            out[o++] = m1;
            out[o++] = m2;
            out[o++] = m3;
        }
        return out;
    }

    private static long edgeKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << NUM_32) | (hi & NUM_0xffffffff);
    }

    private static int midpointOnSphere(int a, int b, ArrayList<Float> positions, float radius,
            Map<Long, Integer> edgeMidpoint) {
        long key = edgeKey(a, b);
        Integer existing = edgeMidpoint.get(key);
        if (existing != null) {
            return existing;
        }
        float ax = positions.get(NUM_3 * a);
        float ay = positions.get(NUM_3 * a + 1);
        float az = positions.get(NUM_3 * a + 2);
        float bx = positions.get(NUM_3 * b);
        float by = positions.get(NUM_3 * b + 1);
        float bz = positions.get(NUM_3 * b + 2);
        float mx = (ax + bx) * NUM_0_5;
        float my = (ay + by) * NUM_0_5;
        float mz = (az + bz) * NUM_0_5;
        float len = (float) Math.sqrt(mx * mx + my * my + mz * mz);
        mx = (mx / len) * radius;
        my = (my / len) * radius;
        mz = (mz / len) * radius;
        int idx = positions.size() / NUM_3;
        positions.add(mx);
        positions.add(my);
        positions.add(mz);
        edgeMidpoint.put(key, idx);
        return idx;
    }
}
