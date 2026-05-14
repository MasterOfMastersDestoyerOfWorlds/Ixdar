package ixdar.geometry.mesh.quadlayout.seamless;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;

/**
 * Stand-alone driver: load a triangle mesh, run the LCK21 stages 1–3 (cross
 * field + singularities + BZK09 §5 seamless parametrization), and print a
 * verification report. Used to inspect the output on real models like the
 * rockerarm without the JUnit harness's timeouts.
 *
 * <p>Usage:
 * <pre>
 *   mvn -pl ixdar-app -am compile
 *   mvn -pl ixdar-app exec:java \
 *       -Dexec.mainClass=ixdar.geometry.mesh.quadlayout.SeamlessParameterizationCLI \
 *       -Dexec.args="test/resources/quadlayout/figure_9/rockerarm_in_tri.off out/rockerarm_uv.tsv"
 * </pre>
 *
 * <p>The optional second argument writes a TSV with one row per corner
 * ({@code faceActiveIdx, cornerIdx, vertexId, u, v}) so the result can be
 * visualised externally.
 */
public final class SeamlessParameterizationCLI {

    private static final double NS_PER_SEC = 1.0e9;
    private static final double PERCENT = 100.0;
    private static final String TAB = "\t";
    private static final int CORNERS_PER_FACE = SeamlessParameterization.CORNERS_PER_FACE;

    private SeamlessParameterizationCLI() {
    }

