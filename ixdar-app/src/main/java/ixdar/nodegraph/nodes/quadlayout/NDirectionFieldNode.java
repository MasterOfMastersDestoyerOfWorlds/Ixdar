package ixdar.nodegraph.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.fields.crossfield.CrossField;
import ixdar.geometry.mesh.data.fields.crossfield.NDirectionField;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.FieldBroadcast;

/**
 * The {@code cross_field} node: builds the quad-layout cross field of a mesh with
 * {@link NDirectionField} and extracts its singularities.
 */
@MeshNodeAnnotation(id = "cross_field", desktopOnly = true, category = NodeCategory.QUAD_LAYOUT)
public final class NDirectionFieldNode implements MeshNode {

    public static final InputPort GEOMETRY = InputPort.manifoldGeometry("geometry");
    public static final InputPort CURVATURE_BIAS = new InputPort("curvature_bias", PortType.FLOAT,
            -1f);
    public static final InputPort METRIC = new InputPort("metric", PortType.SURFACE_METRIC, null);
    public static final OutputPort FIELD = new OutputPort("field", PortType.CROSS_FIELD);
    public static final OutputPort SINGULARITY_COUNT = new OutputPort("singularity_count", PortType.INT);
    public static final OutputPort SINGULARITIES = new OutputPort("singularities",
            PortType.INT);
    public static final OutputPort FEATURE_EDGES = new OutputPort("feature_edges",
            PortType.BOOLEAN);
    public static final OutputPort DOFS = new OutputPort("dofs", PortType.DOF_SYSTEM);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, CURVATURE_BIAS, METRIC);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(FIELD, SINGULARITY_COUNT, SINGULARITIES, FEATURE_EDGES, DOFS);
    }

    @Override
    public String description() {
        return "Builds the quad-layout cross field (curvature-aligned n-direction field) over a"
                + " triangle mesh and extracts its singularities. Vertex frames, corner angles and"
                + " transport come from the surface's metric and connection, so the field's"
                + " directions agree with geodesics traced on the same metric.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Triangle mesh to build the field on; manifold, possibly with boundary.",
                FIELD.name, "The cross field with per-face frames and singularities.",
                SINGULARITY_COUNT.name, "Number of extracted singularities.",
                SINGULARITIES.name, "Per-vertex singularity index4 attribute (0 = not singular),"
                        + " for tracing stages.",
                CURVATURE_BIAS.name, "Where the field puts singularities, in [-1, 1]: 0 is"
                        + " Dirichlet and -1 pulls them onto high Gaussian curvature.",
                FEATURE_EDGES.name, "Per-edge selection of sharp feature and boundary edges.",
                DOFS.name, "The smoothing solve's system, for solver-composing graphs.",
                METRIC.name, "Optional: the metric output of a surface_metric statement measured on"
                        + " this very mesh, so the field shares its frames and connection with"
                        + " ring geodesics; read geometry from that statement. Absent, the node"
                        + " measures the mesh itself."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = ctx.getInput(GEOMETRY.name, GeometryBundle.class);
        NDirectionField solve = new NDirectionField();
        solve.curvatureBias = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, CURVATURE_BIAS.name,
                        CURVATURE_BIAS.defaultValue), 0f);
        HalfEdgeMesh surface = (HalfEdgeMesh) bundle.mesh();
        SurfaceMetric surfaceMetric = ctx.getInput(METRIC.name, SurfaceMetric.class);
        if (surfaceMetric == null) {
            surfaceMetric = SurfaceMetric.of(surface);
        } else if (surfaceMetric.sourceMesh != surface) {
            throw new IllegalArgumentException("cross_field: its metric was measured on another"
                    + " mesh; read the geometry from the surface_metric statement that measured"
                    + " it");
        }
        CrossField field = solve.build(surface, surfaceMetric);
        ctx.setOutput(FIELD.name, field);
        ctx.setOutput(SINGULARITY_COUNT.name, field.singularityCount());
        ctx.setOutput(SINGULARITIES.name, field.singularityIndex4);
        ctx.setOutput(FEATURE_EDGES.name, field.alignmentEdges);
        ctx.setOutput(DOFS.name, field.system);
    }
}
