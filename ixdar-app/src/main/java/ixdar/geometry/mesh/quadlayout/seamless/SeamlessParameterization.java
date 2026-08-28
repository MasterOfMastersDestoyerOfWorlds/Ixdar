package ixdar.geometry.mesh.quadlayout.seamless;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh.EdgeFaceIds;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.exact.SeamlessProjector;
import ixdar.geometry.mesh.quadlayout.solver.DirectSolver;
import ixdar.geometry.mesh.quadlayout.solver.InteriorPointQp;
import ixdar.geometry.mesh.quadlayout.solver.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.system.GreedyRounding;
import ixdar.geometry.mesh.quadlayout.solver.system.LazyConstraints;
import ixdar.platform.Platforms;

/**
 * Turns a {@link CrossField} into per-corner (u, v) satisfying
 *
 * <pre>
 *   (u', v') = R<sub>r_e · π/2</sub>(u, v) + (s<sub>e</sub>, t<sub>e</sub>)
 * </pre>
 *
 * across every cut edge, with r<sub>e</sub> fixed by the cross field.
 *
 * <p>
 * See also: BZK09 Section 5
 *
 * @see CrossField
 */
@MeshNodeAnnotation(id = "seamless_uv", desktopOnly = true)
public final class SeamlessParameterization implements MeshNode {

    public static final InputPort FIELD = new InputPort("field", PortType.CROSS_FIELD, null);
    public static final OutputPort UV = new OutputPort("uv", PortType.UV_FIELD);
    public static final OutputPort FLIPPED_TRIANGLES = new OutputPort("flipped_triangles", PortType.INT);
    public static final OutputPort INJECTIVE = new OutputPort("injective", PortType.BOOLEAN);
    public static final OutputPort DOFS = new OutputPort("dofs", PortType.DOF_SYSTEM);
    public static final OutputPort CHARTS = new OutputPort("charts", PortType.CHART_ATLAS);

    static final float HALF_PI = (float) (Math.PI / 2.0);
    private static final float HALF = 0.5f;
    private static final double HALF_D = 0.5;
    private static final double DEGENERATE_AREA_EPSILON = 1.0e-30;

    /**
     * A parametric triangle below this fraction of its expected area
     * ({@code faceArea / targetQuadEdgeLength²}) counts as a local-injectivity
     * violation alongside flips, because collapsed triangles merge singularities.
     */
    private static final double DEGENERATE_UV_AREA_FRACTION = 1.0e-6;

    /** The parametrization being built; every durable product lands here. */
    public SeamlessUv uv;

    /** The mesh being parametrized; build scratch. */
    public HalfEdgeMesh mesh;

    /** The cross field being parametrized; build scratch. */
    public CrossField field;

    /** Validation metrics of the last {@link #resolve()}. */
    public ParameterizationMetrics metrics;

    /** Hard cap on lazy-constraint rounds (BCE13 §3.4's outer iterations). */
    public int maxConstraintRounds = 60;

    /**
     * If true, run MC19 (Mandad–Campen 2019) exact-constraint projection after the
     * injectivity-constraint solve. Drives the per-cut-edge transition residual to
     * literal zero, making the output safe to feed into Lyon 2021's T-mesh stage;
     * the BCE13 ε margin absorbs the projection's adjustment (MC19 §7).
     */
    public boolean exactSeams = true;

    /** §5.4 IRLS weights, initialized to 1. */
    public double[] faceWeight;

    /** Per-face 3D area (active-face order). */
    public double[] faceArea;
    /** Length 3 per face: b_i (= ∂φ/∂x coefficients in local frame). */
    public double[] faceShapeB;
    /** Length 3 per face: c_i (= ∂φ/∂y coefficients in local frame). */
    public double[] faceShapeC;
    /** u_T(ξ) x-component in local frame, post branch rotation. */
    public double[] faceUtxLocal;
    /** u_T(ξ) y-component in local frame, post branch rotation. */
    public double[] faceUtyLocal;
    /** v_T(ξ) x-component in local frame, post branch rotation. */
    public double[] faceVtxLocal;
    /** v_T(ξ) y-component in local frame, post branch rotation. */
    public double[] faceVtyLocal;

