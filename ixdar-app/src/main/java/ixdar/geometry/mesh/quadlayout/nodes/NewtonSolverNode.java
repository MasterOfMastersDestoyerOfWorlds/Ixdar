package ixdar.geometry.mesh.quadlayout.nodes;

import java.util.List;
import java.util.Map;

import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.quadlayout.solver.system.DofSystem;
import ixdar.nodegraph.field.UvField;

/**
 * Newton-relaxes an integer grid map's UV field in place, minimizing symmetric
 * Dirichlet energy while keeping the map flip-free.
 *
 * <p>See also: LCK21a Section 7
 */
@MeshNodeAnnotation(id = "newton_solver", desktopOnly = true, category = NodeCategory.QUAD_LAYOUT)
public class NewtonSolverNode implements MeshNode {

    public static final InputPort UV = new InputPort("uv", PortType.UV_FIELD, null);
    public static final InputPort DOFS = new InputPort("dofs", PortType.DOF_SYSTEM, null);
    public static final OutputPort UV_OUT = new OutputPort(UV.name, PortType.UV_FIELD);
    public static final OutputPort DOFS_OUT = new OutputPort(DOFS.name, PortType.DOF_SYSTEM);

    @Override
    public List<InputPort> inputs() {
        return List.of(UV, DOFS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(UV_OUT, DOFS_OUT);
    }

    @Override
    public String description() {
        return "Newton-relaxes an integer grid map's UV field in place, minimizing symmetric"
                + " Dirichlet energy while keeping the map flip-free.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                UV.name, "UV field in (relaxed in place) and the relaxed UV field out.",
                DOFS.name, "The DOF system coupling the field's coordinates, from the same node"
                        + " that produced the field."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        UvField uv = ctx.getInput(UV.name, UvField.class);
        DofSystem dofs = ctx.getInput(DOFS.name, DofSystem.class);
        dofs.relax();
        ctx.setOutput(UV_OUT.name, uv);
        ctx.setOutput(DOFS.name, dofs);
    }
}
