package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.nodegraph.api.MapNodeContext;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.nodegraph.nodes.data.LoadMeshNode;
import ixdar.nodegraph.nodes.quadlayout.GridMapAssemblyNode;
import ixdar.nodegraph.nodes.quadlayout.LayoutEmbeddingNode;
import ixdar.nodegraph.nodes.quadlayout.MotorcycleGraphNode;
import ixdar.nodegraph.nodes.quadlayout.NDirectionFieldNode;
import ixdar.nodegraph.nodes.quadlayout.NetworkContractionNode;
import ixdar.nodegraph.nodes.quadlayout.NewtonSolverNode;
import ixdar.nodegraph.nodes.quadlayout.QuadExtractNode;
import ixdar.nodegraph.nodes.quadlayout.QuantizedMeshGridNode;
import ixdar.nodegraph.nodes.quadlayout.SeamlessParameterizationNode;
import ixdar.geometry.mesh.data.fields.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.gridmap.GlobalGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapAssembly;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapIsoSurface;
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

        NDirectionFieldNode crossField = new NDirectionFieldNode();
        MapNodeContext fieldCtx = new MapNodeContext(crossField);
        fieldCtx.setInput(NDirectionFieldNode.GEOMETRY.name, bundle);
        crossField.evaluate(fieldCtx);
        CrossField field = fieldCtx.getOutput(NDirectionFieldNode.FIELD.name, CrossField.class);
        assertEquals(field.singularityCount(),
                fieldCtx.getOutput(NDirectionFieldNode.SINGULARITY_COUNT.name, Integer.class),
                "the count output matches the field");

        SeamlessParameterizationNode seamlessUv = new SeamlessParameterizationNode();
        MapNodeContext uvCtx = new MapNodeContext(seamlessUv);
        uvCtx.setInput(SeamlessParameterizationNode.FIELD.name, field);
        seamlessUv.evaluate(uvCtx);
        SeamlessUv seamless =
                uvCtx.getOutput(SeamlessParameterizationNode.UV.name, SeamlessUv.class);
        assertEquals(0, uvCtx.getOutput(SeamlessParameterizationNode.FLIPPED_TRIANGLES.name,
                Integer.class), "the parametrization flips no triangle");
        assertTrue(uvCtx.getOutput(SeamlessParameterizationNode.INJECTIVE.name, Boolean.class),
                "the parametrization is injective");

        MotorcycleGraphNode motorcycle = new MotorcycleGraphNode();
        MapNodeContext graphCtx = new MapNodeContext(motorcycle);
        graphCtx.setInput(MotorcycleGraphNode.GEOMETRY.name, bundle);
        graphCtx.setInput(MotorcycleGraphNode.UV.name, seamless);
        graphCtx.setInput(MotorcycleGraphNode.CHARTS.name,
                uvCtx.getOutput(SeamlessParameterizationNode.CHARTS.name, Object.class));
        graphCtx.setInput(MotorcycleGraphNode.SINGULARITIES.name,
                fieldCtx.getOutput(NDirectionFieldNode.SINGULARITIES.name, Object.class));
        graphCtx.setInput(MotorcycleGraphNode.FEATURE_EDGES.name,
                fieldCtx.getOutput(NDirectionFieldNode.FEATURE_EDGES.name, Object.class));
        motorcycle.evaluate(graphCtx);
        ArcNetwork graph =
                graphCtx.getOutput(MotorcycleGraphNode.GRAPH.name, ArcNetwork.class);

        int meshEuler = graph.sourceMesh.vertexCount() - graph.sourceMesh.edgeCount()
                + graph.sourceMesh.faceCount();
        assertEquals(meshEuler, graph.nodes.size() - graph.arcs.size() + graph.patches.size(),
                "the arrangement is a cell complex of the surface");
        assertEquals(graph.patches.size(),
                graphCtx.getOutput(MotorcycleGraphNode.PATCH_COUNT.name, Integer.class),
                "the patch count output matches the graph");
        assertTrue(graph.patches.size() > 0, "the arrangement has patches");

        QuantizedMeshGridNode quantize = new QuantizedMeshGridNode();
        MapNodeContext skeletonCtx = new MapNodeContext(quantize);
        skeletonCtx.setInput(QuantizedMeshGridNode.GRAPH.name, graph);
        quantize.evaluate(skeletonCtx);
        ArcNetwork skeleton =
                skeletonCtx.getOutput(QuantizedMeshGridNode.SKELETON.name, ArcNetwork.class);
        long positiveArcs = skeleton.arcs.stream()
                .filter(arc -> arc.quantizedLength > 0).count();
        assertTrue(positiveArcs > 0, "the skeleton keeps positive arcs");

        LayoutEmbeddingNode embed = new LayoutEmbeddingNode();
        MapNodeContext tmeshCtx = new MapNodeContext(embed);
        tmeshCtx.setInput(LayoutEmbeddingNode.SKELETON.name, skeleton);
        tmeshCtx.setInput(LayoutEmbeddingNode.UV.name, seamless);
        embed.evaluate(tmeshCtx);
        ArcNetwork tmesh =
                tmeshCtx.getOutput(LayoutEmbeddingNode.TMESH.name, ArcNetwork.class);
        assertEquals(graph.patches.size(), tmesh.patches.size(),
                "every arrangement patch becomes one embedded patch");

        NetworkContractionNode contract = new NetworkContractionNode();
        MapNodeContext contractedCtx = new MapNodeContext(contract);
        contractedCtx.setInput(NetworkContractionNode.TMESH.name, tmesh);
        contract.evaluate(contractedCtx);
        ArcNetwork contracted =
                contractedCtx.getOutput(NetworkContractionNode.TMESH_OUT.name, ArcNetwork.class);
        assertEquals(0, liveZeroArcs(contracted), "contraction leaves no live zero arc");

        GridMapAssemblyNode gridMapNode = new GridMapAssemblyNode();
        MapNodeContext gridCtx = new MapNodeContext(gridMapNode);
        gridCtx.setInput(GridMapAssemblyNode.TMESH.name, contracted);
        gridCtx.setInput(GridMapAssemblyNode.UV.name, seamless);
        gridMapNode.evaluate(gridCtx);
        GlobalGridMap gridMap =
                gridCtx.getOutput(GridMapAssemblyNode.UV_OUT.name, GlobalGridMap.class);
        assertEquals(0, gridMap.offGridNodeCount, "every layout node lands on an integer");

        NewtonSolverNode newton = new NewtonSolverNode();
        MapNodeContext relaxCtx = new MapNodeContext(newton);
        relaxCtx.setInput(NewtonSolverNode.UV.name, gridMap);
        relaxCtx.setInput(NewtonSolverNode.DOFS.name,
                gridCtx.getOutput(GridMapAssemblyNode.DOFS.name, Object.class));
        newton.evaluate(relaxCtx);
        assertTrue(gridMap.gridOptimizer.energyAfter <= gridMap.gridOptimizer.energyBefore,
                "the relaxation does not raise the energy");
        assertEquals(0, gridMap.isoSurfaceRelaxed.flippedFaceCount,
                "the relaxed map flips no face");

        GridMapAssembly.extractQuads(gridMap);
        assertEquals(meshEuler, gridMap.quadMesh.eulerCharacteristic(),
                "the extracted quad mesh closes to the surface's Euler characteristic");

        QuadExtractNode extract = new QuadExtractNode();
        MapNodeContext quadCtx = new MapNodeContext(extract);
        quadCtx.setInput(QuadExtractNode.UV.name,
                new GridMapIsoSurface(gridMap.patchMaps, gridMap.uvByPatchId).build());
        quadCtx.setInput(QuadExtractNode.GEOMETRY.name,
                GeometryBundle.ofMesh(contracted.topology.copy));
        quadCtx.setInput(QuadExtractNode.TMESH.name, contracted);
        extract.evaluate(quadCtx);
        GeometryBundle quadBundle =
                quadCtx.getOutput(QuadExtractNode.GEOMETRY_OUT.name, GeometryBundle.class);
        assertEquals(gridMap.quadGridInitial.quadCount, quadBundle.mesh().faceCount(),
                "the extracted quad mesh carries the quantized quad count");
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
