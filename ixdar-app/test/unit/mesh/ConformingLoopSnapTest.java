package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.paths.geodesic.ConformingLoopSnap;
import ixdar.geometry.mesh.data.paths.geodesic.TracedSurfacePath;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * CRAW-89: a closed path whose snapped vertices go round a triangle and back to where they left
 * the ring, as ring_11's did on IMG_4110, snaps to one simple edge cycle without that triangle.
 */
class ConformingLoopSnapTest {

    private static final int ROW = 4;

    @Test
    void aLoopRoundOneTriangleHangingOffTheRingIsDropped() {
        MeshTopology cylinder = RingRegionsTest.cappedCylinder();
        int around = RingRegionsTest.SEGMENTS_AROUND;
        int junction = ROW * around;
        // Round the ring's row, but from its first vertex up the triangle above it and back.
        int[] detour = { junction, junction + around, junction + around + 1 };
        int[] vertexIds = new int[detour.length + around];
        for (int index = 0; index < detour.length; index++) {
            vertexIds[index] = cylinder.vertexIdAt(detour[index]);
        }
        for (int segment = 0; segment < around; segment++) {
            vertexIds[detour.length + segment] = cylinder.vertexIdAt(junction + segment);
        }
        double[] positions = new double[3 * vertexIds.length];
        Vector3f position = new Vector3f();
        for (int point = 0; point < vertexIds.length; point++) {
            cylinder.vertexPosition(vertexIds[point], position);
            positions[3 * point] = position.x;
            positions[3 * point + 1] = position.y;
            positions[3 * point + 2] = position.z;
        }
        int[] noEdge = new int[vertexIds.length];
        double[] noFraction = new double[vertexIds.length];
        Arrays.fill(noEdge, -1);
        Arrays.fill(noFraction, -1.0);
        TracedSurfacePath path = new TracedSurfacePath(positions, vertexIds, noEdge, noFraction,
                vertexIds.length, true);

        boolean[] marks = new ConformingLoopSnap().snap(cylinder, path);

        int[] loop = EdgeMarks.orderedLoop(cylinder, marks);
        assertEquals(around, loop.length, "the snapped marks are not one simple cycle of the row");
        int[] row = Arrays.copyOfRange(vertexIds, detour.length, vertexIds.length);
        Arrays.sort(row);
        Arrays.sort(loop);
        assertArrayEquals(row, loop, "the cycle leaves the ring's row");
    }
}
