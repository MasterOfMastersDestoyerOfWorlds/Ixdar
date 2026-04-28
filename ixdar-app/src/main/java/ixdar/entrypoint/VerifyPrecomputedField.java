package ixdar.entrypoint;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import ixdar.geometry.mesh.quadlayout.field.PrecomputedFieldImporter;
import ixdar.geometry.mesh.quadlayout.integergrid.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.quantization.Quantization;
import ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;
import ixdar.geometry.mesh.quadlayout.tmesh.TNode;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

/**
 * PATCH-51 verifier — bootstraps a {@link PrecomputedFieldImporter.Result} from
 * metriko's stage1 baseline, then runs the downstream
 * PATCH-40 ({@link SeamlessParameterization}) and PATCH-41
 * ({@link MotorcycleGraph} + {@link TMesh}) stages on top of it. Prints the
 * per-stage counts so we can see whether the downstream pipeline is correct
 * conditional on a clean upstream cross field.
 *
 * <pre>
 *   mvn -pl ixdar-app exec:java -Dexec.mainClass=ixdar.entrypoint.VerifyPrecomputedField \
 *     -Dexec.args="/path/to/baseline-hand"
 *
 * default baseline dir = ixdar-app/test/resources/quadlayout/baseline-hand
 * </pre>
 */
public final class VerifyPrecomputedField {

    private VerifyPrecomputedField() {}

    public static void main(String[] args) throws Exception {
        Path baselineDir = args.length > 0
                ? Paths.get(args[0])
                : Paths.get("ixdar-app/test/resources/quadlayout/baseline-hand");

        Path obj = baselineDir.resolve("Hand-tri-30k.obj");
        Path extr = baselineDir.resolve("stage1_extrinsic_field.tsv");
        Path matching = baselineDir.resolve("stage1_matching.txt");
        Path seam = baselineDir.resolve("stage1_seam.txt");
        Path singular = baselineDir.resolve("stage1_singular.txt");

        long t0 = System.currentTimeMillis();
        PrecomputedFieldImporter.Result boot = PrecomputedFieldImporter.load(obj, extr, matching, seam, singular);
        long tBoot = System.currentTimeMillis() - t0;

        List<Singularity> singularities = boot.singularities();
        System.out.println("[verify-metriko] " + obj);
        System.out.println("  faces             = " + boot.mesh().faceCount());
        System.out.println("  verts             = " + boot.mesh().vertexCount());
        System.out.println("  interior edges    = " + boot.field().interiorEdgeCount());
        System.out.println("  singularities     = " + singularities.size());
        int sumIdx4 = singularities.stream().mapToInt(Singularity::index4).sum();
        System.out.println("  sum(index4)       = " + sumIdx4 + "  (expect 4*chi)");
        System.out.println("  seam edges        = " + boot.combed().seamEdgeCount());
        System.out.println("  bootstrap        : " + tBoot + " ms");

        SeamlessParameterization param;
        try {
            long t1 = System.currentTimeMillis();
            // Cap iterative rounding to keep CLI runtime bounded — each pin
            // attempt is a full IGM re-solve (~1s after PATCH-54). 8 pins is
            // plenty to expose downstream pipeline issues without burning
            // hours.
            param = new SeamlessParameterization(
                    boot.mesh(), boot.field(), boot.combed(), singularities,
                    /*maxRoundingIter=*/8);
            long tParam = System.currentTimeMillis() - t1;

            int injective = 0;
            int F = boot.mesh().faceCount();
            for (int f = 0; f < F; f++) if (param.uvSignedArea(f) > 0) injective++;
            System.out.println("  IGM iterations    = " + param.iterationCount());
            System.out.println("  injective faces   = " + injective + " / " + F);
            System.out.println("  IGM globally inj  = " + param.injectiveOnAllTriangles());
            System.out.println("  IGM time          = " + tParam + " ms");
        } catch (IndexOutOfBoundsException scaleOverflow) {
            // Known PATCH-40 issue: ojAlgo's sparse store uses 32-bit element
            // indices; for N = 9 * faceCount (= 269640 here) the row*col
            // product overflows during baseH iteration. Surface as a clean
            // diagnostic rather than a stack trace so the bottom-line is
            // unambiguous.
            System.out.println("  IGM               = BLOCKED (ojAlgo 32-bit overflow"
                    + " at N=" + (boot.mesh().faceCount() * 9) + ")");
            System.out.println("  IGM detail        = " + scaleOverflow.getMessage());
            System.out.println("  T-mesh patches    = 0  (downstream skipped)");
            return;
        }

        long t2 = System.currentTimeMillis();
        MotorcycleGraph.Result graph = MotorcycleGraph.trace(
                param, boot.mesh(), boot.field(), boot.combed(), singularities);
        long tGraph = System.currentTimeMillis() - t2;

        long t3 = System.currentTimeMillis();
        TMesh tmesh = TMesh.build(graph, param);
        long tMesh = System.currentTimeMillis() - t3;

        System.out.println("  motorcycle traces = " + graph.traces().size()
                + "  (" + tGraph + " ms)");
        System.out.println("  T-mesh nodes      = " + graph.nodes().size()
                + "  (sing="
                + countNodes(graph.nodes(), TNode.NodeKind.SINGULARITY)
                + " inter="
                + countNodes(graph.nodes(), TNode.NodeKind.INTERSECTION)
                + " bound="
                + countNodes(graph.nodes(), TNode.NodeKind.BOUNDARY) + ")");
        System.out.println("  T-mesh arcs       = " + tmesh.arcs().size());
        System.out.println("  T-mesh patches    = " + tmesh.patches().size()
                + "  (" + tMesh + " ms)");

        // PATCH-42: solve quantization ILP on top of the T-mesh.
        if (!tmesh.arcs().isEmpty()) {
            long t4 = System.currentTimeMillis();
            Quantization.Result quant = Quantization.solve(tmesh);
            long tQuant = System.currentTimeMillis() - t4;

            int totalQuads = 0;
            int minQ = Integer.MAX_VALUE;
            int maxQ = Integer.MIN_VALUE;
            for (int q : quant.arcQuantization()) {
                totalQuads += q;
                if (q < minQ) minQ = q;
                if (q > maxQ) maxQ = q;
            }
            System.out.println("  quantization      = "
                    + (quant.feasible() ? "feasible" : "INFEASIBLE")
                    + "  (" + tQuant + " ms)");
            System.out.println("  quant Σ q_i       = " + totalQuads
                    + "  (min=" + (minQ == Integer.MAX_VALUE ? 0 : minQ)
                    + " max=" + (maxQ == Integer.MIN_VALUE ? 0 : maxQ)
                    + ")");
            System.out.println("  quant deviation   = "
                    + String.format("%.4f", quant.objectiveValue())
                    + "  (Σ |q_i - r_i|)");
            boolean consistent = Quantization.verifyConsistency(
                    tmesh, quant.arcQuantization());
            System.out.println("  quant consistent  = " + consistent
                    + "  (independent self-check: q≥1 + opposite-side equality)");
        }
    }

    private static int countNodes(List<TNode> nodes, TNode.NodeKind kind) {
        int c = 0;
        for (TNode n : nodes) if (n.kind() == kind) c++;
        return c;
    }
}
