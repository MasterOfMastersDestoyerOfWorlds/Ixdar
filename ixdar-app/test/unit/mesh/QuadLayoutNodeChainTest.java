package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.nodes.data.LoadMeshNode;
import ixdar.geometry.mesh.nodes.quadlayout.NewtonSolverNode;
import ixdar.geometry.mesh.nodes.quadlayout.QuadExtractNode;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.crossfield.NDirectionField;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.gridmap.GlobalGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapAssembly;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.quantization.QuantizedMeshGrid;
import ixdar.geometry.mesh.quadlayout.embedding.LayoutEmbedding;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessUv;

/**
 * Chains the quad-layout stage nodes through their port interfaces,
 * {@code load_mesh -> cross_field -> seamless_uv -> motorcycle_graph}, and
 * checks the same invariants the engine-driven tests check: the parametrization
 * is injective with no flipped triangle, and the traced arrangement is a cell
 * complex of the surface.
 */
class QuadLayoutNodeChainTest {

    private static final String OFF_PATH =
            "test/resources/quadlayout/figure_7/sphere_base_in_tri.off";

    @Test
    void stageNodesChainIntoACellComplex() {
        LoadMeshNode load = new LoadMeshNode();
        MapNodeContext loadCtx = new MapNodeContext(load);
        loadCtx.setInput(LoadMeshNode.PATH.name, OFF_PATH);
        load.evaluate(loadCtx);
        GeometryBundle bundle = loadCtx.getOutput(LoadMeshNode.GEOMETRY.name, GeometryBundle.class);

        NDirectionField crossField = new NDirectionField();
        MapNodeContext fieldCtx = new MapNodeContext(crossField);
        fieldCtx.setInput(NDirectionField.GEOMETRY.name, bundle);
        crossField.evaluate(fieldCtx);
        CrossField field = fieldCtx.getOutput(NDirectionField.FIELD.name, CrossField.class);
        assertEquals(field.singularities.size(),
                fieldCtx.getOutput(NDirectionField.SINGULARITY_COUNT.name, Integer.class),
                "the count output matches the field");

        SeamlessParameterization seamlessUv = new SeamlessParameterization();
        MapNodeContext uvCtx = new MapNodeContext(seamlessUv);
        uvCtx.setInput(SeamlessParameterization.FIELD.name, field);
        seamlessUv.evaluate(uvCtx);
        SeamlessUv seamless =
                uvCtx.getOutput(SeamlessParameterization.UV.name, SeamlessUv.class);
        assertEquals(0, uvCtx.getOutput(SeamlessParameterization.FLIPPED_TRIANGLES.name, Integer.class),
                "the parametrization flips no triangle");
        assertTrue(uvCtx.getOutput(SeamlessParameterization.INJECTIVE.name, Boolean.class),
                "the parametrization is injective");

        MotorcycleGraph motorcycle = new MotorcycleGraph();
        MapNodeContext graphCtx = new MapNodeContext(motorcycle);
        graphCtx.setInput(MotorcycleGraph.GEOMETRY.name, bundle);
        graphCtx.setInput(MotorcycleGraph.UV.name, seamless);
        graphCtx.setInput(MotorcycleGraph.SINGULARITIES.name,
                fieldCtx.getOutput(NDirectionField.SINGULARITIES.name, Object.class));
        graphCtx.setInput(MotorcycleGraph.FEATURE_EDGES.name,
                fieldCtx.getOutput(NDirectionField.FEATURE_EDGES.name, Object.class));
        motorcycle.evaluate(graphCtx);
        ArcNetwork graph =
                graphCtx.getOutput(MotorcycleGraph.GRAPH.name, ArcNetwork.class);

        int meshEuler = graph.sourceMesh.vertexCount() - graph.sourceMesh.edgeCount()
                + graph.sourceMesh.faceCount();
        assertEquals(meshEuler, graph.nodes.size() - graph.arcs.size() + graph.patches.size(),
                "the arrangement is a cell complex of the surface");
        assertEquals(graph.patches.size(),
                graphCtx.getOutput(MotorcycleGraph.PATCH_COUNT.name, Integer.class),
                "the patch count output matches the graph");
        assertTrue(graph.patches.size() > 0, "the arrangement has patches");

        QuantizedMeshGrid quantize = new QuantizedMeshGrid();
        MapNodeContext skeletonCtx = new MapNodeContext(quantize);
        skeletonCtx.setInput(QuantizedMeshGrid.GRAPH.name, graph);
        quantize.evaluate(skeletonCtx);
        ArcNetwork skeleton =
                skeletonCtx.getOutput(QuantizedMeshGrid.SKELETON.name, ArcNetwork.class);
        long positiveArcs = skeleton.arcs.stream()
                .filter(arc -> arc.quantizedLength > 0).count();
        assertTrue(positiveArcs > 0, "the skeleton keeps positive arcs");

        LayoutEmbedding embed = new LayoutEmbedding();
        MapNodeContext tmeshCtx = new MapNodeContext(embed);
        tmeshCtx.setInput(LayoutEmbedding.SKELETON.name, skeleton);
        tmeshCtx.setInput(LayoutEmbedding.UV.name, seamless);
        embed.evaluate(tmeshCtx);
        ArcNetwork tmesh =
                tmeshCtx.getOutput(LayoutEmbedding.TMESH.name, ArcNetwork.class);
        assertEquals(graph.patches.size(), tmesh.patches.size(),
                "every arrangement patch becomes one embedded patch");

        NetworkContraction contract = new NetworkContraction();
        MapNodeContext contractedCtx = new MapNodeContext(contract);
        contractedCtx.setInput(NetworkContraction.TMESH.name, tmesh);
        contract.evaluate(contractedCtx);
        ArcNetwork contracted =
                contractedCtx.getOutput(NetworkContraction.TMESH_OUT.name, ArcNetwork.class);
        assertEquals(0, liveZeroArcs(contracted), "contraction leaves no live zero arc");

        GridMapAssembly gridMapNode = new GridMapAssembly();
        MapNodeContext gridCtx = new MapNodeContext(gridMapNode);
        gridCtx.setInput(GridMapAssembly.TMESH.name, contracted);
        gridCtx.setInput(GridMapAssembly.UV.name, seamless);
        gridMapNode.evaluate(gridCtx);
        GlobalGridMap gridMap =
                gridCtx.getOutput(GridMapAssembly.UV_OUT.name, GlobalGridMap.class);
        assertEquals(0, gridMap.offGridNodeCount, "every layout node lands on an integer");

        NewtonSolverNode newton = new NewtonSolverNode();
        MapNodeContext relaxCtx = new MapNodeContext(newton);
        relaxCtx.setInput(NewtonSolverNode.UV.name, gridMap);
        relaxCtx.setInput(NewtonSolverNode.DOFS.name,
                gridCtx.getOutput(GridMapAssembly.DOFS.name, Object.class));
        newton.evaluate(relaxCtx);
        assertTrue(gridMap.gridOptimizer.energyAfter <= gridMap.gridOptimizer.energyBefore,
                "the relaxation does not raise the energy");
        assertEquals(0, gridMap.isoSurfaceRelaxed.flippedFaceCount,
                "the relaxed map flips no face");

        QuadExtractNode extract = new QuadExtractNode();
        MapNodeContext quadCtx = new MapNodeContext(extract);
        quadCtx.setInput(QuadExtractNode.UV.name, gridMap);
        extract.evaluate(quadCtx);
        GeometryBundle quadBundle =
                quadCtx.getOutput(QuadExtractNode.GEOMETRY.name, GeometryBundle.class);
        assertEquals(gridMap.quadGridInitial.quadCount, quadBundle.mesh().faceCount(),
                "the extracted quad mesh carries the quantized quad count");
        assertEquals(meshEuler, gridMap.quadMesh.eulerCharacteristic(),
                "the extracted quad mesh closes to the surface's Euler characteristic");
    }

    private int liveZeroArcs(ArcNetwork tmesh) {
        int zero = 0;
        for (int arcId = 0; arcId < tmesh.arcs.size(); arcId++) {
            if (tmesh.arcs.get(arcId).alive && tmesh.arcs.get(arcId).quantizedLength == 0) {
                zero++;
            }
        }
        return zero;
    }
}
