package ixdar.nodegraph.nodes.geometry;

import java.util.List;
import java.util.Map;

import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.ops.MeshAppend;
import ixdar.geometry.mesh.data.ops.MeshMergeByDistance;
import ixdar.geometry.mesh.data.bundle.SlotCarry;

@MeshNodeAnnotation(id = "join_geometry", category = NodeCategory.GEOMETRY_OPERATIONS)
public class JoinGeometryNode implements MeshNode {
    public static final InputPort A = new InputPort("a", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort B = new InputPort("b", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort MERGE_DISTANCE = new InputPort("merge_distance", PortType.FLOAT, 0f, 0f, 1f);
    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(A, B, MERGE_DISTANCE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public String description() {
        return "Combines two geometry inputs into one mesh, merging tags and bone weights. Optionally welds nearby vertices within merge_distance.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                A.name, "First geometry bundle. Tags/weights from both are preserved in the output.",
                B.name, "Second geometry bundle.",
                MERGE_DISTANCE.name, "Weld threshold for seam vertices. 0 = no weld (a and b remain disjoint); typical 0.001 for light seam cleanup.",
                GEOMETRY.name, "Combined bundle."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle ga = ctx.getInput(A.name, GeometryBundle.class);
        GeometryBundle gb = ctx.getInput(B.name, GeometryBundle.class);
        MeshTopology ma = ga == null ? null : ga.mesh();
        MeshTopology mb = gb == null ? null : gb.mesh();
        if (ma == null || ma.vertexCount() == 0) {
            if (gb != null) {
                ctx.setOutput(GEOMETRY.name, gb);
            } else {
                ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            }
            return;
        }
        if (mb == null || mb.vertexCount() == 0) {
            ctx.setOutput(GEOMETRY.name, ga);
            return;
        }
        Number mergeDist = ctx.getInput(MERGE_DISTANCE.name, Number.class);
        float md = mergeDist == null ? 0f : mergeDist.floatValue();

        GeometryBundle result = SlotCarry.carry(List.of(ga, gb),
                GeometryBundle.ofMesh(MeshAppend.join(ma, mb)));
        if (md > 0f) {
            result = SlotCarry.carry(List.of(result),
                    GeometryBundle.ofMesh(MeshMergeByDistance.merge(result.mesh(), md)));
        }
        ctx.setOutput(GEOMETRY.name, result);
    }
}
