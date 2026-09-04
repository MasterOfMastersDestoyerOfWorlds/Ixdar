package ixdar.geometry.mesh.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.nodes.api.BoolField;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.IntField;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;
import ixdar.geometry.mesh.nodes.quadlayout.NewtonSolverNode;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.crossfield.NDirectionField;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.LayoutEmbedding;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.extraction.PatchGridExtraction;
import ixdar.geometry.mesh.quadlayout.extraction.PatchSurfaceGeometry;
import ixdar.geometry.mesh.quadlayout.gridmap.GlobalGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapAssembly;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.quantization.QuantizedMeshGrid;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessUv;
import ixdar.geometry.mesh.quadlayout.solver.chol.CholeskyBackend;
import ixdar.geometry.mesh.quadlayout.solver.system.DofSystem;
import ixdar.platform.Platforms;

/**
 * Staged driver for the quad-layout pipeline, itself the registered
 * {@code quad_layout} node. Every stage runs through its registered mesh
 * node's ports. Each {@code build*} method runs its prerequisites lazily and
 * caches its product, so a caller can run only as far as the stage it needs.
 *
 * <p>
 * See also: Lyon 2021
 */
@MeshNodeAnnotation(id = "quad_layout", desktopOnly = true)
public final class QuadLayoutEngine implements MeshNode {

    /**
     * Default maximum separatrix deviation α (Lyon Table 1 uses 15° on ROCKERARM).
     */
    public static final float DEFAULT_ALPHA_RADIANS = (float) Math.toRadians(15.0);

    /**
     * Default parametric length one quad edge spans. The seamless parametrization
     * is scaled so that one unit is one quad edge, so this is its own unit.
     */
    public static final double DEFAULT_TARGET_EDGE_LENGTH = 1.0;

