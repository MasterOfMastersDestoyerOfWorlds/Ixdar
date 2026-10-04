package ixdar.geometry.mesh.nodes.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.load.GltfMeshWriter;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.load.PlyMeshWriter;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.ModeConstraint;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Writes the incoming geometry to a mesh file and passes the same bundle on, so an export can sit
 * inline in a graph. Exists so an expensive stage such as {@code repair_mesh} runs once and every
 * later graph loads its result.
 */
@MeshNodeAnnotation(id = "export_mesh", desktopOnly = true)
public class ExportMeshNode implements MeshNode {

    public static final String FORMAT_GLB = "GLB";

    public static final String FORMAT_PLY = "PLY";

    public static final InputPort GEOMETRY =
            new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);

    public static final InputPort PATH = new InputPort("path", PortType.STRING, "");

    public static final InputPort FORMAT = new InputPort("format", PortType.STRING, "",
            new ModeConstraint(FORMAT_GLB, List.of(FORMAT_GLB, FORMAT_PLY), Map.of()));

    public static final OutputPort GEOMETRY_OUT =
            new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, PATH, FORMAT);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Writes the incoming geometry to a .glb or .ply file and passes the bundle through"
                + " unchanged, so a graph can park an expensive result on disk and later graphs"
                + " load it instead of recomputing it.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output. The bundle is written and then passed on untouched,"
                        + " including its slots; the file carries positions, normals, triangles and"
                        + " the per-corner " + CornerUvField.SLOT + " field, with seam vertices"
                        + " re-split so a per-vertex format can hold it. Materials are not written"
                        + " yet.",
                PATH.name, "File to write, absolute or relative to the working directory. Missing"
                        + " parent directories are created; an empty path writes nothing.",
                FORMAT.name, "GLB or PLY. Empty (the default) takes the format from the path's"
                        + " extension, defaulting to GLB."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(
                ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        ctx.setOutput(GEOMETRY.name, base);
        Object pathInput = FieldBroadcast.getInputOrDefault(ctx, PATH.name, PATH.defaultValue);
        String path = pathInput instanceof String text ? text.trim() : "";
        if (path.isEmpty()) {
            return;
        }
        Object formatInput = FieldBroadcast.getInputOrDefault(ctx, FORMAT.name, FORMAT.defaultValue);
        String requested = formatInput instanceof String text ? text : "";
        String format = formatOf(path, requested);
        try {
            Path file = Path.of(path);
            if (FORMAT_PLY.equals(format)) {
                PlyMeshWriter.write(base, file);
            } else {
                GltfMeshWriter.write(base, file);
            }
        } catch (IOException failed) {
            throw new UncheckedIOException("export_mesh failed for " + path, failed);
        }
    }

    /**
     * The format an export should be written in: what the caller asked for, else what the path's
     * extension names. Shared with the {@code /mesh/export} endpoint so both spell it the same way.
     *
     * @param path file to write
     * @param requested the caller's {@code format}, which may be null or empty
     * @return {@link #FORMAT_PLY} or {@link #FORMAT_GLB}
     * @throws IllegalArgumentException when {@code requested} names neither format
     */
    public static String formatOf(String path, String requested) {
        if (requested != null && !requested.trim().isEmpty()) {
            return FORMAT.modes.normalize(requested);
        }
        return path.toLowerCase().endsWith(MeshLoader.PLY_EXTENSION) ? FORMAT_PLY : FORMAT_GLB;
    }
}
