package ixdar.geometry.mesh.graph;

import java.util.ArrayList;
import java.util.List;

import ixdar.parsing.python.PythonParser;

/**
 * Metadata for user-editable DSL inputs: {@code input_float}, {@code input_int}, {@code input_boolean},
 * and curve parameters {@code float_curve} (see {@link ixdar.geometry.mesh.nodes.curve.FloatCurveNode}).
 */
public record InputParameterDescriptor(
        String nodeId,
        String nodeType,
        String name,
        InputParameterKind kind,
        Float floatDefault,
        Float minFloat,
        Float maxFloat,
        Integer intDefault,
        Integer minInt,
        Integer maxInt,
        Boolean booleanDefault,
        String curvePointsDefault) {

    public enum InputParameterKind {
        FLOAT,
        INT,
        BOOLEAN,
        CURVE
    }

    /**
     * Collects parameter descriptors from literal node arguments (for UI / validation). Port wiring
     * references are not resolved — defaults are only present when the DSL uses literals.
     */
    public static List<InputParameterDescriptor> collect(List<PythonParser.ParsedNode> nodes) {
        List<InputParameterDescriptor> out = new ArrayList<>();
        for (PythonParser.ParsedNode n : nodes) {
            switch (n.type) {
                case "input_float" -> out.add(fromInputFloat(n));
                case "input_int" -> out.add(fromInputInt(n));
                case "input_boolean" -> out.add(fromInputBoolean(n));
                case "float_curve" -> out.add(fromFloatCurve(n));
                default -> {
                }
            }
        }
        return out;
    }

    private static InputParameterDescriptor fromInputFloat(PythonParser.ParsedNode n) {
        String name = stringArg(n, "name");
        Float def = floatArg(n, "default");
        Float min = floatArg(n, "min");
        Float max = floatArg(n, "max");
        return new InputParameterDescriptor(
                n.id,
                n.type,
                name,
                InputParameterKind.FLOAT,
                def != null ? def : 0f,
                min != null ? min : Float.NEGATIVE_INFINITY,
                max != null ? max : Float.POSITIVE_INFINITY,
                null,
                null,
                null,
                null,
                null);
    }

    private static InputParameterDescriptor fromInputInt(PythonParser.ParsedNode n) {
        String name = stringArg(n, "name");
        Integer def = intArg(n, "default");
        Integer min = intArg(n, "min");
        Integer max = intArg(n, "max");
        return new InputParameterDescriptor(
                n.id,
                n.type,
                name,
                InputParameterKind.INT,
                null,
                null,
                null,
                def != null ? def : 0,
                min != null ? min : Integer.MIN_VALUE,
                max != null ? max : Integer.MAX_VALUE,
                null,
                null);
    }

    private static InputParameterDescriptor fromInputBoolean(PythonParser.ParsedNode n) {
        String name = stringArg(n, "name");
        Boolean def = booleanArg(n, "default");
        return new InputParameterDescriptor(
                n.id,
                n.type,
                name,
                InputParameterKind.BOOLEAN,
                null,
                null,
                null,
                null,
                null,
                null,
                def != null ? def : Boolean.FALSE,
                null);
    }

    private static InputParameterDescriptor fromFloatCurve(PythonParser.ParsedNode n) {
        Object pv = n.arguments.get("points");
        String points = pv instanceof String s ? s : null;
        if (points == null || points.isBlank()) {
            points = "0,0,1,1";
        }
        return new InputParameterDescriptor(
                n.id,
                n.type,
                n.id,
                InputParameterKind.CURVE,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                points);
    }

    private static String stringArg(PythonParser.ParsedNode n, String key) {
        Object v = n.arguments.get(key);
        if (v instanceof String s) {
            return s;
        }
        return "";
    }

    private static Float floatArg(PythonParser.ParsedNode n, String key) {
        Object v = n.arguments.get(key);
        if (v instanceof Number num) {
            return num.floatValue();
        }
        return null;
    }

    private static Integer intArg(PythonParser.ParsedNode n, String key) {
        Object v = n.arguments.get(key);
        if (v instanceof Number num) {
            return num.intValue();
        }
        return null;
    }

    private static Boolean booleanArg(PythonParser.ParsedNode n, String key) {
        Object v = n.arguments.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number num) {
            return num.doubleValue() != 0.0;
        }
        return null;
    }
}
