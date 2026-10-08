package ixdar.geometry.mesh.nodes.data;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.SurfaceCreases;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;

/**
 * Measures a surface's grooves once, so every crease-mode ring downstream shares one read-only
 * {@link SurfaceCreases} cost field.
 */
@MeshNodeAnnotation(id = "surface_creases")
public class SurfaceCreasesNode implements MeshNode {

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE,
            null);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name,
            PortType.GEOMETRY_BUNDLE);
    public static final OutputPort CREASES = new OutputPort("creases", PortType.SURFACE_CREASES);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT, CREASES);
    }

    @Override
    public String description() {
        return "Measures the surface's grooves once: the minimum principal curvature, the "
                + "curvature across a valley, diffused over a few edges and ranked into a valley "
                + "strength per vertex, and each edge's length scaled down where it runs in a "
                + "groove. Concave grooves are cheap to follow and convex ridges are not. The "
                + "value is never written after it is built, so every ring wired to it shares one.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name,
                "Input: the surface to measure, which must be the very mesh the rings using the "
                        + "creases ring (read it from surface_metric's geometry). Output: the "
                        + "same bundle, unchanged.",
                CREASES.name,
                "The surface's valley strength and crease path cost, for spline_ring's creases "
                        + "input, which its crease mode follows.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = ctx.getInput(GEOMETRY.name, GeometryBundle.class);
        if (bundle == null) {
            bundle = GeometryBundle.empty();
        }
        ctx.setOutput(GEOMETRY.name, bundle);
        MeshTopology mesh = bundle.mesh();
        ctx.setOutput(CREASES.name, mesh == null || mesh.faceCount() == 0 ? null
                : SurfaceCreases.of(mesh));
    }
}
