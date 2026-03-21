package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

@MeshNodeAnnotation(id = "input_shortest_edge_paths")
public class InputShortestEdgePathsNode implements MeshNode {

    private static final InputPort END = new InputPort("end", PortType.BOOLEAN, false);
    private static final InputPort EDGE_COST = new InputPort("edge_cost", PortType.FLOAT, 1.0f);
    private static final OutputPort NEXT_VERTEX = new OutputPort("next_vertex", PortType.INT);
    private static final OutputPort TOTAL_COST = new OutputPort("total_cost", PortType.FLOAT);

    @Override
    public List<InputPort> inputs() {
        return List.of(END, EDGE_COST);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(NEXT_VERTEX, TOTAL_COST);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        ctx.setOutput("next_vertex", 0);
        ctx.setOutput("total_cost", 0f);
    }
}
