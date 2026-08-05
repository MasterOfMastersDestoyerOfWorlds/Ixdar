package ixdar.geometry.mesh.quadlayout.embedding;

import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * Copy faces whose image in their source face's chart is too thin for the optimizer to
 * divide by, classified by where their corners were minted.
 *
 * <p>
 * See also: LCBK19 Section 6.1
 */
public final class CopyMeshNeedles {

    /**
     * Fraction of its source face's chart area a copy face must cover for the
     * differences taken across it to survive double precision. This is the same floor
     * {@code GridMapOptimizer} divides by, so a face under it cannot reach Stage 5.
     */
    public static final double MINIMUM_CHART_FRACTION = 1.0e-9;

    /** Corners of a triangle. */
    private static final int CORNERS = 3;

    /** Corner {@code (u, v)} pairs one source face's chart is read into. */
    private static final int CORNER_UV_SIZE = 6;

    public final EmbeddedMeshTopology topology;

    /** The parametrization every face's image is measured in. */
    public final SeamlessParameterization seamless;

    /** Faces whose chart image has no usable area. */
    public int needleCount;

    /** Needles all of whose corners are original mesh vertices. */
    public int allOriginalCount;

    /** Needles holding at least one T-mesh node vertex. */
    public int withNodeCount;

    /** Needles holding at least one arc-path vertex but no node. */
    public int withArcCount;

    /** Needles whose minted corners belong to no node and no arc. */
    public int withUnownedCount;

    /** Smallest chart area fraction any face reaches, for the report. */
    public double smallestFraction = 1.0;

    /** The first needle found, described for the report. */
    public String firstNeedle;

    private final double[] cornerUv = new double[CORNER_UV_SIZE];

    /**
     * Stores the working copy to measure and the parametrization to measure it in.
     *
     * @param topology working copy carrying provenance and claims
     * @param seamless the parametrization each face's image is measured in
     */
    public CopyMeshNeedles(EmbeddedMeshTopology topology, SeamlessParameterization seamless) {
        this.topology = topology;
        this.seamless = seamless;
    }