    /**
     * Soft-pin diagonal weight applied to a rounded integer DOF; passed to
     * {@link SeamlessDofSystem}.
     */
    public double integerPinWeight = 1.0e10;

    /**
     * DOF state + cached assembly plan. Constructed in {@link #build}.
     */
    public SeamlessDofSystem dofSystem;

    /**
     * Native factor of the base system, created by the rounding stage's
     * no-integer-DOFs fast path. Released by the injectivity loop, which owns its
     * own handle on the fixed superset pattern.
     */
    public DirectSolver.CholeskyHandle baseFactorHandle;

    /**
     * Matrix backing {@link #baseFactorHandle}; needed by
     * {@code DirectSolver.solveCompact} when solving through the handle.
     */
    public NormalMatrix baseFactorMatrix;

    /**
     * Target quad edge length, expressed as a fraction of the bounding-box
     * diagonal.
     */
    public float targetEdgeLengthFractionOfBounds = 0.01f;


    /** Last solver output (size {@code dofSystem.dofCount}). */
    private double[] solution;


    @Override
    public List<InputPort> inputs() {
        return List.of(FIELD);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(UV, FLIPPED_TRIANGLES, INJECTIVE, DOFS, CHARTS);
    }

    @Override
    public String description() {
        return "Builds the seamless parametrization over a cross field, reporting whether the"
                + " result is injective and how many UV triangles flipped.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                FIELD.name, "Cross field to parametrize, from a cross_field node.",
                UV.name, "The seamless parametrization with per-corner UVs.",
                FLIPPED_TRIANGLES.name, "Number of UV triangles with negative signed area.",
                INJECTIVE.name, "Whether the parametrization is injective.",
                DOFS.name, "The parametrization solve's DOF system.",
                CHARTS.name, "The per-face charts and the cut transitions between them."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        CrossField inputField = (CrossField) ctx.getInput(FIELD.name, Object.class);
        SeamlessParameterization stage = new SeamlessParameterization();
        SeamlessUv built = stage.build(inputField);
        ctx.setOutput(UV.name, built);
        ctx.setOutput(FLIPPED_TRIANGLES.name, stage.metrics.flippedTriangleCount);
        ctx.setOutput(INJECTIVE.name, built.injective);
        ctx.setOutput(DOFS.name, stage.dofSystem.system);
        ctx.setOutput(CHARTS.name, built.cutGraph.atlas);
    }

