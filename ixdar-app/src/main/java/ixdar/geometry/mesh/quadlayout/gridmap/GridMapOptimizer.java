package ixdar.geometry.mesh.quadlayout.gridmap;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.embedding.ExactBarycentricOrient;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.solver.DirectSolver;
import ixdar.geometry.mesh.quadlayout.solver.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.OrderingMethod;
import ixdar.platform.Platforms;

/**
 * LCBK19 §6.2's re-parametrization: projected Newton on the symmetric Dirichlet
 * energy of the map from the seamless parametrization to the grid, freeing the
 * vertices the per-patch maps pinned.
 *
 * <p>
 * See also: LCBK19 Section 7; RPP17 Equation 11; SPH17 Section 5; SS15 Section
 * 3.3
 */
public final class GridMapOptimizer {

    /** Coordinates each slot contributes to the system. */
    public static final int SLOT_COORDINATES = 2;

    /** Corners of a triangle. */
    public static final int TRIANGLE_CORNERS = 3;

    /**
     * Entries of a triangle's gradient operator: two Jacobian rows over three
     * corners.
     */
    public static final int OPERATOR_SIZE = 6;

    /**
     * Backtracking factor applied to the step when a trial point does not lower the
     * energy.
     */
    public static final double BACKTRACK = 0.5;

    /** Backtracks tried before the iteration gives up. */
    public static final int MAX_BACKTRACKS = 20;

    /** Relative energy drop below which the relaxation is called converged. */
    public static final double CONVERGENCE = 1.0e-4;

    /**
     * Fraction of the maximal non-inverting step the line search starts at, RPP17
     * §3's {@code min(1, 0.8·alphaMax)}.
     */
    public static final double MAX_STEP_MARGIN = 0.8;

    /** Armijo sufficient-decrease coefficient on the directional derivative. */
    public static final double ARMIJO_SLOPE = 1.0e-4;

    /**
     * Smallest Jacobian determinant the barrier can be evaluated at: below this,
     * symmetric Dirichlet's {@code 1/det²} overflows double. A bound of the
     * arithmetic, not a tolerance on the geometry.
     */
    public static final double MINIMUM_INVERTIBLE_JACOBIAN = 1.0e-150;

    /**
     * Fraction of its own source face's chart area a copy triangle must cover to
     * carry a shape worth fitting. The gradient operator divides by the reference,
     * so a smaller one contributes only an arbitrarily large Hessian row.
     */
    public static final double REFERENCE_AREA_FLOOR_FRACTION = 1.0e-12;

    /**
     * Consecutive below-{@link #CONVERGENCE} iterations before the relaxation
     * stops.
     */
    public static final int STALL_LIMIT = 200;

    /**
     * Absolute ridge keeping a row with no triangle contribution — or a gauge
     * nullspace on a component with no pinned node — solvable. Numerics only, not
     * damping.
     */
    public static final double RIDGE_FLOOR = 1.0e-12;

    /**
     * Ridge as a fraction of the largest diagonal. SPH17's projection gives a
     * positive <em>semi</em>-definite Hessian; Cholesky needs it definite, and an
     * absolute floor is nothing against diagonals this large.
     */
    public static final double RIDGE_RELATIVE = 1.0e-10;

    private static final int KEY_ROW_SHIFT = 32;

    public final GridMapDofSystem dofs;
    public final GlobalGridMap gridMap;

    /**
     * The parametrization the relaxed map is fitted to: each triangle's reference
     * shape is its shape in this parametrization, so the energy's minimum is the
     * map that reproduces it up to rotation (LCBK19 §7's "difference between output
     * integer grid map and input parametrization").
     */
    public final SeamlessParameterization seamless;

    /**
     * Newton iterations to run; {@link #timeBudgetMilliseconds} usually stops the
     * loop first.
     */
    public int maxIterations = 10_000;

    /**
     * Wall-clock budget for the whole relaxation, the cap that fits the scene's run
     * budget.
     */
    public long timeBudgetMilliseconds = 500_000;

    /** Symmetric Dirichlet energy of the grid map before the relaxation. */
    public double energyBefore;

    /** Energy after it; the relaxation never raises this. */
    public double energyAfter;

    /** Iterations actually run. */
    public int iterationCount;

    /**
     * Triangles folded over in the grid at the end; the energy's barrier must keep
     * this at zero.
     */
    public int flippedTriangleCount;

    /** Largest distance any vertex moved in the grid. */
    public double worstVertexMove;

    /**
     * Step the line search accepted on the last iteration, zero when it gave up.
     */
    public double acceptedStep;

    /** Gradient operator of every triangle, six entries each, in triangle order. */
    private double[] operatorByTriangle;

    /**
     * Reference area of every triangle in the parametrization, in triangle order.
     */
    private double[] areaByTriangle;

    /** The three slots of every triangle, in triangle order. */
    private int[] slotByTriangleCorner;

    /**
     * Quarter turns reading each triangle corner's slot into its patch chart; zero
     * uncoupled.
     */
    private int[] rotationByTriangleCorner;

    /** Grid u translation of each corner's read transform. */
    private double[] translationUByTriangleCorner;

    /** Grid v translation of each corner's read transform. */
    private double[] translationVByTriangleCorner;

    /** Owning patch of every gathered triangle, in triangle order. */
    private int[] patchByTriangle;

    /** Triangles gathered from all live patches. */
    private int triangleCount;

    /** Triangles with all three corners held, whose energy no step can change. */
    private int pinnedTriangleCount;

    /** Triangles whose corners could not be read in the parametrization. */
    private int unreadableSourceCount;

    /**
     * Triangles whose reference in the parametrization is degenerate or negatively
     * oriented, which a locally injective parametrization cannot produce.
     */
    private int nonPositiveReferenceCount;

    private double worstReferenceDeterminant;
    private int firstNonPositivePatch = -1;
    private int firstNonPositiveCopyFace = -1;
    private double firstNonPositiveDeterminant;
    private String firstNonPositiveReference;

    /** References whose parametrization triangle has exactly zero area. */
    private int zeroReferenceCount;

    /** Source faces the bad references fall in, so the count is per chart. */
    private final Set<Integer> collapsedSourceFaces = new HashSet<>();

    /**
     * Patches the bad references fall in, separating whole patches from slivers.
     */
    private final Set<Integer> collapsedPatches = new HashSet<>();

