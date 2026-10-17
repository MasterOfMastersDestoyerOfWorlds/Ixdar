package ixdar.geometry.mesh.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.quadlayout.extraction.PatchGridExtraction;
import ixdar.geometry.mesh.quadlayout.extraction.PatchSurfaceGeometry;
import ixdar.geometry.mesh.quadlayout.gridmap.GlobalGridMap;

/**
 * The render-ready layout surfaces of an extracted grid map: every live patch's quad grid with
 * per-face patch ids, and the Coons-blend variant of the same topology.
 */
@MeshNodeAnnotation(id = "patch_surfaces", desktopOnly = true)
public class PatchSurfacesNode implements MeshNode {

    public static final InputPort UV = new InputPort("uv", PortType.UV_FIELD, null);
    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);
    public static final OutputPort COONS = new OutputPort("coons", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(UV);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY, COONS);
    }

    @Override
    public String description() {
        return "Builds each live layout patch's quad grid on the surface and its Coons blend, with"
                + " per-face patch ids.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                UV.name, "Global grid map after grid_map_quads extracted it.",
                GEOMETRY.name, "Every live patch's extracted quad grid on the surface.",
                COONS.name, "The same grids as each patch's Coons blend of its boundary."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GlobalGridMap gridMap = (GlobalGridMap) ctx.getInput(UV.name, Object.class);
        PatchGridExtraction grids = PatchGridExtraction.fromRelaxedMap(gridMap);
        ctx.setOutput(GEOMETRY.name, PatchSurfaceGeometry.surfaceBundle(grids));
        ctx.setOutput(COONS.name, PatchSurfaceGeometry.coonsBundle(grids));
    }
}