    /**
     * Run the BZK09 §5 pipeline; populate the public output arrays.
     *
     * @throws IllegalStateException if the projected parametrization still contains
     *                               flipped triangles after MC19 §5.4 repair;
     *                               downstream motorcycle / ILP stages require an
     *                               uv.injective parametrization
     * @param field built cross field to parametrize
     * @return the seamless parametrization data
     */
    public SeamlessUv build(CrossField field) {
        this.field = field;
        this.mesh = field.mesh;
        this.uv = new SeamlessUv(mesh.faceCount(), mesh.edgeCount());
        uv.faceIdToActive = field.faceIdToActive;
        uv.edgeIdToActive = field.edgeIdToActive;
        uv.targetQuadEdgeLength = targetEdgeLengthFractionOfBounds
                * mesh.computeBoundingBoxDiagonal();
        uv.cutGraph = new CutGraph(mesh, field, uv);
        System.out.println("[seamless] Building seamless parameterization");
        for (int ae2 = 0; ae2 < uv.edgeCount; ae2++) {
            EdgeFaceIds edgeFaceIds = mesh.edgeFaceIds(ae2);

            if (edgeFaceIds.faceA != MeshTopology.NONE) {
                uv.edgeFaceA[ae2] = field.faceIdToActive.get(edgeFaceIds.faceA);
                int corner = -1;
                for (int c1 = 0; c1 < SeamlessUv.CORNERS_PER_FACE; c1++) {
                    if (mesh.faceVertexAt(edgeFaceIds.faceA, c1) == edgeFaceIds.edgeStartVertex) {
                        corner = c1;
                        break;
                    }
                }
                uv.edgeCornerInA[ae2] = corner;
            }
            if (edgeFaceIds.faceB != MeshTopology.NONE) {
                uv.edgeFaceB[ae2] = field.faceIdToActive.get(edgeFaceIds.faceB);
                int corner1 = -1;
                for (int c2 = 0; c2 < SeamlessUv.CORNERS_PER_FACE; c2++) {
                    if (mesh.faceVertexAt(edgeFaceIds.faceB, c2) == edgeFaceIds.edgeStartVertex) {
                        corner1 = c2;
                        break;
                    }
                }
                uv.edgeCornerInB[ae2] = corner1;
            }
        }

        System.out.println("[seamless] Mesh setup done, building cut graph");

        uv.cutGraph.buildCutGraph();
        uv.cutTranslationS = uv.cutGraph.atlas.translationU;
        uv.cutTranslationT = uv.cutGraph.atlas.translationV;

        System.out.println("[seamless] Cut graph built, precomputing per-face geometry and targets");
        precomputePerFaceGeometryAndTargets();

        System.out.println("[seamless] Per-face geometry and targets precomputed, assigning cut edge translation DOFs");

        this.faceWeight = new double[uv.faceCount];
        Arrays.fill(faceWeight, 1.0);

        long dofSystemStart = System.nanoTime();
        this.dofSystem = new SeamlessDofSystem(this, uv.cutGraph);
        this.solution = dofSystem.system.solution;
        this.dofSystem.system.writeBack = this::writeChartVerticesFromSolution;
        this.dofSystem.system.solve = () -> resolve();
        Platforms.log("[seamless timing] dof system %.3fs%n",
                (System.nanoTime() - dofSystemStart) / 1.0e9);
        resolve();
        return uv;
    }

    /**
     * Solves the parametrization over the already-built DOF system: greedy
     * integer rounding, the injectivity-constraint loop, chart-vertex write-back
     * and optional exact-seam projection. Clears pin state on entry so a
     * re-solve never replays stale pins into a fresh factor.
     *
     * @return the {@link ParameterizationMetrics} computed from the final
     *         parametrization
     */
    public ParameterizationMetrics resolve() {
        Arrays.fill(dofSystem.dofPinned, false);

        System.out.println("[seamless] Running greedy integer rounding");
        long roundingStart = System.nanoTime();
        runGreedyIntegerRounding();
        Platforms.log("[seamless timing] greedy integer rounding %.3fs%n",
                (System.nanoTime() - roundingStart) / 1.0e9);

        System.out.println("[seamless] Running BCE13 injectivity-constraint loop");
        long constraintStart = System.nanoTime();
        runInjectivityConstraintLoop();
        Platforms.log("[seamless timing] injectivity loop %.3fs%n",
                (System.nanoTime() - constraintStart) / 1.0e9);

        System.out.println("[seamless] Writing chart vertices from solution");
        writeChartVerticesFromSolution();

        if (exactSeams) {

            System.out.println("[seamless] Projecting onto exact-seam parameterization");
            new SeamlessProjector(this).project();
        }
        metrics = new ParameterizationMetrics(this, mesh);
        System.out.println("[seamless] Metrics computed, returning");
        System.out.println("[seamless] Metrics: " + metrics);
        return metrics;
    }

    /**
     * Greedy rounding: repeatedly snap the unpinned integer DOF closest to an
     * integer and re-solve. When constraint reduction already pinned every integer
     * DOF, the base system is factored natively once and solved directly.
     *
     * <p>
     * See also: BZK09 Section 5
     */
    private void runGreedyIntegerRounding() {
        GreedyRounding rounding = new GreedyRounding(dofSystem.system, dofSystem.dofIsInteger,
                dofSystem.dofPinned, integerPinWeight, dofSystem::pinDof);
        rounding.run();
        baseFactorHandle = rounding.retainedHandle;
        baseFactorMatrix = rounding.retainedMatrix;
    }



