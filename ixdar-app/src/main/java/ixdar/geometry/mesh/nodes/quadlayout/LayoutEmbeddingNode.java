package ixdar.geometry.mesh.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.LayoutEmbedding;
import ixdar.geometry.mesh.quadlayout.quantization.LayoutExtraction;

/**
 * Carves a quantized skeleton onto a working copy of its mesh, nodes onto
 * vertices and arcs onto edge paths, and assembles the embedded T-mesh,
 * validated against the surface's Euler characteristic. Zero arcs and patches
 * are still present; contraction is a separate node.
 *
 * <p>See also: LCBK19 Section 6.1
 */
@MeshNodeAnnotation(id = "layout_embedding", desktopOnly = true)
public class LayoutEmbeddingNode implements MeshNode {

    public static final InputPort SKELETON = new InputPort("skeleton", PortType.ARC_NETWORK, null);
    public static final OutputPort TMESH = new OutputPort("tmesh", PortType.ARC_NETWORK);

    @Override
    public List<InputPort> inputs() {
        return List.of(SKELETON);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(TMESH);
    }

    @Override
    public String description() {
        return "Carves a quantized skeleton onto a working copy of the mesh and assembles the"
                + " embedded T-mesh, validated as a cell decomposition of the surface.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                SKELETON.name, "Quantized skeleton to embed, from an arc_quantization node.",
                TMESH.name, "The embedded T-mesh, uncontracted; zero arcs and patches remain."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        LayoutExtraction skeleton = (LayoutExtraction) ctx.getInput(SKELETON.name, Object.class);
        LayoutEmbedding embedding = new LayoutEmbedding(skeleton).build();
        EmbeddedTMesh tmesh = new EmbeddedTMesh(embedding.topology).build(embedding);
        tmesh.validate();
        ctx.setOutput(TMESH.name, tmesh);
    }
}
