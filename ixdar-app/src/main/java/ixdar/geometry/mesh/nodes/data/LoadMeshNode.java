package ixdar.geometry.mesh.nodes.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Loads a mesh from an OBJ, PLY, OFF or glTF ({@code .glb}/{@code .gltf}) file into the graph;
 * glTF texture coordinates ride the bundle as {@link MeshLoader#UV_SLOT}. Paths resolve
 * absolute, or relative to the working directory with an {@code ixdar-app/} retry.
 */
@MeshNodeAnnotation(id = "load_mesh", desktopOnly = true)
public class LoadMeshNode implements MeshNode {

    public static final InputPort PATH = new InputPort("path", PortType.STRING, "");
    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(PATH);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public String description() {
        return "Loads a mesh from an OBJ, PLY, OFF or glTF (.glb/.gltf) file, resolving relative paths"
                + " against the working directory and the ixdar-app module directory.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                PATH.name, "File path to load; .obj, .ply, .off, .glb and .gltf are supported.",
                GEOMETRY.name, "The loaded mesh as a geometry bundle; glTF files add the per-vertex "
                        + MeshLoader.UV_SLOT + " slot holding TEXCOORD_0 as (u, v, 0)."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Object pathInput = FieldBroadcast.getInputOrDefault(ctx, PATH.name, PATH.defaultValue);
        String path = pathInput instanceof String text ? text : "";
        if (path.isEmpty()) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }
        try {
            ctx.setOutput(GEOMETRY.name, MeshLoader.loadBundle(path));
        } catch (IOException e) {
            throw new UncheckedIOException("load_mesh failed for " + path, e);
        }
    }
}
