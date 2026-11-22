package ixdar.nodegraph.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.fields.crossfield.CrossField;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.FeatureCornerSplit;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.FieldBroadcast;
import ixdar.platform.Platforms;

/**
 * The {@code split_feature_corners} node, the quad layout's first stage: splits the third edge of
 * every triangle with two alignment edges ({@link FeatureCornerSplit}).
 */
@MeshNodeAnnotation(id = "split_feature_corners", desktopOnly = true, category = NodeCategory.QUAD_LAYOUT)
public final class SplitFeatureCornersNode implements MeshNode {

    public static final InputPort GEOMETRY = InputPort.manifoldGeometry("geometry");

    // Boundaries only: a per-edge dihedral test finds noise on scans and organic models and misses
    // rounded CAD creases (FeatureDetectionSuiteBenchmark), so creases are opt-in.
    public static final float BOUNDARIES_ONLY = -2f;

    public static final InputPort FEATURE_DIHEDRAL_COS = new InputPort("feature_dihedral_cos",
            PortType.FLOAT, BOUNDARIES_ONLY);

    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, FEATURE_DIHEDRAL_COS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Chooses the alignment (feature or boundary) edges, leaving the mildest crease of a"
                + " triangle with three unaligned, and splits the third edge of every triangle with"
                + " two, which would collapse it on one iso-line (BCE13 3.2).";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(FEATURE_DIHEDRAL_COS.name, "Crease threshold: an interior edge whose two face"
                + " normals' cosine is below it aligns. The default, below -1, aligns boundaries"
                + " only; set it (0.2 is about 78 degrees) for meshes with real sharp creases.",
                GEOMETRY.name, "Triangle mesh in; out, the same mesh when no triangle needed a"
                + " split, else a split copy (same surface, more triangles, no other slots), with the"
                + " chosen alignment edges in the " + FeatureCornerSplit.ALIGNMENT_SLOT + " slot.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = ctx.getInput(GEOMETRY.name, GeometryBundle.class);
        float featureDihedralCos = FieldBroadcast.floatScalarOrDefault(FieldBroadcast.getInputOrDefault(
                ctx, FEATURE_DIHEDRAL_COS.name, FEATURE_DIHEDRAL_COS.defaultValue), BOUNDARIES_ONLY);
        FeatureCornerSplit split = FeatureCornerSplit.of((HalfEdgeMesh) bundle.mesh(), featureDihedralCos);
        if (split.edgeSplitCount > 0 || split.prunedCount > 0) {
            Platforms.log("%sedgeSplits=%d prunedCreases=%d%n", FeatureCornerSplit.LOG_PREFIX,
                    split.edgeSplitCount, split.prunedCount);
        }
        GeometryBundle out = split.mesh == bundle.mesh() ? bundle : GeometryBundle.ofMesh(split.mesh);
        ctx.setOutput(GEOMETRY.name, out.withSlot(FeatureCornerSplit.ALIGNMENT_SLOT, split.alignmentByEdgeId));
    }
}
