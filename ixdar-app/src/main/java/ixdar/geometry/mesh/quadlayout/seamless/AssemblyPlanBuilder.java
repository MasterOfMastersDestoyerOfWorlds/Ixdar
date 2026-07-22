package ixdar.geometry.mesh.quadlayout.seamless;

import java.util.Arrays;

import ixdar.geometry.mesh.quadlayout.solver.NormalMatrix;

/**
 * Primitive-array accumulator for {@link SeamlessDofSystem}'s cached assembly
 * playback plan.
 *
 * <p>
 * Call {@code beginFace()}, then {@code addOuterProduct} / {@code addRhsExpansion},
 * then {@code endFace(face)} for each face in order, then {@code finish(upperScale)}
 * once. Results are read from the public plan arrays.
 */
public final class AssemblyPlanBuilder {

    /**
     * Diagonal regularization added to DOF 0 in soft-seam mode to break the 1D
     * translation nullspace so cold sparse Cholesky succeeds.
     */
    static final double NULLSPACE_ANCHOR_WEIGHT = 1.0;

    /** Corners per triangular face. */
    private static final int CORNERS_PER_FACE = 3;

    /** Halve factor for the upper-triangle double-count correction. */
    private static final double UPPER_HALVE_FACTOR = 0.5;

    /** Sorted unique packed (row, col) upper-triangle keys; set by finish. */
    public long[] planUpperKeys;
    /** Per-face upper-entry CSR starts, length faceCount + 1. */
    public final int[] perFaceUpperStart;
    /** Per-entry slot into {@link #planUpperKeys}; set by finish. */
    public int[] perFaceUpperSlot;
    /** Per-entry upper coefficient (scaled by finish's {@code upperScale}). */
    public double[] perFaceUpperCoef;
    /** Per-face diagonal-entry CSR starts, length faceCount + 1. */
    public final int[] perFaceDiagonalStart;
    /** Flat diagonal DOF indices; trimmed by finish. */
    public int[] perFaceDiagonalDof;
    /** Coefficients matching {@link #perFaceDiagonalDof}. */
    public double[] perFaceDiagonalCoef;
    /** Per-face RHS-entry CSR starts, length faceCount + 1. */
    public final int[] perFaceRhsStart;
    /** Flat RHS DOF indices; trimmed by finish. */
    public int[] perFaceRhsDof;
    /** Coefficients matching {@link #perFaceRhsDof}. */
    public double[] perFaceRhsCoef;
    /** Gauge-pin static diagonal contributions, length dofCount; set by finish. */
    public double[] planStaticDiagonal;
    /** Gauge-pin static upper contributions, indexed by {@link #planUpperKeys} slot. */
    public double[] planStaticUpperValues;

    private final int faceCount;
    private long[] upperKeysFlat;
    private int upperCursor;
    private int diagonalCursor;
    private int rhsCursor;

    private long[] faceUpperKeys;
    private double[] faceUpperVals;
    private int faceUpperCount;
    private int[] faceDiagonalDofs;
    private double[] faceDiagonalVals;
    private int faceDiagonalCount;
    private int[] faceRhsDofs;
    private double[] faceRhsVals;
    private int faceRhsCount;

    /**
     * Size the CSR start arrays and the initial flat/scratch buffers.
     *
     * @param faceCount number of faces that will be accumulated, in order
     */
    public AssemblyPlanBuilder(int faceCount) {
        final int initialFlatCapacity = Math.max(16, faceCount * 4);
        final int initialScratchCapacity = 32;
        this.faceCount = faceCount;
        this.perFaceUpperStart = new int[faceCount + 1];
        this.perFaceDiagonalStart = new int[faceCount + 1];
        this.perFaceRhsStart = new int[faceCount + 1];
        this.upperKeysFlat = new long[initialFlatCapacity];
        this.perFaceUpperCoef = new double[initialFlatCapacity];
        this.perFaceDiagonalDof = new int[initialFlatCapacity];
        this.perFaceDiagonalCoef = new double[initialFlatCapacity];
        this.perFaceRhsDof = new int[initialFlatCapacity];
        this.perFaceRhsCoef = new double[initialFlatCapacity];
        this.faceUpperKeys = new long[initialScratchCapacity];
        this.faceUpperVals = new double[initialScratchCapacity];
        this.faceDiagonalDofs = new int[initialScratchCapacity];
        this.faceDiagonalVals = new double[initialScratchCapacity];
        this.faceRhsDofs = new int[initialScratchCapacity];
        this.faceRhsVals = new double[initialScratchCapacity];
    }

