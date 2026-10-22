package ixdar.nodegraph.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.LayoutEmbedding;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.UvField;

/**
 * The {@code layout_embedding} node: carves a quantized skeleton onto a working copy of the mesh
 * with {@link LayoutEmbedding}.
 */
@MeshNodeAnnotation(id = "layout_embedding", desktopOnly = true, category = NodeCategory.QUAD_LAYOUT)
public final class LayoutEmbeddingNode implements MeshNode {

    public static final InputPort SKELETON = new InputPort("skeleton", PortType.ARC_NETWORK, null);
    public static final InputPort UV = new InputPort("uv", PortType.UV_FIELD, null);
    public static final OutputPort TMESH = new OutputPort("tmesh", PortType.ARC_NETWORK);

    @Override
    public List<InputPort> inputs() {
        return List.of(SKELETON, UV);
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
                UV.name, "Seamless UV field the carve reads chart coordinates from.",
                TMESH.name, "The embedded T-mesh, uncontracted; zero arcs and patches remain."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        ArcNetwork skeleton = (ArcNetwork) ctx.getInput(SKELETON.name, Object.class);
        UvField field = (UvField) ctx.getInput(UV.name, Object.class);
        ctx.setOutput(TMESH.name, new LayoutEmbedding().build(skeleton, field));
    }
}
