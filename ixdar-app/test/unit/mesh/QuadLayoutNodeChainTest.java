package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.MapNodeContext;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.nodes.data.LoadMeshNode;
import ixdar.geometry.mesh.nodes.quadlayout.ArcQuantizationNode;
import ixdar.geometry.mesh.nodes.quadlayout.CrossFieldNode;
import ixdar.geometry.mesh.nodes.quadlayout.LayoutEmbeddingNode;
import ixdar.geometry.mesh.nodes.quadlayout.MotorcycleGraphNode;
import ixdar.geometry.mesh.nodes.quadlayout.SeamlessUvNode;
import ixdar.geometry.mesh.nodes.quadlayout.TmeshContractNode;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.quantization.LayoutExtraction;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

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

        CrossFieldNode crossField = new CrossFieldNode();
        MapNodeContext fieldCtx = new MapNodeContext(crossField);
        fieldCtx.setInput(CrossFieldNode.GEOMETRY.name, bundle);
        crossField.evaluate(fieldCtx);
        CrossField field = fieldCtx.getOutput(CrossFieldNode.FIELD.name, CrossField.class);
        assertEquals(field.singularities.size(),
                fieldCtx.getOutput(CrossFieldNode.SINGULARITY_COUNT.name, Integer.class),
                "the count output matches the field");

        SeamlessUvNode seamlessUv = new SeamlessUvNode();
        MapNodeContext uvCtx = new MapNodeContext(seamlessUv);
        uvCtx.setInput(SeamlessUvNode.FIELD.name, field);
        seamlessUv.evaluate(uvCtx);
        SeamlessParameterization seamless =
                uvCtx.getOutput(SeamlessUvNode.UV.name, SeamlessParameterization.class);
        assertEquals(0, uvCtx.getOutput(SeamlessUvNode.FLIPPED_TRIANGLES.name, Integer.class),
                "the parametrization flips no triangle");
        assertTrue(uvCtx.getOutput(SeamlessUvNode.INJECTIVE.name, Boolean.class),
                "the parametrization is injective");

        MotorcycleGraphNode motorcycle = new MotorcycleGraphNode();
        MapNodeContext graphCtx = new MapNodeContext(motorcycle);
        graphCtx.setInput(MotorcycleGraphNode.UV.name, seamless);
        motorcycle.evaluate(graphCtx);
        MotorcycleGraph graph =
                graphCtx.getOutput(MotorcycleGraphNode.GRAPH.name, MotorcycleGraph.class);

        int meshEuler = seamless.mesh.vertexCount() - seamless.mesh.edgeCount()
                + seamless.mesh.faceCount();
        assertEquals(meshEuler, graph.nodes.size() - graph.arcs.size() + graph.patches.size(),
                "the arrangement is a cell complex of the surface");
        assertEquals(graph.patches.size(),
                graphCtx.getOutput(MotorcycleGraphNode.PATCH_COUNT.name, Integer.class),
                "the patch count output matches the graph");
        assertTrue(graph.patches.size() > 0, "the arrangement has patches");

        ArcQuantizationNode quantize = new ArcQuantizationNode();
        MapNodeContext skeletonCtx = new MapNodeContext(quantize);
        skeletonCtx.setInput(ArcQuantizationNode.GRAPH.name, graph);
        quantize.evaluate(skeletonCtx);
        LayoutExtraction skeleton =
                skeletonCtx.getOutput(ArcQuantizationNode.SKELETON.name, LayoutExtraction.class);
        assertTrue(skeleton.layoutArcs.size() > 0, "the skeleton keeps positive arcs");

        LayoutEmbeddingNode embed = new LayoutEmbeddingNode();
        MapNodeContext tmeshCtx = new MapNodeContext(embed);
        tmeshCtx.setInput(LayoutEmbeddingNode.SKELETON.name, skeleton);
        embed.evaluate(tmeshCtx);
        EmbeddedTMesh tmesh =
                tmeshCtx.getOutput(LayoutEmbeddingNode.TMESH.name, EmbeddedTMesh.class);
        assertEquals(graph.patches.size(), tmesh.patches.size(),
                "every arrangement patch becomes one embedded patch");

        TmeshContractNode contract = new TmeshContractNode();
        MapNodeContext contractedCtx = new MapNodeContext(contract);
        contractedCtx.setInput(TmeshContractNode.TMESH.name, tmesh);
        contract.evaluate(contractedCtx);
        EmbeddedTMesh contracted =
                contractedCtx.getOutput(TmeshContractNode.TMESH_OUT.name, EmbeddedTMesh.class);
        assertEquals(0, liveZeroArcs(contracted), "contraction leaves no live zero arc");
    }

    private int liveZeroArcs(EmbeddedTMesh tmesh) {
        int zero = 0;
        for (int arcId = 0; arcId < tmesh.arcs.size(); arcId++) {
            if (tmesh.arcs.get(arcId).alive && tmesh.arcs.get(arcId).quantizedLength == 0) {
                zero++;
            }
        }
        return zero;
    }
}
