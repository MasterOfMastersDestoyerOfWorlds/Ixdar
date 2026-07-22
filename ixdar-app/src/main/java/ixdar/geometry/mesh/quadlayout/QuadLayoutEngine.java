package ixdar.geometry.mesh.quadlayout;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.embedding.LayoutEmbedding;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroElementContraction;
import ixdar.geometry.mesh.quadlayout.crossfield.NDirectionField;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.quantization.LayoutExtraction;
import ixdar.geometry.mesh.quadlayout.quantization.QuantizedMeshGrid;
import ixdar.geometry.mesh.quadlayout.quantization.TJunctionElimination;
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
    public TJunctionElimination conforming;
    public LayoutEmbedding embedding;
    public ZeroElementContraction contraction;

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
     * Stage 7: extend remaining T-junctions into a conforming layout and count
     * the final patches (Lyon §6, extension half).
     *
     * @return the cached T-junction elimination with {@code finalPatchCount}
     */
    public TJunctionElimination buildConformingLayout() {
        if (conforming == null) {
            buildLayout();
            conforming = new TJunctionElimination(layout).build();
        }
        return conforming;
    }

    /**
     * Stage 8: re-embed the T-mesh as a subcomplex of a working copy of the
     * mesh (LCBK19 §6.1) — nodes onto vertices, arcs onto edge paths.
     *
     * @return the cached layout embedding
     */
    public LayoutEmbedding buildLayoutEmbedding() {
        if (embedding == null) {
            buildConformingLayout();
            embedding = new LayoutEmbedding(conforming).build();
        }
        return embedding;
    }

    /**
     * Stage 9: contract every zero-quantized element of the embedded T-mesh
     * onto points (LCBK19 §6.1 operator 1), updating the embedding in place.
     *
     * @return the cached contraction
     */
    public ZeroElementContraction buildContraction() {
        if (contraction == null) {
            buildLayoutEmbedding();
            contraction = new ZeroElementContraction(embedding).build();
        }
        return contraction;
    }
}
