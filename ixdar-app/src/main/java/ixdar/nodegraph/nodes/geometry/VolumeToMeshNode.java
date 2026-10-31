package ixdar.nodegraph.nodes.geometry;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.volume.MarchingTetrahedra;
import ixdar.geometry.volume.VoxelGrid;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.FieldBroadcast;

/** The surface where a volume crosses a level, as a closed triangle mesh. */
@MeshNodeAnnotation(id = "volume_to_mesh", category = NodeCategory.GEOMETRY_OPERATIONS)
public class VolumeToMeshNode implements MeshNode {
    public static final InputPort VOLUME = new InputPort("volume", PortType.VOLUME, null);
    public static final InputPort LEVEL = new InputPort("level", PortType.FLOAT, 0f, -10f, 10f);
    public static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(VOLUME, LEVEL);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Extracts the surface where a volume crosses a level as a triangle mesh, by marching tetrahedra; "
                + "closed and manifold when the volume's outer layer lies below the level.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                VOLUME.name, "Scalar volume, e.g. silhouette_hull's; values above the level are inside.",
                LEVEL.name, "Value the surface passes through; 0 for a signed distance.",
                GEOMETRY_OUT.name, "Triangle mesh wound outward, crossing points placed linearly along lattice edges.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        VoxelGrid volume = ctx.getInput(VOLUME.name, VoxelGrid.class);
        float level = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, LEVEL.name, LEVEL.defaultValue), 0f);
        ctx.setOutput(GEOMETRY_OUT.name, volume == null ? GeometryBundle.empty()
                : GeometryBundle.ofMesh(MarchingTetrahedra.extract(volume, level)));
    }
}
