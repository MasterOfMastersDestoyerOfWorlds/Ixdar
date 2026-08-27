package ixdar.geometry.mesh.quadlayout.gridmap;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.embedding.ExactBarycentricOrient;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.solver.system.NewtonRelaxation;
import ixdar.platform.Platforms;
import ixdar.platform.concurrent.WorkerPool;

/**
 * LCBK19 §6.2's re-parametrization: Newton with SPH17's composite-majorization
 * PSD Hessian on the symmetric Dirichlet energy of the map from the seamless
 * parametrization to the grid, freeing the vertices the per-patch maps pinned.
 *
 * <p>
 * See also: LCBK19 Section 7; RPP17 Equation 11; SPH17 Section 5; SS15 Section
 * 3.3
 */
public final class GridMapOptimizer {

    /** Coordinates each slot contributes to the system. */
    public static final int SLOT_COORDINATES = 2;

    /**
     * Entries of a triangle's gradient operator: two Jacobian rows over three
     * corners.
     */
    public static final int OPERATOR_SIZE = 6;

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

    /** Cap on the worker threads assembling the Newton system. */
    public static final int MAX_WORKER_THREADS = 8;

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
     * Worker threads assembling the Newton system; one worker reproduces the serial
     * summation order exactly.
     */
    public int workerThreads = Math.min(Runtime.getRuntime().availableProcessors(),
            MAX_WORKER_THREADS);

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

    private double worstReferenceDeterminant;
    private int firstNonPositivePatch = -1;

    /** Source faces the bad references fall in, so the count is per chart. */
    private final Set<Integer> collapsedSourceFaces = new HashSet<>();

    /**
     * Patches the bad references fall in, separating whole patches from slivers.
     */
    private final Set<Integer> collapsedPatches = new HashSet<>();

    /**
     * Source face the last {@link #sourceCorners} call read, for the diagnostic.
     */
    private int sourceFaceOfLastRead;

    /**
     * Twice the signed chart area of that source face, the scale a reference is
     * judged at.
     */
    private double chartDeterminantOfLastRead;

    /** Barycentrics the last {@link #sourceCorners} call read, in corner order. */
    private final double[][] cornerBarycentric = new double[HalfEdgeMesh.TRIANGLE_CORNERS][];

    /**
     * Sorted strict-upper Hessian keys {@code (row << 32) | column}; the pattern
     * never changes.
     */
    private long[] upperKeys;

    /**
     * Destination of each triangle's 36 Hessian entries: an upper-values index, a
     * diagonal index encoded as {@code -(index + 1)}, or {@link Integer#MIN_VALUE}
     * for the strict lower triangle.
     */
    private int[] scatterByTriangleEntry;

    /** Workers the assembly actually uses, at most one per gathered triangle. */
    private int assemblyWorkerCount;

    /** Fixed pool running the per-triangle Newton assembly. */
    private WorkerPool assemblyPool;

    /** Per-worker element evaluators, thread-confined. */
    private SymmetricDirichletEnergy[] elementByWorker;

    /** Per-worker negative-gradient accumulators, reduced in worker order. */
    private double[][] rightHandSideByWorker;

    /** Per-worker Hessian-diagonal accumulators, reduced in worker order. */
    private double[][] diagonalByWorker;

    /** Per-worker strict-upper accumulators, reduced in worker order. */
    private double[][] upperValuesByWorker;

    /**
     * One triangle's three corners in the parametrization, as
     * {@code u0, v0, u1, v1, u2, v2}.
     */
    private final double[] sourceUv = new double[HalfEdgeMesh.TRIANGLE_CORNERS * SLOT_COORDINATES];