    /**
     * Reset the per-face scratch accumulators; call before the face's
     * contributions.
     */
    public void beginFace() {
        faceUpperCount = 0;
        faceDiagonalCount = 0;
        faceRhsCount = 0;
    }

    /**
     * Accumulate {@code scale · (expansionA ⊗ expansionB)} into the face's diagonal
     * and upper-triangle scratch: equal DOFs land on the diagonal, distinct DOFs on
     * the (min, max) upper key.
     *
     * @param dofsA  final-DOF indices of the first expansion
     * @param coefsA coefficients matching {@code dofsA}
     * @param dofsB  final-DOF indices of the second expansion
     * @param coefsB coefficients matching {@code dofsB}
     * @param scale  outer-product scale factor
     */
    public void addOuterProduct(int[] dofsA, double[] coefsA,
            int[] dofsB, double[] coefsB, double scale) {
        for (int a = 0; a < dofsA.length; a++) {
            for (int b = 0; b < dofsB.length; b++) {
                double value = scale * coefsA[a] * coefsB[b];
                if (value == 0.0) {
                    continue;
                }
                int rowA = dofsA[a];
                int colB = dofsB[b];
                if (rowA == colB) {
                    addDiagonal(rowA, value);
                } else {
                    long key = ((long) Math.min(rowA, colB) << NormalMatrix.KEY_ROW_SHIFT)
                            | Math.max(rowA, colB);
                    addUpper(key, value);
                }
            }
        }
    }

    /**
     * Accumulate {@code constant · expansion} into the face's RHS scratch.
     *
     * @param dofs     final-DOF indices of the expansion
     * @param coefs    coefficients matching {@code dofs}
     * @param constant per-face RHS constant multiplying the expansion
     */
    public void addRhsExpansion(int[] dofs, double[] coefs, double constant) {
        for (int i = 0; i < dofs.length; i++) {
            double value = constant * coefs[i];
            int dof = dofs[i];
            int slot = -1;
            for (int s = 0; s < faceRhsCount; s++) {
                if (faceRhsDofs[s] == dof) {
                    slot = s;
                    break;
                }
            }
            if (slot >= 0) {
                faceRhsVals[slot] += value;
            } else {
                if (faceRhsCount == faceRhsDofs.length) {
                    faceRhsDofs = Arrays.copyOf(faceRhsDofs, faceRhsCount * 2);
                    faceRhsVals = Arrays.copyOf(faceRhsVals, faceRhsCount * 2);
                }
                faceRhsDofs[faceRhsCount] = dof;
                faceRhsVals[faceRhsCount] = value;
                faceRhsCount++;
            }
        }
    }

    /**
     * Append the face's deduplicated scratch entries to the flat plan arrays and
     * record its CSR starts.
     *
     * @param face active-face index; faces must be ended in ascending order
     */
    public void endFace(int face) {
        perFaceUpperStart[face] = upperCursor;
        perFaceDiagonalStart[face] = diagonalCursor;
        perFaceRhsStart[face] = rhsCursor;
        if (upperCursor + faceUpperCount > upperKeysFlat.length) {
            int grown = Math.max(upperKeysFlat.length * 2, upperCursor + faceUpperCount);
            upperKeysFlat = Arrays.copyOf(upperKeysFlat, grown);
            perFaceUpperCoef = Arrays.copyOf(perFaceUpperCoef, grown);
        }
        for (int i = 0; i < faceUpperCount; i++) {
            upperKeysFlat[upperCursor] = faceUpperKeys[i];
            perFaceUpperCoef[upperCursor] = faceUpperVals[i];
            upperCursor++;
        }
        if (diagonalCursor + faceDiagonalCount > perFaceDiagonalDof.length) {
            int grown = Math.max(perFaceDiagonalDof.length * 2, diagonalCursor + faceDiagonalCount);
            perFaceDiagonalDof = Arrays.copyOf(perFaceDiagonalDof, grown);
            perFaceDiagonalCoef = Arrays.copyOf(perFaceDiagonalCoef, grown);
        }
        for (int i = 0; i < faceDiagonalCount; i++) {
            perFaceDiagonalDof[diagonalCursor] = faceDiagonalDofs[i];
            perFaceDiagonalCoef[diagonalCursor] = faceDiagonalVals[i];
            diagonalCursor++;
        }
        if (rhsCursor + faceRhsCount > perFaceRhsDof.length) {
            int grown = Math.max(perFaceRhsDof.length * 2, rhsCursor + faceRhsCount);
            perFaceRhsDof = Arrays.copyOf(perFaceRhsDof, grown);
            perFaceRhsCoef = Arrays.copyOf(perFaceRhsCoef, grown);
        }
        for (int i = 0; i < faceRhsCount; i++) {
            perFaceRhsDof[rhsCursor] = faceRhsDofs[i];
            perFaceRhsCoef[rhsCursor] = faceRhsVals[i];
            rhsCursor++;
        }
    }

