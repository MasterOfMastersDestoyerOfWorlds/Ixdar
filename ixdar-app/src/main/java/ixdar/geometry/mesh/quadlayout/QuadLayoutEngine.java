package ixdar.geometry.mesh.quadlayout;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.embedding.ArcParametricLength;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.LayoutEmbedding;
import ixdar.geometry.mesh.quadlayout.embedding.LayoutPatchMaps;
import ixdar.geometry.mesh.quadlayout.embedding.LayoutQualityReport;
import ixdar.geometry.mesh.quadlayout.embedding.LayoutStripSizing;
import ixdar.geometry.mesh.quadlayout.embedding.LayoutPatchSurfaces;
import ixdar.geometry.mesh.quadlayout.embedding.PatchGridExtraction;
import ixdar.geometry.mesh.quadlayout.crossfield.NDirectionField;
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
 * <p>Each {@code build*} method runs its prerequisites lazily and caches its
 * product, so a caller can run the pipeline only as far as the stage it needs.
 *
 * <p>See also: Lyon 2021
 */
public final class QuadLayoutEngine {

    /** Default maximum separatrix deviation α (Lyon Table 1 uses 15° on ROCKERARM). */
    public static final float DEFAULT_ALPHA_RADIANS = (float) Math.toRadians(15.0);

    /**
     * Default parametric length one quad edge spans. The seamless parametrization is scaled so
     * that one unit is one quad edge, so this is its own unit.
     */
    public static final double DEFAULT_TARGET_EDGE_LENGTH = 1.0;

    /** Diagnostic stage label for the T-mesh at the contraction's fixed point. */
    public static final String STAGE_CONTRACTED = "contracted";

    /** Diagnostic stage label for the T-mesh once no T-junction is left. */
    public static final String STAGE_CONFORMING = "conforming";

    public final HalfEdgeMesh mesh;

    /** Maximum separatrix deviation α in radians, Lyon's single quality knob. */
    public final float alphaRadians;

    /** Parametric length one quad edge spans; set before {@link #buildConformingTMesh()}. */
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

    public LayoutQualityReport quality;
    public ArcParametricLength arcLength;
    public LayoutStripSizing sizing;
    public LayoutPatchMaps patchMaps;
    public PatchGridExtraction quadGrid;
    public LayoutPatchSurfaces patchSurfaces;

    /** Whether {@link #buildContractedTMesh()} has run; the T-mesh is mutated in place. */
    public boolean contracted;

    /** Whether {@link #buildConformingTMesh()} has run; the T-mesh is mutated in place. */
    public boolean conforming;

    /**
     * Stage products start unbuilt; call the {@code build*} method of the
     * furthest stage you need.
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
     * Stage 6: collapse zero-quantized arcs and extract the layout's
     * separatrix skeleton (Lyon §6, collapse half).
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
     * Stage 7: re-embed the T-mesh as a subcomplex of a working copy of the
     * mesh (LCBK19 §6.1) — nodes onto vertices, arcs onto edge paths.
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
     * Stage 8: assemble the embedded T-mesh from the carve and validate it against the
     * surface's Euler characteristic. Zero arcs and patches are still present.
     *
     * @return the cached embedded T-mesh, uncontracted
     */
    public EmbeddedTMesh buildTMesh() {
        if (tmesh == null) {
            buildLayoutEmbedding();
            tmesh = new EmbeddedTMesh(embedding.topology).build(embedding);
            tmesh.validate();
            new ArcParametricLength(tmesh, seamless, motorcycleGraph, "assembled").build();
        }
        return tmesh;
    }

    /**
     * Stage 9: contract the embedded T-mesh to a fixed point, leaving no zero-quantized
     * arc or patch (LCBK19 §6.1 operators 1–3). The layout is still non-conforming.
     *
     * @return the cached T-mesh, contracted
     */
    public EmbeddedTMesh buildContractedTMesh() {
        if (!contracted) {
            buildTMesh();
            tmesh.contract();
            contracted = true;
            ArcParametricLength contractedLength =
                    new ArcParametricLength(tmesh, seamless, motorcycleGraph, STAGE_CONTRACTED).build();
            new LayoutQualityReport(tmesh, STAGE_CONTRACTED, contractedLength.lengthByArc,
                    motorcycleGraph).build();
        }
        return tmesh;
    }

    /**
     * Stage 10: extend every surviving T-junction across its patch, leaving a conforming
     * layout of four-sided patches (LCK21a §6).
     *
     * @return the cached T-mesh, conforming
     */
    public EmbeddedTMesh buildConformingTMesh() {
        if (!conforming) {
            buildContractedTMesh();
            tmesh.conform();
            conforming = true;
            System.out.println("[quad-layout] conforming: extensions="
                    + tmesh.extendTJunction.extensionCount + " (opposite splits="
                    + tmesh.extendTJunction.oppositeSplitCount + ") patches=" + livePatchCount());
            arcLength = new ArcParametricLength(tmesh, seamless, motorcycleGraph, STAGE_CONFORMING).build();
            quality = new LayoutQualityReport(tmesh, STAGE_CONFORMING, arcLength.lengthByArc,
                    motorcycleGraph).build();
            sizing = new LayoutStripSizing(tmesh, arcLength.lengthByArc, targetEdgeLength).build();
        }
        return tmesh;
    }

    /**
     * Stage 11: map every layout patch onto its quantized rectangle with a Tutte embedding
     * (LCBK19 §6.2).
     *
     * @return the cached per-patch rectangle maps
     */
    public LayoutPatchMaps buildPatchMaps() {
        if (patchMaps == null) {
            buildConformingTMesh();
            patchMaps = new LayoutPatchMaps(tmesh).build();
        }
        return patchMaps;
    }

    /**
     * Stage 12: place the quad mesh's vertices on the surface by inverting each patch's
     * rectangle map at the integer lattice (LCK21a §6, "map a regular quad patch").
     *
     * @return the cached per-patch quad grids
     */
    public PatchGridExtraction buildQuadGrid() {
        if (quadGrid == null) {
            buildPatchMaps();
            quadGrid = new PatchGridExtraction(patchMaps, sizing).build();
        }
        return quadGrid;
    }

    /**
     * Stage 13: the render-ready surfaces — four boundary polylines, the extracted quad grid
     * and a Coons blend of the sides — one per layout patch.
     *
     * @return the cached patch surfaces
     */
    public LayoutPatchSurfaces buildPatchSurfaces() {
        if (patchSurfaces == null) {
            buildQuadGrid();
            patchSurfaces = new LayoutPatchSurfaces(quadGrid).build();
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
