package ixdar.geometry.mesh.nodes.network;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

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
@MeshNodeAnnotation(id = "arc_network", desktopOnly = true)
public final class ArcNetworkNode implements MeshNode {

    public static final InputPort MESH = new InputPort("mesh", PortType.GEOMETRY_BUNDLE, null);
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
        MeshTopology mesh = GeometryBundles.meshPart(ctx.getInput(MESH.name, GeometryBundle.class));
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(
                HalfEdgeMeshEngine.fromMeshTopology(mesh));
        ArcNetwork net = new ArcNetwork(topology);
        ctx.setOutput(NET.name, net);
    }
}