    // =====================================================================
    // C4 prep. per-face shape gradients + branch-rotated cross targets.
    // =====================================================================

    private void precomputePerFaceGeometryAndTargets() {
        faceArea = new double[uv.faceCount];
        faceShapeB = new double[uv.faceCount * SeamlessUv.CORNERS_PER_FACE];
        faceShapeC = new double[uv.faceCount * SeamlessUv.CORNERS_PER_FACE];
        faceUtxLocal = new double[uv.faceCount];
        faceUtyLocal = new double[uv.faceCount];
        faceVtxLocal = new double[uv.faceCount];
        faceVtyLocal = new double[uv.faceCount];

        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f rel = new Vector3f();

        for (int af = 0; af < uv.faceCount; af++) {
            int fId = mesh.faceIdAt(af);
            int v0 = mesh.faceVertexAt(fId, 0);
            int v1 = mesh.faceVertexAt(fId, 1);
            int v2 = mesh.faceVertexAt(fId, 2);
            mesh.vertexPosition(v0, p0);
            mesh.vertexPosition(v1, p1);
            mesh.vertexPosition(v2, p2);

            Vector3f xAxis = field.faceX[af];
            Vector3f yAxis = field.faceY[af];

            // Project (p_i - p_0) into local frame.
            double x0 = 0, y0 = 0;
            rel.set(p1).sub(p0);
            double x1 = rel.dot(xAxis), y1 = rel.dot(yAxis);
            rel.set(p2).sub(p0);
            double x2 = rel.dot(xAxis), y2 = rel.dot(yAxis);

            double twoArea = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
            if (Math.abs(twoArea) < DEGENERATE_AREA_EPSILON) {
                // Degenerate triangle: skip.
                faceArea[af] = 0.0;
                continue;
            }
            faceArea[af] = HALF_D * Math.abs(twoArea);

            int o = af * SeamlessUv.CORNERS_PER_FACE;
            // ∇φ = (Σ b_i φ_i, Σ c_i φ_i) for linear φ on a 2D triangle.
            faceShapeB[o] = (y1 - y2) / twoArea;
            faceShapeB[o + 1] = (y2 - y0) / twoArea;
            faceShapeB[o + 2] = (y0 - y1) / twoArea;
            faceShapeC[o] = (x2 - x1) / twoArea;
            faceShapeC[o + 1] = (x0 - x2) / twoArea;
            faceShapeC[o + 2] = (x1 - x0) / twoArea;

            // Branch-rotated cross targets.
            double theta = field.theta[af] + uv.cutGraph.faceBranch[af] * HALF_PI;
            double cu = Math.cos(theta), su = Math.sin(theta);
            faceUtxLocal[af] = cu;
            faceUtyLocal[af] = su;
            faceVtxLocal[af] = -su;
            faceVtyLocal[af] = cu;
        }
    }

    /**
     * BCE13 §3.4's lazy-constraint loop: evaluate every Equation 4 inequality,
     * activate the violated plus every one below the activation threshold, and
     * re-solve the hard-constrained convex QP over the active set with
     * {@link InteriorPointQp} until no constraint is violated or the round cap is
     * reached.
     */
    private void runInjectivityConstraintLoop() {
        if (baseFactorHandle != null) {
            DirectSolver.releaseHandle(baseFactorHandle);
            baseFactorHandle = null;
            baseFactorMatrix = null;
        }
        InjectivityConstraints constraints = new InjectivityConstraints(this).build();
        NormalMatrix baseMatrix = dofSystem.assembleWeighted(faceWeight);
        dofSystem.applyIntegerPinPenalty(baseMatrix);
        LazyConstraints loop = new LazyConstraints(dofSystem.system, baseMatrix, constraints,
                maxConstraintRounds);
        loop.run();
        uv.injective = loop.violated == 0;
        Platforms.log("[injectivity] done violated=%d flippedTriangles=%d%n", loop.violated,
                countFlippedTrianglesFromSolution());
    }