    /** One source face's three corner {@code (u, v)} pairs. */
    private final double[] faceCornerUv = new double[HalfEdgeMesh.TRIANGLE_CORNERS * SLOT_COORDINATES];

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
        gatherTriangles();
        buildSparsityPattern();
        double[] start = dofs.system.solution.clone();
        int size = dofs.slotCount * SLOT_COORDINATES;
        assemblyWorkerCount = Math.max(1,
                Math.min(Math.min(workerThreads, MAX_WORKER_THREADS), triangleCount));
        assemblyPool = Platforms.get().newWorkerPool(assemblyWorkerCount, "grid-optimize-assembly");
        elementByWorker = new SymmetricDirichletEnergy[assemblyWorkerCount];
        rightHandSideByWorker = new double[assemblyWorkerCount][size];
        diagonalByWorker = new double[assemblyWorkerCount][size];
        upperValuesByWorker = new double[assemblyWorkerCount][upperKeys.length];
        for (int worker = 0; worker < assemblyWorkerCount; worker++) {
            elementByWorker[worker] = new SymmetricDirichletEnergy();
        }
        energyBefore = totalEnergy(dofs.system.solution);
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
        dofs.system.energy = this::totalEnergy;
        dofs.system.writeBack = dofs::writeBack;
        NewtonRelaxation newton = new NewtonRelaxation(dofs.system, this::assembleInto, upperKeys,
                this::maximumStep);
        newton.run();
        assemblyPool.shutdown();
        energyAfter = newton.energyAfter;
        iterationCount = newton.iterationCount;
        acceptedStep = newton.acceptedStep;
        for (int slot = 0; slot < dofs.slotCount; slot++) {
            worstVertexMove = Math.max(worstVertexMove, Math.hypot(
                    dofs.system.solution[slot * SLOT_COORDINATES]
                            - start[slot * SLOT_COORDINATES],
                    dofs.system.solution[slot * SLOT_COORDINATES + 1]
                            - start[slot * SLOT_COORDINATES + 1]));
        }
        flippedTriangleCount = countFlipped();
        Platforms.log("[grid-optimize] energy %.4e -> %.4e (%.1f%%) iterations=%d"
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
        slotByTriangleCorner = new int[triangleCount * HalfEdgeMesh.TRIANGLE_CORNERS];
        rotationByTriangleCorner = new int[triangleCount * HalfEdgeMesh.TRIANGLE_CORNERS];
        translationUByTriangleCorner = new double[triangleCount * HalfEdgeMesh.TRIANGLE_CORNERS];
        translationVByTriangleCorner = new double[triangleCount * HalfEdgeMesh.TRIANGLE_CORNERS];
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
            boolean swapCorners = !map.isCounterClockwise();
            List<Integer> regionFaces = gridMap.patchMaps.regions.copyFacesByPatch
                    .get(patch.patchId);
            for (int local = 0; local < map.triangles.length; local++) {
                int[] triangle = map.triangles[local];
                if (dofs.system.frozen[slotByDense[triangle[0]] * SLOT_COORDINATES]
                        && dofs.system.frozen[slotByDense[triangle[1]] * SLOT_COORDINATES]
                        && dofs.system.frozen[slotByDense[triangle[2]] * SLOT_COORDINATES]) {

                    pinnedTriangleCount++;
                    continue;
                }
                int first = swapCorners ? triangle[2] : triangle[1];
                int second = swapCorners ? triangle[1] : triangle[2];
                if (!sourceCorners(map, regionFaces.get(local), triangle[0], first, second,
                        swapCorners)) {
                    continue;
                }

                double chartV = swapCorners ? -1.0 : 1.0;
                double firstEdgeU = 0.0;
                double firstEdgeV = 0.0;
                double secondEdgeU = 0.0;
                double secondEdgeV = 0.0;
                for (int weight = 0; weight < HalfEdgeMesh.TRIANGLE_CORNERS; weight++) {
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
                        worstReferenceDeterminant = determinant;
                    }
                    worstReferenceDeterminant = Math.min(worstReferenceDeterminant, determinant);
                    collapsedSourceFaces.add(sourceFaceOfLastRead);
                    collapsedPatches.add(patch.patchId);

                    int copySign = ExactBarycentricOrient.sign(cornerBarycentric[0],
                            cornerBarycentric[1], cornerBarycentric[2]) * (swapCorners ? -1 : 1);
                    if (copySign <= 0) {
                        continue;
                    }
                    continue;
                }
                if (determinant < REFERENCE_AREA_FLOOR_FRACTION * chartDeterminantOfLastRead) {
                    continue;
                }

                double gridArea = rectangleSignedArea(map, triangle[0], first, second);
                if (gridArea <= 0.0 || gridArea < MINIMUM_INVERTIBLE_JACOBIAN * determinant) {
                    continue;
                }
                int[] corners = { triangle[0], first, second };

                double originU = translationUByDense[corners[0]];
                double originV = translationVByDense[corners[0]];
                for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
                    int at = index * HalfEdgeMesh.TRIANGLE_CORNERS + corner;
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
        int[] corners = { origin, first, second };
        for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
            double[] barycentric = gridMap.tmesh.topology.barycentricOf(sourceFace,
                    map.vertexLabel[corners[corner]]);
            if (barycentric == null) {
                return false;
            }
            cornerBarycentric[corner] = barycentric;
            sourceUv[corner * 2] = 0.0;
            sourceUv[corner * 2 + 1] = 0.0;
            for (int weight = 0; weight < HalfEdgeMesh.TRIANGLE_CORNERS; weight++) {
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
     * One triangle corner's chart position, its slot read through the corner's
     * transform.
     *
     * @param cornerIndex index into the per-corner arrays,
     *                    {@code triangle * 3 + corner}
     * @param x           interleaved slot coordinates
     * @param out         receives the position
     */
    private void cornerPosition(int cornerIndex, double[] x, double[] out) {
        int slot = slotByTriangleCorner[cornerIndex];
        IntegerGridMap.rotate(rotationByTriangleCorner[cornerIndex],
                x[slot * SLOT_COORDINATES], x[slot * SLOT_COORDINATES + 1], out);
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
        cornerPosition(triangle * HalfEdgeMesh.TRIANGLE_CORNERS, dofs.system.solution, origin);
        cornerPosition(triangle * HalfEdgeMesh.TRIANGLE_CORNERS + 1, dofs.system.solution, along);
        cornerPosition(triangle * HalfEdgeMesh.TRIANGLE_CORNERS + 2, dofs.system.solution, across);
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
        operatorByTriangle[at + HalfEdgeMesh.TRIANGLE_CORNERS] = -(secondRowSecond + secondRowThird);
        operatorByTriangle[at + HalfEdgeMesh.TRIANGLE_CORNERS + 1] = secondRowSecond;
        operatorByTriangle[at + HalfEdgeMesh.TRIANGLE_CORNERS + 2] = secondRowThird;
    }

    /**
     * Builds the invariant assembly structures: the sorted upper-triangle key set,
     * the scatter table sending each triangle's Hessian entries to their slots, and
     * the fixed-variable mask. Slots no gathered triangle touches are held.
     */
    private void buildSparsityPattern() {
        boolean[] touchedBySlot = new boolean[dofs.slotCount];
        for (int corner = 0; corner < triangleCount * HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
            touchedBySlot[slotByTriangleCorner[corner]] = true;
        }
        for (int slot = 0; slot < dofs.slotCount; slot++) {
            boolean fixed = dofs.system.frozen[slot * SLOT_COORDINATES] || !touchedBySlot[slot];
            dofs.system.frozen[slot * SLOT_COORDINATES] = fixed;
            dofs.system.frozen[slot * SLOT_COORDINATES + 1] = fixed;
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
        for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
            int slot = slotByTriangleCorner[triangle * HalfEdgeMesh.TRIANGLE_CORNERS + corner];
            variable[corner] = slot * SLOT_COORDINATES;
            variable[HalfEdgeMesh.TRIANGLE_CORNERS + corner] = slot * SLOT_COORDINATES + 1;
        }
    }

    /**
     * Assembles the projected Hessian and negative gradient at x over every
     * triangle on the worker pool, reduced in fixed chunk order so the result is
     * deterministic run-to-run (SPH17's composite majorization).
     *
     * @param x              interleaved slot coordinates
     * @param diagonal       receives the Hessian diagonal
     * @param upperValuesOut receives strict-upper values matching the key set
     * @param rightHandSide  receives the negative gradient
     */
    public void assembleInto(double[] x, double[] diagonal, double[] upperValuesOut,
            double[] rightHandSide) {
        int chunk = (triangleCount + assemblyWorkerCount - 1) / assemblyWorkerCount;
        Runnable[] tasks = new Runnable[assemblyWorkerCount];
        for (int worker = 0; worker < assemblyWorkerCount; worker++) {
            int workerIndex = worker;
            int firstTriangle = Math.min(triangleCount, worker * chunk);
            int endTriangle = Math.min(triangleCount, firstTriangle + chunk);
            tasks[worker] = () -> accumulateTriangleRange(workerIndex, firstTriangle, endTriangle, x);
        }
        assemblyPool.runAll(tasks, "Newton assembly");
        for (int worker = 0; worker < assemblyWorkerCount; worker++) {
            double[] workerRightHandSide = rightHandSideByWorker[worker];
            double[] workerDiagonal = diagonalByWorker[worker];
            double[] workerUpperValues = upperValuesByWorker[worker];
            for (int index = 0; index < diagonal.length; index++) {
                rightHandSide[index] += workerRightHandSide[index];
                diagonal[index] += workerDiagonal[index];
            }
            for (int index = 0; index < upperValuesOut.length; index++) {
                upperValuesOut[index] += workerUpperValues[index];
            }
        }
    }

    /**
     * Evaluates and scatters a contiguous triangle range into one worker's
     * accumulation buffers; every mutable it touches is confined to that worker.
     *
     * @param worker        worker index owning the buffers
     * @param firstTriangle first triangle of the range, inclusive
     * @param endTriangle   end of the range, exclusive
     * @param x             interleaved slot coordinates
     */
    private void accumulateTriangleRange(int worker, int firstTriangle, int endTriangle,
            double[] x) {
        SymmetricDirichletEnergy element = elementByWorker[worker];
        double[] rightHandSide = rightHandSideByWorker[worker];
        double[] diagonal = diagonalByWorker[worker];
        double[] upperValuesLocal = upperValuesByWorker[worker];
        Arrays.fill(rightHandSide, 0.0);
        Arrays.fill(diagonal, 0.0);
        Arrays.fill(upperValuesLocal, 0.0);
        double[] targetX = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        double[] targetY = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        double[] operator = new double[OPERATOR_SIZE];
        double[] position = new double[SLOT_COORDINATES];
        int[] variable = new int[SymmetricDirichletEnergy.VARIABLES];
        int at = firstTriangle * SymmetricDirichletEnergy.VARIABLES
                * SymmetricDirichletEnergy.VARIABLES;
        for (int triangle = firstTriangle; triangle < endTriangle; triangle++) {
            System.arraycopy(operatorByTriangle, triangle * OPERATOR_SIZE, operator, 0,
                    OPERATOR_SIZE);
            for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
                cornerPosition(triangle * HalfEdgeMesh.TRIANGLE_CORNERS + corner, x, position);
                targetX[corner] = position[0];
                targetY[corner] = position[1];
            }
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
                        upperValuesLocal[destination] += element.hessian[row][column];
                    }
                }
            }
        }
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
        for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
            int rotation = rotationByTriangleCorner[triangle * HalfEdgeMesh.TRIANGLE_CORNERS + corner];
            if (rotation == 0) {
                continue;
            }
            int inverse = IntegerGridMap.QUARTER_TURNS - rotation;
            int second = HalfEdgeMesh.TRIANGLE_CORNERS + corner;
            double atFirst = element.gradient[corner];
            double atSecond = element.gradient[second];
            if (inverse == 1) {
                element.gradient[corner] = -atSecond;
                element.gradient[second] = atFirst;
            } else if (inverse == 2) {
                element.gradient[corner] = -atFirst;
                element.gradient[second] = -atSecond;
            } else {
                element.gradient[corner] = atSecond;
                element.gradient[second] = -atFirst;
            }
            for (int column = 0; column < SymmetricDirichletEnergy.VARIABLES; column++) {
                atFirst = element.hessian[corner][column];
                atSecond = element.hessian[second][column];
                if (inverse == 1) {
                    element.hessian[corner][column] = -atSecond;
                    element.hessian[second][column] = atFirst;
                } else if (inverse == 2) {
                    element.hessian[corner][column] = -atFirst;
                    element.hessian[second][column] = -atSecond;
                } else {
                    element.hessian[corner][column] = atSecond;
                    element.hessian[second][column] = -atFirst;
                }
            }
        }
        for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
            int rotation = rotationByTriangleCorner[triangle * HalfEdgeMesh.TRIANGLE_CORNERS + corner];
            if (rotation == 0) {
                continue;
            }
            int inverse = IntegerGridMap.QUARTER_TURNS - rotation;
            int second = HalfEdgeMesh.TRIANGLE_CORNERS + corner;
            for (int row = 0; row < SymmetricDirichletEnergy.VARIABLES; row++) {
                double atFirst = element.hessian[row][corner];
                double atSecond = element.hessian[row][second];
                if (inverse == 1) {
                    element.hessian[row][corner] = -atSecond;
                    element.hessian[row][second] = atFirst;
                } else if (inverse == 2) {
                    element.hessian[row][corner] = -atFirst;
                    element.hessian[row][second] = -atSecond;
                } else {
                    element.hessian[row][corner] = atSecond;
                    element.hessian[row][second] = -atFirst;
                }
            }
        }
    }

    /**
     * The largest step along a direction before any triangle's grid area reaches
     * zero, from each triangle's quadratic {@code det(t) = c + b·t + a·t²} with
     * {@code c > 0} — SS15 §3.3's maximal non-inverting step.
     *
     * @param x     interleaved slot coordinates
     * @param delta the displacement of every coordinate
     * @return the smallest positive root over all triangles, infinite when none
     *         degenerates
     */
    public double maximumStep(double[] x, double[] delta) {
        int chunk = (triangleCount + assemblyWorkerCount - 1) / assemblyWorkerCount;
        double[] rootByWorker = new double[assemblyWorkerCount];
        int[] triangleByWorker = new int[assemblyWorkerCount];
        Runnable[] tasks = new Runnable[assemblyWorkerCount];
        for (int worker = 0; worker < assemblyWorkerCount; worker++) {
            int workerIndex = worker;
            int firstTriangle = Math.min(triangleCount, worker * chunk);
            int endTriangle = Math.min(triangleCount, firstTriangle + chunk);
            tasks[worker] = () -> maximumStepOfRange(workerIndex,
                    firstTriangle, endTriangle, x, delta, rootByWorker, triangleByWorker);
        }
        assemblyPool.runAll(tasks, "maximal-step");
        double alphaMax = Double.POSITIVE_INFINITY;
        for (int worker = 0; worker < assemblyWorkerCount; worker++) {
            if (rootByWorker[worker] < alphaMax) {
                alphaMax = rootByWorker[worker];
            }
        }
        return alphaMax;
    }

    /**
     * The smallest positive degeneracy root over one contiguous triangle range,
     * written into the worker's reduction slots; thread-confined.
     *
     * @param worker           worker index owning the reduction slots
     * @param firstTriangle    first triangle of the range, inclusive
     * @param endTriangle      end of the range, exclusive
     * @param delta            the displacement of every coordinate
     * @param rootByWorker     receives the range's smallest root
     * @param triangleByWorker receives the triangle setting that root
     */
    private void maximumStepOfRange(int worker, int firstTriangle, int endTriangle, double[] x,
            double[] delta, double[] rootByWorker, int[] triangleByWorker) {
        double alphaMax = Double.POSITIVE_INFINITY;
        int blocking = -1;
        double[] origin = new double[SLOT_COORDINATES];
        double[] along = new double[SLOT_COORDINATES];
        double[] across = new double[SLOT_COORDINATES];
        double[] originMove = new double[SLOT_COORDINATES];
        double[] alongMove = new double[SLOT_COORDINATES];
        double[] acrossMove = new double[SLOT_COORDINATES];
        for (int triangle = firstTriangle; triangle < endTriangle; triangle++) {
            cornerPosition(triangle * HalfEdgeMesh.TRIANGLE_CORNERS, x, origin);
            cornerPosition(triangle * HalfEdgeMesh.TRIANGLE_CORNERS + 1, x, along);
            cornerPosition(triangle * HalfEdgeMesh.TRIANGLE_CORNERS + 2, x, across);
            cornerMove(triangle * HalfEdgeMesh.TRIANGLE_CORNERS, delta, originMove);
            cornerMove(triangle * HalfEdgeMesh.TRIANGLE_CORNERS + 1, delta, alongMove);
            cornerMove(triangle * HalfEdgeMesh.TRIANGLE_CORNERS + 2, delta, acrossMove);
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
                blocking = triangle;
            }
        }
        rootByWorker[worker] = alphaMax;
        triangleByWorker[worker] = blocking;
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
     * The energy of every gathered triangle at one set of slot values, summed on
     * the worker pool in fixed chunk order so the result is deterministic.
     *
     * @param x interleaved slot coordinates
     * @return the summed energy, infinite when any triangle has folded
     */
    public double totalEnergy(double[] x) {
        int chunk = (triangleCount + assemblyWorkerCount - 1) / assemblyWorkerCount;
        double[] energyByWorker = new double[assemblyWorkerCount];
        Runnable[] tasks = new Runnable[assemblyWorkerCount];
        for (int worker = 0; worker < assemblyWorkerCount; worker++) {
            int workerIndex = worker;
            int firstTriangle = Math.min(triangleCount, worker * chunk);
            int endTriangle = Math.min(triangleCount, firstTriangle + chunk);
            tasks[worker] = () -> energyByWorker[workerIndex] = energyOfTriangleRange(workerIndex, firstTriangle,
                    endTriangle, x);
        }
        assemblyPool.runAll(tasks, "trial-energy");
        double total = 0.0;
        for (int worker = 0; worker < assemblyWorkerCount; worker++) {
            total += energyByWorker[worker];
        }
        return total;
    }

    /**
     * The summed energy of one contiguous triangle range, using the worker's own
     * evaluator; thread-confined.
     *
     * @param worker        worker index owning the evaluator
     * @param firstTriangle first triangle of the range, inclusive
     * @param endTriangle   end of the range, exclusive
     * @param x             interleaved slot coordinates
     * @return the range's summed energy, infinite when a triangle has folded
     */
    private double energyOfTriangleRange(int worker, int firstTriangle, int endTriangle,
            double[] x) {
        SymmetricDirichletEnergy element = elementByWorker[worker];
        double[] targetX = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        double[] targetY = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        double[] operator = new double[OPERATOR_SIZE];
        double[] position = new double[SLOT_COORDINATES];
        double total = 0.0;
        for (int triangle = firstTriangle; triangle < endTriangle; triangle++) {
            System.arraycopy(operatorByTriangle, triangle * OPERATOR_SIZE, operator, 0,
                    OPERATOR_SIZE);
            for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
                cornerPosition(triangle * HalfEdgeMesh.TRIANGLE_CORNERS + corner, x, position);
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
