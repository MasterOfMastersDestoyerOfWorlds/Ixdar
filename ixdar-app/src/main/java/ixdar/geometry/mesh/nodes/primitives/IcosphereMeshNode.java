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
import ixdar.geometry.mesh.data.HalfEdgeMesh;

@MeshNodeAnnotation(id = "icosphere")
public class IcosphereMeshNode implements MeshNode {
    private static final InputPort RADIUS = new InputPort("radius", PortType.FLOAT, 1.0f);
    private static final InputPort SUBDIVISIONS = new InputPort("subdivisions", PortType.INT, 0);
    private static final OutputPort MESH = new OutputPort("mesh", PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(RADIUS, SUBDIVISIONS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        float radius = ctx.getInput("radius", Number.class) != null ? ctx.getInput("radius", Number.class).floatValue()
                : 1.0f;

        Number subInput = ctx.getInput("subdivisions", Number.class);
        int subdivisions = subInput == null ? 0 : subInput.intValue();

        ArrayList<Float> positions = new ArrayList<>(36);
        appendIcosahedronVertices(radius, positions);
        int[] indices = icosahedronFaceIndices();

        for (int s = 0; s < subdivisions; s++) {
            indices = subdivideTriangles(positions, indices, radius);
        }

        HalfEdgeMesh mesh = new HalfEdgeMesh();
        int vCount = positions.size() / 3;
        for (int i = 0; i < vCount; i++) {
            mesh.addVertex(positions.get(3 * i), positions.get(3 * i + 1), positions.get(3 * i + 2));
        }
        for (int t = 0; t < indices.length; t += 3) {
            mesh.addFace(indices[t], indices[t + 1], indices[t + 2]);
        }

        mesh.computeNormals();
        ctx.setOutput("mesh", mesh);
    }

    private static void appendIcosahedronVertices(float radius, ArrayList<Float> out) {
        float pi = (float) Math.PI;
        float horizontalOffset = pi / 180 * 72;
        float elevation = (float) Math.atan(1.0 / 2.0);

        out.add(0f);
        out.add(radius);
        out.add(0f);

        for (int i = 0; i < 5; i++) {
            float hAngle = i * horizontalOffset;
            float x = (float) (radius * Math.cos(elevation) * Math.cos(hAngle));
            float y = (float) (radius * Math.sin(elevation));
            float z = (float) (radius * Math.cos(elevation) * Math.sin(hAngle));
            out.add(x);
            out.add(y);
            out.add(z);
        }

        for (int i = 0; i < 5; i++) {
            float hAngle = i * horizontalOffset + (horizontalOffset / 2);
            float x = (float) (radius * Math.cos(elevation) * Math.cos(hAngle));
            float y = (float) (radius * -Math.sin(elevation));
            float z = (float) (radius * Math.cos(elevation) * Math.sin(hAngle));
            out.add(x);
            out.add(y);
            out.add(z);
        }

        out.add(0f);
        out.add(-radius);
        out.add(0f);
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
        int nTri = indices.length / 3;
        int[] out = new int[nTri * 4 * 3];
        int o = 0;
        for (int t = 0; t < indices.length; t += 3) {
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
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }

    private static int midpointOnSphere(int a, int b, ArrayList<Float> positions, float radius,
            Map<Long, Integer> edgeMidpoint) {
        long key = edgeKey(a, b);
        Integer existing = edgeMidpoint.get(key);
        if (existing != null) {
            return existing;
        }
        float ax = positions.get(3 * a);
        float ay = positions.get(3 * a + 1);
        float az = positions.get(3 * a + 2);
        float bx = positions.get(3 * b);
        float by = positions.get(3 * b + 1);
        float bz = positions.get(3 * b + 2);
        float mx = (ax + bx) * 0.5f;
        float my = (ay + by) * 0.5f;
        float mz = (az + bz) * 0.5f;
        float len = (float) Math.sqrt(mx * mx + my * my + mz * mz);
        mx = (mx / len) * radius;
        my = (my / len) * radius;
        mz = (mz / len) * radius;
        int idx = positions.size() / 3;
        positions.add(mx);
        positions.add(my);
        positions.add(mz);
        edgeMidpoint.put(key, idx);
        return idx;
    }
}
