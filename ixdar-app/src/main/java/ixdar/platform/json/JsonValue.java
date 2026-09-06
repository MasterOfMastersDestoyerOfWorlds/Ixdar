package ixdar.platform.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One node of a platform-neutral JSON tree, built by {@code Platform.parseJson}: Gson on the
 * desktop, the browser's {@code JSON.parse} on the web. {@link #kind} says which of the value
 * fields is meaningful; object keys keep their document order.
 */
public final class JsonValue {

    /** {@link #kind} of JSON {@code null}, and of anything a platform could not represent. */
    public static final int KIND_NULL = 0;

    /** {@link #kind} of a string; the text is in {@link #stringValue}. */
    public static final int KIND_STRING = 1;

    /** {@link #kind} of a number; the value is in {@link #numberValue}. */
    public static final int KIND_NUMBER = 2;

    /** {@link #kind} of a boolean; the value is in {@link #booleanValue}. */
    public static final int KIND_BOOLEAN = 3;

    /** {@link #kind} of an array; the elements are in {@link #items}. */
    public static final int KIND_ARRAY = 4;

    /** {@link #kind} of an object; the members are in {@link #members}. */
    public static final int KIND_OBJECT = 5;

    /** Which of the value fields carries this node's content: one of the {@code KIND_} constants. */
    public int kind = KIND_NULL;

    /** Text of a {@link #KIND_STRING} node. */
    public String stringValue = "";

    /** Value of a {@link #KIND_NUMBER} node. JSON has one numeric type, so integers arrive here. */
    public double numberValue;

    /** Value of a {@link #KIND_BOOLEAN} node. */
    public boolean booleanValue;

    /** Elements of a {@link #KIND_ARRAY} node, in document order. */
    public final List<JsonValue> items = new ArrayList<>();

    /** Members of a {@link #KIND_OBJECT} node, keyed by name in document order. */
    public final Map<String, JsonValue> members = new LinkedHashMap<>();

    /**
     * A JSON {@code null}.
     *
     * @return a fresh null node
     */
    public static JsonValue ofNull() {
        return new JsonValue();
    }

    /**
     * A JSON string.
     *
     * @param text the string's text
     * @return a fresh string node
     */
    public static JsonValue ofString(String text) {
        JsonValue value = new JsonValue();
        value.kind = KIND_STRING;
        value.stringValue = text == null ? "" : text;
        return value;
    }

    /**
     * A JSON number.
     *
     * @param number the numeric value
     * @return a fresh number node
     */
    public static JsonValue ofNumber(double number) {
        JsonValue value = new JsonValue();
        value.kind = KIND_NUMBER;
        value.numberValue = number;
        return value;
    }

    /**
     * A JSON boolean.
     *
     * @param flag the boolean value
     * @return a fresh boolean node
     */
    public static JsonValue ofBoolean(boolean flag) {
        JsonValue value = new JsonValue();
        value.kind = KIND_BOOLEAN;
        value.booleanValue = flag;
        return value;
    }

    /**
     * An empty JSON array, for a reader to fill through {@link #items}.
     *
     * @return a fresh array node
     */
    public static JsonValue newArray() {
        JsonValue value = new JsonValue();
        value.kind = KIND_ARRAY;
        return value;
    }

    /**
     * An empty JSON object, for a reader to fill through {@link #members}.
     *
     * @return a fresh object node
     */
    public static JsonValue newObject() {
        JsonValue value = new JsonValue();
        value.kind = KIND_OBJECT;
        return value;
    }

    /**
     * Whether this node is JSON {@code null}.
     *
     * @return {@code true} for a {@link #KIND_NULL} node
     */
    public boolean isNull() {
        return kind == KIND_NULL;
    }

    /**
     * Whether this node is a JSON object.
     *
     * @return {@code true} for a {@link #KIND_OBJECT} node
     */
    public boolean isObject() {
        return kind == KIND_OBJECT;
    }

    /**
     * Whether this node is a JSON array.
     *
     * @return {@code true} for a {@link #KIND_ARRAY} node
     */
    public boolean isArray() {
        return kind == KIND_ARRAY;
    }

    /**
     * Element count of an array, or member count of an object; zero for a scalar.
     *
     * @return the child count
     */
    public int size() {
        if (kind == KIND_ARRAY) {
            return items.size();
        }
        return kind == KIND_OBJECT ? members.size() : 0;
    }

    /**
     * Element of an array by position.
     *
     * @param index zero-based position
     * @return the element, or a null node when the index is out of range or this is not an array
     */
    public JsonValue item(int index) {
        if (kind != KIND_ARRAY || index < 0 || index >= items.size()) {
            return ofNull();
        }
        return items.get(index);
    }

    /**
     * Member of an object by name.
     *
     * @param name member name
     * @return the member, or a null node when absent or this is not an object
     */
    public JsonValue get(String name) {
        JsonValue member = kind == KIND_OBJECT ? members.get(name) : null;
        return member == null ? ofNull() : member;
    }

    /**
     * Whether this object has a member of that name, null members included.
     *
     * @param name member name
     * @return {@code true} when the member is present
     */
    public boolean has(String name) {
        return kind == KIND_OBJECT && members.containsKey(name);
    }

    /**
     * Text of a string member, or of a number or boolean member rendered as the document spelled
     * it, so a settings value like {@code "1024"} and {@code 1024} read the same.
     *
     * @param name member name
     * @param fallback value returned when the member is absent or null
     * @return the member's text
     */
    public String getString(String name, String fallback) {
        return get(name).asString(fallback);
    }

    /**
     * Numeric value of a member.
     *
     * @param name member name
     * @param fallback value returned when the member is absent or not a number
     * @return the member's number
     */
    public double getDouble(String name, double fallback) {
        JsonValue member = get(name);
        return member.kind == KIND_NUMBER ? member.numberValue : fallback;
    }

    /**
     * Integer value of a member, truncated from its number.
     *
     * @param name member name
     * @param fallback value returned when the member is absent or not a number
     * @return the member's integer value
     */
    public int getInt(String name, int fallback) {
        JsonValue member = get(name);
        return member.kind == KIND_NUMBER ? (int) member.numberValue : fallback;
    }

    /**
     * Boolean value of a member.
     *
     * @param name member name
     * @param fallback value returned when the member is absent or not a boolean
     * @return the member's boolean value
     */
    public boolean getBoolean(String name, boolean fallback) {
        JsonValue member = get(name);
        return member.kind == KIND_BOOLEAN ? member.booleanValue : fallback;
    }

    /**
     * This node's text: a string's own text, a number or boolean rendered, {@code fallback} for
     * null and for the container kinds. Integral numbers render without a trailing {@code .0}.
     *
     * @param fallback value returned for a null, array or object node
     * @return the rendered text
     */
    public String asString(String fallback) {
        if (kind == KIND_STRING) {
            return stringValue;
        }
        if (kind == KIND_BOOLEAN) {
            return Boolean.toString(booleanValue);
        }
        if (kind != KIND_NUMBER) {
            return fallback;
        }
        if (numberValue == Math.rint(numberValue) && !Double.isInfinite(numberValue)) {
            return Long.toString((long) numberValue);
        }
        return Double.toString(numberValue);
    }
}