    /**
     * Materialise per-corner {@code uv.uCorner} / {@code uv.vCorner} from the current
     * {@link #solution} via each chart vertex's final-DOF expansion.
     */
    public void writeChartVerticesFromSolution() {
        int totalCorners = uv.faceCount * SeamlessUv.CORNERS_PER_FACE;
        uv.uCorner = new double[totalCorners];
        uv.vCorner = new double[totalCorners];
        for (int corner = 0; corner < totalCorners; corner++) {
            int chartVertex = uv.cutGraph.cornerToChartVertex[corner];
            uv.uCorner[corner] = dofSystem.evaluateChartComponent(chartVertex, 0, solution);
            uv.vCorner[corner] = dofSystem.evaluateChartComponent(chartVertex, 1, solution);
        }
        Arrays.fill(uv.cutTranslationS, 0.0);
        Arrays.fill(uv.cutTranslationT, 0.0);
        for (int activeEdge = 0; activeEdge < uv.edgeCount; activeEdge++) {
            if (dofSystem.cutEdgeSDof[activeEdge] < 0) {
                continue;
            }
            uv.cutTranslationS[activeEdge] = dofSystem.evaluateRawDof(
                    dofSystem.cutEdgeSDof[activeEdge], solution);
            uv.cutTranslationT[activeEdge] = dofSystem.evaluateRawDof(
                    dofSystem.cutEdgeTDof[activeEdge], solution);
        }
        boolean inj = true;
        for (int af = 0; af < uv.faceCount; af++) {
            int o = af * SeamlessUv.CORNERS_PER_FACE;
            double u0 = uv.uCorner[o], v0p = uv.vCorner[o];
            double u1 = uv.uCorner[o + 1], v1 = uv.vCorner[o + 1];
            double u2 = uv.uCorner[o + 2], v2 = uv.vCorner[o + 2];
            double sa = HALF * ((u1 - u0) * (v2 - v0p) - (u2 - u0) * (v1 - v0p));
            if (sa <= 0f) {
                inj = false;
                break;
            }
        }

        uv.injective = inj && uv.injective;
    }

    /**
     * Count local-injectivity violations of the current solution: flipped triangles
     * (negative parametric area) and collapsed ones (parametric area below
     * {@link #DEGENERATE_UV_AREA_FRACTION} of the face's expected area
     * {@code faceArea / h²}). Both counts must reach zero.
     *
     * @return number of flipped or collapsed triangles
     */
    private int countFlippedTrianglesFromSolution() {
        int flipped = 0;
        double inverseTargetAreaScale = 1.0 / (uv.targetQuadEdgeLength * uv.targetQuadEdgeLength);
        for (int af = 0; af < uv.faceCount; af++) {
            if (faceArea[af] <= 0)
                continue;
            int o = af * SeamlessUv.CORNERS_PER_FACE;
            int cv0 = uv.cutGraph.cornerToChartVertex[o];
            int cv1 = uv.cutGraph.cornerToChartVertex[o + 1];
            int cv2 = uv.cutGraph.cornerToChartVertex[o + 2];
            double u0 = dofSystem.evaluateChartComponent(cv0, 0, solution);
            double v0 = dofSystem.evaluateChartComponent(cv0, 1, solution);
            double u1 = dofSystem.evaluateChartComponent(cv1, 0, solution);
            double v1 = dofSystem.evaluateChartComponent(cv1, 1, solution);
            double u2 = dofSystem.evaluateChartComponent(cv2, 0, solution);
            double v2 = dofSystem.evaluateChartComponent(cv2, 1, solution);
            double sa = HALF_D * ((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0));
            double expectedUvArea = faceArea[af] * inverseTargetAreaScale;
            if (sa <= DEGENERATE_UV_AREA_FRACTION * expectedUvArea)
                flipped++;
        }
        return flipped;
    }



}
