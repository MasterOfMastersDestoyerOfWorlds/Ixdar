package ixdar.entrypoint;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.DiscreteGradient;

/**
 * Sanity test for {@link DiscreteGradient} on a triangulated cube
 * (topologically a 2-sphere, χ=2). Expected: critical-cell count
 * #mins − #saddles + #maxes = 2.
 *
 * <p>With scalar = z-coordinate: 4 bottom vertices have no lower
 * neighbours → 4 minima. The triangulation determines the rest, but
 * the Euler-characteristic invariant must hold.
 */
public final class VerifyDiscreteGradient {

    public static void main(String[] args) {
        // Same cube as VerifyCubeReconstruction.
        float[] positions = new float[]{
                0,0,0, 1,0,0, 1,1,0, 0,1,0,
                0,0,1, 1,0,1, 1,1,1, 0,1,1,
        };
        int[] faces = new int[]{
                0,2,1,  0,3,2,    // bottom
                4,5,6,  4,6,7,    // top
                0,1,5,  0,5,4,    // front
                3,7,6,  3,6,2,    // back
                0,4,7,  0,7,3,    // left
                1,2,6,  1,6,5,    // right
        };
        ArrayMesh cube = new ArrayMesh(positions, null, faces, 3);

        // Scalar = z-coordinate, with tiny vertex-index perturbation to avoid ties.
        float[] scalar = new float[8];
        for (int i = 0; i < 8; i++) scalar[i] = positions[i * 3 + 2] + i * 1e-5f;

        DiscreteGradient.Result r = DiscreteGradient.compute(cube, scalar);
        int mins = 0, saddles = 0, maxes = 0;
        for (int c : r.criticalCells()) {
            int d = r.dimOf(c);
            if (d == 0) mins++;
            else if (d == 1) saddles++;
            else maxes++;
        }
        System.out.println("Cube discrete-gradient critical cells:");
        System.out.println("  mins (critical 0-cells)   = " + mins);
        System.out.println("  saddles (critical 1-cells) = " + saddles);
        System.out.println("  maxes (critical 2-cells)   = " + maxes);
        System.out.println("  Euler char = mins - saddles + maxes = "
                + (mins - saddles + maxes) + "  (expected 2 for sphere)");
        System.out.println("  Total simplices: " + r.nv() + " verts + " + r.ne() + " edges + " + r.nt() + " tris");
        System.out.println("  Total critical / Total cells: " + r.criticalCells().length
                + " / " + (r.nv() + r.ne() + r.nt()));

        // Print a few example pairs.
        System.out.println();
        System.out.println("Sample pairings (first 8 paired cells):");
        int shown = 0;
        for (int c = 0; c < r.pair().length && shown < 8; c++) {
            if (r.pair()[c] >= 0 && r.pair()[c] > c) {
                int p = r.pair()[c];
                System.out.printf("  (%s%d, %s%d)%n",
                        dimChar(r.dimOf(c)), r.localIdx(c),
                        dimChar(r.dimOf(p)), r.localIdx(p));
                shown++;
            }
        }
    }

    private static String dimChar(int d) {
        return d == 0 ? "v" : (d == 1 ? "e" : "t");
    }
}
