package ixdar.geometry.mesh.nodes.quadlayout;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.extraction.ExtractedQuadMesh;
import ixdar.geometry.mesh.quadlayout.gridmap.GlobalGridMap;

/**
 * QEx extraction: vertices at the preimages of integer grid points,
 * connectivity by tracing unit iso-segments, faces by clockwise port cycling.
 * The generic mesh-plus-UV input form is planned; today the input is the
 * integer grid map's chart UV field.
 *
 * <p>See also: EBC13
 */
@MeshNodeAnnotation(id = "quad_extract", desktopOnly = true)
public class QuadExtractNode implements MeshNode {

    public static final InputPort UV = new InputPort("uv", PortType.UV_FIELD, null);
    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(UV);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public String description() {
        return "Extracts the pure quad mesh of a UV field: vertices at integer grid point"
                + " preimages, connectivity by tracing unit iso-segments (QEx).";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                UV.name, "UV field to extract from, typically relaxed by a newton_solver node.",
                GEOMETRY.name, "The extracted quad mesh as a geometry bundle."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GlobalGridMap gridMap = (GlobalGridMap) ctx.getInput(UV.name, Object.class);
        gridMap.extractQuads();
        ExtractedQuadMesh quads = gridMap.quadMesh;
        ArrayMesh mesh = ArrayMesh.fromQuads(
                Arrays.copyOf(quads.positions, quads.quadVertexCount * ExtractedQuadMesh.POSITION_FLOATS),
                Arrays.copyOf(quads.quadCorner, quads.quadCount * ExtractedQuadMesh.QUAD_CORNERS));
        ctx.setOutput(GEOMETRY.name, GeometryBundle.ofMesh(mesh));
    }
}
