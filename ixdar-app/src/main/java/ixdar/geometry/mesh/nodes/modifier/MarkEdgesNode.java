package ixdar.geometry.mesh.nodes.modifier;

import java.util.Objects;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.ModeConstraint;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Writes a named, typed per-edge mark into the {@link EdgeMarks#SLOT} slot, indexed by edge id.
 * The {@code type} mode selects which value input is read, the way {@code random_value} switches
 * outputs. Consumers ask for a label at a type through {@link EdgeMarks}, e.g.
 * {@code subdivision_surface} reads float {@code "crease"}.
 */
@MeshNodeAnnotation(id = "mark_edges")
public class MarkEdgesNode implements MeshNode {
    public static final String TYPE_FLOAT = "FLOAT";
    public static final String TYPE_INT = "INT";
    public static final String TYPE_BOOL = "BOOL";

    /** Label {@code subdivision_surface} reads its crease weights from. */
    public static final String CREASE_LABEL = "crease";

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort LABEL = new InputPort("label", PortType.STRING, CREASE_LABEL);
    public static final InputPort TYPE = new InputPort("type", PortType.STRING, TYPE_FLOAT,
            new ModeConstraint(TYPE_FLOAT, List.of(TYPE_FLOAT, TYPE_INT, TYPE_BOOL), Map.of()));
    public static final InputPort SELECTION = new InputPort("selection", PortType.BOOLEAN, true);
    public static final InputPort FACE_BOUNDARY = new InputPort("face_boundary", PortType.BOOLEAN, false);
    public static final InputPort VALUE_FLOAT = new InputPort("value_float", PortType.FLOAT, 1.0f);
    public static final InputPort VALUE_INT = new InputPort("value_int", PortType.INT, 1);
    public static final InputPort VALUE_BOOL = new InputPort("value_bool", PortType.BOOLEAN, true);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, LABEL, TYPE, SELECTION, FACE_BOUNDARY, VALUE_FLOAT, VALUE_INT, VALUE_BOOL);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Marks selected edges with a named, typed value (float, int or boolean) that downstream nodes read by label, such as crease weights for subdivision or arc marks for T-meshes.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output. Output carries the marks in the edge-marks slot, merged with any existing labels.",
                LABEL.name, "Name downstream consumers look the marks up by, e.g. \"crease\".",
                TYPE.name, "Which value input is written: FLOAT, INT or BOOL. Existing marks under the label are merged float-by-max, int-by-write, bool-by-or.",
                SELECTION.name, "Per-face OR per-edge BOOLEAN mask choosing which edges are marked.",
                FACE_BOUNDARY.name, "If true, interpret `selection` as per-face and mark only the boundary edges of selected regions; if false, per-edge directly.",
                VALUE_FLOAT.name, "Value written when type is FLOAT, e.g. a crease weight where 0 is smooth and higher stays sharp for more subdivision levels.",
                VALUE_INT.name, "Value written when type is INT, e.g. a quantized arc length.",
                VALUE_BOOL.name, "Value written when type is BOOL, e.g. a plain arc mark."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.edgeCount() == 0) {
            ctx.setOutput(GEOMETRY.name, base);
            return;
        }

        String label = stringInput(ctx, LABEL.name, CREASE_LABEL);
        String type = stringInput(ctx, TYPE.name, TYPE_FLOAT).toUpperCase();
        Object selObj = FieldBroadcast.getInputOrDefault(ctx, SELECTION.name, SELECTION.defaultValue);
        Object fbObj = FieldBroadcast.getInputOrDefault(ctx, FACE_BOUNDARY.name, FACE_BOUNDARY.defaultValue);
        boolean faceBoundary = fbObj instanceof Boolean b && b;

        int maxEdgeId = 0;
        for (int i = 0; i < mesh.edgeCount(); i++) {
            maxEdgeId = Math.max(maxEdgeId, mesh.edgeIdAt(i));
        }
        boolean[] selected = new boolean[maxEdgeId + 1];
        if (faceBoundary) {
            int faceCount = mesh.faceCount();
            for (int fi = 0; fi < faceCount; fi++) {
                if (!FieldBroadcast.boolAt(selObj, fi, true)) {
                    continue;
                }
                int fid = mesh.faceIdAt(fi);
                int edgeCount = mesh.faceEdgeCount(fid);
                for (int k = 0; k < edgeCount; k++) {
                    int eid = mesh.faceEdgeAt(fid, k);
                    if (eid >= 0) {
                        selected[eid] = true;
                    }
                }
            }
        } else {
            int edgeCount = mesh.edgeCount();
            for (int ei = 0; ei < edgeCount; ei++) {
                if (FieldBroadcast.boolAt(selObj, ei, true)) {
                    selected[mesh.edgeIdAt(ei)] = true;
                }
            }
        }

        Object marks = switch (type) {
            case TYPE_INT -> markInts(ctx, base, label, selected);
            case TYPE_BOOL -> markBools(base, label, selected);
            default -> markFloats(ctx, base, label, selected);
        };
        ctx.setOutput(GEOMETRY.name, EdgeMarks.with(base, label, marks));
    }

    private static float[] markFloats(NodeContext ctx, GeometryBundle base, String label, boolean[] selected) {
        float value = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, VALUE_FLOAT.name, VALUE_FLOAT.defaultValue), 1.0f);
        float[] marks = new float[selected.length];
        float[] existing = EdgeMarks.floats(base, label);
        if (existing != null) {
            System.arraycopy(existing, 0, marks, 0, Math.min(existing.length, marks.length));
        }
        for (int eid = 0; eid < selected.length; eid++) {
            if (selected[eid]) {
                marks[eid] = Math.max(marks[eid], value);
            }
        }
        return marks;
    }

    private static int[] markInts(NodeContext ctx, GeometryBundle base, String label, boolean[] selected) {
        Object raw = FieldBroadcast.getInputOrDefault(ctx, VALUE_INT.name, VALUE_INT.defaultValue);
        int value = raw instanceof Number n ? n.intValue() : 1;
        int[] marks = new int[selected.length];
        int[] existing = EdgeMarks.ints(base, label);
        if (existing != null) {
            System.arraycopy(existing, 0, marks, 0, Math.min(existing.length, marks.length));
        }
        for (int eid = 0; eid < selected.length; eid++) {
            if (selected[eid]) {
                marks[eid] = value;
            }
        }
        return marks;
    }

    private static boolean[] markBools(GeometryBundle base, String label, boolean[] selected) {
        boolean[] marks = new boolean[selected.length];
        boolean[] existing = EdgeMarks.bools(base, label);
        if (existing != null) {
            System.arraycopy(existing, 0, marks, 0, Math.min(existing.length, marks.length));
        }
        for (int eid = 0; eid < selected.length; eid++) {
            marks[eid] = marks[eid] || selected[eid];
        }
        return marks;
    }

    private static String stringInput(NodeContext ctx, String port, String fallback) {
        Object raw = ctx.getInputValue(port);
        return raw instanceof String s && !s.isBlank() ? s : fallback;
    }
}
