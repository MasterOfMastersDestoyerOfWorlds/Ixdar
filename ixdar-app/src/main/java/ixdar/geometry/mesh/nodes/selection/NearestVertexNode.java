package ixdar.geometry.mesh.nodes.selection;

import java.util.Objects;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.NearestVertex;
import ixdar.geometry.mesh.nodes.api.BoolField;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.api.Vector3Value;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Per-vertex selection of the one vertex nearest a point, delegating to
 * {@link NearestVertex}: a deterministic pick where
 * {@code select_by_distance} selects a ball. A near-tie between the two
 * closest vertices throws instead of picking by id.
 */
@MeshNodeAnnotation(id = "nearest_vertex")
public class NearestVertexNode implements MeshNode {
    public static final float NUM_0 = 0f;

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort POINT = new InputPort("point", PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    public static final OutputPort SELECTION = new OutputPort("selection", PortType.BOOLEAN);
    public static final OutputPort INDEX = new OutputPort("index", PortType.INT);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, POINT);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(SELECTION, INDEX);
    }

    @Override
    public String description() {
        return "Selects the one vertex nearest a point: a per-vertex boolean selection with"
                + " exactly one true entry, plus that vertex's index; a near-tie between the"
                + " two closest vertices throws.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Geometry bundle whose vertices are scanned.",
                POINT.name, "World-space point resolved to its nearest vertex.",
                SELECTION.name, "Per-vertex BOOLEAN mask with exactly the nearest vertex true.",
                INDEX.name, "Index of the selected vertex, matching the selection field."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        Vector3Value pt = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, POINT.name, POINT.defaultValue),
                new Vector3Value(NUM_0, NUM_0, NUM_0));
        MeshTopology mesh = base.mesh();
        if (mesh == null) {
            throw new IllegalStateException("nearest vertex to (" + pt.x() + ", " + pt.y()
                    + ", " + pt.z() + "): the geometry has no mesh");
        }
        int vertexId = NearestVertex.find(mesh, pt.x(), pt.y(), pt.z());
        int vc = mesh.vertexCount();
        boolean[] sel = new boolean[vc];
        int index = -1;
        for (int i = 0; i < vc; i++) {
            if (mesh.vertexIdAt(i) == vertexId) {
                sel[i] = true;
                index = i;
                break;
            }
        }
        ctx.setOutput(SELECTION.name, new BoolField(sel));
        ctx.setOutput(INDEX.name, index);
    }
}
