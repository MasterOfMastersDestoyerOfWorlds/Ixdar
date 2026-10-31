package ixdar.nodegraph.nodes.modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.sculpt.SculptCageFile;
import ixdar.geometry.mesh.data.sculpt.StrokeSculpt;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.FieldBroadcast;

/**
 * Sculpts geometry with weighted strokes lifted off multiview screenshots by {@code stroke-lift}:
 * normal displacement with a falloff plus a crease (mode B), or the crease alone (mode A).
 */
@MeshNodeAnnotation(id = "stroke_sculpt", category = NodeCategory.MODIFIERS)
public class StrokeSculptNode implements MeshNode {
    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort STROKES = new InputPort("strokes", PortType.STRING, "");
    public static final InputPort DISPLACE = new InputPort("displace", PortType.BOOLEAN, true);
    public static final InputPort SCALE = new InputPort("scale", PortType.FLOAT, 0.2f);
    public static final InputPort FALLOFF = new InputPort("falloff", PortType.FLOAT, 0.4f);
    public static final InputPort CREASE = new InputPort("crease", PortType.FLOAT, 2f);
    public static final InputPort BASE = new InputPort("base", PortType.STRING, "");
    public static final InputPort STORE = new InputPort("store", PortType.STRING, "");
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, STROKES, DISPLACE, SCALE, FALLOFF, CREASE, BASE, STORE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Sculpts geometry with weighted strokes: each snaps to an edge chain, moves nearby vertices along the normal by its strength with a smooth falloff, and creases the chain by its absolute strength.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output. The output carries the strokes' crease marks merged into the float \"crease\" edge marks for subdivision_surface.",
                STROKES.name, "Strokes as `stroke-lift` prints them: \"s r=R [fill] [mirror]: x y z w, ...; ...\", s in [-1, 1] (positive a ridge, negative a valley), R the falloff radius, w each point's view weight; fill sculpts the region under the points, mirror repeats the stroke at -x, smooth relaxes the region instead of displacing it.",
                DISPLACE.name, "true moves vertices and creases (mode B); false only creases (mode A).",
                SCALE.name, "Distance along the normal a strength-1 stroke moves its chain.",
                FALLOFF.name, "Falloff radius, as an edge-path distance, of strokes whose header names none.",
                CREASE.name, "Crease weight a strength-1 stroke writes on its chain's edges.",
                BASE.name, "Cage a previous round stored; when the file exists it replaces `geometry`, so rounds build on each other. Paths resolve as load_mesh's do.",
                STORE.name, "File the sculpted cage and its creases are written to for the next round's `base`; empty writes nothing."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        StrokeSculpt sculpt = new StrokeSculpt();
        sculpt.displace = !(FieldBroadcast.getInputOrDefault(ctx, DISPLACE.name, DISPLACE.defaultValue) instanceof Boolean b) || b;
        sculpt.displacementScale = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, SCALE.name, SCALE.defaultValue), sculpt.displacementScale);
        sculpt.falloffRadius = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, FALLOFF.name, FALLOFF.defaultValue), sculpt.falloffRadius);
        sculpt.creaseScale = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, CREASE.name, CREASE.defaultValue), sculpt.creaseScale);
        Object strokes = ctx.getInputValue(STROKES.name);
        String basePath = ctx.getInputValue(BASE.name) instanceof String text ? text : "";
        String storePath = ctx.getInputValue(STORE.name) instanceof String text ? text : "";
        try {
            if (!basePath.isBlank() && Files.exists(MeshLoader.resolve(basePath))) {
                base = SculptCageFile.read(MeshLoader.resolve(basePath));
            }
            GeometryBundle sculpted = sculpt.apply(base, strokes instanceof String text ? text : "");
            if (!storePath.isBlank()) {
                SculptCageFile.write(sculpted, Paths.get(storePath));
            }
            ctx.setOutput(GEOMETRY.name, sculpted);
        } catch (IOException failure) {
            throw new IllegalStateException("stroke_sculpt cannot read or store its cage: "
                    + failure.getMessage(), failure);
        }
    }
}
