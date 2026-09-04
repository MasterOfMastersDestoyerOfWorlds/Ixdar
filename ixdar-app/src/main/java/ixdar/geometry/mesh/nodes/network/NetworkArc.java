package ixdar.geometry.mesh.nodes.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.paths.NearestVertex;
import ixdar.geometry.mesh.data.paths.UniqueShortestPath;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.api.Vector3Value;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;

/**
 * Authors one network arc between two nodes on the {@code net} threaded
 * from the previous statement (see {@link ArcNetworkNode}). Each via point
 * resolves to its nearest copy vertex; the path concatenates the unique
 * shortest edge paths between consecutive waypoints, and a tied segment
 * throws.
 */
@MeshNodeAnnotation(id = "network_arc", desktopOnly = true)
public final class NetworkArc implements MeshNode {

    /** Maximum via waypoints an authored arc can carry. */
    public static final int VIA_COUNT = 8;

    public static final InputPort NET = new InputPort("net", PortType.ARC_NETWORK, null);
    public static final InputPort FROM = new InputPort("from", PortType.INT, null);
    public static final InputPort TO = new InputPort("to", PortType.INT, null);
    public static final InputPort LENGTH = new InputPort("length", PortType.INT, 0);
    public static final InputPort FEATURE = new InputPort("feature", PortType.BOOLEAN, false);
    public static final List<InputPort> VIAS = buildViaPorts();
    public static final OutputPort NET_OUT = new OutputPort(NET.name, PortType.ARC_NETWORK);
    public static final OutputPort ID = new OutputPort("id", PortType.INT);

    @Override
    public List<InputPort> inputs() {
        List<InputPort> ports = new ArrayList<>();
        ports.add(NET);
        ports.add(FROM);
        ports.add(TO);
        ports.add(LENGTH);
        ports.add(FEATURE);
        ports.addAll(VIAS);
        return ports;
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(NET_OUT, ID);
    }

    @Override
    public String description() {
        return "Authors one network arc as the unique shortest edge path between two"
                + " authored nodes, through optional via waypoints; tied paths throw.";
    }

    @Override
    public Map<String, String> socketDocs() {
        Map<String, String> docs = new HashMap<>();
        docs.put(NET.name, "Network being authored, threaded from the previous statement and"
                + " passed on with the arc added.");
        docs.put(FROM.name, "Node id the arc runs from.");
        docs.put(TO.name, "Node id the arc runs to.");
        docs.put(LENGTH.name, "Prescribed quantized length, never negative.");
        docs.put(FEATURE.name, "Whether the arc lies on a feature or boundary curve.");
        for (int index = 0; index < VIA_COUNT; index++) {
            docs.put(VIAS.get(index).name, "Optional waypoint " + (index + 1)
                    + ", resolved to its nearest copy vertex; unset vias are skipped.");
        }
        docs.put(ID.name, "The new arc's id, for patches to reference.");
        return docs;
    }

    @Override
    public void evaluate(NodeContext ctx) {
        ArcNetwork net = ctx.getInput(NET.name, ArcNetwork.class);
        int from = ctx.getInput(FROM.name, Number.class).intValue();
        int to = ctx.getInput(TO.name, Number.class).intValue();
        int length = ((Number) FieldBroadcast.getInputOrDefault(ctx, LENGTH.name,
                LENGTH.defaultValue)).intValue();
        boolean feature = Boolean.TRUE.equals(ctx.getInput(FEATURE.name, Boolean.class));
        List<Integer> waypoints = new ArrayList<>();
        waypoints.add(net.nodes.get(from).copyVertex);
        for (InputPort via : VIAS) {
            Object value = ctx.getInput(via.name, Object.class);
            if (value == null) {
                continue;
            }
            Vector3Value point = (Vector3Value) value;
            waypoints.add(NearestVertex.find(net.topology.copy,
                    point.x(), point.y(), point.z()));
        }
        waypoints.add(net.nodes.get(to).copyVertex);
        List<Integer> path = new ArrayList<>();
        for (int leg = 1; leg < waypoints.size(); leg++) {
            List<Integer> segment = UniqueShortestPath.find(net.topology.copy,
                    waypoints.get(leg - 1), waypoints.get(leg));
            path.addAll(path.isEmpty() ? segment : segment.subList(1, segment.size()));
        }
        int arcId = net.addArc(ArcNetwork.NONE, from, to, length, feature, path);
        ctx.setOutput(NET_OUT.name, net);
        ctx.setOutput(ID.name, arcId);
    }

    private static List<InputPort> buildViaPorts() {
        List<InputPort> ports = new ArrayList<>(VIA_COUNT);
        for (int index = 1; index <= VIA_COUNT; index++) {
            ports.add(new InputPort("via" + index, PortType.VECTOR3, null));
        }
        return List.copyOf(ports);
    }
}
