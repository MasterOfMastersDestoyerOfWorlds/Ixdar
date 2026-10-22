package ixdar.nodegraph.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.gridmap.GlobalGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapAssembly;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.FieldBroadcast;
import ixdar.nodegraph.field.UvField;

/**
 * The {@code integer_grid_map} node: assembles the integer grid map of a contracted T-mesh with
 * {@link GridMapAssembly}.
 */
@MeshNodeAnnotation(id = "integer_grid_map", desktopOnly = true, category = NodeCategory.QUAD_LAYOUT)
public final class GridMapAssemblyNode implements MeshNode {

    public static final InputPort TMESH = new InputPort("tmesh", PortType.ARC_NETWORK, null);
    public static final InputPort UV = new InputPort("uv", PortType.UV_FIELD, null);
    public static final InputPort TARGET_EDGE_LENGTH = new InputPort("target_edge_length",
            PortType.FLOAT, GridMapAssembly.DEFAULT_TARGET_EDGE_LENGTH);
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
        ArcNetwork contracted = (ArcNetwork) ctx.getInput(TMESH.name, Object.class);
        UvField constraint = (UvField) ctx.getInput(UV.name, Object.class);
        float targetEdgeLength = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, TARGET_EDGE_LENGTH.name,
                        TARGET_EDGE_LENGTH.defaultValue),
                GridMapAssembly.DEFAULT_TARGET_EDGE_LENGTH);
        GlobalGridMap gridMap = new GridMapAssembly().assemble(contracted, constraint,
                targetEdgeLength);
        ctx.setOutput(UV_OUT.name, gridMap);
        ctx.setOutput(DOFS.name, gridMap.gridDofs.system);
        ctx.setOutput(CHARTS.name, gridMap.atlas);
    }
}
