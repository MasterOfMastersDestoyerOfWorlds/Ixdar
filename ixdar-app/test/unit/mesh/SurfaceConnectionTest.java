package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.SurfaceConnection;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.scenes.ring.RingTool;

/**
 * The connection between two points on two spheres joined by a thin tube: the path runs through
 * the tube, and the neck Enter confirms is one closed edge loop around the tube that, as a wall,
 * separates the spheres.
 */
class SurfaceConnectionTest {

    private static final int AROUND = 24;

    private static final int SPHERE_ROWS = 20;

    private static final int TUBE_ROWS = 10;

    private static final double TUBE_RADIUS = 0.2;

    private static final double SPHERE_CENTRE = 2.0;

    private static final double TUBE_END = SPHERE_CENTRE - Math.sqrt(1.0 - TUBE_RADIUS * TUBE_RADIUS);

    private static final double CIRCUMFERENCE_TOLERANCE = 0.1;

    private static final float POLE = 3f;

    @Test
    void theNeckIsAnEdgeLoopOnTheTubeThatSeparatesTheSpheres() {
        // Two unit spheres on the z axis joined by a tube of radius TUBE_RADIUS, as one surface
        // of revolution closed by a vertex at each pole.
        int rows = 2 * SPHERE_ROWS + TUBE_ROWS;
        double[] rowRadius = new double[rows];
        double[] rowHeight = new double[rows];
        double capAngle = Math.PI - Math.asin(TUBE_RADIUS);
        for (int row = 0; row < SPHERE_ROWS; row++) {
            double angle = capAngle * (row + 1) / SPHERE_ROWS;
            rowRadius[row] = Math.sin(angle);
            rowHeight[row] = -SPHERE_CENTRE - Math.cos(angle);
            rowRadius[rows - 1 - row] = rowRadius[row];
            rowHeight[rows - 1 - row] = -rowHeight[row];
        }
        for (int row = 0; row < TUBE_ROWS; row++) {
            rowRadius[SPHERE_ROWS + row] = TUBE_RADIUS;
            rowHeight[SPHERE_ROWS + row] = -TUBE_END + 2.0 * TUBE_END * (row + 1) / (TUBE_ROWS + 1);
        }
        float[] positions = new float[3 * (rows * AROUND + 2)];
        for (int row = 0; row < rows; row++) {
            for (int step = 0; step < AROUND; step++) {
                double angle = 2.0 * Math.PI * step / AROUND;
                int base = 3 * (row * AROUND + step);
                positions[base] = (float) (rowRadius[row] * Math.cos(angle));
                positions[base + 1] = (float) (rowRadius[row] * Math.sin(angle));
                positions[base + 2] = (float) rowHeight[row];
            }
        }
        int southPole = rows * AROUND;
        int northPole = southPole + 1;
        positions[3 * southPole + 2] = (float) (-SPHERE_CENTRE - 1.0);
        positions[3 * northPole + 2] = (float) (SPHERE_CENTRE + 1.0);
        int[] faces = new int[3 * (2 * AROUND + 2 * AROUND * (rows - 1))];
        int cursor = 0;
        for (int step = 0; step < AROUND; step++) {
            int nextStep = (step + 1) % AROUND;
            cursor = triangle(faces, cursor, southPole, nextStep, step);
            int last = (rows - 1) * AROUND;
            cursor = triangle(faces, cursor, northPole, last + step, last + nextStep);
            for (int row = 0; row + 1 < rows; row++) {
                int low = row * AROUND;
                int high = low + AROUND;
                cursor = triangle(faces, cursor, low + step, low + nextStep, high + nextStep);
                cursor = triangle(faces, cursor, low + step, high + nextStep, high + step);
            }
        }
        MeshTopology dumbbell = HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faces);

        SurfaceConnection connection = new SurfaceConnection(dumbbell);
        int south = connection.nearestActiveFace(0f, 0f, -POLE);
        int north = connection.nearestActiveFace(0f, 0f, POLE);

        assertTrue(connection.connect(south, north), connection.failure);

        boolean throughTube = false;
        for (int face : connection.pathActiveFace) {
            float z = connection.centroidXyz[3 * face + 2];
            throughTube |= Math.abs(z) < TUBE_END * 0.5;
        }
        assertTrue(throughTube, "the path runs through the tube");
        double tubeGirth = 2.0 * Math.PI * TUBE_RADIUS;
        assertEquals(tubeGirth, connection.narrowestGirth, tubeGirth * CIRCUMFERENCE_TOLERANCE);

        int[] neck = RingTool.orderedLoop(dumbbell, connection.narrowestMarkedByEdgeId);
        assertTrue(neck.length >= AROUND, "the neck snaps to one closed edge loop");
        Vector3f position = new Vector3f();
        Vector3f following = new Vector3f();
        double neckLength = 0.0;
        for (int index = 0; index < neck.length; index++) {
            dumbbell.vertexPosition(neck[index], position);
            dumbbell.vertexPosition(neck[(index + 1) % neck.length], following);
            neckLength += position.distance(following);
            assertTrue(Math.abs(position.z) <= TUBE_END + 1e-4,
                    "the confirmed loop sits on the tube, not at z " + position.z);
        }
        assertEquals(tubeGirth, neckLength, tubeGirth * CIRCUMFERENCE_TOLERANCE);

        connection.wallByEdgeId = connection.narrowestMarkedByEdgeId;
        assertFalse(connection.connect(south, north));
        assertFalse(connection.connected, "the neck as a ring separates the spheres");
    }

    private static int triangle(int[] faces, int cursor, int first, int second, int third) {
        faces[cursor] = first;
        faces[cursor + 1] = second;
        faces[cursor + 2] = third;
        return cursor + 3;
    }
}
