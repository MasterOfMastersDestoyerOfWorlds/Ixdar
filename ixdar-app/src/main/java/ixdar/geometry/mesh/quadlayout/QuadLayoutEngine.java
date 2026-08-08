package ixdar.geometry.mesh.quadlayout;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.crossfield.NDirectionField;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.LayoutEmbedding;
import ixdar.geometry.mesh.quadlayout.extraction.LayoutPatchSurfaces;
import ixdar.geometry.mesh.quadlayout.extraction.PatchGridExtraction;
import ixdar.geometry.mesh.quadlayout.gridmap.GlobalGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapDofSystem;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapOptimizer;
import ixdar.geometry.mesh.quadlayout.gridmap.IntegerGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.LayoutPatchMaps;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.quantization.LayoutExtraction;
import ixdar.geometry.mesh.quadlayout.quantization.QuantizedMeshGrid;
import ixdar.geometry.mesh.quadlayout.seamless.ParameterizationMetrics;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.solver.CholeskyBackend;

/**
 * Staged driver for the quad-layout pipeline: cross field, singularities,
 * seamless parametrization, motorcycle-graph T-mesh, ILP quantization, layout
 * extraction.
 *
 * <p>
 * Each {@code build*} method runs its prerequisites lazily and caches its
 * product, so a caller can run the pipeline only as far as the stage it needs.
 *
 * <p>
 * See also: Lyon 2021
 */
public final class QuadLayoutEngine {

    /**
     * Default maximum separatrix deviation α (Lyon Table 1 uses 15° on ROCKERARM).
     */
    public static final float DEFAULT_ALPHA_RADIANS = (float) Math.toRadians(15.0);

    /**
     * Default parametric length one quad edge spans. The seamless parametrization
     * is scaled so that one unit is one quad edge, so this is its own unit.
     */
    public static final double DEFAULT_TARGET_EDGE_LENGTH = 1.0;


    public final HalfEdgeMesh mesh;

    /** Maximum separatrix deviation α in radians, Lyon's single quality knob. */
    public final float alphaRadians;

    /**
     * Parametric length one quad edge spans; set before
     * {@link #buildConformingTMesh()}.
     */
    public double targetEdgeLength = DEFAULT_TARGET_EDGE_LENGTH;

    public CrossField crossField;
    public SeamlessParameterization seamless;
    public ParameterizationMetrics seamlessMetrics;
    public MotorcycleGraph motorcycleGraph;
    public QuantizedMeshGrid quantization;
    public LayoutExtraction layout;
    public LayoutEmbedding embedding;

    /** The embedded T-mesh, contracted and conforming: the pipeline's output. */
    public EmbeddedTMesh tmesh;

    public LayoutPatchMaps patchMaps;

    /**
     * Common grid framing the patch maps, so their union is an integer grid map.
     */
    public IntegerGridMap integerGrid;

    /**
     * The patch maps carried into that common grid; what the re-parametrization
     * optimizes.
     */
    public GlobalGridMap globalGrid;

    /** Which grid coordinates the re-parametrization may move. */
    public GridMapDofSystem gridDofs;

    /** The relaxation that frees patch boundaries from their rectangles. */
    public GridMapOptimizer gridOptimizer;

    /**
     * Extraction of the map before the relaxation, kept so the scene can compare
     * the two.
     */
    public PatchGridExtraction quadGridInitial;

    /**
     * Render records of the pre-relaxation extraction, for the scene's comparison
     * toggle.
     */
    public LayoutPatchSurfaces patchSurfacesInitial;

    public PatchGridExtraction quadGrid;
    public LayoutPatchSurfaces patchSurfaces;

    /**
     * Whether {@link #buildContractedTMesh()} has run; the T-mesh is mutated in
     * place.
     */
    public boolean contracted;

    /**
     * Whether {@link #buildConformingTMesh()} has run; the T-mesh is mutated in
     * place.
     */
    public boolean conforming;

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

    /**
     * Stage 1–2: build the default cross field (Knöppel n-direction field with
     * curvature alignment and soft feature/boundary guidance) and extract its
     * singularities.
     *
     * @return the cached cross field
     */
    public CrossField buildCrossField() {
        if (crossField == null) {
            crossField = new NDirectionField(mesh).build();
            System.out.println("[quad-layout] singularities: " + crossField.singularities.size());
        }
        return crossField;
    }

    /**
     * Stage 3: build the seamless parametrization on top of the cross field;
     * validation metrics land in {@link #seamlessMetrics}.
     *
     * @return the cached seamless parametrization
     */
    public SeamlessParameterization buildSeamless() {
        if (seamless == null) {
            buildCrossField();
            seamless = new SeamlessParameterization(crossField);
            seamlessMetrics = seamless.build();
        }
        return seamless;
    }

    /**
     * Stage 4: build the modified motorcycle-graph T-mesh (Lyon §3) over the
     * seamless parametrization.
     *
     * @return the cached motorcycle graph
     */
    public MotorcycleGraph buildMotorcycleGraph() {
        if (motorcycleGraph == null) {
            buildSeamless();
            motorcycleGraph = new MotorcycleGraph(seamless, alphaRadians);
            motorcycleGraph.build();
        }
        return motorcycleGraph;
    }

