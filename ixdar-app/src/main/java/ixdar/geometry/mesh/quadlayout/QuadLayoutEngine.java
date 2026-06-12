package ixdar.geometry.mesh.quadlayout;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.crossfield.NDirectionField;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.seamless.ParameterizationMetrics;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * Staged driver for the Lyon 2021 quad-layout pipeline:
 *
 * <ol>
 * <li><b>Cross field</b> — Knöppel 2013 globally-optimal n-direction field
 * ({@link NDirectionField}), curvature-aligned with soft feature/boundary
 * guidance. (BZK09's mixed-integer field remains available as
 * {@code BommesCrossField} for comparison but is not the default.)</li>
 * <li><b>Singularities</b> — read off the cross field's index per vertex.</li>
 * <li><b>Seamless parametrization</b> with these singularities (BZK09 §5),
 * post-processed for exact seamlessness (MC19).</li>
 * <li><b>Modified motorcycle graph T-mesh</b> — Lyon §3: traces survive
 * crashes and stop only via the two-sided α criterion.</li>
 * <li><b>ILP quantization</b> — Lyon §4–§5: one integer per arc, consistency +
 * validity + layout constraints, coarseness objective.</li>
 * <li><b>Layout extraction</b> — Lyon §6: collapse zero arcs and extend
 * T-junctions into a conforming quad layout.</li>
 * </ol>
 *
 * <p>
 * Each {@code build*} method runs its prerequisites lazily and caches the
 * product in the corresponding public field, so inspector scenes can run the
 * shared pipeline exactly as far as the stage they visualize — and may mutate a
 * stage's product (e.g. substitute a reference cross field) before asking for
 * the next stage.
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
            motorcycleGraph = new MotorcycleGraph(seamless, alphaRadians).build();
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
}
