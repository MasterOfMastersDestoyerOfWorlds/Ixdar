package ixdar.geometry.mesh.nodes.network;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;

/**
 * Authors one network patch on the {@code net} threaded from the previous
 * statement (see {@link ArcNetworkNode}): the boundary is the one traced face
 * cycle splitting at the four corner nodes. Corners may repeat, sides may be
 * empty, and side arc counts pin down an ambiguous split.
 */
@MeshNodeAnnotation(id = "network_patch", desktopOnly = true)
public final class NetworkPatch implements MeshNode {

    /** Count value meaning a side's arc count is unconstrained. */
    public static final int UNCONSTRAINED = -1;

    public static final InputPort NET = new InputPort("net", PortType.ARC_NETWORK, null);
    public static final InputPort A = new InputPort("a", PortType.INT, null);
    public static final InputPort B = new InputPort("b", PortType.INT, null);
    public static final InputPort C = new InputPort("c", PortType.INT, null);
    public static final InputPort D = new InputPort("d", PortType.INT, null);
    public static final InputPort FIRST_SIDE =
            new InputPort("first_side", PortType.INT, UNCONSTRAINED);
    public static final InputPort SECOND_SIDE =
            new InputPort("second_side", PortType.INT, UNCONSTRAINED);
    public static final InputPort THIRD_SIDE =
            new InputPort("third_side", PortType.INT, UNCONSTRAINED);
    public static final InputPort FOURTH_SIDE =
            new InputPort("fourth_side", PortType.INT, UNCONSTRAINED);
    public static final OutputPort NET_OUT = new OutputPort(NET.name, PortType.ARC_NETWORK);
    public static final OutputPort ID = new OutputPort("id", PortType.INT);

    @Override
    public List<InputPort> inputs() {
        return List.of(NET, A, B, C, D, FIRST_SIDE, SECOND_SIDE, THIRD_SIDE, FOURTH_SIDE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(NET_OUT, ID);
    }

    @Override
    public String description() {
        return "Authors one network patch by tracing the arc graph's faces and splitting"
                + " the one face matching the four corner nodes and any given side counts.";
    }

    @Override
    public Map<String, String> socketDocs() {
        Map<String, String> docs = new HashMap<>();
        docs.put(NET.name, "Network being authored, threaded from the previous statement and"
                + " passed on with the patch added.");
        docs.put(A.name, "First corner node id, where the first side starts.");
        docs.put(B.name, "Second corner node id; corners may repeat.");
        docs.put(C.name, "Third corner node id; corners may repeat.");
        docs.put(D.name, "Fourth corner node id; corners may repeat.");
        docs.put(FIRST_SIDE.name, "Arc count of the first side, or -1 for unconstrained.");
        docs.put(SECOND_SIDE.name, "Arc count of the second side, or -1 for unconstrained.");
        docs.put(THIRD_SIDE.name, "Arc count of the third side, or -1 for unconstrained.");
        docs.put(FOURTH_SIDE.name, "Arc count of the fourth side, or -1 for unconstrained.");
        docs.put(ID.name, "The new patch's id.");
        return docs;
    }

    @Override
    public void evaluate(NodeContext ctx) {
        ArcNetwork net = ctx.getInput(NET.name, ArcNetwork.class);
        int a = ctx.getInput(A.name, Number.class).intValue();
        int b = ctx.getInput(B.name, Number.class).intValue();
        int c = ctx.getInput(C.name, Number.class).intValue();
        int d = ctx.getInput(D.name, Number.class).intValue();
        int firstSide = sideCount(ctx, FIRST_SIDE);
        int secondSide = sideCount(ctx, SECOND_SIDE);
        int thirdSide = sideCount(ctx, THIRD_SIDE);
        int fourthSide = sideCount(ctx, FOURTH_SIDE);
        int patchId = new NetworkTracer(net).addPatch(a, b, c, d,
                firstSide, secondSide, thirdSide, fourthSide);
        ctx.setOutput(NET_OUT.name, net);
        ctx.setOutput(ID.name, patchId);
    }

    private static int sideCount(NodeContext ctx, InputPort port) {
        return ((Number) FieldBroadcast.getInputOrDefault(ctx, port.name,
                port.defaultValue)).intValue();
    }
}
