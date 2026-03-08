package ixdar.geometry.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class Icosphere {

    private final ArrayList<Face> faces;
    private final ArrayList<FaceState> idealStates;
    private final ArrayList<Vector3f> axes;
    private final float radius;

    public Icosphere(float radius) {
        this.radius = radius;
        this.faces = new ArrayList<>();
        this.idealStates = new ArrayList<>();
        this.axes = new ArrayList<>();
        float phi = (1f + (float) Math.sqrt(5f)) * 0.5f;
        Vector3f[] vertices = new Vector3f[] {
                new Vector3f(-1f, phi, 0f),
                new Vector3f(1f, phi, 0f),
                new Vector3f(-1f, -phi, 0f),
                new Vector3f(1f, -phi, 0f),
                new Vector3f(0f, -1f, phi),
                new Vector3f(0f, 1f, phi),
                new Vector3f(0f, -1f, -phi),
                new Vector3f(0f, 1f, -phi),
                new Vector3f(phi, 0f, -1f),
                new Vector3f(phi, 0f, 1f),
                new Vector3f(-phi, 0f, -1f),
                new Vector3f(-phi, 0f, 1f),
        };
        for (int i1 = 0; i1 < vertices.length; i1++) {
            vertices[i1].normalize(radius);
            axes.add(new Vector3f(vertices[i1]).normalize());
        }
        int[][] triIndices = new int[][] {
                { 0, 11, 5 }, { 0, 5, 1 }, { 0, 1, 7 }, { 0, 7, 10 }, { 0, 10, 11 },
                { 1, 5, 9 }, { 5, 11, 4 }, { 11, 10, 2 }, { 10, 7, 6 }, { 7, 1, 8 },
                { 3, 9, 4 }, { 3, 4, 2 }, { 3, 2, 6 }, { 3, 6, 8 }, { 3, 8, 9 },
                { 4, 9, 5 }, { 2, 4, 11 }, { 6, 2, 10 }, { 8, 6, 7 }, { 9, 8, 1 },
        };

        for (int i = 0; i < triIndices.length; i++) {
            Vector3f v1 = new Vector3f(vertices[triIndices[i][0]]);
            Vector3f v2 = new Vector3f(vertices[triIndices[i][1]]);
            Vector3f v3 = new Vector3f(vertices[triIndices[i][2]]);

            Vector3f center = new Vector3f(v1).add(v2).add(v3).div(3f);
            Vector3f normal = new Vector3f(center).normalize();
            Vector3f yAxis = new Vector3f(v1).sub(center).normalize();
            Vector3f xAxis = new Vector3f(yAxis).cross(normal).normalize();
            Matrix4f basis = new Matrix4f();
            basis.m00(xAxis.x);
            basis.m01(xAxis.y);
            basis.m02(xAxis.z);
            basis.m10(yAxis.x);
            basis.m11(yAxis.y);
            basis.m12(yAxis.z);
            basis.m20(normal.x);
            basis.m21(normal.y);
            basis.m22(normal.z);
            Quaternionf idealRot = new Quaternionf().setFromNormalized(basis);
            Quaternionf inv = new Quaternionf(idealRot).invert();
            Vector3f localV1 = new Vector3f(v1).sub(center).rotate(inv);
            Vector3f localV2 = new Vector3f(v2).sub(center).rotate(inv);
            Vector3f localV3 = new Vector3f(v3).sub(center).rotate(inv);

            faces.add(new Face(
                    i,
                    localV1,
                    localV2,
                    localV3,
                    new Vector3f(center),
                    new Quaternionf(idealRot),
                    new Vector3f(normal)));
            idealStates.add(new FaceState(i, new Vector3f(center), new Quaternionf(idealRot), new Vector3f(normal)));
        }
    }

    public List<Face> faces() {
        return Collections.unmodifiableList(faces);
    }

    public List<FaceState> idealStates() {
        return Collections.unmodifiableList(idealStates);
    }

    public List<Vector3f> axes() {
        return Collections.unmodifiableList(axes);
    }

    public float radius() {
        return radius;
    }

    public Vector3f randomAxis(Random random) {
        if (axes.isEmpty()) {
            return new Vector3f(0f, 1f, 0f);
        }
        return new Vector3f(axes.get(random.nextInt(axes.size())));
    }

    public ArrayList<Integer> selectBand(Vector3f axis, boolean capBand) {
        ArrayList<Integer> group = new ArrayList<>();
        for (int i = 0; i < idealStates.size(); i++) {
            FaceState face = idealStates.get(i);
            float dot = face.position.normalize(new Vector3f()).dot(axis);
            if (capBand) {
                if (dot > 0.6f) {
                    group.add(i);
                }
            } else {
                if (dot > -0.4f && dot < 0.4f) {
                    group.add(i);
                }
            }
        }
        return group;
    }
}