    /**
     * Seal the plan: trim the flat arrays, build the sorted unique key array,
     * resolve every upper entry's slot by binary search, and scale the upper
     * coefficients.
     *
     * @param upperScale           factor applied to every upper coefficient (the
     *                             caller's double-count halve correction)
     * @param cutGraph             cut graph queried for the first primary chart
     *                             vertex, which receives the gauge-pin anchor
     * @param chartVertexFinalDofs per chart vertex / component final-DOF
     *                             expansions; the anchor's leading DOFs get the
     *                             static diagonal weight
     * @param dofCount             final-DOF count sizing the static diagonal
     */
    public void finish(double upperScale, CutGraph cutGraph, int[][][] chartVertexFinalDofs, int dofCount) {
        perFaceUpperStart[faceCount] = upperCursor;
        perFaceDiagonalStart[faceCount] = diagonalCursor;
        perFaceRhsStart[faceCount] = rhsCursor;
        upperKeysFlat = Arrays.copyOf(upperKeysFlat, upperCursor);
        perFaceUpperCoef = Arrays.copyOf(perFaceUpperCoef, upperCursor);
        perFaceDiagonalDof = Arrays.copyOf(perFaceDiagonalDof, diagonalCursor);
        perFaceDiagonalCoef = Arrays.copyOf(perFaceDiagonalCoef, diagonalCursor);
        perFaceRhsDof = Arrays.copyOf(perFaceRhsDof, rhsCursor);
        perFaceRhsCoef = Arrays.copyOf(perFaceRhsCoef, rhsCursor);

        long[] sortedKeys = upperKeysFlat.clone();
        Arrays.sort(sortedKeys);
        int uniqueCount = 0;
        for (int i = 0; i < sortedKeys.length; i++) {
            if (i == 0 || sortedKeys[i] != sortedKeys[i - 1]) {
                sortedKeys[uniqueCount++] = sortedKeys[i];
            }
        }
        planUpperKeys = Arrays.copyOf(sortedKeys, uniqueCount);

        perFaceUpperSlot = new int[upperCursor];
        for (int i = 0; i < upperCursor; i++) {
            perFaceUpperSlot[i] = Arrays.binarySearch(planUpperKeys, upperKeysFlat[i]);
            perFaceUpperCoef[i] *= upperScale;
        }
        planStaticDiagonal = new double[dofCount];
        planStaticUpperValues = new double[planUpperKeys.length];
        for (int cv = 0; cv < cutGraph.chartVertexCount; cv++) {
            if (cutGraph.chartVertexIsPrimary[cv]) {
                planStaticDiagonal[chartVertexFinalDofs[cv][0][0]] += NULLSPACE_ANCHOR_WEIGHT;
                planStaticDiagonal[chartVertexFinalDofs[cv][1][0]] += NULLSPACE_ANCHOR_WEIGHT;
                break;
            }
        }
    }

    /**
     * Accumulate one diagonal contribution into the face scratch, deduplicating by
     * linear scan.
     *
     * @param dof   diagonal DOF index
     * @param value contribution to add
     */
    private void addDiagonal(int dof, double value) {
        for (int s = 0; s < faceDiagonalCount; s++) {
            if (faceDiagonalDofs[s] == dof) {
                faceDiagonalVals[s] += value;
                return;
            }
        }
        if (faceDiagonalCount == faceDiagonalDofs.length) {
            faceDiagonalDofs = Arrays.copyOf(faceDiagonalDofs, faceDiagonalCount * 2);
            faceDiagonalVals = Arrays.copyOf(faceDiagonalVals, faceDiagonalCount * 2);
        }
        faceDiagonalDofs[faceDiagonalCount] = dof;
        faceDiagonalVals[faceDiagonalCount] = value;
        faceDiagonalCount++;
    }

    /**
     * Accumulate one upper-triangle contribution into the face scratch,
     * deduplicating by linear scan.
     *
     * @param key   packed (row, col) upper key
     * @param value contribution to add
     */
    private void addUpper(long key, double value) {
        for (int s = 0; s < faceUpperCount; s++) {
            if (faceUpperKeys[s] == key) {
                faceUpperVals[s] += value;
                return;
            }
        }
        if (faceUpperCount == faceUpperKeys.length) {
            faceUpperKeys = Arrays.copyOf(faceUpperKeys, faceUpperCount * 2);
            faceUpperVals = Arrays.copyOf(faceUpperVals, faceUpperCount * 2);
        }
        faceUpperKeys[faceUpperCount] = key;
        faceUpperVals[faceUpperCount] = value;
        faceUpperCount++;
    }

