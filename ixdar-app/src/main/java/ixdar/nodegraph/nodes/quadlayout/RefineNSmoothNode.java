package ixdar.nodegraph.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.FeatureCornerSplit;
import ixdar.geometry.mesh.quadlayout.NSmoothRefinement;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.platform.Platforms;

/**
 * The {@code refine_n_smooth} node, the quad layout's last mesh change before the cross field:
 * refines the triangles that break KCP13 Appendix B's n-smooth condition
 * ({@link NSmoothRefinement}), carrying the alignment edges through the splits.
 */
@MeshNodeAnnotation(id = "refine_n_smooth", desktopOnly = true, category = NodeCategory.QUAD_LAYOUT)
public final class RefineNSmoothNode implements MeshNode {

    public static final InputPort GEOMETRY = InputPort.manifoldGeometry("geometry");

    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Splits, for every triangle whose curvature breaks KCP13's n-smooth condition"
                + " |4 κ_t| < π, the edge opposite its worst corner until none does, so the cross"
                + " field's triangle indices obey Poincaré-Hopf (KCP13 Appendix B).";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(GEOMETRY.name, "Triangle mesh in, with its " + FeatureCornerSplit.ALIGNMENT_SLOT
                + " slot when it has one; out, the same bundle when every triangle complies, else a"
                + " split copy (same surface, more triangles) carrying only the alignment slot.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = ctx.getInput(GEOMETRY.name, GeometryBundle.class);
        boolean[] alignment = bundle.slots().get(FeatureCornerSplit.ALIGNMENT_SLOT) instanceof boolean[] carried
                ? carried : null;
        NSmoothRefinement refinement = NSmoothRefinement.of((HalfEdgeMesh) bundle.mesh(), alignment);
        if (refinement.mesh == bundle.mesh()) {
            ctx.setOutput(GEOMETRY.name, bundle);
            return;
        }
        Platforms.log("%sviolating=%d edgeSplits=%d rounds=%d%n", NSmoothRefinement.LOG_PREFIX,
                refinement.violatingAtStart, refinement.edgeSplitCount, refinement.roundCount);
        GeometryBundle out = GeometryBundle.ofMesh(refinement.mesh);
        ctx.setOutput(GEOMETRY.name, refinement.alignmentByEdgeId == null ? out
                : out.withSlot(FeatureCornerSplit.ALIGNMENT_SLOT, refinement.alignmentByEdgeId));
    }
}
