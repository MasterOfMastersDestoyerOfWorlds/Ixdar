package ixdar.geometry.mesh.standalone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import ixdar.geometry.mesh.data.Face;
import ixdar.geometry.mesh.data.FaceState;

public class Icosphere {
    public static final float NUM_1 = 1f;
    public static final float NUM_5 = 5f;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_0 = 0f;
    public static final int NUM_11 = 11;
    public static final int NUM_5_2 = 5;
    public static final int NUM_7 = 7;
    public static final int NUM_10 = 10;
    public static final int NUM_9 = 9;
    public static final int NUM_4 = 4;
    public static final int NUM_6 = 6;
    public static final int NUM_8 = 8;
    public static final int NUM_3 = 3;
    public static final float NUM_3_2 = 3f;
    public static final float NUM_0_6 = 0.6f;
    public static final float NUM_0_4 = 0.4f;

    private final ArrayList<Face> faces;
    private final ArrayList<FaceState> idealStates;
    private final ArrayList<Vector3f> axes;
    private final float radius;

    /**
     * TODO: document {@code Icosphere}.
     *
     * @param radius TODO: describe
     */
    public Icosphere(float radius) {
        this.radius = radius;
        this.faces = new ArrayList<>();
        this.idealStates = new ArrayList<>();
        this.axes = new ArrayList<>();
        float phi = (NUM_1 + (float) Math.sqrt(NUM_5)) * NUM_0_5;
        Vector3f[] vertices = new Vector3f[] {
                new Vector3f(-NUM_1, phi, NUM_0),
                new Vector3f(NUM_1, phi, NUM_0),
                new Vector3f(-NUM_1, -phi, NUM_0),
                new Vector3f(NUM_1, -phi, NUM_0),
                new Vector3f(NUM_0, -NUM_1, phi),
                new Vector3f(NUM_0, NUM_1, phi),
                new Vector3f(NUM_0, -NUM_1, -phi),
                new Vector3f(NUM_0, NUM_1, -phi),
                new Vector3f(phi, NUM_0, -NUM_1),
                new Vector3f(phi, NUM_0, NUM_1),
                new Vector3f(-phi, NUM_0, -NUM_1),
                new Vector3f(-phi, NUM_0, NUM_1),
        };
        for (int i1 = 0; i1 < vertices.length; i1++) {
            vertices[i1].normalize(radius);
            axes.add(new Vector3f(vertices[i1]).normalize());
        }
        int[][] triIndices = new int[][] {
                { 0, NUM_11, NUM_5_2 }, { 0, NUM_5_2, 1 }, { 0, 1, NUM_7 }, { 0, NUM_7, NUM_10 }, { 0, NUM_10, NUM_11 },
                { 1, NUM_5_2, NUM_9 }, { NUM_5_2, NUM_11, NUM_4 }, { NUM_11, NUM_10, 2 }, { NUM_10, NUM_7, NUM_6 }, { NUM_7, 1, NUM_8 },
                { NUM_3, NUM_9, NUM_4 }, { NUM_3, NUM_4, 2 }, { NUM_3, 2, NUM_6 }, { NUM_3, NUM_6, NUM_8 }, { NUM_3, NUM_8, NUM_9 },
                { NUM_4, NUM_9, NUM_5_2 }, { 2, NUM_4, NUM_11 }, { NUM_6, 2, NUM_10 }, { NUM_8, NUM_6, NUM_7 }, { NUM_9, NUM_8, 1 },
        };

        for (int i = 0; i < triIndices.length; i++) {
            Vector3f v1 = new Vector3f(vertices[triIndices[i][0]]);
            Vector3f v2 = new Vector3f(vertices[triIndices[i][1]]);
            Vector3f v3 = new Vector3f(vertices[triIndices[i][2]]);

            Vector3f center = new Vector3f(v1).add(v2).add(v3).div(NUM_3_2);
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

    /**
     * TODO: document {@code faces}.
     *
     * @return TODO: describe
     */
    public List<Face> faces() {
        return Collections.unmodifiableList(faces);
    }

    /**
     * TODO: document {@code idealStates}.
     *
     * @return TODO: describe
     */
    public List<FaceState> idealStates() {
        return Collections.unmodifiableList(idealStates);
    }

    /**
     * TODO: document {@code axes}.
     *
     * @return TODO: describe
     */
    public List<Vector3f> axes() {
        return Collections.unmodifiableList(axes);
    }

    /**
     * TODO: document {@code radius}.
     *
     * @return TODO: describe
     */
    public float radius() {
        return radius;
    }

    /**
     * TODO: document {@code randomAxis}.
     *
     * @param random TODO: describe
     * @return TODO: describe
     */
    public Vector3f randomAxis(Random random) {
        if (axes.isEmpty()) {
            return new Vector3f(NUM_0, NUM_1, NUM_0);
        }
        return new Vector3f(axes.get(random.nextInt(axes.size())));
    }

    /**
     * TODO: document {@code selectBand}.
     *
     * @param axis TODO: describe
     * @param capBand TODO: describe
     * @return TODO: describe
     */
    public ArrayList<Integer> selectBand(Vector3f axis, boolean capBand) {
        ArrayList<Integer> group = new ArrayList<>();
        for (int i = 0; i < idealStates.size(); i++) {
            FaceState face = idealStates.get(i);
            float dot = face.position.normalize(new Vector3f()).dot(axis);
            if (capBand) {
                if (dot > NUM_0_6) {
                    group.add(i);
                }
            } else {
                if (dot > -NUM_0_4 && dot < NUM_0_4) {
                    group.add(i);
                }
            }
        }
        return group;
    }
}
