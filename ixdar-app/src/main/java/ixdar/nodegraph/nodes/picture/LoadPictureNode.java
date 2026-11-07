package ixdar.nodegraph.nodes.picture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.graphics.image.SubjectOutline;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;

/**
 * Brings a PNG or JPEG picture into the graph, its path resolved as {@code load_mesh} resolves one.
 */
@MeshNodeAnnotation(id = LoadPictureNode.NODE_ID, desktopOnly = true, category = NodeCategory.FIELD_DATA)
public class LoadPictureNode implements MeshNode {

    public static final String NODE_ID = "load_picture";

    public static final InputPort PATH = new InputPort("path", PortType.STRING, "");

    public static final OutputPort IMAGE = new OutputPort("image", PortType.IMAGE);

    @Override
    public List<InputPort> inputs() {
        return List.of(PATH);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(IMAGE);
    }

    @Override
    public String description() {
        return "Loads a PNG or JPEG picture, such as a reference photograph of a subject, for trace_outline and "
                + "shading_edges; relative paths resolve as load_mesh's do.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                PATH.name, "Picture file: relative to the working directory, the ixdar-app module or its "
                        + "src/main/resources (dsl/<subject>/references/ref_01_front.png), or below this "
                        + "machine's data root with a " + MeshLoader.DATA_ROOT_PLACEHOLDER + " prefix.",
                IMAGE.name, "The decoded picture, row 0 at its top; empty input gives none.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        String path = ctx.getInputValue(PATH.name) instanceof String text ? text.trim() : "";
        if (path.isEmpty()) {
            ctx.setOutput(IMAGE.name, null);
            return;
        }
        try {
            ctx.setOutput(IMAGE.name, SubjectOutline.read(MeshLoader.resolve(path)));
        } catch (IOException e) {
            throw new UncheckedIOException(NODE_ID + " failed for " + path, e);
        }
    }
}
