package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.solver.DirectSolver;
import ixdar.geometry.mesh.quadlayout.solver.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.OrderingMethod;

/**
 * LCBK19 §6.2's re-parametrization: the vertices the per-patch maps pinned are
 * freed and the whole grid map is relaxed by projected Newton, so patch
 * boundaries leave their rectangles.
 *
 * <p>
 * See also: LCBK19 Section 6.2, Figure 10e
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
     * Fraction of the analytic fold distance the line search starts at. Starting a
     * fixed fraction short of the wall is what lets the worst determinants grow
     * geometrically instead of by blind halving from a full step.
     */
    public static final double MAX_STEP_MARGIN = 0.95;

    /** Armijo sufficient-decrease coefficient on the directional derivative. */
    public static final double ARMIJO_SLOPE = 1.0e-4;

    /**
     * Consecutive below-{@link #CONVERGENCE} iterations before the relaxation
     * stops.
     */
    public static final int STALL_LIMIT = 200;

    /**
     * Every how many iterations the full Newton system is solved; the rest take the
     * block-diagonal direction, whose per-vertex scaling keeps one wall-limited
     * triangle from capping every other vertex's step.
     */
    public static final int FULL_NEWTON_PERIOD = 20;

    /** Absolute ridge keeping a row with no triangle contribution solvable. */
    public static final double RIDGE_FLOOR = 1.0e-12;

    /**
     * Marquardt damping scaling each Hessian row's own diagonal. Constant: the
     * barrier already forces backtracking near a wall, and raising damping on top
     * collapses the direction into a tiny gradient step.
     */
    public static final double DAMPING = 1.0e-6;

    /**
     * Smallest height a reference triangle keeps, as a fraction of its longest
     * edge. A floor of 1e-9 overflows the energy: operator entries scale as one
     * over the height, and the determinant cancels catastrophically past roughly
     * 1e-6.
     */
    public static final double REFERENCE_HEIGHT_FLOOR = 1.0e-4;

    /** Max grid-units any single slot may move in one iteration. */
    public static final double STEP_CAP = 0.5;

    private static final int KEY_ROW_SHIFT = 32;

    public final GridMapDofSystem dofs;
    public final GlobalGridMap gridMap;

    /**
     * The parametrization the relaxed map is fitted to. It already carries the
     * quantization's compression, so its Jacobian to the grid is near the identity;
     * the raw surface metric is not a usable reference because no patch can be
     * mapped isometrically onto its integer rectangle.
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

    /**
     * Patches the relaxation is restricted to; null relaxes the whole map. A focus
     * turns the solve local: only the focus patches' triangles enter the energy,
     * every slot outside them is held, and the map elsewhere is untouched.
     */
    public Set<Integer> focusPatchIds;

    /** Symmetric Dirichlet energy of the grid map before the relaxation. */
    public double energyBefore;

    /** Energy after it; the relaxation never raises this. */
    public double energyAfter;

    /** Iterations actually run. */
    public int iterationCount;

    /**
     * Triangles folded over in the grid at the end; the barrier must keep this at
     * zero.
     */
    public int flippedTriangleCount;

    /** Largest distance any vertex moved in the grid. */
    public double worstVertexMove;

    /**
     * Step the line search accepted on the last iteration, zero when it gave up.
     */
    public double acceptedStep;

    /**
     * Area factor applied to the reference so it matches the grid's overall scale.
     */
    public double referenceScale = 1.0;

    /** Gradient operator of every triangle, six entries each, in triangle order. */
    private double[] operatorByTriangle;

    /** Source area of every triangle, in triangle order. */
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

    /** Triangles gathered from all live patches. */
    private int triangleCount;

    /**
     * Triangles with no source area or no grid area, which carry no measurable
     * distortion.
     */
    private int skippedTriangleCount;

    /** Triangles whose corners could not be read in the parametrization. */
    private int unreadableSourceCount;

    /** Triangles whose parametrization area runs opposite to their grid area. */
    private int oppositeOrientationCount;

    /** Triangles whose parametrization shape collapsed to a single point. */
    private int degenerateReferenceCount;

    /** Triangles whose sliver reference was lifted to the height floor. */
    private int regularizedReferenceCount;

    /** Triangles with all three corners held, whose energy no step can change. */
    private int pinnedTriangleCount;

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

    /** Fold distance of the last Newton direction, before the margin. */
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

    /** Owning patch of every gathered triangle, in triangle order. */
    private int[] patchByTriangle;

    private double[] targetByTriangle;

    private int degenerateGridCount;

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
     * Patches owning a triangle whose grid area is below the threshold relative to
     * its reference area. Gathers the triangles if build() has not run yet; safe to
     * call on a probe instance that is then discarded.
     *
     * @param ratioThreshold grid-to-reference det ratio below which a triangle
     *                       counts crushed
     * @return the ids of patches containing at least one crushed triangle
     */
    public Set<Integer> findCrushedPatches(double ratioThreshold) {
        if (operatorByTriangle == null) {
            gatherTriangles();
        }
        Set<Integer> sick = new HashSet<>();
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            double ratio = gridSignedArea(triangle) / (2.0 * areaByTriangle[triangle]);
            if (ratio < ratioThreshold) {
                sick.add(patchByTriangle[triangle]);
            }
        }
        return sick;
    }

    /**
     * Relaxes every free coordinate by projected Newton on the symmetric Dirichlet
     * energy, holding the layout's nodes and seams, then writes the result back
     * into the grid map.
     *
     * @return this, solved
     */
    public GridMapOptimizer build() {
        if (timeBudgetMilliseconds <= 0) {
            System.out.println("[grid-optimize] skipped: no time budget");
            return this;
        }
        if (operatorByTriangle == null) {
            gatherTriangles();
        }
        buildSparsityPattern();
        ParameterizationEnergy element = new ParameterizationEnergy();
        double[] startU = dofs.slotU.clone();
        double[] startV = dofs.slotV.clone();

        energyBefore = totalEnergy(element, dofs.slotU, dofs.slotV);
        long[] worst = new long[10]; // energy bits << — just do it simply:
        double[] topEnergy = new double[10];
        int[] topTriangle = new int[10];
        double[] tx = new double[3], ty = new double[3], op = new double[6];
        for (int t = 0; t < triangleCount; t++) {
            loadTriangle(t, op, tx, ty);
            System.arraycopy(targetByTriangle, t * 4, element.target, 0, 4);
            double e = element.energyOnly(op, areaByTriangle[t], tx, ty);
            for (int k = 0; k < 10; k++) {
                if (e > topEnergy[k]) {
                    System.arraycopy(topEnergy, k, topEnergy, k + 1, 9 - k);
                    System.arraycopy(topTriangle, k, topTriangle, k + 1, 9 - k);
                    topEnergy[k] = e;
                    topTriangle[k] = t;
                    break;
                }
            }
        }
        for (int k = 0; k < 10; k++) {
            System.out.printf("[grid-optimize] top%d triangle=%d energy=%.3e det=%.3e%n",
                    k, topTriangle[k], topEnergy[k],
                    gridSignedArea(topTriangle[k]) / (2.0 * areaByTriangle[topTriangle[k]]));
        }
        reportStartingDistortion();
        double energy = energyBefore;
        long startedAt = System.currentTimeMillis();
        for (int iteration = 0; iteration < maxIterations
                && System.currentTimeMillis() - startedAt < timeBudgetMilliseconds; iteration++) {
            double[] delta = iteration % FULL_NEWTON_PERIOD == 0 ? newtonDirection(element)
                    : blockDirection(element);
            if (delta == null) {
                break;
            }
            double largestDelta = 0.0;
            for (double component : delta) {
                largestDelta = Math.max(largestDelta, Math.abs(component));
            }
            clampStep(delta); // kills 1e8 junk components
            foldClamp(delta);
            double stepped = takeStep(element, delta, energy);
            if (acceptedStep == 0.0 && iteration % FULL_NEWTON_PERIOD == 0) {
                // Newton direction rejected outright; retry the iteration with the block
                // direction.
                delta = blockDirection(element);
                if (delta != null) {
                    clampStep(delta); // kills 1e8 junk components
                    foldClamp(delta);
                    stepped = takeStep(element, delta, energy);
                }
            }
            iterationCount++;
            System.out.printf("[grid-optimize]   iteration %d energy %.6e (%.3f%%)"
                    + " maxDelta=%.3e step=%.3e alphaMax=%.3e blocking=%d det=%.3e%n", iteration,
                    stepped, 100.0 * (energy - stepped) / Math.max(1.0e-30, energy), largestDelta,
                    acceptedStep, lastAlphaMax, blockingTriangle,
                    gridSignedArea(blockingTriangle) / (2.0 * areaByTriangle[blockingTriangle]));
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
        System.out.printf("[grid-optimize] energy %.4e -> %.4e (%.1f%%) iterations=%d flipped=%d/%d"
                + " skipped=%d(unreadable=" + unreadableSourceCount + " degenerate="
                + degenerateReferenceCount + ") pinned=" + pinnedTriangleCount + " reflected="
                + oppositeOrientationCount + " degenerate= " + degenerateGridCount + " regularized="
                + regularizedReferenceCount
                + " worstMove=%.4f%n", energyBefore, energyAfter,
                100.0 * (energyBefore - energyAfter) / Math.max(1.0e-30, energyBefore),
                iterationCount, flippedTriangleCount, triangleCount, skippedTriangleCount,
                worstVertexMove);
        return this;
    }

    /**
     * Reports how squashed the starting map already is, as the spread of each
     * triangle's grid area against its area in the parametrization. A minimum near
     * zero means the barrier starts against a wall.
     */
    private void reportStartingDistortion() {
        double[] ratios = new double[triangleCount];
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            ratios[triangle] = gridSignedArea(triangle) / (2.0 * areaByTriangle[triangle]);
        }
        double[] areas = new double[triangleCount];
        System.arraycopy(areaByTriangle, 0, areas, 0, triangleCount);
        Arrays.sort(ratios);
        Arrays.sort(areas);
        System.out.printf("[grid-optimize] starting area ratio min=%.3e p01=%.3e p50=%.3e"
                + " p99=%.3e max=%.3e%n", ratios[0], ratios[triangleCount / 100],
                ratios[triangleCount / 2], ratios[triangleCount * 99 / 100],
                ratios[triangleCount - 1]);
        System.out.printf("[grid-optimize] reference area min=%.3e p01=%.3e p50=%.3e p99=%.3e"
                + " max=%.3e%n", areas[0], areas[triangleCount / 100], areas[triangleCount / 2],
                areas[triangleCount * 99 / 100], areas[triangleCount - 1]);
    }

    private void foldClamp(double[] delta) {
        double[] limit = new double[dofs.slotCount];
        Arrays.fill(limit, Double.POSITIVE_INFINITY);
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            double alpha = foldDistance(triangle, delta); // per-triangle, from maximumStep's body
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int slot = slotByTriangleCorner[triangle * TRIANGLE_CORNERS + corner];
                limit[slot] = Math.min(limit[slot], alpha);
            }
        }
        for (int slot = 0; slot < dofs.slotCount; slot++) {
            double scale = Math.min(1.0, 0.8 * limit[slot]);
            delta[slot * SLOT_COORDINATES] *= scale;
            delta[slot * SLOT_COORDINATES + 1] *= scale;
        }
        recomputeGradientDot(delta); // same as clampStep's tail
    }

    /**
     * The step multiple at which one triangle's grid area reaches zero along the
     * direction, from the quadratic {@code det(alpha) = a*alpha^2 + b*alpha + c}.
     *
     * @return smallest positive root, or +infinity when the triangle never folds
     */
    private double foldDistance(int triangle, double[] delta) {
        double[] origin = new double[SLOT_COORDINATES];
        double[] along = new double[SLOT_COORDINATES];
        double[] across = new double[SLOT_COORDINATES];
        cornerPosition(triangle * TRIANGLE_CORNERS, dofs.slotU, dofs.slotV, origin);
        cornerPosition(triangle * TRIANGLE_CORNERS + 1, dofs.slotU, dofs.slotV, along);
        cornerPosition(triangle * TRIANGLE_CORNERS + 2, dofs.slotU, dofs.slotV, across);
        double[] originMove = new double[SLOT_COORDINATES];
        double[] alongMove = new double[SLOT_COORDINATES];
        double[] acrossMove = new double[SLOT_COORDINATES];
        cornerMove(triangle * TRIANGLE_CORNERS, delta, originMove);
        cornerMove(triangle * TRIANGLE_CORNERS + 1, delta, alongMove);
        cornerMove(triangle * TRIANGLE_CORNERS + 2, delta, acrossMove);
        double e1u = along[0] - origin[0];
        double e1v = along[1] - origin[1];
        double e2u = across[0] - origin[0];
        double e2v = across[1] - origin[1];
        double d1u = alongMove[0] - originMove[0];
        double d1v = alongMove[1] - originMove[1];
        double d2u = acrossMove[0] - originMove[0];
        double d2v = acrossMove[1] - originMove[1];
        double c = e1u * e2v - e1v * e2u; // current det, positive
        double b = e1u * d2v - e1v * d2u + d1u * e2v - d1v * e2u;
        double a = d1u * d2v - d1v * d2u;
        double root;
        if (Math.abs(a) < 1.0e-30) {
            root = b < 0.0 ? -c / b : Double.POSITIVE_INFINITY;
        } else {
            double discriminant = b * b - 4.0 * a * c;
            if (discriminant < 0.0) {
                root = Double.POSITIVE_INFINITY;
            } else {
                double sqrt = Math.sqrt(discriminant);
                double first = (-b - sqrt) / (2.0 * a);
                double second = (-b + sqrt) / (2.0 * a);
                root = Double.POSITIVE_INFINITY;
                if (first > 0.0) {
                    root = first;
                }
                if (second > 0.0 && second < root) {
                    root = second;
                }
            }
        }
        return root;
    }

    /** Refreshes the Armijo slope after the direction was rescaled per slot. */
    private void recomputeGradientDot(double[] delta) {
        gradientDotDirection = 0.0;
        for (int index = 0; index < delta.length && index < gradientScratch.length; index++) {
            gradientDotDirection += gradientScratch[index] * delta[index];
        }
        gradientDotDirection = Math.min(0.0, gradientDotDirection);
    }

    /**
     * Flattens every live patch's triangles into one list, taking each one's
     * reference shape from the seamless parametrization so the energy measures
     * distortion against it.
     */
    private void gatherTriangles() {
        for (EmbeddedPatch patch : gridMap.tmesh.patches) {
            if (patch.alive && inFocus(patch.patchId)) {
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
        targetByTriangle = new double[triangleCount * 4];
        int index = 0;
        for (EmbeddedPatch patch : gridMap.tmesh.patches) {
            if (!patch.alive || !inFocus(patch.patchId)) {
                continue;
            }
            PatchRectangleMap map = gridMap.patchMaps.mapByPatchId[patch.patchId];
            int[] slotByDense = dofs.slotByPatchDense[patch.patchId];
            int[] rotationByDense = dofs.rotationByPatchDense[patch.patchId];
            double[] translationUByDense = dofs.translationUByPatchDense[patch.patchId];
            double[] translationVByDense = dofs.translationVByPatchDense[patch.patchId];
            double[] uv = gridMap.uvByPatchId[patch.patchId];
            List<Integer> regionFaces = gridMap.patchMaps.regions.copyFacesByPatch
                    .get(patch.patchId);
            // The energy is undefined on a triangle with no area to measure distortion
            // against.
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
                int first = triangle[1];
                int second = triangle[2];

                if (uvSignedArea(uv, triangle[0], first, second) < 0.0) {
                    first = triangle[2];
                    second = triangle[1];
                }
                if (!sourceCorners(map, regionFaces.get(local), triangle[0], first, second)) {
                    skippedTriangleCount++;
                    unreadableSourceCount++;
                    continue;
                }
                double firstEdgeU = sourceUv[2] - sourceUv[0];
                double firstEdgeV = sourceUv[3] - sourceUv[1];
                double secondEdgeU = sourceUv[4] - sourceUv[0];
                double secondEdgeV = sourceUv[5] - sourceUv[1];
                double determinant = firstEdgeU * secondEdgeV - secondEdgeU * firstEdgeV;
                double gridArea = uvSignedArea(uv, triangle[0], first, second);
                if (gridArea < 1.0e-8 * Math.abs(determinant)) { // grid det ratio below noise
                    degenerateGridCount++;
                    skippedTriangleCount++;
                    continue;
                }
                if (determinant < 0.0) {
                    // Reflecting the reference leaves the distortion it measures unchanged, since
                    // the energy sees only the square of the determinant.
                    oppositeOrientationCount++;
                    firstEdgeV = -firstEdgeV;
                    secondEdgeV = -secondEdgeV;
                    determinant = -determinant;
                }
                double thirdEdgeU = secondEdgeU - firstEdgeU;
                double thirdEdgeV = secondEdgeV - firstEdgeV;
                double longestEdgeSquared = Math.max(
                        firstEdgeU * firstEdgeU + firstEdgeV * firstEdgeV,
                        Math.max(secondEdgeU * secondEdgeU + secondEdgeV * secondEdgeV,
                                thirdEdgeU * thirdEdgeU + thirdEdgeV * thirdEdgeV));
                if (longestEdgeSquared == 0.0) {
                    degenerateReferenceCount++;
                    skippedTriangleCount++;
                    continue;
                }
                double targetDeterminant = REFERENCE_HEIGHT_FLOOR * longestEdgeSquared;
                if (determinant < targetDeterminant) {
                    regularizedReferenceCount++;
                    double firstSquared = firstEdgeU * firstEdgeU + firstEdgeV * firstEdgeV;
                    double secondSquared = secondEdgeU * secondEdgeU + secondEdgeV * secondEdgeV;
                    // Lift the shorter edge perpendicular to the longer, so the triangle fattens
                    // to the floor without moving its long direction.
                    if (firstSquared >= secondSquared) {
                        double lift = (targetDeterminant - determinant) / firstSquared;
                        secondEdgeU -= firstEdgeV * lift;
                        secondEdgeV += firstEdgeU * lift;
                    } else {
                        double lift = (targetDeterminant - determinant) / secondSquared;
                        firstEdgeU += secondEdgeV * lift;
                        firstEdgeV -= secondEdgeU * lift;
                    }
                    determinant = targetDeterminant;
                }
                if (uvSignedArea(uv, triangle[0], first, second) <= 0.0) {
                    skippedTriangleCount++;
                    continue;
                }
                int[] corners = { triangle[0], first, second };
                for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                    int at = index * TRIANGLE_CORNERS + corner;
                    slotByTriangleCorner[at] = slotByDense[corners[corner]];
                    rotationByTriangleCorner[at] = rotationByDense[corners[corner]];
                    translationUByTriangleCorner[at] = translationUByDense[corners[corner]];
                    translationVByTriangleCorner[at] = translationVByDense[corners[corner]];
                }
                areaByTriangle[index] = 0.5 * determinant;
                fillOperator(index, firstEdgeU, firstEdgeV, secondEdgeU, secondEdgeV, determinant);
                patchByTriangle[index] = patch.patchId;
                index++;
            }
        }
        triangleCount = index;
        normalizeReferenceScale();
        double[] operator = new double[OPERATOR_SIZE];
        double[] tx = new double[TRIANGLE_CORNERS];
        double[] ty = new double[TRIANGLE_CORNERS];
        int kept = 0;
        for (int t = 0; t < triangleCount; t++) {
            loadTriangle(t, operator, tx, ty);
            double j00 = operator[0] * tx[0] + operator[1] * tx[1] + operator[2] * tx[2];
            double j01 = operator[3] * tx[0] + operator[4] * tx[1] + operator[5] * tx[2];
            double j10 = operator[0] * ty[0] + operator[1] * ty[1] + operator[2] * ty[2];
            double j11 = operator[3] * ty[0] + operator[4] * ty[1] + operator[5] * ty[2];
            if (j00 * j11 - j01 * j10 <= 1.0e-9) {
                degenerateGridCount++;
                continue; // drop: don't copy to slot 'kept'
            }
            double angle = Math.atan2(j10 - j01, j00 + j11);
            double snapped = Math.round(angle / (0.5 * Math.PI)) * 0.5 * Math.PI;
            double cos = Math.cos(snapped);
            double sin = Math.sin(snapped);
            targetByTriangle[t * 4] = cos;
            targetByTriangle[t * 4 + 1] = -sin;
            targetByTriangle[t * 4 + 2] = sin;
            targetByTriangle[t * 4 + 3] = cos;
            copyTriangle(t, kept); // compact all per-triangle arrays t -> kept
            kept++;
        }

        triangleCount = kept;
    }

    /**
     * Compacts one gathered triangle's slices from index {@code from} to
     * {@code to}.
     */
    private void copyTriangle(int from, int to) {
        if (from == to) {
            return;
        }
        System.arraycopy(operatorByTriangle, from * OPERATOR_SIZE,
                operatorByTriangle, to * OPERATOR_SIZE, OPERATOR_SIZE);
        areaByTriangle[to] = areaByTriangle[from];
        patchByTriangle[to] = patchByTriangle[from];
        System.arraycopy(slotByTriangleCorner, from * TRIANGLE_CORNERS,
                slotByTriangleCorner, to * TRIANGLE_CORNERS, TRIANGLE_CORNERS);
        System.arraycopy(rotationByTriangleCorner, from * TRIANGLE_CORNERS,
                rotationByTriangleCorner, to * TRIANGLE_CORNERS, TRIANGLE_CORNERS);
        System.arraycopy(translationUByTriangleCorner, from * TRIANGLE_CORNERS,
                translationUByTriangleCorner, to * TRIANGLE_CORNERS, TRIANGLE_CORNERS);
        System.arraycopy(translationVByTriangleCorner, from * TRIANGLE_CORNERS,
                translationVByTriangleCorner, to * TRIANGLE_CORNERS, TRIANGLE_CORNERS);
        System.arraycopy(targetByTriangle, from * 4, targetByTriangle, to * 4, 4);
    }

    /**
     * Brings the reference to the grid's overall scale, so the energy measures
     * shape rather than the uniform shrink the quantization applied.
     *
     * <p>
     * No vertex motion can remove a global scale factor, so leaving it in place
     * would spend every step on it.
     */
    private void normalizeReferenceScale() {
        if (triangleCount == 0) {
            return;
        }
        double[] ratios = new double[triangleCount];
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            ratios[triangle] = gridSignedArea(triangle) / (2.0 * areaByTriangle[triangle]);
        }
        Arrays.sort(ratios);
        referenceScale = ratios[triangleCount / 2];
        if (!(referenceScale > 0.0)) {
            referenceScale = 1.0;
            return;
        }
        double edgeScale = Math.sqrt(referenceScale);
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            areaByTriangle[triangle] *= referenceScale;
            for (int entry = 0; entry < OPERATOR_SIZE; entry++) {
                operatorByTriangle[triangle * OPERATOR_SIZE + entry] /= edgeScale;
            }
        }
        double total = 0.0;
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            total += areaByTriangle[triangle];
        }
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            areaByTriangle[triangle] /= total;
        }
    }

    /**
     * Reads one copy triangle's three corners in the seamless parametrization, all
     * within the one source face the copy face came from so no transition is
     * involved.
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
            int second) {
        int sourceFace = gridMap.tmesh.topology.sourceFaceByCopyFace[copyFace];
        if (sourceFace == EmbeddedMeshTopology.UNCLAIMED) {
            return false;
        }
        seamless.faceCornerUv(sourceFace, faceCornerUv);
        int[] corners = { origin, first, second };
        for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
            double[] barycentric = gridMap.tmesh.topology.barycentricOf(sourceFace,
                    map.vertexLabel[corners[corner]]);
            if (barycentric == null) {
                return false;
            }
            sourceUv[corner * 2] = 0.0;
            sourceUv[corner * 2 + 1] = 0.0;
            for (int weight = 0; weight < TRIANGLE_CORNERS; weight++) {
                sourceUv[corner * 2] += barycentric[weight] * faceCornerUv[weight * 2];
                sourceUv[corner * 2 + 1] += barycentric[weight] * faceCornerUv[weight * 2 + 1];
            }
        }
        return true;
    }

    /**
     * Writes sourceUv from the copy triangle's intrinsic 3D shape: first corner at
     * the origin, second on the x axis, third above it. Always positively oriented.
     */
    private boolean surfaceCorners(PatchRectangleMap map, int origin, int first, int second) {
        var copy = gridMap.tmesh.topology.copy; // working-copy HalfEdgeMesh
        // adapt these three lines to your position accessor:
        Vector3f p0 = copy.vertexPosition(map.vertexLabel[origin]);
        Vector3f p1 = copy.vertexPosition(map.vertexLabel[first]);
        Vector3f p2 = copy.vertexPosition(map.vertexLabel[second]);
        double e1x = p1.x - p0.x, e1y = p1.y - p0.y, e1z = p1.z - p0.z;
        double e2x = p2.x - p0.x, e2y = p2.y - p0.y, e2z = p2.z - p0.z;
        double len1 = Math.sqrt(e1x * e1x + e1y * e1y + e1z * e1z);
        if (len1 == 0.0) {
            return false;
        }
        double along = (e1x * e2x + e1y * e2y + e1z * e2z) / len1;
        double cx = e1y * e2z - e1z * e2y;
        double cy = e1z * e2x - e1x * e2z;
        double cz = e1x * e2y - e1y * e2x;
        double height = Math.sqrt(cx * cx + cy * cy + cz * cz) / len1;
        sourceUv[0] = 0.0;
        sourceUv[1] = 0.0;
        sourceUv[2] = len1;
        sourceUv[3] = 0.0;
        sourceUv[4] = along;
        sourceUv[5] = height;
        return height > 0.0;
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
     * Jacobian, for a source triangle laid out as
     * {@code (0,0), (base,0), (along,height)}.
     *
     * @param triangle    triangle index
     * @param firstEdgeU  u of the source edge from the first corner to the second
     * @param firstEdgeV  v of that edge
     * @param secondEdgeU u of the source edge from the first corner to the third
     * @param secondEdgeV v of that edge
     * @param determinant the two edges' cross product, zero for a degenerate source
     *                    triangle
     */
    private void fillOperator(int triangle, double firstEdgeU, double firstEdgeV,
            double secondEdgeU, double secondEdgeV, double determinant) {
        int at = triangle * OPERATOR_SIZE;
        if (determinant == 0.0) {
            return;
        }
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
     * Whether a patch's triangles enter the energy, which is every patch without a
     * focus.
     *
     * @param patchId patch to test
     * @return whether the relaxation covers the patch
     */
    private boolean inFocus(int patchId) {
        return focusPatchIds == null || focusPatchIds.contains(patchId);
    }

    /**
     * Builds the invariant assembly structures: the sorted upper-triangle key set,
     * the scatter table sending each triangle's Hessian entries to their slots, and
     * the fixed-variable mask. Slots no gathered triangle touches are held, which
     * is what makes a focus local.
     */
    private void buildSparsityPattern() {
        int size = dofs.slotCount * SLOT_COORDINATES;
        boolean[] touchedBySlot = new boolean[dofs.slotCount];
        for (int corner = 0; corner < triangleCount * TRIANGLE_CORNERS; corner++) {
            touchedBySlot[slotByTriangleCorner[corner]] = true;
        }
        if (focusPatchIds != null) {
            // A slot any non-focus patch holds must stay put: moving it would deform
            // triangles
            // outside the energy, where nothing guards against folds.
            for (EmbeddedPatch patch : gridMap.tmesh.patches) {
                if (!patch.alive || inFocus(patch.patchId)) {
                    continue;
                }
                for (int slot : dofs.slotByPatchDense[patch.patchId]) {
                    touchedBySlot[slot] = false;
                }
            }
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
     * pattern and cached ordering.
     *
     * @param element per-triangle evaluator
     * @return the displacement of every coordinate, or null when the system could
     *         not be solved
     */
    private double[] newtonDirection(ParameterizationEnergy element) {
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
            System.arraycopy(targetByTriangle, triangle * 4, element.target, 0, 4);
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
        // Marquardt scaling: each row is damped relative to its own curvature, so one
        // extreme
        // triangle cannot set a ridge that freezes every other vertex.
        for (int index = 0; index < size; index++) {
            diagonal[index] = diagonal[index] * (1.0 + DAMPING) + RIDGE_FLOOR;
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
    private void rotateElementToSlots(int triangle, ParameterizationEnergy element) {
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
     * The block-diagonal Newton direction: each free slot solves its own
     * {@code 2×2} system over its incident triangles, so a vertex against a fold
     * wall takes a small step without capping anyone else's. The blocks come out
     * positive semi-definite because the element Hessian is already projected.
     *
     * @param element per-triangle evaluator
     * @return the displacement of every coordinate
     */
    private double[] blockDirection(ParameterizationEnergy element) {
        int size = dofs.slotCount * SLOT_COORDINATES;
        double[] gradientBySlot = new double[size];
        double[] blockUu = new double[dofs.slotCount];
        double[] blockUv = new double[dofs.slotCount];
        double[] blockVv = new double[dofs.slotCount];
        double[] targetX = new double[TRIANGLE_CORNERS];
        double[] targetY = new double[TRIANGLE_CORNERS];
        double[] operator = new double[OPERATOR_SIZE];
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            loadTriangle(triangle, operator, targetX, targetY);
            System.arraycopy(targetByTriangle, triangle * 4, element.target, 0, 4);
            element.evaluate(operator, areaByTriangle[triangle], targetX, targetY);
            rotateElementToSlots(triangle, element);
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int slot = slotByTriangleCorner[triangle * TRIANGLE_CORNERS + corner];
                gradientBySlot[slot * SLOT_COORDINATES] += element.gradient[corner];
                gradientBySlot[slot * SLOT_COORDINATES + 1] += element.gradient[TRIANGLE_CORNERS + corner];
                blockUu[slot] += element.hessian[corner][corner];
                blockUv[slot] += element.hessian[corner][TRIANGLE_CORNERS + corner];
                blockVv[slot] += element.hessian[TRIANGLE_CORNERS + corner][TRIANGLE_CORNERS + corner];
            }
        }
        double[] delta = new double[size];
        gradientDotDirection = 0.0;
        for (int slot = 0; slot < dofs.slotCount; slot++) {
            if (fixedByVariable[slot * SLOT_COORDINATES]) {
                continue;
            }
            double uu = blockUu[slot] * (1.0 + DAMPING) + RIDGE_FLOOR;
            double vv = blockVv[slot] * (1.0 + DAMPING) + RIDGE_FLOOR;
            double uv = blockUv[slot];
            double determinant = uu * vv - uv * uv;
            if (determinant <= 0.0) {
                continue;
            }
            double gradientU = gradientBySlot[slot * SLOT_COORDINATES];
            double gradientV = gradientBySlot[slot * SLOT_COORDINATES + 1];
            double moveU = -(vv * gradientU - uv * gradientV) / determinant;
            double moveV = -(uu * gradientV - uv * gradientU) / determinant;
            delta[slot * SLOT_COORDINATES] = moveU;
            delta[slot * SLOT_COORDINATES + 1] = moveV;
            gradientDotDirection += gradientU * moveU + gradientV * moveV;
        }
        gradientScratch = gradientBySlot;
        return delta;
    }

    /**
     * The largest step along a direction before any triangle's grid area reaches
     * zero, from each triangle's quadratic {@code det(t) = c + b·t + a·t²} with
     * {@code c > 0}.
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

    private void clampStep(double[] delta) {
        boolean clamped = false;
        for (int slot = 0; slot < dofs.slotCount; slot++) {
            double du = delta[slot * SLOT_COORDINATES];
            double dv = delta[slot * SLOT_COORDINATES + 1];
            double norm = Math.sqrt(du * du + dv * dv);
            if (norm > STEP_CAP) {
                double scale = STEP_CAP / norm;
                delta[slot * SLOT_COORDINATES] *= scale;
                delta[slot * SLOT_COORDINATES + 1] *= scale;
                clamped = true;
            }
        }
        if (!clamped) {
            return; // gradientDotDirection from the direction method is still valid
        }
        gradientDotDirection = 0.0;
        for (int index = 0; index < delta.length; index++) {
            gradientDotDirection += gradientScratch[index] * delta[index];
        }
    }

    /**
     * Moves along the Newton direction, starting a fixed margin short of the
     * analytic fold distance and backtracking under an Armijo sufficient-decrease
     * test. The fold guard in {@link SymmetricDirichletEnergy#energyOnly} stays as
     * the roundoff backstop.
     *
     * @param element per-triangle evaluator
     * @param delta   the Newton displacement of every coordinate
     * @param current the energy being improved on
     * @return the energy reached
     */
    private double takeStep(ParameterizationEnergy element, double[] delta, double current) {
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
    private double totalEnergy(ParameterizationEnergy element, double[] slotU, double[] slotV) {
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
            System.arraycopy(targetByTriangle, triangle * 4, element.target, 0, 4);
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
}
