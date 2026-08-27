package ixdar.geometry.mesh.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.gridmap.GlobalGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.IntegerGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.LayoutPatchMaps;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * Maps every layout patch onto its quantized rectangle with a harmonic
 * embedding and frames the patches in one common integer grid: the UV field
 * the relaxation optimizes.
 *
 * <p>See also: LCBK19 Section 6.2
 */
@MeshNodeAnnotation(id = "integer_grid_map", desktopOnly = true)
public class IntegerGridMapNode implements MeshNode {

    public static final float DEFAULT_TARGET_EDGE_LENGTH = 1.0f;

    public static final InputPort TMESH = new InputPort("tmesh", PortType.ARC_NETWORK, null);
    public static final InputPort UV = new InputPort("uv", PortType.UV_FIELD, null);
    public static final InputPort TARGET_EDGE_LENGTH = new InputPort("target_edge_length",
            PortType.FLOAT, DEFAULT_TARGET_EDGE_LENGTH);
    public static final OutputPort UV_OUT = new OutputPort(UV.name, PortType.UV_FIELD);
    public static final OutputPort DOFS = new OutputPort("dofs", PortType.DOF_SYSTEM);
    public static final OutputPort CHARTS = new OutputPort("charts", PortType.CHART_ATLAS);

    @Override
    public List<InputPort> inputs() {
        return List.of(TMESH, UV, TARGET_EDGE_LENGTH);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(UV_OUT, DOFS, CHARTS);
    }

    @Override
    public String description() {
        return "Maps every layout patch onto its quantized rectangle and frames the patches in"
                + " one common integer grid, the UV field the relaxation optimizes.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                TMESH.name, "Contracted, conforming T-mesh, from a tmesh_contract node.",
                UV.name, "Seamless UV field in (constrains the map); the framed integer grid map"
                        + " UV field out.",
                TARGET_EDGE_LENGTH.name, "Parametric length one quad edge spans.",
                DOFS.name, "The map's DOF system, what a newton_solver node relaxes.",
                CHARTS.name, "The map's patch charts and the arc transitions between them."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        EmbeddedTMesh tmesh = (EmbeddedTMesh) ctx.getInput(TMESH.name, Object.class);
        SeamlessParameterization seamless =
                (SeamlessParameterization) ctx.getInput(UV.name, Object.class);
        float targetEdgeLength = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, TARGET_EDGE_LENGTH.name,
                        TARGET_EDGE_LENGTH.defaultValue),
                DEFAULT_TARGET_EDGE_LENGTH);
        LayoutPatchMaps patchMaps = new LayoutPatchMaps(tmesh, seamless, targetEdgeLength);
        patchMaps.build();
        IntegerGridMap frames = new IntegerGridMap(tmesh).build();
        GlobalGridMap gridMap = new GlobalGridMap(patchMaps, frames, seamless).buildInitialMap();
        ctx.setOutput(UV_OUT.name, gridMap);
        ctx.setOutput(DOFS.name, gridMap.gridDofs.system);
        ctx.setOutput(CHARTS.name, gridMap.atlas);
    }
}