    /**
     * Counts every needle and classifies it by the provenance of its corners.
     *
     * @return this, measured
     */
    public CopyMeshNeedles build() {
        for (int faceIndex = 0; faceIndex < topology.copy.faceCount(); faceIndex++) {
            int copyFace = topology.copy.faceIdAt(faceIndex);
            int sourceFace = topology.sourceFaceByCopyFace[copyFace];
            if (sourceFace == EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            double chart = Math.abs(chartArea(sourceFace));
            double image = Math.abs(imageArea(sourceFace, copyFace));
            double fraction = chart == 0.0 ? 0.0 : image / chart;
            smallestFraction = Math.min(smallestFraction, fraction);
            if (!(fraction <= MINIMUM_CHART_FRACTION)) {
                continue;
            }
            needleCount++;
            classify(copyFace);
            if (firstNeedle == null) {
                firstNeedle = describe(copyFace, sourceFace, fraction);
            }
        }
        return this;
    }

    /**
     * Buckets one needle by the strongest provenance among its three corners: a node
     * outranks an arc, and an arc outranks an unowned minted vertex.
     *
     * @param copyFace needle face to classify
     */
    private void classify(int copyFace) {
        boolean anyMinted = false;
        boolean anyNode = false;
        boolean anyArc = false;
        for (int corner = 0; corner < CORNERS; corner++) {
            int vertexId = topology.copy.faceVertexAt(copyFace, corner);
            anyMinted |= vertexId >= topology.originalVertexBound;
            anyNode |= topology.ownerNodeByCopyVertex[vertexId] != EmbeddedMeshTopology.UNCLAIMED;
            anyArc |= topology.ownerArcByCopyVertex[vertexId] != EmbeddedMeshTopology.UNCLAIMED;
        }
        if (!anyMinted) {
            allOriginalCount++;
        } else if (anyNode) {
            withNodeCount++;
        } else if (anyArc) {
            withArcCount++;
        } else {
            withUnownedCount++;
        }
    }

    /**
     * Reports the needle count and where the needles' corners came from.
     *
     * @param label pipeline stage the measurement was taken at
     */
    public void report(String label) {
        System.out.printf("[needles] %s: %d needles of %d faces (allOriginal=%d node=%d arc=%d"
                + " unowned=%d) smallestFraction=%.3e%n", label, needleCount,
                topology.copy.faceCount(), allOriginalCount, withNodeCount, withArcCount,
                withUnownedCount, smallestFraction);
        if (firstNeedle != null) {
            System.out.println("[needles]   first: " + firstNeedle);
        }
    }

    /**
     * Describes one needle's corners and how thin it is, for the report.
     *
     * @param copyFace   needle face
     * @param sourceFace face whose chart it was measured in
     * @param fraction   its share of that chart's area
     * @return the description
     */
    private String describe(int copyFace, int sourceFace, double fraction) {
        StringBuilder description = new StringBuilder("copy face ").append(copyFace)
                .append(" in source face ").append(sourceFace)
                .append(" covers ").append(String.format("%.3e", fraction))
                .append(" of its chart; corners");
        for (int corner = 0; corner < CORNERS; corner++) {
            int vertexId = topology.copy.faceVertexAt(copyFace, corner);
            description.append(' ').append(vertexId)
                    .append(vertexId < topology.originalVertexBound ? "|original" : "|minted");
            if (topology.ownerNodeByCopyVertex[vertexId] != EmbeddedMeshTopology.UNCLAIMED) {
                description.append("|node").append(topology.ownerNodeByCopyVertex[vertexId]);
            }
            if (topology.ownerArcByCopyVertex[vertexId] != EmbeddedMeshTopology.UNCLAIMED) {
                description.append("|arc").append(topology.ownerArcByCopyVertex[vertexId]);
            }
        }
        return description.toString();
    }

    /**
     * Twice the signed area of one source face's own chart triangle.
     *
     * @param sourceFace source face to measure
     * @return twice its signed chart area
     */
    private double chartArea(int sourceFace) {
        seamless.faceCornerUv(sourceFace, cornerUv);
        return (cornerUv[2] - cornerUv[0]) * (cornerUv[5] - cornerUv[1])
                - (cornerUv[4] - cornerUv[0]) * (cornerUv[3] - cornerUv[1]);
    }

    /**
     * Twice the signed area of a copy face's image in its chart, taken from barycentric
     * differences so a thin triangle survives the subtraction.
     *
     * @param sourceFace chart the corners are read in
     * @param copyFace   copy face to measure
     * @return twice the signed image area, or {@code 0} when a corner is unregistered
     */
    private double imageArea(int sourceFace, int copyFace) {
        double[] at = topology.barycentricOf(sourceFace, topology.copy.faceVertexAt(copyFace, 0));
        double[] to = topology.barycentricOf(sourceFace, topology.copy.faceVertexAt(copyFace, 1));
        double[] across =
                topology.barycentricOf(sourceFace, topology.copy.faceVertexAt(copyFace, 2));
        if (at == null || to == null || across == null) {
            return 0.0;
        }
        seamless.faceCornerUv(sourceFace, cornerUv);
        double firstU = 0.0;
        double firstV = 0.0;
        double secondU = 0.0;
        double secondV = 0.0;
        for (int corner = 0; corner < CORNERS; corner++) {
            double alongFirst = to[corner] - at[corner];
            double alongSecond = across[corner] - at[corner];
            firstU += alongFirst * cornerUv[corner * 2];
            firstV += alongFirst * cornerUv[corner * 2 + 1];
            secondU += alongSecond * cornerUv[corner * 2];
            secondV += alongSecond * cornerUv[corner * 2 + 1];
        }
        return firstU * secondV - secondU * firstV;
    }
}
