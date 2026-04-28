package ixdar.entrypoint;

import java.io.File;
import java.util.List;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.quadlayout.boundary.BoundaryCapper;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

/**
 * CLI verifier for the PATCH-39 cross field.
 *
 * <pre>
 *   uv run mvn -pl ixdar-app exec:java -Dexec.mainClass=ixdar.entrypoint.VerifyCrossField -Dexec.args="/path/to/mesh.obj"
 * </pre>
 *
 * Loads an OBJ, optionally caps any open boundaries, runs the angle-based
 * MI cross field, and prints singularity statistics + Euler-characteristic
 * agreement (sum of indices = chi(M) per Poincare-Hopf).
 */
public final class VerifyCrossField {

    private VerifyCrossField() {}

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "/Users/acw28/Blends/Hand/Hand.obj";
        if (!new File(path).exists()) {
            System.err.println("[verify-xfield] mesh not found: " + path);
            System.exit(2);
        }

        long t0 = System.currentTimeMillis();
        ArrayMesh raw = MeshLoader.load(path);
        long tLoad = System.currentTimeMillis() - t0;
        System.out.println("Loaded " + path + " in " + tLoad + " ms"
                + "  V=" + raw.vertexCount()
                + "  F=" + (raw.copyFaceIndices().length / 3));

        // Cap any open boundaries (PATCH-45) so the angle-based field can
        // run on a closed manifold.
        long t1 = System.currentTimeMillis();
        BoundaryCapper.CapResult cap = BoundaryCapper.cap(raw);
        ArrayMesh mesh = cap.closedMesh();
        long tCap = System.currentTimeMillis() - t1;
        System.out.println("Capped " + cap.originalLoops().size() + " boundary loops in "
                + tCap + " ms  -> V=" + mesh.vertexCount()
                + "  F=" + mesh.faceCount());

        long t2 = System.currentTimeMillis();
        FaceRosyField xfield = new FaceRosyField(mesh);
        xfield.solve();
        long tSolve = System.currentTimeMillis() - t2;
        System.out.println("Cross field solved in " + tSolve + " ms"
                + "  smoothness energy = " + xfield.smoothnessEnergy());

        List<Singularity> sings = xfield.findSingularities();
        int sumIndex4 = 0;
        for (Singularity s : sings) sumIndex4 += s.index4();
        int chi = computeEuler(mesh);
        System.out.println("Singularities: " + sings.size());
        System.out.println("  sum(index4) = " + sumIndex4
                + "  expected 4*chi = " + (4 * chi)
                + "  chi(M) = " + chi);
        if (sings.size() <= 64) {
            for (Singularity s : sings) {
                System.out.println("  v=" + s.vertexId() + "  index=" + s.index4() + "/4");
            }
        } else {
            // Histogram by index4
            int[] counts = new int[9]; // -4 .. +4
            for (Singularity s : sings) {
                int idx = s.index4() + 4;
                if (idx >= 0 && idx < counts.length) counts[idx]++;
            }
            System.out.println("  histogram (index4 -> count):");
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] != 0) System.out.println("    " + (i - 4) + "/4  -> " + counts[i]);
            }
        }

        long t3 = System.currentTimeMillis();
        CombedField combed = CombedField.comb(xfield);
        long tComb = System.currentTimeMillis() - t3;
        System.out.println("Combed in " + tComb + " ms"
                + "  seam edges = " + combed.seamEdgeCount()
                + " / " + xfield.interiorEdgeCount() + " interior");
    }

    private static int computeEuler(ArrayMesh mesh) {
        return mesh.vertexCount() - mesh.edgeCount() + mesh.faceCount();
    }
}
