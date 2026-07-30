package ixdar.geometry.mesh.quadlayout;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.LayoutEmbedding;
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

    public final HalfEdgeMesh mesh;

    /** Maximum separatrix deviation α in radians, Lyon's single quality knob. */
    public final float alphaRadians;

    public CrossField crossField;
    public SeamlessParameterization seamless;
    public ParameterizationMetrics seamlessMetrics;
    public MotorcycleGraph motorcycleGraph;
    public QuantizedMeshGrid quantization;
    public LayoutExtraction layout;
    public LayoutEmbedding embedding;

    /** The embedded T-mesh, contracted to a fixed point: the pipeline's output. */
    public EmbeddedTMesh tmesh;

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
        }
        return tmesh;
    }

    /**
     * Stage 9: contract the embedded T-mesh to a fixed point, leaving no zero-quantized
     * arc or patch (LCBK19 §6.1 operators 1–3).
     *
     * <p>
     * LCK21a §6's T-junction extension belongs after this, on the embedded T-mesh; until
     * it exists the layout left here is still non-conforming.
     *
     * @return the cached T-mesh, contracted
     */
    public EmbeddedTMesh buildContractedTMesh() {
        buildTMesh();
        tmesh.contract();
        return tmesh;
    }
}
