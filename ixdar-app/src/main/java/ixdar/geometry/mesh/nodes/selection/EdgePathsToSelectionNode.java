package ixdar.geometry.mesh.nodes.selection;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

@MeshNodeAnnotation(id = "edge_paths_to_selection")
public class EdgePathsToSelectionNode implements MeshNode {

    private static final InputPort START = new InputPort("start", PortType.BOOLEAN, false);
    private static final InputPort NEXT_VERTEX = new InputPort("next_vertex", PortType.INT, 0);
    private static final OutputPort SELECTION = new OutputPort("selection", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(START, NEXT_VERTEX);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(SELECTION);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        ctx.setOutput("selection", false);
    }
}