    /**
     * Entry point.
     *
     * @param args mesh path, optional output TSV path
     * @throws IOException on filesystem failure
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: SeamlessParameterizationCLI <mesh.off> [out_uv.tsv]");
            System.exit(1);
            return;
        }
        String meshPath = args[0];
        Path outTsv = args.length >= 2 ? Path.of(args[1]) : null;

        System.out.println("[seam-cli] loading: " + meshPath);
        ArrayMesh arrayMesh = MeshLoader.load(meshPath);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
        System.out.printf("[seam-cli] V=%d E=%d F=%d%n",
                mesh.vertexCount(), mesh.edgeCount(), mesh.faceCount());

        long tCross0 = System.nanoTime();
        CrossField crossField = new CrossField(mesh).build();
        long tCross1 = System.nanoTime();
        System.out.printf("[seam-cli] cross field: %.2fs  singularities=%d%n",
                (tCross1 - tCross0) / NS_PER_SEC, crossField.singularities.size());

        long tSeam0 = System.nanoTime();
        SeamlessParameterization seamless = new SeamlessParameterization(crossField);
        seamless.build();
        long tSeam1 = System.nanoTime();
        System.out.printf("[seam-cli] seamless: %.2fs  injective=%b stiffeningIters=%d%n",
                (tSeam1 - tSeam0) / NS_PER_SEC, seamless.injective, seamless.stiffeningIterations);

        printCutAndChartStats(seamless, mesh);
        printSingularityCheck(seamless, crossField, mesh);
        printTransitionResiduals(seamless, mesh);
        printInjectivityStats(seamless, mesh);

        if (outTsv != null) {
            writeUvTsv(outTsv, seamless, mesh);
            System.out.printf("[seam-cli] wrote per-corner UV TSV: %s%n", outTsv.toAbsolutePath());
        }
    }

    private static void printCutAndChartStats(SeamlessParameterization seamless, HalfEdgeMesh mesh) {
        int totalCut = 0;
        int boundaryCut = 0;
        for (int ae = 0; ae < mesh.edgeCount(); ae++) {
            if (!seamless.cutGraph.isCutEdge[ae]) continue;
            totalCut++;
            if (mesh.isBoundaryEdge(mesh.edgeIdAt(ae))) boundaryCut++;
        }
        System.out.printf("[seam-cli] cuts: %d total (%d boundary, %d interior)%n",
                totalCut, boundaryCut, totalCut - boundaryCut);
    }

    private static void printSingularityCheck(SeamlessParameterization seamless, CrossField crossField, HalfEdgeMesh mesh) {
        int onCut = 0;
        int offCut = 0;
        int minCutDeg = Integer.MAX_VALUE;
        int maxCutDeg = 0;
        for (Singularity s : crossField.singularities) {
            int sVid = s.vertexId();
            int incident = mesh.vertexEdgeCount(sVid);
            int cutDeg = 0;
            for (int i = 0; i < incident; i++) {
                int eId = mesh.vertexEdgeAt(sVid, i);
                int ae = crossField.edgeIdToActive.get(eId);
                if (seamless.cutGraph.isCutEdge[ae] && !mesh.isBoundaryEdge(eId)) cutDeg++;
            }
            if (cutDeg > 0) onCut++; else offCut++;
            if (cutDeg < minCutDeg) minCutDeg = cutDeg;
            if (cutDeg > maxCutDeg) maxCutDeg = cutDeg;
        }
        System.out.printf("[seam-cli] singularity placement: onCut=%d offCut=%d cutDeg∈[%d, %d]%n",
                onCut, offCut, minCutDeg == Integer.MAX_VALUE ? 0 : minCutDeg, maxCutDeg);
    }

    private static void printTransitionResiduals(SeamlessParameterization seamless, HalfEdgeMesh mesh) {
        int n = 0;
        double sum = 0.0;
        double max = 0.0;
        double sumSq = 0.0;
        for (int ae = 0; ae < mesh.edgeCount(); ae++) {
            if (!seamless.cutGraph.isCutEdge[ae]) continue;
            int eId = mesh.edgeIdAt(ae);
            if (mesh.isBoundaryEdge(eId)) continue;
            int hCanon = mesh.edgeHalfEdge(eId);
            int twin = mesh.halfEdgeTwin(hCanon);
            int faceA = mesh.halfEdgeFace(hCanon);
            int faceB = mesh.halfEdgeFace(twin);
            int vStart = mesh.halfEdgeVertex(hCanon);
            int vEnd = mesh.halfEdgeEndVertex(hCanon);
            float[] coordsA = lookupCorners(seamless, mesh, faceA, vStart, vEnd);
            float[] coordsB = lookupCorners(seamless, mesh, faceB, vStart, vEnd);
            int r = seamless.cutGraph.cutRotation[ae];
            double cr = Math.cos(r * Math.PI / 2.0);
            double sr = Math.sin(r * Math.PI / 2.0);
            double s = seamless.cutTranslationS[ae];
            double t = seamless.cutTranslationT[ae];
            for (int end = 0; end < 2; end++) {
                double uA = coordsA[end * 2];
                double vA = coordsA[end * 2 + 1];
                double uB = coordsB[end * 2];
                double vB = coordsB[end * 2 + 1];
                double resU = (cr * uA - sr * vA + s) - uB;
                double resV = (sr * uA + cr * vA + t) - vB;
                double r2 = resU * resU + resV * resV;
                double rN = Math.sqrt(r2);
                sum += rN;
                sumSq += r2;
                if (rN > max) max = rN;
                n++;
            }
        }
        if (n > 0) {
            System.out.printf("[seam-cli] transition residual: max=%.4e mean=%.4e rms=%.4e (over %d cut endpoints)%n",
                    max, sum / n, Math.sqrt(sumSq / n), n);
        } else {
            System.out.println("[seam-cli] no interior cut edges");
        }
    }

    private static void printInjectivityStats(SeamlessParameterization seamless, HalfEdgeMesh mesh) {
        int F = mesh.faceCount();
        int flipped = 0;
        double minSa = Double.POSITIVE_INFINITY;
        double maxSa = Double.NEGATIVE_INFINITY;
        double sum = 0.0;
        for (int af = 0; af < F; af++) {
            int faceId = mesh.faceIdAt(af);
            float sa = seamless.uvSignedArea(faceId);
            if (sa <= 0.0f) flipped++;
            if (sa < minSa) minSa = sa;
            if (sa > maxSa) maxSa = sa;
            sum += sa;
        }
        System.out.printf("[seam-cli] uv signed area: flipped=%d/%d (%.2f%%)  min=%.4e max=%.4e mean=%.4e%n",
                flipped, F, PERCENT * flipped / F, minSa, maxSa, sum / Math.max(1, F));
    }

    private static float[] lookupCorners(SeamlessParameterization seamless, HalfEdgeMesh mesh,
                                          int faceId, int vStart, int vEnd) {
        int cStart = -1, cEnd = -1;
        for (int c = 0; c < CORNERS_PER_FACE; c++) {
            int v = mesh.faceVertexAt(faceId, c);
            if (v == vStart) cStart = c;
            else if (v == vEnd) cEnd = c;
        }
        return new float[] {
                seamless.u(faceId, cStart), seamless.v(faceId, cStart),
                seamless.u(faceId, cEnd),   seamless.v(faceId, cEnd),
        };
    }

    private static void writeUvTsv(Path out, SeamlessParameterization seamless, HalfEdgeMesh mesh) throws IOException {
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            w.write("faceActiveIdx" + TAB + "cornerIdx" + TAB + "vertexId" + TAB + "u" + TAB + "v\n");
            for (int af = 0; af < mesh.faceCount(); af++) {
                int faceId = mesh.faceIdAt(af);
                for (int c = 0; c < CORNERS_PER_FACE; c++) {
                    int vId = mesh.faceVertexAt(faceId, c);
                    w.write(af + TAB + c + TAB + vId + TAB
                            + seamless.u(faceId, c) + TAB + seamless.v(faceId, c) + "\n");
                }
            }
        }
    }
}
