package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import manifold3d.Manifold;
import manifold3d.linalg.DoubleVec3;
import manifold3d.manifold.MeshGL;

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
     */
    @Test
    public void cornerAtCentreCubesBooleanToTheExpectedVolumes() {
        Manifold cubeA = Manifold.Cube(new DoubleVec3(UNIT, UNIT, UNIT), false);
        Manifold cubeB = Manifold.Cube(new DoubleVec3(UNIT, UNIT, UNIT), false)
                .translate(HALF, HALF, HALF);

        Manifold union = cubeA.add(cubeB);
        Manifold difference = cubeA.subtract(cubeB);
        Manifold intersection = cubeA.intersect(cubeB);

        assertEquals(2 * UNIT - OVERLAP_VOLUME, union.volume(), VOLUME_TOLERANCE,
                "union is both cubes less their shared octant");
        assertEquals(UNIT - OVERLAP_VOLUME, difference.volume(), VOLUME_TOLERANCE,
                "difference is cube A less the shared octant");
        assertEquals(OVERLAP_VOLUME, intersection.volume(), VOLUME_TOLERANCE,
                "intersection is exactly the shared octant");

        assertEquals(0, union.genus(), "the union is a ball, not a torus");
        assertFalse(union.isEmpty(), "the union has geometry");

        MeshGL mesh = union.getMeshGL();
        assertTrue(mesh.NumTri() > 0, "the union produced triangles");
        assertTrue(mesh.NumVert() > 0, "the union produced vertices");
    }
}
