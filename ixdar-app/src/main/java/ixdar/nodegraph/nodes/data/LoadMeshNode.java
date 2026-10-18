package ixdar.nodegraph.nodes.data;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import ixdar.geometry.mesh.data.bundle.CornerUvField;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.nodegraph.field.FieldBroadcast;

/**
 * Loads a mesh from an OBJ, PLY, OFF or glTF ({@code .glb}/{@code .gltf}) file into the graph;
 * glTF texture coordinates ride the bundle's {@link CornerUvField#SLOT} slot as a per-corner
 * {@code UvField}. Paths resolve
 * absolute, or relative to the working directory with an {@code ixdar-app/} retry.
 */
@MeshNodeAnnotation(id = "load_mesh", desktopOnly = true, category = NodeCategory.FIELD_DATA)
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
                GEOMETRY.name, "The loaded mesh as a geometry bundle; glTF files weld duplicate "
                        + "positions and add the " + CornerUvField.SLOT
                        + " slot holding TEXCOORD_0 as a per-corner UvField."
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