    /**
     * Stage 5: solve the Lyon §4–§5 quantization ILP over the T-mesh arcs.
     *
     * @return the cached quantization with one integer per arc
     */
    public QuantizedMeshGrid buildQuantization() {
        if (quantization == null) {
            buildMotorcycleGraph();
            quantization = new QuantizedMeshGrid(motorcycleGraph, alphaRadians).build();
        }
        return quantization;
    }

    /**
     * Stage 6: collapse zero-quantized arcs and extract the layout's separatrix
     * skeleton (Lyon §6, collapse half).
     *
     * @return the cached layout extraction
     */
    public LayoutExtraction buildLayout() {
        if (layout == null) {
            buildQuantization();
            layout = new LayoutExtraction(quantization).build();
        }
        return layout;
    }

    /**
     * Stage 7: re-embed the T-mesh as a subcomplex of a working copy of the mesh
     * (LCBK19 §6.1) — nodes onto vertices, arcs onto edge paths.
     *
     * @return the cached layout embedding
     */
    public LayoutEmbedding buildLayoutEmbedding() {
        if (embedding == null) {
            buildLayout();
            embedding = new LayoutEmbedding(layout).build();
        }
        return embedding;
    }

    /**
     * Stage 8: assemble the embedded T-mesh from the carve and validate it against
     * the surface's Euler characteristic. Zero arcs and patches are still present.
     *
     * @return the cached embedded T-mesh, uncontracted
     */
    public EmbeddedTMesh buildTMesh() {
        if (tmesh == null) {
            buildLayoutEmbedding();
            tmesh = new EmbeddedTMesh(embedding.topology).build(embedding);
            tmesh.validate();
        }
        return tmesh;
    }

    /**
     * Stage 9–10: contract the embedded T-mesh to a fixed point, leaving no
     * zero-quantized arc or patch (LCBK19 §6.1 operators 1–3), then extend every
     * surviving T-junction across its patch so the layout conforms (LCK21a §6).
     *
     * @return the cached T-mesh, contracted and conforming
     */
    public EmbeddedTMesh buildContractedTMesh() {
        if (!contracted) {
            buildTMesh();
            tmesh.contract();
            tmesh.conform();
            tmesh = tmesh.recarve(mesh);
            contracted = true;
            conforming = true;
        }
        return tmesh;
    }

    /**
     * Stage 11: map every layout patch onto its quantized rectangle with a harmonic
     * embedding, and frame the patches in one common grid so their union is an
     * integer grid map (LCBK19 §6.2).
     *
     * @return the cached per-patch rectangle maps
     */
    public LayoutPatchMaps buildPatchMaps() {
        if (patchMaps == null) {
            buildContractedTMesh();
            patchMaps = new LayoutPatchMaps(tmesh, seamless, targetEdgeLength);
            patchMaps.build();
            integerGrid = new IntegerGridMap(tmesh).build();
        }
        return patchMaps;
    }

    /**
     * Stage 12: carry every patch's map into one common integer grid, the object
     * the re-parametrization optimizes (LCBK19 §6.2, Figure 10d).
     *
     * @return the cached global grid map
     */
    public GlobalGridMap buildGlobalGridMap() {
        if (globalGrid == null) {
            buildPatchMaps();
            globalGrid = new GlobalGridMap(patchMaps, integerGrid).build();
            gridDofs = new GridMapDofSystem(globalGrid);
            gridDofs.seamCouplingPinned = false;
            gridDofs.nodeFreedomPinned = false;
            gridDofs.build();
            globalGrid.reportRectangleFit("framed");
            PatchGridExtraction initialExtraction = new PatchGridExtraction(patchMaps);
            initialExtraction.optimizedGrid = globalGrid;
            quadGridInitial = initialExtraction.build();
            gridOptimizer = new GridMapOptimizer(gridDofs, seamless);
            gridOptimizer.build();
            globalGrid.reportRectangleFit("relaxed");
        }
        return globalGrid;
    }

    /**
     * Stage 13: place the quad mesh's vertices on the surface by inverting each
     * patch's rectangle map at the integer lattice (LCK21a §6, "map a regular quad
     * patch").
     *
     * @return the cached per-patch quad grids
     */
    public PatchGridExtraction buildQuadGrid() {
        if (quadGrid == null) {
            buildGlobalGridMap();
            PatchGridExtraction extraction = new PatchGridExtraction(patchMaps);
            extraction.optimizedGrid = globalGrid;
            quadGrid = extraction.build();
        }
        return quadGrid;
    }

    /**
     * Stage 14: the render-ready surfaces — four boundary polylines, the extracted
     * quad grid and a Coons blend of the sides — one per layout patch.
     *
     * @return the cached patch surfaces
     */
    public LayoutPatchSurfaces buildPatchSurfaces() {
        if (patchSurfaces == null) {
            buildQuadGrid();
            patchSurfaces = new LayoutPatchSurfaces(quadGrid).build();
            patchSurfacesInitial = new LayoutPatchSurfaces(quadGridInitial).build();
        }
        return patchSurfaces;
    }

    /**
     * The number of patches in the layout, LCK21a Table 1's {@code #P}.
     *
     * @return the count of live patches in the T-mesh
     */
    public int livePatchCount() {
        int live = 0;
        for (EmbeddedPatch patch : tmesh.patches) {
            if (patch.alive) {
                live++;
            }
        }
        return live;
    }
}
