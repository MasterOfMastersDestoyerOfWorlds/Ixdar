package unit.mesh;

import java.util.List;

import ixdar.nodegraph.runtime.GraphNodeContext;
import ixdar.nodegraph.runtime.GroupBuilder;
import ixdar.nodegraph.runtime.NodeGroup;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.nodes.math.FloatMathNode;

/**
 * A compiled node group small enough to check by hand: float nodes {@code total} and
 * {@code product} wired to outputs {@code sum} and {@code product}, and a computed
 * {@code difference}.
 */
public class SumAndProductGroup extends NodeGroup {

    public static final InputPort A = new InputPort(FloatMathNode.A.name, PortType.FLOAT, 0f);
    public static final InputPort B = new InputPort(FloatMathNode.B.name, PortType.FLOAT, 0f);
    public static final OutputPort SUM = new OutputPort("sum", PortType.FLOAT);
    public static final OutputPort PRODUCT = new OutputPort("product", PortType.FLOAT);
    public static final OutputPort DIFFERENCE = new OutputPort("difference", PortType.FLOAT);
    public static final String TOTAL_NODE = "total";
    public static final String MULTIPLY = "MULTIPLY";

    @Override
    public List<InputPort> inputs() {
        return List.of(A, B);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(SUM, PRODUCT, DIFFERENCE);
    }

    @Override
    public void build(GroupBuilder group) {
        GraphNodeContext total = group.node(TOTAL_NODE, FloatMathNode::new)
                .input(A.name, group.scope, A.name).input(B.name, group.scope, B.name);
        GraphNodeContext product = group.node(PRODUCT.name, FloatMathNode::new)
                .input(FloatMathNode.OPERATION.name, MULTIPLY)
                .input(A.name, group.scope, A.name).input(B.name, group.scope, B.name);
        group.output(SUM.name, total, FloatMathNode.RESULT.name);
        group.output(PRODUCT.name, product, FloatMathNode.RESULT.name);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        ctx.setOutput(DIFFERENCE.name, ctx.getOutput(SUM.name, Float.class)
                - ctx.getOutput(PRODUCT.name, Float.class));
    }
}
