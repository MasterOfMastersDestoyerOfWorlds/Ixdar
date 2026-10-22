package ixdar.nodegraph.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;

/**
 * The {@code tmesh_contract} node: contracts an embedded T-mesh to its fixed point with
 * {@link NetworkContraction}, optionally conforming and re-carving it.
 */
@MeshNodeAnnotation(id = "tmesh_contract", desktopOnly = true, category = NodeCategory.QUAD_LAYOUT)
public final class NetworkContractionNode implements MeshNode {

    public static final InputPort TMESH = new InputPort("tmesh", PortType.ARC_NETWORK, null);
    public static final InputPort CONFORM = new InputPort("conform", PortType.BOOLEAN, Boolean.TRUE);
    public static final InputPort RECARVE = new InputPort("recarve", PortType.BOOLEAN, Boolean.TRUE);
    public static final OutputPort TMESH_OUT = new OutputPort(TMESH.name, PortType.ARC_NETWORK);

    @Override
    public List<InputPort> inputs() {
        return List.of(TMESH, CONFORM, RECARVE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(TMESH_OUT);
    }

    @Override
    public String description() {
        return "Contracts an embedded T-mesh until no zero arc or patch remains, then optionally"
                + " extends surviving T-junctions so the layout conforms.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                TMESH.name, "Embedded T-mesh in (from layout_embedding, mutated in place) and the"
                        + " contracted, by default conforming, T-mesh out.",
                CONFORM.name, "Whether to extend T-junctions after contraction so every patch conforms.",
                RECARVE.name, "Whether to conform and replay the contracted layout onto a fresh working"
                        + " copy; off, the rounds run in place and surviving T-junctions remain."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        ArcNetwork input = (ArcNetwork) ctx.getInput(TMESH.name, Object.class);
        Boolean conformInput = ctx.getInput(CONFORM.name, Boolean.class);
        Boolean recarveInput = ctx.getInput(RECARVE.name, Boolean.class);
        boolean conformRequested = conformInput == null || conformInput;
        boolean recarveRequested = recarveInput == null || recarveInput;
        NetworkContraction contraction = new NetworkContraction(input);
        ArcNetwork result = recarveRequested ? contraction.contract() : contraction.contractRounds();
        if (conformRequested) {
            result = new NetworkContraction(result).conform();
        }
        ctx.setOutput(TMESH_OUT.name, result);
    }
}
