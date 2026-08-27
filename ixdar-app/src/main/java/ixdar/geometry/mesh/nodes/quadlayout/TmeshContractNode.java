package ixdar.geometry.mesh.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;

/**
 * Contracts an embedded T-mesh to a fixed point with the three zero-element
 * operators, then, by default, extends surviving T-junctions so the layout
 * conforms. The input T-mesh is mutated in place.
 *
 * <p>See also: LCBK19 Section 6.1 operators 1-3, LCK21a Section 6
 */
@MeshNodeAnnotation(id = "tmesh_contract", desktopOnly = true)
public class TmeshContractNode implements MeshNode {

    public static final InputPort TMESH = new InputPort("tmesh", PortType.ARC_NETWORK, null);
    public static final InputPort CONFORM = new InputPort("conform", PortType.BOOLEAN, Boolean.TRUE);
    public static final OutputPort TMESH_OUT = new OutputPort(TMESH.name, PortType.ARC_NETWORK);

    @Override
    public List<InputPort> inputs() {
        return List.of(TMESH, CONFORM);
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
                CONFORM.name, "Whether to extend T-junctions after contraction so every patch conforms."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        EmbeddedTMesh tmesh = (EmbeddedTMesh) ctx.getInput(TMESH.name, Object.class);
        Boolean conformInput = ctx.getInput(CONFORM.name, Boolean.class);
        boolean conform = conformInput == null || conformInput;
        EmbeddedTMesh contracted = tmesh.contract();
        if (conform) {
            contracted = contracted.conform();
        }
        ctx.setOutput(TMESH_OUT.name, contracted);
    }
}
