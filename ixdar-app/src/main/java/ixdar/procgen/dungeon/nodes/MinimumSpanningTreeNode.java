package ixdar.procgen.dungeon.nodes;

import java.util.Objects;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.nodes.api.BoolField;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.procgen.dungeon.algo.PrimMinimumSpanningTree;

@MeshNodeAnnotation(id = "minimum_spanning_tree", scopes = { "dungeon" })
public class MinimumSpanningTreeNode implements MeshNode {
    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort EXTRA_EDGE_PROB = new InputPort(
            "extra_edge_prob", PortType.FLOAT, (float) PrimMinimumSpanningTree.DEFAULT_EXTRA_EDGE_PROB, 0f, 1f);
    public static final InputPort SEED = new InputPort("seed", PortType.INT, 0, 0f, 1_000_000f);
    public static final OutputPort SELECTION = new OutputPort("selection", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, EXTRA_EDGE_PROB, SEED);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(SELECTION);
    }

    @Override
    public String description() {
        return "Minimum spanning tree over the input mesh's edges (Prim's, weighted by Euclidean "
                + "distance between endpoint positions), plus a probabilistic extra-edge pass on a "
                + "separate RNG stream. Emits a per-edge BOOLEAN selection over the input geometry. "
                + "Stage 3 of the dungeon pipeline.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Graph mesh whose wire edges are the candidate set (typically delaunay_graph output).",
                EXTRA_EDGE_PROB.name, "Probability [0,1] of keeping each non-MST edge as a loop (default 0.125 per vazgriz).",
                SEED.name, "Seed for the extra-edge RNG stream (separate from upstream room placement).",
                SELECTION.name, "Per-edge BOOLEAN mask in dense edge order: true for MST edges and kept extras.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle graph = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        Number extraProb = ctx.getInput(EXTRA_EDGE_PROB.name, Number.class);
        Number seed = ctx.getInput(SEED.name, Number.class);
        double prob = extraProb == null ? PrimMinimumSpanningTree.DEFAULT_EXTRA_EDGE_PROB : extraProb.doubleValue();
        long seedLong = seed == null ? 0L : seed.longValue();
        boolean[] selection = PrimMinimumSpanningTree.build(graph.mesh(), prob, seedLong);
        ctx.setOutput(SELECTION.name, new BoolField(selection));
    }
}
