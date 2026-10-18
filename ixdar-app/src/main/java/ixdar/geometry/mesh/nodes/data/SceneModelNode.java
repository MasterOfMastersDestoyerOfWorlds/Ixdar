package ixdar.geometry.mesh.nodes.data;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;

/**
 * The model the host scene loaded, as the graph's input: a scene graph starts
 * {@code model = scene_model()} and never names a mesh path, so the scene's model switch feeds it.
 */
@MeshNodeAnnotation(id = "scene_model", category = NodeCategory.FIELD_DATA)
public class SceneModelNode implements MeshNode {

    public static final String BINDING = "model";

    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of();
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public String description() {
        return "The model the host scene loaded, bound by the scene; standalone graphs use load_mesh"
                + " instead.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(GEOMETRY.name, "The scene's loaded model as a geometry bundle.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        if (!(ctx.graphInput(BINDING) instanceof GeometryBundle model)) {
            throw new IllegalStateException("scene_model() has no model: it runs only in a graph"
                    + " a model scene executes; a standalone graph uses load_mesh(path=...)");
        }
        ctx.setOutput(GEOMETRY.name, model);
    }
}
