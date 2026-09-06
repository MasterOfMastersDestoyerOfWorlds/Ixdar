package ixdar.platform.gl.web;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;

import ixdar.platform.json.JsonValue;

/**
 * Builds a {@link JsonValue} tree from the browser's {@code JSON.parse}, so the web platform reads
 * JSON with the engine's own parser and no Java JSON library reaches the TeaVM output.
 */
public final class WebJsonTree {

    /**
     * Separator joining an object's keys into one string. A NUL keeps the crossing to a single
     * string instead of a marshalled JS array, and no JSON key this codebase reads contains one.
     */
    public static final String KEY_SEPARATOR = "\0";

    /** {@link #kindOf} answer for {@code null} and {@code undefined}. */
    public static final int JS_NULL = 0;

    /** {@link #kindOf} answer for a JS string. */
    public static final int JS_STRING = 1;

    /** {@link #kindOf} answer for a JS number. */
    public static final int JS_NUMBER = 2;

    /** {@link #kindOf} answer for a JS boolean. */
    public static final int JS_BOOLEAN = 3;

    /** {@link #kindOf} answer for a JS array. */
    public static final int JS_ARRAY = 4;

    /** {@link #kindOf} answer for any other JS object. */
    public static final int JS_OBJECT = 5;

    private WebJsonTree() {
    }

    /**
     * Parse JSON text into the neutral tree.
     *
     * @param json JSON document text
     * @return the parsed tree; a null node for empty or malformed input
     */
    public static JsonValue parse(String json) {
        if (json == null || json.isEmpty()) {
            return JsonValue.ofNull();
        }
        return convert(parseJson(json));
    }

    /**
     * Convert one JS value and everything below it.
     *
     * @param value value handed back by {@code JSON.parse}, possibly {@code null}
     * @return the equivalent neutral node
     */
    private static JsonValue convert(JSObject value) {
        switch (kindOf(value)) {
            case JS_STRING:
                return JsonValue.ofString(stringOf(value));
            case JS_NUMBER:
                return JsonValue.ofNumber(numberOf(value));
            case JS_BOOLEAN:
                return JsonValue.ofBoolean(booleanOf(value));
            case JS_ARRAY:
                return convertArray(value);
            case JS_OBJECT:
                return convertObject(value);
            default:
                return JsonValue.ofNull();
        }
    }

    /**
     * Convert a JS array, element by element.
     *
     * @param value a JS array
     * @return the equivalent array node
     */
    private static JsonValue convertArray(JSObject value) {
        JsonValue array = JsonValue.newArray();
        int length = lengthOf(value);
        for (int index = 0; index < length; index++) {
            array.items.add(convert(elementOf(value, index)));
        }
        return array;
    }

    /**
     * Convert a JS object, keeping the key order {@code Object.keys} reports.
     *
     * @param value a JS object
     * @return the equivalent object node
     */
    private static JsonValue convertObject(JSObject value) {
        JsonValue object = JsonValue.newObject();
        String joined = keysOf(value);
        if (joined.isEmpty()) {
            return object;
        }
        for (String key : joined.split(KEY_SEPARATOR, -1)) {
            object.members.put(key, convert(memberOf(value, key)));
        }
        return object;
    }

    @JSBody(params = {"json"}, script = "try { return JSON.parse(json); } catch (e) { return null; }")
    private static native JSObject parseJson(String json);

    @JSBody(params = {"value"}, script = "if (value === null || value === undefined) return 0;"
            + " if (Array.isArray(value)) return 4;"
            + " var kind = typeof value;"
            + " if (kind === 'string') return 1;"
            + " if (kind === 'number') return 2;"
            + " if (kind === 'boolean') return 3;"
            + " if (kind === 'object') return 5;"
            + " return 0;")
    private static native int kindOf(JSObject value);

    @JSBody(params = {"value"}, script = "return String(value);")
    private static native String stringOf(JSObject value);

    @JSBody(params = {"value"}, script = "return +value;")
    private static native double numberOf(JSObject value);

    @JSBody(params = {"value"}, script = "return !!value;")
    private static native boolean booleanOf(JSObject value);

    @JSBody(params = {"value"}, script = "return value.length;")
    private static native int lengthOf(JSObject value);

    @JSBody(params = {"value", "index"}, script = "return value[index];")
    private static native JSObject elementOf(JSObject value, int index);

    @JSBody(params = {"value"}, script = "return Object.keys(value).join('\\u0000');")
    private static native String keysOf(JSObject value);

    @JSBody(params = {"value", "key"}, script = "return value[key];")
    private static native JSObject memberOf(JSObject value, String key);
}
