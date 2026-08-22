package ixdar.geometry.mesh.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.crossfield.NDirectionField;

/**
 * Builds the quad-layout cross field over a triangle mesh: the Knöppel
 * n-direction field with curvature alignment and soft feature/boundary
 * guidance, with its singularities extracted.
 *
 * <p>See also: Lyon 2021 stages 1-2
 */
@MeshNodeAnnotation(id = "cross_field", desktopOnly = true)
public class CrossFieldNode implements MeshNode {

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final OutputPort FIELD = new OutputPort("field", PortType.CROSS_FIELD);
    public static final OutputPort SINGULARITY_COUNT = new OutputPort("singularity_count", PortType.INT);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(FIELD, SINGULARITY_COUNT);
    }

    @Override
    public String description() {
        return "Builds the quad-layout cross field (curvature-aligned n-direction field) over a"
                + " triangle mesh and extracts its singularities.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Triangle mesh to build the field on; manifold, possibly with boundary.",
                FIELD.name, "The cross field with per-face frames and singularities.",
                SINGULARITY_COUNT.name, "Number of extracted singularities."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = GeometryBundles.bundlePart(ctx.getInput(GEOMETRY.name, Object.class));
        CrossField field = new NDirectionField(HalfEdgeMeshEngine.fromMeshTopology(bundle.mesh())).build();
        ctx.setOutput(FIELD.name, field);
        ctx.setOutput(SINGULARITY_COUNT.name, field.singularities.size());
    }
}