    /**
     * Build the cached assembly playback log, walking every face once and compacting
     * into the CSR-style flat arrays used for replay. Gauge-pin contributions go
     * into the static arrays because they do not vary with face weight, and the
     * {@link #UPPER_HALVE_FACTOR} correction is baked into the upper coefficients.
     *
     * @param seamless              seamless build providing per-face geometry,
     *                              areas, and gradient targets
     * @param chartVertexFinalDofs  per chart vertex / component final-DOF
     *                              expansion indices
     * @param chartVertexFinalCoefs coefficients matching
     *                              {@code chartVertexFinalDofs}
     * @param cutGraph              cut graph providing corner → chart-vertex
     *                              mapping and the gauge-pin anchor vertex
     * @param dofCount              final-DOF count sizing the static diagonal
     */
    public void build(SeamlessParameterization seamless, int[][][] chartVertexFinalDofs, double[][][] chartVertexFinalCoefs, CutGraph cutGraph, int dofCount) {
        double edgeLengthSquared = (double) seamless.targetQuadEdgeLength * seamless.targetQuadEdgeLength;

        double[] shapeGradX = new double[CORNERS_PER_FACE];
        double[] shapeGradY = new double[CORNERS_PER_FACE];
        int[] cornerChartVertex = new int[CORNERS_PER_FACE];

        for (int f = 0; f < seamless.faceCount; f++) {
            beginFace();
            double area = seamless.faceArea[f];
            if (area <= 0) {
                endFace(f);
                continue;
            }
            int faceCornerBase = f * CORNERS_PER_FACE;
            for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
                shapeGradX[corner] = seamless.faceShapeB[faceCornerBase + corner];
                shapeGradY[corner] = seamless.faceShapeC[faceCornerBase + corner];
                cornerChartVertex[corner] = cutGraph.cornerToChartVertex[faceCornerBase + corner];
            }
            double targetUx = seamless.faceUtxLocal[f], targetUy = seamless.faceUtyLocal[f];
            double targetVx = seamless.faceVtxLocal[f], targetVy = seamless.faceVtyLocal[f];

            for (int cornerI = 0; cornerI < CORNERS_PER_FACE; cornerI++) {
                for (int cornerJ = 0; cornerJ < CORNERS_PER_FACE; cornerJ++) {
                    double stiffnessConstant = area * edgeLengthSquared
                            * (shapeGradX[cornerI] * shapeGradX[cornerJ]
                                    + shapeGradY[cornerI] * shapeGradY[cornerJ]);
                    if (stiffnessConstant == 0.0) {
                        continue;
                    }
                    addOuterProduct(
                            chartVertexFinalDofs[cornerChartVertex[cornerI]][0],
                            chartVertexFinalCoefs[cornerChartVertex[cornerI]][0],
                            chartVertexFinalDofs[cornerChartVertex[cornerJ]][0],
                            chartVertexFinalCoefs[cornerChartVertex[cornerJ]][0],
                            stiffnessConstant);
                    addOuterProduct(
                            chartVertexFinalDofs[cornerChartVertex[cornerI]][1],
                            chartVertexFinalCoefs[cornerChartVertex[cornerI]][1],
                            chartVertexFinalDofs[cornerChartVertex[cornerJ]][1],
                            chartVertexFinalCoefs[cornerChartVertex[cornerJ]][1],
                            stiffnessConstant);
                }
            }

            for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
                double uRhsConstant = area * seamless.targetQuadEdgeLength
                        * (shapeGradX[corner] * targetUx + shapeGradY[corner] * targetUy);
                double vRhsConstant = area * seamless.targetQuadEdgeLength
                        * (shapeGradX[corner] * targetVx + shapeGradY[corner] * targetVy);
                addRhsExpansion(
                        chartVertexFinalDofs[cornerChartVertex[corner]][0],
                        chartVertexFinalCoefs[cornerChartVertex[corner]][0],
                        uRhsConstant);
                addRhsExpansion(
                        chartVertexFinalDofs[cornerChartVertex[corner]][1],
                        chartVertexFinalCoefs[cornerChartVertex[corner]][1],
                        vRhsConstant);
            }
            endFace(f);
        }
        finish(UPPER_HALVE_FACTOR, cutGraph, chartVertexFinalDofs, dofCount);
    }
}
