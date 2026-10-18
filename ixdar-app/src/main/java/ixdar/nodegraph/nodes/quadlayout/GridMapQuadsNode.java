package ixdar.nodegraph.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.quadlayout.gridmap.GlobalGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapAssembly;

/**
 * Extracts the pure quad mesh of a relaxed global grid map (LCK21a §7), verifying the map and
 * recording the per-patch grids on it for the patch-surface step.
 */
@MeshNodeAnnotation(id = "grid_map_quads", desktopOnly = true, category = NodeCategory.QUAD_LAYOUT)
public class GridMapQuadsNode implements MeshNode {

    public static final InputPort UV = new InputPort("uv", PortType.UV_FIELD, null);
    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);
    public static final OutputPort UV_OUT = new OutputPort(UV.name, PortType.UV_FIELD);

    @Override
    public List<InputPort> inputs() {
        return List.of(UV);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY, UV_OUT);
    }

    @Override
    public String description() {
        return "Extracts the quad mesh of a relaxed global grid map and records its per-patch grids"
                + " on the map.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                UV.name, "Global grid map relaxed by newton_solver; the same map, now carrying its"
                        + " extraction, out.",
                GEOMETRY.name, "The extracted pure quad mesh."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GlobalGridMap gridMap = (GlobalGridMap) ctx.getInput(UV.name, Object.class);
        GridMapAssembly.extractQuads(gridMap);
        ctx.setOutput(GEOMETRY.name, GeometryBundle.ofMesh(gridMap.quadMesh.toArrayMesh()));
        ctx.setOutput(UV_OUT.name, gridMap);
    }
}
