package ixdar.nodegraph.nodes.network;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.bundle.GeometryBundles;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.model.EmbeddedMeshTopology;

/**
 * Starts an authored network: a fresh {@link ArcNetwork} over a fresh
 * working copy of the carrier mesh.
 *
 * <p>
 * The {@code net} value is one shared mutable network threaded linearly: each
 * authoring statement consumes the previous statement's output, forcing
 * evaluation order. Authored walks are counter-clockwise seen from outside,
 * interior on the left.
 */
@MeshNodeAnnotation(id = "arc_network", desktopOnly = true, category = NodeCategory.NETWORK)
public final class ArcNetworkNode implements MeshNode {

    public static final InputPort MESH = InputPort.manifoldGeometry("mesh");
    public static final OutputPort NET = new OutputPort("net", PortType.ARC_NETWORK);

    @Override
    public List<InputPort> inputs() {
        return List.of(MESH);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(NET);
    }

    @Override
    public String description() {
        return "Starts an authored network: an empty node-arc-patch network over a fresh"
                + " working copy of the carrier mesh, interior left of every walk.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                MESH.name, "Carrier triangle mesh the network is embedded in.",
                NET.name, "The empty network, threaded linearly through the authoring nodes."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(
                (HalfEdgeMesh) GeometryBundles.meshPart(ctx.getInput(MESH.name, GeometryBundle.class)));
        ArcNetwork net = new ArcNetwork(topology);
        ctx.setOutput(NET.name, net);
    }
}
