package ixdar.geometry.mesh.nodes.data;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;

/**
 * Measures a surface's intrinsic metric and connection once, so every node downstream that
 * traces geodesics or transports directions on it shares one read-only {@link SurfaceMetric}.
 */
@MeshNodeAnnotation(id = "surface_metric", category = NodeCategory.FIELD_DATA)
public class SurfaceMetricNode implements MeshNode {

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE,
            null);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name,
            PortType.GEOMETRY_BUNDLE);
    public static final OutputPort METRIC = new OutputPort("metric", PortType.SURFACE_METRIC);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT, METRIC);
    }

    @Override
    public String description() {
        return "Measures the surface's intrinsic metric and connection once: the signpost "
                + "intrinsic triangulation (Sharp, Soliman & Crane 2019) with its edge lengths, "
                + "per-vertex signpost angles and angle sums, reference half-edges, connectivity "
                + "and source-mesh mapping, plus the mean edge length and a vertex grid that "
                + "lands authored points on the surface. The value is never written after it is "
                + "built, so every consumer wired to it shares one.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name,
                "Input: the surface to measure, of any polygon sizes; larger polygons are split "
                        + "intrinsically. Output: the same bundle on the half-edge mesh the "
                        + "metric was measured on; consumers of the metric must read their "
                        + "geometry from here or downstream of it.",
                METRIC.name,
                "The surface's metric and connection, for spline_ring's metric input and any "
                        + "other node that measures geodesics or angles on this surface.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = ctx.getInput(GEOMETRY.name, GeometryBundle.class);
        if (bundle == null) {
            bundle = GeometryBundle.empty();
        }
        MeshTopology mesh = bundle.mesh();
        if (mesh == null || mesh.faceCount() == 0) {
            ctx.setOutput(GEOMETRY.name, bundle);
            ctx.setOutput(METRIC.name, null);
            return;
        }
        HalfEdgeMesh surface = HalfEdgeMeshEngine.fromMeshTopology(mesh);
        ctx.setOutput(GEOMETRY.name, surface == mesh ? bundle : bundle.withMesh(surface));
        ctx.setOutput(METRIC.name, SurfaceMetric.of(surface));
    }
}
