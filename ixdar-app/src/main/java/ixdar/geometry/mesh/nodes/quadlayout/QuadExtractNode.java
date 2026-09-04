package ixdar.geometry.mesh.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.api.UvField;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.extraction.QuadMeshExtraction;

/**
 * QEx extraction: vertices at the preimages of integer grid points,
 * connectivity by tracing unit iso-segments, faces by clockwise port cycling.
 * Face-local over the mesh, any {@link UvField} covering it, and the layout
 * network's node valences; regrouping onto a layout is the grid map's own step.
 *
 * <p>See also: EBC13
 */
@MeshNodeAnnotation(id = "quad_extract", desktopOnly = true)
public class QuadExtractNode implements MeshNode {

    public static final InputPort UV = new InputPort("uv", PortType.UV_FIELD, null);
    public static final InputPort GEOMETRY = new InputPort("geometry",
            PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort TMESH = new InputPort("tmesh", PortType.ARC_NETWORK, null);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name,
            PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(UV, GEOMETRY, TMESH);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
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
                GEOMETRY.name, "The mesh the UV field's per-corner coordinates are read over in;"
                        + " the extracted quad mesh as a geometry bundle out.",
                TMESH.name, "The layout network, read for node valences at layout vertices."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        UvField uvField = (UvField) ctx.getInput(UV.name, Object.class);
        GeometryBundle bundle = ctx.getInput(GEOMETRY.name, GeometryBundle.class);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.fromMeshTopology(bundle.mesh());
        ArcNetwork tmesh = (ArcNetwork) ctx.getInput(TMESH.name, Object.class);
        QuadMeshExtraction extraction = new QuadMeshExtraction(mesh, uvField, tmesh);
        ctx.setOutput(GEOMETRY_OUT.name, GeometryBundle.ofMesh(extraction.build().toArrayMesh()));
    }
}
