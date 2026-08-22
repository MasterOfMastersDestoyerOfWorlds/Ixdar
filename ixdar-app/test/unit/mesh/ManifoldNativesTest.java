package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;

import org.junit.jupiter.api.Test;

import com.cadoodlecad.manifold.ManifoldBindings;

import ixdar.geometry.mesh.csg.ManifoldMeshBooleanBackend;

/**
 * Proves the Manifold CSG natives load and boolean the QuadMixer target configuration: two unit
 * cubes, one with a corner on the other's centre.
 *
 * <p>A native load failure surfaces here as a linkage error rather than deep inside a scene.
 */
public class ManifoldNativesTest {

    /** Overlap of the two cubes is the corner octant, an eighth of a unit cube. */
    private static final double OVERLAP_VOLUME = 0.125;

    /** Volume tolerance: exact arithmetic on axis-aligned boxes leaves nothing to round. */
    private static final double VOLUME_TOLERANCE = 1e-9;

    private static final double UNIT = 1.0;
    private static final double HALF = 0.5;

    /**
     * Union, difference and intersection of the corner-at-centre cube pair all produce closed,
     * genus-zero solids with the volumes exact set arithmetic predicts.
     *
     * @throws Throwable if a native call fails
     */
    @Test
    public void cornerAtCentreCubesBooleanToTheExpectedVolumes() throws Throwable {
        ManifoldBindings bindings = ManifoldMeshBooleanBackend.BINDINGS;
        MemorySegment cubeA = bindings.cube(UNIT, UNIT, UNIT, false);
        MemorySegment cubeB = bindings.translate(bindings.cube(UNIT, UNIT, UNIT, false),
                HALF, HALF, HALF);

        MemorySegment union = bindings.union(cubeA, cubeB);
        MemorySegment difference = bindings.difference(cubeA, cubeB);
        MemorySegment intersection = bindings.intersection(cubeA, cubeB);

        assertEquals(2 * UNIT - OVERLAP_VOLUME, bindings.volume(union), VOLUME_TOLERANCE,
                "union is both cubes less their shared octant");
        assertEquals(UNIT - OVERLAP_VOLUME, bindings.volume(difference), VOLUME_TOLERANCE,
                "difference is cube A less the shared octant");
        assertEquals(OVERLAP_VOLUME, bindings.volume(intersection), VOLUME_TOLERANCE,
                "intersection is exactly the shared octant");

        assertEquals(0, bindings.genus(union), "the union is a ball, not a torus");
        assertTrue(bindings.numTri(union) > 0, "the union produced triangles");
        assertTrue(bindings.numVert(union) > 0, "the union produced vertices");
    }
}
