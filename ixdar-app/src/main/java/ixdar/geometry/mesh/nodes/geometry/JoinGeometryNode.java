package ixdar.geometry.mesh.nodes.geometry;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshAppend;
import ixdar.geometry.mesh.data.MeshTopology;

@MeshNodeAnnotation(id = "join_geometry")
public class JoinGeometryNode implements MeshNode {

    private static final InputPort A = new InputPort("a", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort B = new InputPort("b", PortType.GEOMETRY_BUNDLE, null);
    private static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(A, B);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle ga = GeometryBundles.bundlePart(ctx.getInput("a", Object.class));
        GeometryBundle gb = GeometryBundles.bundlePart(ctx.getInput("b", Object.class));
        MeshTopology ma = ga == null ? null : ga.mesh();
        MeshTopology mb = gb == null ? null : gb.mesh();
        if (ma == null || ma.vertexCount() == 0) {
            if (gb != null) {
                ctx.setOutput("geometry", gb);
            } else {
                ctx.setOutput("geometry", GeometryBundle.empty());
            }
            return;
        }
        if (mb == null || mb.vertexCount() == 0) {
            ctx.setOutput("geometry", ga);
            return;
        }
        var joined = MeshAppend.join(ma, mb);
        ctx.setOutput("geometry", GeometryBundle.ofMesh(joined));
    }
}