    /**
     * Bad references whose triangle measures exactly zero in global grid
     * coordinates, where the frame's integer translation cancels a sliver away.
     */
    private int cancelledGridAreaCount;

    /**
     * Triangles the frame's translation left with no positive area in the grid, so
     * the barrier energy cannot be evaluated on them.
     */
    private int crushedGridTriangleCount;

    /**
     * Triangles whose reference in the parametrization is too small to divide by,
     * so the gradient operator cannot be formed in double.
     */
    private int unrepresentableReferenceCount;

    /**
     * Source face the last {@link #sourceCorners} call read, for the diagnostic.
     */
    private int sourceFaceOfLastRead;

    /**
     * Twice the signed chart area of that source face, the scale a reference is
     * judged at.
     */
    private double chartDeterminantOfLastRead;

    /**
     * Chart reads whose source face is negatively oriented in the parametrization.
     */
    private int negativeChartCount;

    /**
     * Bad references whose copy triangle is already degenerate in exact barycentric
     * arithmetic, so the working copy is at fault rather than the parametrization.
     */
    private int degenerateCopyTriangleCount;

    /**
     * Bad references whose copy triangle is wound against its source face, which
     * reverses the reference on its own with nothing wrong in the parametrization.
     */
    private int invertedCopyTriangleCount;

    /** Barycentrics the last {@link #sourceCorners} call read, in corner order. */
    private final double[][] cornerBarycentric = new double[TRIANGLE_CORNERS][];

    /**
     * Directional derivative of the energy along the last Newton direction;
     * negative.
     */
    private double gradientDotDirection;

    /**
     * Sorted strict-upper Hessian keys {@code (row << 32) | column}; the pattern
     * never changes.
     */
    private long[] upperKeys;

    /** Values matching {@link #upperKeys}, refilled every iteration. */
    private double[] upperValues;

    /**
     * Destination of each triangle's 36 Hessian entries: an {@link #upperValues}
     * index, a diagonal index encoded as {@code -(index + 1)}, or
     * {@link Integer#MIN_VALUE} for the strict lower triangle.
     */
    private int[] scatterByTriangleEntry;

    /**
     * AMD permutation of the first factorization, reused while the pattern is
     * unchanged.
     */
    private int[] cachedPermutation;

    /**
     * Whether each system variable is held, hoisted because the mask never changes.
     */
    private boolean[] fixedByVariable;

    /**
     * Consecutive iterations whose relative drop stayed below {@link #CONVERGENCE}.
     */
    private int stallCount;

    /**
     * Maximal non-inverting step of the last Newton direction, before the margin.
     */
    private double lastAlphaMax;

    /**
     * Triangle whose fold set {@link #lastAlphaMax}, for reading what blocks the
     * step.
     */
    private int blockingTriangle;

    /**
     * Gradient of the last direction computation, negative RHS for Newton
     * iterations.
     */
    private double[] gradientScratch;

    /**
     * One triangle's three corners in the parametrization, as
     * {@code u0, v0, u1, v1, u2, v2}.
     */
    private final double[] sourceUv = new double[TRIANGLE_CORNERS * SLOT_COORDINATES];

    /** One source face's three corner {@code (u, v)} pairs. */
    private final double[] faceCornerUv = new double[TRIANGLE_CORNERS * SLOT_COORDINATES];

    /**
     * Stores the slot system whose free coordinates are relaxed.
     *
     * @param dofs     which grid coordinates may move
     * @param seamless the parametrization the relaxed map is fitted to
     */
    public GridMapOptimizer(GridMapDofSystem dofs, SeamlessParameterization seamless) {
        this.dofs = dofs;
        this.gridMap = dofs.gridMap;
        this.seamless = seamless;
    }

    /**
     * Relaxes every free coordinate by projected Newton on the symmetric Dirichlet
     * energy, holding the layout's critical nodes and coupling its seams, then
     * writes the result back into the grid map.
     *
     * @throws IllegalStateException when the parametrization's references are not
     *                               locally injective or the starting map is folded
     * @return this, solved
     */
    public GridMapOptimizer build() {
        if (timeBudgetMilliseconds <= 0) {
            System.out.println("[grid-optimize] skipped: no time budget");
            return this;
        }
        gatherTriangles();
        SymmetricDirichletEnergy element = new SymmetricDirichletEnergy();
        int unrepresentable = dropUnrepresentableTriangles(element);
        if (unrepresentable > 0) {
            System.out.println("[grid-optimize] dropped " + unrepresentable + " triangles whose"
                    + " starting energy overflows double, of " + (triangleCount + unrepresentable));
        }
        buildSparsityPattern();
        double[] startU = dofs.slotU.clone();
        double[] startV = dofs.slotV.clone();
        energyBefore = totalEnergy(element, dofs.slotU, dofs.slotV);
        if (Double.isInfinite(energyBefore)) {
            int firstFolded = firstFoldedTriangle();
            if (firstFolded >= 0) {
                throw new IllegalStateException("the starting grid map has " + countFlipped()
                        + " folded triangles; first gathered triangle=" + firstFolded + " patch="
                        + patchByTriangle[firstFolded] + " signedArea="
                        + gridSignedArea(firstFolded) + "; the per-patch Tutte maps guarantee a"
                        + " flip-free start, so an upstream stage broke its postcondition");
            }
            throw new IllegalStateException("the starting grid map has non-finite distortion"
                    + " despite having no folded triangles; a numerically collapsed source"
                    + " reference was not filtered");
        }
        double energy = energyBefore;
        long startedAt = System.currentTimeMillis();
        for (int iteration = 0; iteration < maxIterations
                && System.currentTimeMillis() - startedAt < timeBudgetMilliseconds; iteration++) {
            double[] delta = newtonDirection(element);
            if (delta == null) {
                break;
            }
            double stepped = takeStep(element, delta, energy);
            iterationCount++;
            System.out.printf("[grid-optimize]   iteration %d energy %.6e (%.3f%%)"
                    + " step=%.3e alphaMax=%.3e blocking=%d%n", iteration, stepped,
                    100.0 * (energy - stepped) / Math.max(1.0e-30, energy), acceptedStep,
                    lastAlphaMax, blockingTriangle);
            boolean noProgress = stepped >= energy * (1.0 - CONVERGENCE);
            stallCount = noProgress ? stallCount + 1 : 0;
            energy = stepped;
            if (acceptedStep == 0.0 || stallCount >= STALL_LIMIT) {
                break;
            }
        }
        energyAfter = energy;
        for (int slot = 0; slot < dofs.slotCount; slot++) {
            worstVertexMove = Math.max(worstVertexMove, Math.hypot(dofs.slotU[slot] - startU[slot],
                    dofs.slotV[slot] - startV[slot]));
        }
        dofs.writeBack();
        flippedTriangleCount = countFlipped();
        System.out.printf("[grid-optimize] energy %.4e -> %.4e (%.1f%%) iterations=%d"
                + " flipped=%d/%d pinned=%d worstMove=%.4f%n", energyBefore, energyAfter,
                100.0 * (energyBefore - energyAfter) / Math.max(1.0e-30, energyBefore),
                iterationCount, flippedTriangleCount, triangleCount, pinnedTriangleCount,
                worstVertexMove);
        return this;
    }

