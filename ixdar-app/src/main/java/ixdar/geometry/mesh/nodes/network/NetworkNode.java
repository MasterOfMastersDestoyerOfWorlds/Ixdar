package ixdar.geometry.mesh.nodes.network;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.paths.NearestVertex;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.api.Vector3Value;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;

/**
 * Authors one network node on the copy vertex nearest an authored point,
 * mutating the {@code net} threaded from the previous authoring statement (see
 * {@link ArcNetworkNode}).
 */
@MeshNodeAnnotation(id = "network_node", desktopOnly = true)
public final class NetworkNode implements MeshNode {

    public static final InputPort NET = new InputPort("net", PortType.ARC_NETWORK, null);
    public static final InputPort POINT = new InputPort("point", PortType.VECTOR3, null);
    public static final InputPort CRITICAL = new InputPort("critical", PortType.BOOLEAN, false);
    public static final InputPort BORDER = new InputPort("border", PortType.BOOLEAN, false);
    public static final OutputPort NET_OUT = new OutputPort(NET.name, PortType.ARC_NETWORK);
    public static final OutputPort ID = new OutputPort("id", PortType.INT);

    @Override
    public List<InputPort> inputs() {
        return List.of(NET, POINT, CRITICAL, BORDER);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(NET_OUT, ID);
    }

    @Override
    public String description() {
        return "Authors one network node on the copy vertex nearest the given point;"
                + " a near-tie between the two closest vertices throws.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                NET.name, "Network being authored, threaded from the previous statement and"
                        + " passed on with the node added.",
                POINT.name, "Authored position; resolved to the nearest copy vertex.",
                CRITICAL.name, "Whether the node's position is prescribed (LCBK19 Def 6.2).",
                BORDER.name, "Whether the node lies in the surface boundary (LCBK19 Def 6.1).",
                ID.name, "The new node's id, for arcs and patches to reference."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        ArcNetwork net = ctx.getInput(NET.name, ArcNetwork.class);
        Vector3Value point = (Vector3Value) ctx.getInput(POINT.name, Object.class);
        boolean critical = Boolean.TRUE.equals(ctx.getInput(CRITICAL.name, Boolean.class));
        boolean border = Boolean.TRUE.equals(ctx.getInput(BORDER.name, Boolean.class));
        int vertex = NearestVertex.find(net.topology.copy, point.x(), point.y(), point.z());
        int nodeId = net.addNode(ArcNetwork.NONE, vertex, critical, border);
        ctx.setOutput(NET_OUT.name, net);
        ctx.setOutput(ID.name, nodeId);
    }
}