    /** Nanoseconds per second, for the per-stage timing log. */
    public static final double NANOS_PER_SECOND = 1.0e9;

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort ALPHA_DEGREES = new InputPort("alpha_degrees", PortType.FLOAT,
            MotorcycleGraph.DEFAULT_ALPHA_DEGREES);
    public static final InputPort TARGET_EDGE_LENGTH = new InputPort("target_edge_length",
            PortType.FLOAT, (float) DEFAULT_TARGET_EDGE_LENGTH);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name,
            PortType.GEOMETRY_BUNDLE);
    public static final OutputPort UV = new OutputPort("uv", PortType.UV_FIELD);
    public static final OutputPort TMESH = new OutputPort("tmesh", PortType.ARC_NETWORK);
    public static final OutputPort FIELD = new OutputPort("field", PortType.CROSS_FIELD);
    public static final OutputPort SINGULARITIES = new OutputPort("singularities",
            PortType.INT);

    public final HalfEdgeMesh mesh;

    /** Maximum separatrix deviation α in radians, Lyon's single quality knob. */
    public final float alphaRadians;

    /**
     * Parametric length one quad edge spans; set before
     * {@link #buildGlobalGridMap()}.
     */
    public double targetEdgeLength = DEFAULT_TARGET_EDGE_LENGTH;

    public CrossField crossField;

    /** The field's per-vertex cone-point index4, the cross-field port value. */
    public IntField singularityIndex4;

    /** Per-edge feature/boundary selection, the cross-field port value. */
    public BoolField featureEdges;

    public SeamlessUv seamless;

    /** UV triangles the seamless parametrization flipped (0 when injective). */
    public int seamlessFlippedTriangles;

    /** The parametrization's charts and cut transitions, the CHARTS port value. */
    public ChartAtlas seamlessCharts;

    /** The traced motorcycle-graph arrangement. */
    public ArcNetwork arrangement;

    /** Traces still alive when the motorcycle event queue drained. */
    public int orphanedTraceCount;

    /** Trace chains containing the same node at two different positions. */
    public int repeatedChainNodeCount;

    /** Whether the quantization ILP ran; the arrangement is mutated in place. */
    public boolean quantized;

    /** Separation cuts the quantization needed beyond Lemma 1 (0 expected). */
    public int separationCutCount;

    /** Whether the quantization violated a singularity separation constraint. */
    public boolean separationViolated;

    /** Variables in the quantization ILP. */
    public int quantizationVariableCount;

    /** The embedded T-mesh, contracted and conforming: the pipeline's output. */
    public ArcNetwork tmesh;

    /**
     * The patch maps carried into one common grid; what the re-parametrization
     * optimizes.
     */
    public GlobalGridMap globalGrid;

    /** The extracted quad mesh as a geometry bundle, the node's output form. */
    public GeometryBundle quadBundle;

    /** Per-patch quad-grid geometry of the relaxed layout, patch ids per face. */
    public GeometryBundle patchSurfaces;

    /** Coons-blend variant of {@link #patchSurfaces}, same topology. */
    public GeometryBundle patchCoons;

    /**
     * Whether {@link #buildContractedTMesh()} has run; the T-mesh is mutated in
     * place.
     */
    public boolean contracted;

    /** Inert node-registry instance; evaluation builds a fresh engine. */
    public QuadLayoutEngine() {
        this.mesh = null;
        this.alphaRadians = 0f;
    }

    /**
     * Stage products start unbuilt; call the {@code build*} method of the furthest
     * stage you need.
     *
     * @param mesh         triangle mesh, manifold, possibly with boundary
     * @param alphaRadians maximum separatrix deviation in radians (e.g. 5°…45°)
     */
    public QuadLayoutEngine(HalfEdgeMesh mesh, float alphaRadians) {
        this.mesh = mesh;
        this.alphaRadians = alphaRadians;
        CholeskyBackend.preloadAsync();
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, ALPHA_DEGREES, TARGET_EDGE_LENGTH);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT, UV, TMESH, FIELD, SINGULARITIES);
    }

    @Override
    public String description() {
        return "Runs the whole quad-layout pipeline over a triangle mesh: cross field, seamless"
                + " parametrization, motorcycle T-mesh, quantization, contraction, grid-map"
                + " relaxation, and quad extraction.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Triangle mesh in; the extracted pure quad mesh out.",
                ALPHA_DEGREES.name, "Maximum separatrix deviation in degrees, the quality knob.",
                TARGET_EDGE_LENGTH.name, "Parametric length one quad edge spans.",
                UV.name, "The relaxed integer grid map the quads were extracted from.",
                TMESH.name, "The contracted embedded T-mesh, one patch per quad grid.",
                FIELD.name, "The cross field the layout follows.",
                SINGULARITIES.name, "Per-vertex index4 of the field's cone points"
                        + " (0 = not singular)."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = ctx.getInput(GEOMETRY.name, GeometryBundle.class);
        float alphaDegrees = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, ALPHA_DEGREES.name, ALPHA_DEGREES.defaultValue),
                MotorcycleGraph.DEFAULT_ALPHA_DEGREES);
        float edgeLength = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, TARGET_EDGE_LENGTH.name,
                        TARGET_EDGE_LENGTH.defaultValue),
                (float) DEFAULT_TARGET_EDGE_LENGTH);
        QuadLayoutEngine engine = new QuadLayoutEngine(
                HalfEdgeMeshEngine.fromMeshTopology(bundle.mesh()),
                (float) Math.toRadians(alphaDegrees));
        engine.targetEdgeLength = edgeLength;
        engine.buildGlobalGridMap();
        ctx.setOutput(GEOMETRY_OUT.name, engine.quadBundle);
        ctx.setOutput(UV.name, engine.globalGrid);
        ctx.setOutput(TMESH.name, engine.tmesh);
        ctx.setOutput(FIELD.name, engine.crossField);
        ctx.setOutput(SINGULARITIES.name, engine.singularityIndex4);
    }

    /**
     * Logs one stage's own wall time, measured from after its prerequisite stages
     * ran, so nested {@code build*} calls never double-count.
     *
     * @param stage      stage name for the log line
     * @param startNanos {@link System#nanoTime()} taken when the stage's own work began
     */
    private void logStageTime(String stage, long startNanos) {
        Platforms.log("[quad-layout] %s %.3fs%n", stage,
                (System.nanoTime() - startNanos) / NANOS_PER_SECOND);
    }

    /**
     * Stage 1–2: build the default cross field (Knöppel n-direction field with
     * curvature alignment and soft feature/boundary guidance) and extract its
     * singularities.
     *
     * @return the cached cross field
     */
    public CrossField buildCrossField() {
        if (crossField == null) {
            long startNanos = System.nanoTime();
            MapNodeContext ctx = new MapNodeContext(new NDirectionField())
                    .with(NDirectionField.GEOMETRY, GeometryBundle.ofMesh(mesh))
                    .eval();
            crossField = ctx.output(NDirectionField.FIELD, CrossField.class);
            singularityIndex4 = ctx.output(NDirectionField.SINGULARITIES, IntField.class);
            featureEdges = ctx.output(NDirectionField.FEATURE_EDGES, BoolField.class);
            Platforms.log("[quad-layout] singularities: " + crossField.singularityCount());
            logStageTime("cross field", startNanos);
        }
        return crossField;
    }

    /**
     * Stage 3: build the seamless parametrization on top of the cross field.
     *
     * @return the cached seamless parametrization
     */
    public SeamlessUv buildSeamless() {
        if (seamless == null) {
            buildCrossField();
            long startNanos = System.nanoTime();
            MapNodeContext ctx = new MapNodeContext(new SeamlessParameterization())
                    .with(SeamlessParameterization.FIELD, crossField)
                    .eval();
            seamless = ctx.output(SeamlessParameterization.UV, SeamlessUv.class);
            seamlessFlippedTriangles = ctx.output(
                    SeamlessParameterization.FLIPPED_TRIANGLES, Integer.class);
            seamlessCharts = ctx.output(SeamlessParameterization.CHARTS, ChartAtlas.class);
            logStageTime("seamless", startNanos);
        }
        return seamless;
    }

    /**
     * Stage 4: build the modified motorcycle-graph arrangement (Lyon §3) over the
     * seamless parametrization.
     *
     * @return the cached arrangement
     */
    public ArcNetwork buildMotorcycleGraph() {
        if (arrangement == null) {
            buildSeamless();
            long startNanos = System.nanoTime();
            MapNodeContext ctx = new MapNodeContext(new MotorcycleGraph())
                    .with(MotorcycleGraph.GEOMETRY, GeometryBundle.ofMesh(mesh))
                    .with(MotorcycleGraph.UV, seamless)
                    .with(MotorcycleGraph.CHARTS, seamlessCharts)
                    .with(MotorcycleGraph.SINGULARITIES, singularityIndex4)
                    .with(MotorcycleGraph.FEATURE_EDGES, featureEdges)
                    .with(MotorcycleGraph.ALPHA_DEGREES, (float) Math.toDegrees(alphaRadians))
                    .eval();
            arrangement = ctx.output(MotorcycleGraph.GRAPH, ArcNetwork.class);
            orphanedTraceCount = ctx.output(MotorcycleGraph.ORPHANED_TRACES, Integer.class);
            repeatedChainNodeCount = ctx.output(MotorcycleGraph.REPEATED_CHAIN_NODES, Integer.class);
            logStageTime("motorcycle graph", startNanos);
        }
        return arrangement;
    }

    /**
     * Stage 5–6: solve the Lyon §4–§5 quantization ILP over the arrangement's arcs
     * and extract the layout's separatrix skeleton; the arrangement is quantized in
     * place.
     *
     * @return the quantized arrangement
     */
    public ArcNetwork buildQuantization() {
        if (!quantized) {
            buildMotorcycleGraph();
            long startNanos = System.nanoTime();
            MapNodeContext ctx = new MapNodeContext(new QuantizedMeshGrid())
                    .with(QuantizedMeshGrid.GRAPH, arrangement)
                    .with(QuantizedMeshGrid.ALPHA_DEGREES, (float) Math.toDegrees(alphaRadians))
                    .eval();
            arrangement = ctx.output(QuantizedMeshGrid.SKELETON, ArcNetwork.class);
            separationCutCount = ctx.output(QuantizedMeshGrid.SEPARATION_CUTS, Integer.class);
            separationViolated = ctx.output(QuantizedMeshGrid.SEPARATION_VIOLATED, Boolean.class);
            quantizationVariableCount = ctx.output(QuantizedMeshGrid.VARIABLES, Integer.class);
            quantized = true;
            logStageTime("quantization", startNanos);
        }
        return arrangement;
    }

    /**
     * Stage 7–8: re-embed the skeleton as a subcomplex of a working copy of the
     * mesh (LCBK19 §6.1) and assemble the embedded T-mesh, validated against the
     * surface's Euler characteristic. Zero arcs and patches are still present.
     *
     * @return the cached embedded T-mesh, uncontracted
     */
    public ArcNetwork buildTMesh() {
        if (tmesh == null) {
            buildQuantization();
            long startNanos = System.nanoTime();
            tmesh = new MapNodeContext(new LayoutEmbedding())
                    .with(LayoutEmbedding.SKELETON, arrangement)
                    .with(LayoutEmbedding.UV, seamless)
                    .eval()
                    .output(LayoutEmbedding.TMESH, ArcNetwork.class);
            logStageTime("carve", startNanos);
        }
        return tmesh;
    }

    /**
     * Stage 9–10: contract the embedded T-mesh to a fixed point, leaving no
     * zero-quantized arc or patch (LCBK19 §6.1 operators 1–3). The contraction
     * conforms internally before its recarve; no post-recarve conform runs (see
     * REFACTOR-PLAN 6.12).
     *
     * @return the cached T-mesh, contracted
     */
    public ArcNetwork buildContractedTMesh() {
        if (!contracted) {
            buildTMesh();
            long startNanos = System.nanoTime();
            tmesh = new MapNodeContext(new NetworkContraction())
                    .with(NetworkContraction.TMESH, tmesh)
                    .with(NetworkContraction.CONFORM, Boolean.FALSE)
                    .eval()
                    .output(NetworkContraction.TMESH_OUT, ArcNetwork.class);
            contracted = true;
            logStageTime("contract", startNanos);
        }
        return tmesh;
    }

    /**
     * Stage 11–14: map every patch onto its quantized rectangle, frame the patches
     * in one common integer grid (LCBK19 §6.2), Newton-relax the map, and extract
     * the pure quad mesh (LCK21a §7).
     *
     * @return the cached global grid map, relaxed
     */
    public GlobalGridMap buildGlobalGridMap() {
        if (globalGrid == null) {
            buildContractedTMesh();
            long startNanos = System.nanoTime();
            MapNodeContext assembly = new MapNodeContext(new GridMapAssembly())
                    .with(GridMapAssembly.TMESH, tmesh)
                    .with(GridMapAssembly.UV, seamless)
                    .with(GridMapAssembly.TARGET_EDGE_LENGTH, (float) targetEdgeLength)
                    .eval();
            globalGrid = assembly.output(GridMapAssembly.UV_OUT, GlobalGridMap.class);
            DofSystem gridSystem = assembly.output(GridMapAssembly.DOFS, DofSystem.class);
            new MapNodeContext(new NewtonSolverNode())
                    .with(NewtonSolverNode.UV, globalGrid)
                    .with(NewtonSolverNode.DOFS, gridSystem)
                    .eval();
            GridMapAssembly.extractQuads(globalGrid);
            quadBundle = GeometryBundle.ofMesh(globalGrid.quadMesh.toArrayMesh());
            logStageTime("global grid map", startNanos);
        }
        return globalGrid;
    }

    /**
     * Stage 15: the render-ready surfaces as geometry: every live patch's extracted quad grid
     * with per-face patch ids, plus the Coons-blend variant of the same topology.
     *
     * @return the cached surface-grid bundle
     */
    public GeometryBundle buildPatchSurfaces() {
        if (patchSurfaces == null) {
            buildGlobalGridMap();
            long startNanos = System.nanoTime();
            PatchGridExtraction grids = PatchGridExtraction.fromRelaxedMap(globalGrid);
            patchSurfaces = PatchSurfaceGeometry.surfaceBundle(grids);
            patchCoons = PatchSurfaceGeometry.coonsBundle(grids);
            logStageTime("patch surfaces", startNanos);
        }
        return patchSurfaces;
    }
}
