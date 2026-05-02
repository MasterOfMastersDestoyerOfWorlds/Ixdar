package ixdar.entrypoint;

import java.io.IOException;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.quadlayout.vectorfield.alignment.GeodesicCurvature;
import ixdar.geometry.mesh.quadlayout.vectorfield.alignment.PrincipalCurvatureField;
import ixdar.geometry.mesh.quadlayout.vectorfield.alignment.SmoothRegions;

/**
 * Standalone CIE*16 diagnostic: load a mesh, run each stage of the
 * directional-alignment chain, dump statistics.
 *
 * <p>Used to identify why our rocker-arm CIE*16 result over-constrains
 * (1335 sings vs paper's 36).
 *
 * <p>Usage:
 * <pre>
 *   mvn -pl ixdar-app exec:java -Dexec.mainClass=ixdar.entrypoint.Cie16Diagnose \
 *     [-Dixdar.cie16.diag.mesh=path/to/mesh.obj]
 *     [-Dixdar.cie16.geoRadiusFraction=0.01]
 *     [-Dixdar.cie16.significance=70]
 * </pre>
 */
public final class Cie16Diagnose {

    public static void main(String[] args) throws IOException {
        String obj = System.getProperty(
                "ixdar.cie16.diag.mesh",
                "ixdar-app/test/resources/quadlayout/baseline-rocker-arm/rocker-arm.obj");
        ArrayMesh mesh = MeshLoader.load(obj);
        int F = mesh.faceCount();
        int E = mesh.edgeCount();
        double bbox = bboxDiag(mesh);
        double rGeoFrac = Double.parseDouble(System.getProperty("ixdar.cie16.geoRadiusFraction", "0.01"));
        double rGeo = bbox * rGeoFrac;
        double sigDeg = Double.parseDouble(System.getProperty("ixdar.cie16.significance", "70"));

        System.out.printf("[diag] mesh: F=%d  V=%d  E=%d  bbox=%.4f  rGeo=%.6f  significance=%.1f°%n",
                F, mesh.vertexCount(), E, bbox, rGeo, sigDeg);

        long t0 = System.currentTimeMillis();
        PrincipalCurvatureField pdf = PrincipalCurvatureField.compute(mesh, rGeo);
        System.out.printf("[diag] principal-curvature compute: %dms%n", System.currentTimeMillis() - t0);

        // Histograms of κ_max, anisotropy τ.
        int[] kMaxBuckets = new int[7];
        int[] tauBuckets = new int[10];
        int nFlat = 0;
        for (int f = 0; f < F; f++) {
            double kMax = Math.abs(pdf.kappaMax(f));
            double kMin = Math.abs(pdf.kappaMin(f));
            kMaxBuckets[bucketLog10(kMax)]++;
            double denom = Math.max(kMax, kMin);
            double tau = denom > 0 ? (kMax - kMin) / denom : 0.0;
            tauBuckets[Math.min(9, (int)(tau * 10))]++;
            if (denom < 1e-6 * (1.0 / bbox)) nFlat++;
        }
        System.out.printf("[diag] |κ_max| buckets (log10: <-6, [-6,-4], [-4,-2], [-2,0], [0,2], [2,4], >4):  %s%n",
                java.util.Arrays.toString(kMaxBuckets));
        System.out.printf("[diag] anisotropy τ buckets (0.0..1.0 in 0.1 increments):  %s%n",
                java.util.Arrays.toString(tauBuckets));
        System.out.printf("[diag] near-flat faces (max(|κ_min|,|κ_max|) < 1e-6/bbox): %d / %d%n", nFlat, F);

        // Geodesic curvature.
        long t1 = System.currentTimeMillis();
        double[] kappaG = GeodesicCurvature.computePerEdge(mesh, pdf);
        boolean[] smooth = GeodesicCurvature.computeSmoothFaces(mesh, kappaG, pdf);
        int nSmooth = 0;
        for (boolean s : smooth) if (s) nSmooth++;
        System.out.printf("[diag] geodesic-curvature compute + smooth predicate: %dms,  smooth faces: %d / %d (%.1f%%)%n",
                System.currentTimeMillis() - t1, nSmooth, F, 100.0 * nSmooth / F);

        // Edge κ^g distribution.
        int[] kgBuckets = new int[7];
        int interiorEdges = 0;
        for (int e = 0; e < E; e++) {
            if (mesh.isBoundaryEdge(e)) continue;
            interiorEdges++;
            kgBuckets[bucketLog10(kappaG[e])]++;
        }
        System.out.printf("[diag] interior-edge κ^g buckets (log10): %s  (total interior edges: %d)%n",
                java.util.Arrays.toString(kgBuckets), interiorEdges);

        // Smooth regions before significance filter — get the *raw* connected
        // components by passing significance=0.
        int[] regionAll = SmoothRegions.detect(mesh, smooth, pdf, 0.0);
        int rawRegions = countDistinct(regionAll);
        System.out.printf("[diag] raw connected smooth regions (no significance filter): %d%n", rawRegions);

        // Region size histogram.
        int[] sizeByRegion = new int[rawRegions];
        for (int f = 0; f < F; f++) {
            if (regionAll[f] >= 0) sizeByRegion[regionAll[f]]++;
        }
        int[] sizeBuckets = new int[7]; // 1, 2-3, 4-15, 16-63, 64-255, 256-1023, 1024+
        for (int s : sizeByRegion) {
            if (s == 1) sizeBuckets[0]++;
            else if (s <= 3) sizeBuckets[1]++;
            else if (s <= 15) sizeBuckets[2]++;
            else if (s <= 63) sizeBuckets[3]++;
            else if (s <= 255) sizeBuckets[4]++;
            else if (s <= 1023) sizeBuckets[5]++;
            else sizeBuckets[6]++;
        }
        System.out.printf("[diag] region sizes (1, 2-3, 4-15, 16-63, 64-255, 256-1023, 1024+): %s%n",
                java.util.Arrays.toString(sizeBuckets));

        // Per-region ∠F: get them by re-running with significance=0 and then
        //   probing with progressively higher thresholds.
        // Easiest: bisect on threshold per region to get exact ∠F. Skip for
        //   speed — instead, sweep multiple thresholds and report kept counts.
        for (double th : new double[]{0, 30, 70, 90, 120, 180, 270, 359, 360}) {
            int[] regs = SmoothRegions.detect(mesh, smooth, pdf, th);
            int kept = countDistinct(regs);
            int facesC = 0;
            for (int f = 0; f < F; f++) if (regs[f] >= 0) facesC++;
            System.out.printf("[diag] threshold %.1f°: kept %d regions, constrained %d faces (%.1f%%)%n",
                    th, kept, facesC, 100.0 * facesC / F);
        }

        // Now apply the requested significance filter.
        int[] regionKept = SmoothRegions.detect(mesh, smooth, pdf, sigDeg);
        int keptRegions = countDistinct(regionKept);
        int constrainedFaces = 0;
        for (int f = 0; f < F; f++) if (regionKept[f] >= 0) constrainedFaces++;
        System.out.printf("[diag] regions kept after %.1f° filter: %d  /  faces constrained: %d / %d (%.1f%%)%n",
                sigDeg, keptRegions, constrainedFaces, F, 100.0 * constrainedFaces / F);

        // Distribution of κ_max in constrained faces.
        int[] kMaxCons = new int[7];
        for (int f = 0; f < F; f++) {
            if (regionKept[f] >= 0) kMaxCons[bucketLog10(Math.abs(pdf.kappaMax(f)))]++;
        }
        System.out.printf("[diag] |κ_max| of CONSTRAINED faces: %s%n", java.util.Arrays.toString(kMaxCons));

        System.out.printf("[diag] DONE.  total: %dms%n", System.currentTimeMillis() - t0);
    }

    private static int bucketLog10(double v) {
        if (v <= 0 || !Double.isFinite(v)) return 0;
        double l = Math.log10(v);
        if (l < -6) return 0;
        if (l < -4) return 1;
        if (l < -2) return 2;
        if (l < 0) return 3;
        if (l < 2) return 4;
        if (l < 4) return 5;
        return 6;
    }

    private static int countDistinct(int[] arr) {
        int max = -1;
        for (int x : arr) if (x > max) max = x;
        return max + 1;
    }

    private static double bboxDiag(ArrayMesh mesh) {
        Vector3f p = new Vector3f();
        mesh.vertexPosition(0, p);
        float minX = p.x, minY = p.y, minZ = p.z, maxX = p.x, maxY = p.y, maxZ = p.z;
        for (int v = 1; v < mesh.vertexCount(); v++) {
            mesh.vertexPosition(v, p);
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x;
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y;
            if (p.z < minZ) minZ = p.z; if (p.z > maxZ) maxZ = p.z;
        }
        double dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