    /**
     * Flattens every live patch's triangles into one list, taking each one's
     * reference shape from the seamless parametrization so the energy measures
     * distortion against it.
     *
     * @throws IllegalStateException when any reference is unreadable, degenerate or
     *                               negatively oriented — the parametrization is
     *                               then not locally injective, which Stage 0
     *                               (BCE13 §3.1 injectivity constraints) must fix
     */
    private void gatherTriangles() {
        for (EmbeddedPatch patch : gridMap.tmesh.patches) {
            if (patch.alive) {
                triangleCount += gridMap.patchMaps.mapByPatchId[patch.patchId].triangles.length;
            }
        }
        operatorByTriangle = new double[triangleCount * OPERATOR_SIZE];
        areaByTriangle = new double[triangleCount];
        patchByTriangle = new int[triangleCount];
        slotByTriangleCorner = new int[triangleCount * TRIANGLE_CORNERS];
        rotationByTriangleCorner = new int[triangleCount * TRIANGLE_CORNERS];
        translationUByTriangleCorner = new double[triangleCount * TRIANGLE_CORNERS];
        translationVByTriangleCorner = new double[triangleCount * TRIANGLE_CORNERS];
        int index = 0;
        for (EmbeddedPatch patch : gridMap.tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            PatchRectangleMap map = gridMap.patchMaps.mapByPatchId[patch.patchId];
            int[] slotByDense = dofs.slotByPatchDense[patch.patchId];
            int[] rotationByDense = dofs.rotationByPatchDense[patch.patchId];
            double[] translationUByDense = dofs.translationUByPatchDense[patch.patchId];
            double[] translationVByDense = dofs.translationVByPatchDense[patch.patchId];
            double[] uv = gridMap.uvByPatchId[patch.patchId];
            boolean swapCorners = !map.isCounterClockwise();
            List<Integer> regionFaces = gridMap.patchMaps.regions.copyFacesByPatch
                    .get(patch.patchId);
            for (int local = 0; local < map.triangles.length; local++) {
                int[] triangle = map.triangles[local];
                if (dofs.fixedBySlot[slotByDense[triangle[0]]]
                        && dofs.fixedBySlot[slotByDense[triangle[1]]]
                        && dofs.fixedBySlot[slotByDense[triangle[2]]]) {
                    // Constant energy: keeping it would only swamp the totals the line search and
                    // the convergence test compare.
                    pinnedTriangleCount++;
                    continue;
                }
                int first = swapCorners ? triangle[2] : triangle[1];
                int second = swapCorners ? triangle[1] : triangle[2];
                if (!sourceCorners(map, regionFaces.get(local), triangle[0], first, second,
                        swapCorners)) {
                    unreadableSourceCount++;
                    continue;
                }
                // Built from barycentric differences rather than by subtracting interpolated
                // positions: a triangle whose u spans less than an ulp of u itself survives the
                // difference but not the subtraction.
                // The chart is mirrored only for patches whose own map is mirrored; negating v
                // for every patch inverts the reference of every patch that is not.
                double chartV = swapCorners ? -1.0 : 1.0;
                double firstEdgeU = 0.0;
                double firstEdgeV = 0.0;
                double secondEdgeU = 0.0;
                double secondEdgeV = 0.0;
                for (int weight = 0; weight < TRIANGLE_CORNERS; weight++) {
                    double alongFirst = cornerBarycentric[1][weight] - cornerBarycentric[0][weight];
                    double alongSecond = cornerBarycentric[2][weight] - cornerBarycentric[0][weight];
                    firstEdgeU += alongFirst * faceCornerUv[weight * 2];
                    firstEdgeV += chartV * alongFirst * faceCornerUv[weight * 2 + 1];
                    secondEdgeU += alongSecond * faceCornerUv[weight * 2];
                    secondEdgeV += chartV * alongSecond * faceCornerUv[weight * 2 + 1];
                }
                double determinant = firstEdgeU * secondEdgeV - secondEdgeU * firstEdgeV;
                if (determinant <= 0.0) {
                    if (firstNonPositivePatch < 0) {
                        firstNonPositivePatch = patch.patchId;
                        firstNonPositiveCopyFace = regionFaces.get(local);
                        firstNonPositiveReference = Arrays.toString(sourceUv)
                                + " in a source face whose own chart spans "
                                + chartDeterminantOfLastRead + "; copySign(as read)="
                                + ExactBarycentricOrient.sign(cornerBarycentric[0],
                                        cornerBarycentric[1], cornerBarycentric[2])
                                + " swap=" + swapCorners + " barycentrics "
                                + Arrays.toString(cornerBarycentric[0]) + " "
                                + Arrays.toString(cornerBarycentric[1]) + " "
                                + Arrays.toString(cornerBarycentric[2]);
                        firstNonPositiveDeterminant = determinant;
                        worstReferenceDeterminant = determinant;
                    }
                    worstReferenceDeterminant = Math.min(worstReferenceDeterminant, determinant);
                    collapsedSourceFaces.add(sourceFaceOfLastRead);
                    collapsedPatches.add(patch.patchId);
                    cancelledGridAreaCount += uvSignedArea(uv, triangle[0], triangle[1],
                            triangle[2]) == 0.0 ? 1 : 0;
                    if (determinant == 0.0) {
                        zeroReferenceCount++;
                    }
                    // sourceCorners read the corners in the swapped order, which reverses the
                    // sign; undo that to judge the copy triangle as the mesh stores it.
                    int copySign = ExactBarycentricOrient.sign(cornerBarycentric[0],
                            cornerBarycentric[1], cornerBarycentric[2]) * (swapCorners ? -1 : 1);
                    degenerateCopyTriangleCount += copySign == 0 ? 1 : 0;
                    invertedCopyTriangleCount += copySign < 0 ? 1 : 0;
                    if (copySign <= 0) {
                        // The working copy's own sliver, carrying no shape to fit: the chart is
                        // sound and the reference is only as bad as the triangle already was.
                        continue;
                    }
                    nonPositiveReferenceCount++;
                    continue;
                }
                if (determinant < REFERENCE_AREA_FLOOR_FRACTION * chartDeterminantOfLastRead) {
                    // A vanishing fraction of its own source face: the gradient operator divides
                    // by it, so keeping it only injects an arbitrarily large row into the Hessian.
                    unrepresentableReferenceCount++;
                    continue;
                }
                // Measured in the patch's own rectangle, where the Tutte map's positivity was
                // established. The frame's quarter turn and integer offset preserve area but
                // push the coordinates far enough from the origin to cancel a sliver away.
                double gridArea = rectangleSignedArea(map, triangle[0], first, second);
                if (gridArea <= 0.0 || gridArea < MINIMUM_INVERTIBLE_JACOBIAN * determinant) {
                    // Positive in the patch's own rectangle, but once the frame's integer
                    // translation is applied the Jacobian is too small to invert: the barrier's
                    // 1/det² leaves double's range and no step can be computed.
                    crushedGridTriangleCount++;
                    continue;
                }
                int[] corners = { triangle[0], first, second };
                // The energy and every derivative depend only on differences between the three
                // corners, so shifting the whole triangle by its own first corner's translation
                // changes nothing — except that the differences no longer cancel a frame offset
                // of hundreds against a sliver of 1e-11.
                double originU = translationUByDense[corners[0]];
                double originV = translationVByDense[corners[0]];
                for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                    int at = index * TRIANGLE_CORNERS + corner;
                    slotByTriangleCorner[at] = slotByDense[corners[corner]];
                    rotationByTriangleCorner[at] = rotationByDense[corners[corner]];
                    translationUByTriangleCorner[at] = translationUByDense[corners[corner]]
                            - originU;
                    translationVByTriangleCorner[at] = translationVByDense[corners[corner]]
                            - originV;
                }
                areaByTriangle[index] = 0.5 * determinant;
                fillOperator(index, firstEdgeU, firstEdgeV, secondEdgeU, secondEdgeV, determinant);
                patchByTriangle[index] = patch.patchId;
                index++;
            }
        }
        triangleCount = index;
        // Every chart read was positively oriented, so no reference can be blamed on
        // the
        // parametrization: what is left is the working copy's own needles, positively
        // wound in
        // exact arithmetic but thinner than the chart can resolve.
        int chartFailures = negativeChartCount;
        int needleReferenceCount = nonPositiveReferenceCount - degenerateCopyTriangleCount
                - invertedCopyTriangleCount;
        if (needleReferenceCount > 0) {
            System.out.println("[grid-optimize] skipped " + needleReferenceCount + " triangles"
                    + " whose copy triangle is a needle: positively wound in exact arithmetic but"
                    + " too thin for its chart to give it an area");
        }
        if (degenerateCopyTriangleCount + invertedCopyTriangleCount > 0) {
            System.out.println("[grid-optimize] skipped " + degenerateCopyTriangleCount
                    + " degenerate and " + invertedCopyTriangleCount + " inverted working-copy"
                    + " slivers, which carry no shape to fit; the refinement and the carve are"
                    + " where they are minted");
        }
        if (unrepresentableReferenceCount > 0) {
            System.out.println("[grid-optimize] skipped " + unrepresentableReferenceCount
                    + " triangles whose parametrization reference is too small to divide by");
        }
        if (crushedGridTriangleCount > 0) {
            System.out.println("[grid-optimize] skipped " + crushedGridTriangleCount
                    + " triangles with no positive grid area, positive in their patch's own"
                    + " rectangle but cancelled by the frame's translation");
        }
        if (unreadableSourceCount > 0 || chartFailures > 0) {
            throw new IllegalStateException("the layout cannot be measured against the"
                    + " parametrization: " + unreadableSourceCount + " references are"
                    + " unreadable and " + nonPositiveReferenceCount + " are degenerate or"
                    + " negatively oriented — " + zeroReferenceCount + " of exactly zero area, "
                    + (nonPositiveReferenceCount - zeroReferenceCount) + " inverted, spread over "
                    + collapsedSourceFaces.size() + " source faces of " + collapsedPatches.size()
                    + " patches; of these " + degenerateCopyTriangleCount + " have a copy triangle"
                    + " degenerate in exact arithmetic, " + invertedCopyTriangleCount + " one wound"
                    + " against its source face, and " + cancelledGridAreaCount
                    + " a grid triangle the frame's translation cancelled to zero; "
                    + negativeChartCount + " chart reads hit a negatively oriented source face;"
                    + " (worst determinant " + worstReferenceDeterminant
                    + "); first is patch " + firstNonPositivePatch
                    + " copyFace " + firstNonPositiveCopyFace + " determinant "
                    + firstNonPositiveDeterminant + " source " + firstNonPositiveReference
                    + ". A copy triangle degenerate or wound against its source face is a working"
                    + " copy defect, not a parametrization one; only a bad chart with sound copy"
                    + " triangles points at Stage 0 (see QuadLayoutReparametrizationPseudocode.md)");
        }
    }

    /**
     * Reads one copy triangle's corners in the seamless parametrization, within one
     * source face, with the chart's v axis negated: the patch cycles run clockwise
     * against LCBK19 §4, so one global reflection aligns the handedness
     * conventions.
     *
     * @param map      the patch's solved rectangle map, for the copy vertex of each
     *                 dense index
     * @param copyFace copy face the triangle is
     * @param origin   dense index of the triangle's first corner
     * @param first    dense index of its second corner
     * @param second   dense index of its third corner
     * @return whether all three corners could be read
     */
    private boolean sourceCorners(PatchRectangleMap map, int copyFace, int origin, int first,
            int second, boolean mirror) {
        double chartV = mirror ? -1.0 : 1.0;
        int sourceFace = gridMap.tmesh.topology.sourceFaceByCopyFace[copyFace];
        if (sourceFace == EmbeddedMeshTopology.UNCLAIMED) {
            return false;
        }
        sourceFaceOfLastRead = sourceFace;
        seamless.faceCornerUv(sourceFace, faceCornerUv);
        chartDeterminantOfLastRead = (faceCornerUv[2] - faceCornerUv[0]) * (faceCornerUv[5] - faceCornerUv[1])
                - (faceCornerUv[4] - faceCornerUv[0]) * (faceCornerUv[3]
                        - faceCornerUv[1]);
        negativeChartCount += chartDeterminantOfLastRead < 0.0 ? 1 : 0;
        int[] corners = { origin, first, second };
        for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
            double[] barycentric = gridMap.tmesh.topology.barycentricOf(sourceFace,
                    map.vertexLabel[corners[corner]]);
            if (barycentric == null) {
                return false;
            }
            cornerBarycentric[corner] = barycentric;
            sourceUv[corner * 2] = 0.0;
            sourceUv[corner * 2 + 1] = 0.0;
            for (int weight = 0; weight < TRIANGLE_CORNERS; weight++) {
                sourceUv[corner * 2] += barycentric[weight] * faceCornerUv[weight * 2];
                sourceUv[corner * 2 + 1] += chartV * barycentric[weight]
                        * faceCornerUv[weight * 2 + 1];
            }
        }
        return true;
    }

    /**
     * The signed area of a triangle in its patch's own rectangle coordinates.
     *
     * @param map    the patch's solved rectangle map
     * @param first  dense index of the first corner
     * @param second dense index of the second corner
     * @param third  dense index of the third corner
     * @return twice the signed area, positive for a counter-clockwise triangle
     */
    private double rectangleSignedArea(PatchRectangleMap map, int first, int second, int third) {
        return (map.rectangleU[second] - map.rectangleU[first])
                * (map.rectangleV[third] - map.rectangleV[first])
                - (map.rectangleU[third] - map.rectangleU[first])
                        * (map.rectangleV[second] - map.rectangleV[first]);
    }

    /**
     * The signed area of a triangle read from a patch's grid coordinates by dense
     * index.
     *
     * @param uv     the patch's grid coordinates
     * @param first  dense index of the first corner
     * @param second dense index of the second corner
     * @param third  dense index of the third corner
     * @return twice the signed area, positive for a counter-clockwise triangle
     */
    private double uvSignedArea(double[] uv, int first, int second, int third) {
        double firstU = uv[first * SLOT_COORDINATES];
        double firstV = uv[first * SLOT_COORDINATES + 1];
        return (uv[second * SLOT_COORDINATES] - firstU)
                * (uv[third * SLOT_COORDINATES + 1] - firstV)
                - (uv[third * SLOT_COORDINATES] - firstU)
                        * (uv[second * SLOT_COORDINATES + 1] - firstV);
    }

    /**
     * One triangle corner's chart position, its slot read through the corner's
     * transform.
     *
     * @param cornerIndex index into the per-corner arrays,
     *                    {@code triangle * 3 + corner}
     * @param slotUValues grid u of each slot
     * @param slotVValues grid v of each slot
     * @param out         receives the position
     */
    private void cornerPosition(int cornerIndex, double[] slotUValues, double[] slotVValues,
            double[] out) {
        int slot = slotByTriangleCorner[cornerIndex];
        IntegerGridMap.rotate(rotationByTriangleCorner[cornerIndex], slotUValues[slot],
                slotVValues[slot], out);
        out[0] += translationUByTriangleCorner[cornerIndex];
        out[1] += translationVByTriangleCorner[cornerIndex];
    }

    /**
     * The signed area of a gathered triangle in its patch chart at the current slot
     * values.
     *
     * @param triangle triangle index
     * @return twice the signed area, positive for a counter-clockwise triangle
     */
    private double gridSignedArea(int triangle) {
        double[] origin = new double[SLOT_COORDINATES];
        double[] along = new double[SLOT_COORDINATES];
        double[] across = new double[SLOT_COORDINATES];
        cornerPosition(triangle * TRIANGLE_CORNERS, dofs.slotU, dofs.slotV, origin);
        cornerPosition(triangle * TRIANGLE_CORNERS + 1, dofs.slotU, dofs.slotV, along);
        cornerPosition(triangle * TRIANGLE_CORNERS + 2, dofs.slotU, dofs.slotV, across);
        return (along[0] - origin[0]) * (across[1] - origin[1])
                - (across[0] - origin[0]) * (along[1] - origin[1]);
    }

    /**
     * The operator taking a triangle's three corner coordinates to one row of its
     * Jacobian, for a reference triangle laid out by its two edges in the
     * parametrization.
     *
     * @param triangle    triangle index
     * @param firstEdgeU  u of the reference edge from the first corner to the
     *                    second
     * @param firstEdgeV  v of that edge
     * @param secondEdgeU u of the reference edge from the first corner to the third
     * @param secondEdgeV v of that edge
     * @param determinant the two edges' cross product, positive
     */
    private void fillOperator(int triangle, double firstEdgeU, double firstEdgeV,
            double secondEdgeU, double secondEdgeV, double determinant) {
        int at = triangle * OPERATOR_SIZE;
        double firstRowSecond = secondEdgeV / determinant;
        double firstRowThird = -firstEdgeV / determinant;
        double secondRowSecond = -secondEdgeU / determinant;
        double secondRowThird = firstEdgeU / determinant;
        operatorByTriangle[at] = -(firstRowSecond + firstRowThird);
        operatorByTriangle[at + 1] = firstRowSecond;
        operatorByTriangle[at + 2] = firstRowThird;
        operatorByTriangle[at + TRIANGLE_CORNERS] = -(secondRowSecond + secondRowThird);
        operatorByTriangle[at + TRIANGLE_CORNERS + 1] = secondRowSecond;
        operatorByTriangle[at + TRIANGLE_CORNERS + 2] = secondRowThird;
    }

    /**
     * Builds the invariant assembly structures: the sorted upper-triangle key set,
     * the scatter table sending each triangle's Hessian entries to their slots, and
     * the fixed-variable mask. Slots no gathered triangle touches are held.
     */
    private void buildSparsityPattern() {
        int size = dofs.slotCount * SLOT_COORDINATES;
        boolean[] touchedBySlot = new boolean[dofs.slotCount];
        for (int corner = 0; corner < triangleCount * TRIANGLE_CORNERS; corner++) {
            touchedBySlot[slotByTriangleCorner[corner]] = true;
        }
        fixedByVariable = new boolean[size];
        for (int slot = 0; slot < dofs.slotCount; slot++) {
            boolean fixed = dofs.fixedBySlot[slot] || !touchedBySlot[slot];
            fixedByVariable[slot * SLOT_COORDINATES] = fixed;
            fixedByVariable[slot * SLOT_COORDINATES + 1] = fixed;
        }
        int entriesPerTriangle = SymmetricDirichletEnergy.VARIABLES
                * SymmetricDirichletEnergy.VARIABLES;
        long[] candidates = new long[triangleCount * entriesPerTriangle];
        int candidateCount = 0;
        int[] variable = new int[SymmetricDirichletEnergy.VARIABLES];
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            triangleVariables(triangle, variable);
            for (int row = 0; row < SymmetricDirichletEnergy.VARIABLES; row++) {
                for (int column = 0; column < SymmetricDirichletEnergy.VARIABLES; column++) {
                    if (variable[row] < variable[column]) {
                        candidates[candidateCount++] = ((long) variable[row] << KEY_ROW_SHIFT) | variable[column];
                    }
                }
            }
        }
        Arrays.sort(candidates, 0, candidateCount);
        int distinct = 0;
        for (int index = 0; index < candidateCount; index++) {
            if (index == 0 || candidates[index] != candidates[index - 1]) {
                candidates[distinct++] = candidates[index];
            }
        }
        upperKeys = Arrays.copyOf(candidates, distinct);
        upperValues = new double[distinct];
        scatterByTriangleEntry = new int[triangleCount * entriesPerTriangle];
        int at = 0;
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            triangleVariables(triangle, variable);
            for (int row = 0; row < SymmetricDirichletEnergy.VARIABLES; row++) {
                for (int column = 0; column < SymmetricDirichletEnergy.VARIABLES; column++) {
                    if (variable[row] == variable[column]) {
                        scatterByTriangleEntry[at++] = -(variable[row] + 1);
                    } else if (variable[row] > variable[column]) {
                        scatterByTriangleEntry[at++] = Integer.MIN_VALUE;
                    } else {
                        scatterByTriangleEntry[at++] = Arrays.binarySearch(upperKeys,
                                ((long) variable[row] << KEY_ROW_SHIFT) | variable[column]);
                    }
                }
            }
        }
    }

    /**
     * The six system variables of one triangle, in the element's
     * {@code x0..x2, y0..y2} order.
     *
     * @param triangle triangle index
     * @param variable receives the variable indices
     */
    private void triangleVariables(int triangle, int[] variable) {
        for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
            int slot = slotByTriangleCorner[triangle * TRIANGLE_CORNERS + corner];
            variable[corner] = slot * SLOT_COORDINATES;
            variable[TRIANGLE_CORNERS + corner] = slot * SLOT_COORDINATES + 1;
        }
    }

    /**
     * Assembles the projected Hessian and gradient over every triangle and solves
     * for the Newton displacement of the free coordinates, reusing the fixed
     * pattern and cached ordering (SPH17 §5's projected Newton).
     *
     * @param element per-triangle evaluator
     * @return the displacement of every coordinate, or null when the system could
     *         not be solved
     */
    private double[] newtonDirection(SymmetricDirichletEnergy element) {
        int size = dofs.slotCount * SLOT_COORDINATES;
        double[] diagonal = new double[size];
        double[] rightHandSide = new double[size];
        Arrays.fill(upperValues, 0.0);
        double[] targetX = new double[TRIANGLE_CORNERS];
        double[] targetY = new double[TRIANGLE_CORNERS];
        double[] operator = new double[OPERATOR_SIZE];
        int[] variable = new int[SymmetricDirichletEnergy.VARIABLES];
        int at = 0;
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            loadTriangle(triangle, operator, targetX, targetY);
            element.evaluate(operator, areaByTriangle[triangle], targetX, targetY);
            rotateElementToSlots(triangle, element);
            triangleVariables(triangle, variable);
            for (int row = 0; row < SymmetricDirichletEnergy.VARIABLES; row++) {
                rightHandSide[variable[row]] -= element.gradient[row];
                for (int column = 0; column < SymmetricDirichletEnergy.VARIABLES; column++) {
                    int destination = scatterByTriangleEntry[at++];
                    if (destination == Integer.MIN_VALUE) {
                        continue;
                    }
                    if (destination < 0) {
                        diagonal[-destination - 1] += element.hessian[row][column];
                    } else {
                        upperValues[destination] += element.hessian[row][column];
                    }
                }
            }
        }
        double largestDiagonal = 0.0;
        for (double value : diagonal) {
            largestDiagonal = Math.max(largestDiagonal, value);
        }
        double ridge = Math.max(RIDGE_FLOOR, RIDGE_RELATIVE * largestDiagonal);
        for (int index = 0; index < size; index++) {
            diagonal[index] += ridge;
        }
        NormalMatrix matrix = new NormalMatrix(diagonal, upperKeys, upperValues, rightHandSide);
        DirectSolver.CholeskyHandle handle = cachedPermutation == null
                ? DirectSolver.factorize(matrix, fixedByVariable, OrderingMethod.AMD)
                : DirectSolver.factorizeWithPerm(matrix, fixedByVariable, cachedPermutation);
        if (cachedPermutation == null) {
            cachedPermutation = handle.perm();
        }
        double[] delta = new double[size];
        DirectSolver.solveCompact(handle, matrix, rightHandSide, delta, new double[size],
                fixedByVariable);
        DirectSolver.releaseHandle(handle);
        gradientDotDirection = 0.0;
        for (int index = 0; index < size; index++) {
            gradientDotDirection -= rightHandSide[index] * delta[index];
        }
        gradientScratch = new double[size];
        for (int index = 0; index < size; index++) {
            gradientScratch[index] = -rightHandSide[index];
        }
        return delta;
    }

    /**
     * Chain-rules one evaluated element from chart coordinates to slot coordinates:
     * each corner's gradient pair and Hessian rows and columns rotate by the
     * inverse of its read transform.
     *
     * @param triangle triangle index
     * @param element  evaluator holding the chart-coordinate gradient and Hessian
     */
    private void rotateElementToSlots(int triangle, SymmetricDirichletEnergy element) {
        double[] rotated = new double[SLOT_COORDINATES];
        for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
            int rotation = rotationByTriangleCorner[triangle * TRIANGLE_CORNERS + corner];
            if (rotation == 0) {
                continue;
            }
            int inverse = (IntegerGridMap.QUARTER_TURNS - rotation)
                    % IntegerGridMap.QUARTER_TURNS;
            IntegerGridMap.rotate(inverse, element.gradient[corner],
                    element.gradient[TRIANGLE_CORNERS + corner], rotated);
            element.gradient[corner] = rotated[0];
            element.gradient[TRIANGLE_CORNERS + corner] = rotated[1];
            for (int column = 0; column < SymmetricDirichletEnergy.VARIABLES; column++) {
                IntegerGridMap.rotate(inverse, element.hessian[corner][column],
                        element.hessian[TRIANGLE_CORNERS + corner][column], rotated);
                element.hessian[corner][column] = rotated[0];
                element.hessian[TRIANGLE_CORNERS + corner][column] = rotated[1];
            }
        }
        for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
            int rotation = rotationByTriangleCorner[triangle * TRIANGLE_CORNERS + corner];
            if (rotation == 0) {
                continue;
            }
            int inverse = (IntegerGridMap.QUARTER_TURNS - rotation)
                    % IntegerGridMap.QUARTER_TURNS;
            for (int row = 0; row < SymmetricDirichletEnergy.VARIABLES; row++) {
                IntegerGridMap.rotate(inverse, element.hessian[row][corner],
                        element.hessian[row][TRIANGLE_CORNERS + corner], rotated);
                element.hessian[row][corner] = rotated[0];
                element.hessian[row][TRIANGLE_CORNERS + corner] = rotated[1];
            }
        }
    }

    /**
     * The largest step along a direction before any triangle's grid area reaches
     * zero, from each triangle's quadratic {@code det(t) = c + b·t + a·t²} with
     * {@code c > 0} — SS15 §3.3's maximal non-inverting step.
     *
     * @param delta the displacement of every coordinate
     * @return the smallest positive root over all triangles, infinite when none
     *         degenerates
     */
    private double maximumStep(double[] delta) {
        double alphaMax = Double.POSITIVE_INFINITY;
        double[] origin = new double[SLOT_COORDINATES];
        double[] along = new double[SLOT_COORDINATES];
        double[] across = new double[SLOT_COORDINATES];
        double[] originMove = new double[SLOT_COORDINATES];
        double[] alongMove = new double[SLOT_COORDINATES];
        double[] acrossMove = new double[SLOT_COORDINATES];
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            cornerPosition(triangle * TRIANGLE_CORNERS, dofs.slotU, dofs.slotV, origin);
            cornerPosition(triangle * TRIANGLE_CORNERS + 1, dofs.slotU, dofs.slotV, along);
            cornerPosition(triangle * TRIANGLE_CORNERS + 2, dofs.slotU, dofs.slotV, across);
            cornerMove(triangle * TRIANGLE_CORNERS, delta, originMove);
            cornerMove(triangle * TRIANGLE_CORNERS + 1, delta, alongMove);
            cornerMove(triangle * TRIANGLE_CORNERS + 2, delta, acrossMove);
            double edgeOneU = along[0] - origin[0];
            double edgeOneV = along[1] - origin[1];
            double edgeTwoU = across[0] - origin[0];
            double edgeTwoV = across[1] - origin[1];
            double moveOneU = alongMove[0] - originMove[0];
            double moveOneV = alongMove[1] - originMove[1];
            double moveTwoU = acrossMove[0] - originMove[0];
            double moveTwoV = acrossMove[1] - originMove[1];
            double constant = edgeOneU * edgeTwoV - edgeTwoU * edgeOneV;
            double linear = edgeOneU * moveTwoV - moveTwoU * edgeOneV
                    + moveOneU * edgeTwoV - edgeTwoU * moveOneV;
            double quadratic = moveOneU * moveTwoV - moveTwoU * moveOneV;
            double root;
            if (quadratic == 0.0) {
                root = linear < 0.0 ? -constant / linear : Double.POSITIVE_INFINITY;
            } else {
                double discriminant = linear * linear - 4.0 * quadratic * constant;
                if (discriminant < 0.0) {
                    continue;
                }
                double q = -0.5 * (linear + Math.copySign(Math.sqrt(discriminant), linear));
                double firstRoot = q / quadratic;
                double secondRoot = q == 0.0 ? Double.POSITIVE_INFINITY : constant / q;
                root = Double.POSITIVE_INFINITY;
                if (firstRoot > 0.0) {
                    root = firstRoot;
                }
                if (secondRoot > 0.0 && secondRoot < root) {
                    root = secondRoot;
                }
            }
            if (root < alphaMax) {
                alphaMax = root;
                blockingTriangle = triangle;
            }
        }
        lastAlphaMax = alphaMax;
        return alphaMax;
    }

    /**
     * One triangle corner's chart displacement for a slot displacement, which
     * rotates with the corner's read transform while its translation cancels.
     *
     * @param cornerIndex index into the per-corner arrays,
     *                    {@code triangle * 3 + corner}
     * @param delta       the displacement of every coordinate
     * @param out         receives the chart displacement
     */
    private void cornerMove(int cornerIndex, double[] delta, double[] out) {
        int slot = slotByTriangleCorner[cornerIndex];
        IntegerGridMap.rotate(rotationByTriangleCorner[cornerIndex],
                delta[slot * SLOT_COORDINATES], delta[slot * SLOT_COORDINATES + 1], out);
    }

    /**
     * Moves along the Newton direction, starting at RPP17 §3's
     * {@code min(1, 0.8·alphaMax)} and backtracking under an Armijo
     * sufficient-decrease test (SPH17 §5). The energy's own barrier in
     * {@link SymmetricDirichletEnergy#energyOnly} is the roundoff backstop.
     *
     * @param element per-triangle evaluator
     * @param delta   the Newton displacement of every coordinate
     * @param current the energy being improved on
     * @return the energy reached
     */
    private double takeStep(SymmetricDirichletEnergy element, double[] delta, double current) {
        double step = Math.min(1.0, MAX_STEP_MARGIN * maximumStep(delta));
        if (!(step > 0.0)) {
            acceptedStep = 0.0;
            return current;
        }
        double[] trialU = new double[dofs.slotCount];
        double[] trialV = new double[dofs.slotCount];
        for (int backtrack = 0; backtrack < MAX_BACKTRACKS; backtrack++) {
            for (int slot = 0; slot < dofs.slotCount; slot++) {
                trialU[slot] = dofs.slotU[slot] + step * delta[slot * SLOT_COORDINATES];
                trialV[slot] = dofs.slotV[slot] + step * delta[slot * SLOT_COORDINATES + 1];
            }
            double trial = totalEnergy(element, trialU, trialV);
            if (trial <= current + ARMIJO_SLOPE * step * gradientDotDirection) {
                System.arraycopy(trialU, 0, dofs.slotU, 0, dofs.slotCount);
                System.arraycopy(trialV, 0, dofs.slotV, 0, dofs.slotCount);
                acceptedStep = step;
                return trial;
            }
            step *= BACKTRACK;
        }
        acceptedStep = 0.0;
        return current;
    }

    /**
     * The symmetric Dirichlet energy of the whole map at one assignment of grid
     * positions.
     *
     * @param element per-triangle evaluator
     * @param slotU   grid u of each slot
     * @param slotV   grid v of each slot
     * @return the summed energy, infinite when any triangle has folded
     */
    /**
     * Removes triangles whose starting energy is not a finite double, compacting
     * the gathered arrays around them. A triangle so distorted that the barrier
     * overflows cannot be stepped, and keeping it makes every total infinite.
     *
     * @param element per-triangle energy, reused across the scan
     * @return the number of triangles removed
     */
    private int dropUnrepresentableTriangles(SymmetricDirichletEnergy element) {
        double[] targetX = new double[TRIANGLE_CORNERS];
        double[] targetY = new double[TRIANGLE_CORNERS];
        double[] operator = new double[OPERATOR_SIZE];
        double[] position = new double[SLOT_COORDINATES];
        int kept = 0;
        int dropped = 0;
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            System.arraycopy(operatorByTriangle, triangle * OPERATOR_SIZE, operator, 0,
                    OPERATOR_SIZE);
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                cornerPosition(triangle * TRIANGLE_CORNERS + corner, dofs.slotU, dofs.slotV,
                        position);
                targetX[corner] = position[0];
                targetY[corner] = position[1];
            }
            if (!Double.isFinite(element.energyOnly(operator, areaByTriangle[triangle], targetX,
                    targetY))) {
                dropped++;
                continue;
            }
            if (kept != triangle) {
                System.arraycopy(operatorByTriangle, triangle * OPERATOR_SIZE, operatorByTriangle,
                        kept * OPERATOR_SIZE, OPERATOR_SIZE);
                System.arraycopy(slotByTriangleCorner, triangle * TRIANGLE_CORNERS,
                        slotByTriangleCorner, kept * TRIANGLE_CORNERS, TRIANGLE_CORNERS);
                System.arraycopy(rotationByTriangleCorner, triangle * TRIANGLE_CORNERS,
                        rotationByTriangleCorner, kept * TRIANGLE_CORNERS, TRIANGLE_CORNERS);
                System.arraycopy(translationUByTriangleCorner, triangle * TRIANGLE_CORNERS,
                        translationUByTriangleCorner, kept * TRIANGLE_CORNERS, TRIANGLE_CORNERS);
                System.arraycopy(translationVByTriangleCorner, triangle * TRIANGLE_CORNERS,
                        translationVByTriangleCorner, kept * TRIANGLE_CORNERS, TRIANGLE_CORNERS);
                areaByTriangle[kept] = areaByTriangle[triangle];
                patchByTriangle[kept] = patchByTriangle[triangle];
            }
            kept++;
        }
        triangleCount = kept;
        return dropped;
    }

    /**
     * The energy of every gathered triangle at one set of slot values.
     *
     * @param element per-triangle energy, reused across the sum
     * @param slotU   grid u of each slot
     * @param slotV   grid v of each slot
     * @return the summed energy, infinite when any triangle has folded
     */
    private double totalEnergy(SymmetricDirichletEnergy element, double[] slotU, double[] slotV) {
        double[] targetX = new double[TRIANGLE_CORNERS];
        double[] targetY = new double[TRIANGLE_CORNERS];
        double[] operator = new double[OPERATOR_SIZE];
        double[] position = new double[SLOT_COORDINATES];
        double total = 0.0;
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            System.arraycopy(operatorByTriangle, triangle * OPERATOR_SIZE, operator, 0,
                    OPERATOR_SIZE);
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                cornerPosition(triangle * TRIANGLE_CORNERS + corner, slotU, slotV, position);
                targetX[corner] = position[0];
                targetY[corner] = position[1];
            }
            total += element.energyOnly(operator, areaByTriangle[triangle], targetX, targetY);
            if (Double.isInfinite(total)) {
                return Double.POSITIVE_INFINITY;
            }
        }
        return total;
    }

    /**
     * Reads one triangle's operator and its corners' current grid positions.
     *
     * @param triangle triangle index
     * @param operator receives the gradient operator
     * @param targetX  receives the corners' grid u
     * @param targetY  receives the corners' grid v
     */
    private void loadTriangle(int triangle, double[] operator, double[] targetX, double[] targetY) {
        System.arraycopy(operatorByTriangle, triangle * OPERATOR_SIZE, operator, 0, OPERATOR_SIZE);
        double[] position = new double[SLOT_COORDINATES];
        for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
            cornerPosition(triangle * TRIANGLE_CORNERS + corner, dofs.slotU, dofs.slotV, position);
            targetX[corner] = position[0];
            targetY[corner] = position[1];
        }
    }

    /**
     * The number of triangles folded over in the grid.
     *
     * @return the count of non-positive signed areas
     */
    private int countFlipped() {
        int flipped = 0;
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            flipped += gridSignedArea(triangle) <= 0.0 ? 1 : 0;
        }
        return flipped;
    }

    /**
     * First gathered triangle with non-positive grid area.
     *
     * @return folded triangle index
     */
    private int firstFoldedTriangle() {
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            if (gridSignedArea(triangle) <= 0.0) {
                return triangle;
            }
        }
        return -1;
    }
}
