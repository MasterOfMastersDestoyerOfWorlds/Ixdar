package ixdar.platform.json;

import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Builds a {@link JsonValue} tree from Gson, for the desktop and headless platforms. Only those
 * platforms call it, so the browser build never reaches Gson.
 */
public final class GsonJsonTree {

    private GsonJsonTree() {
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
        try {
            return convert(JsonParser.parseString(json));
        } catch (RuntimeException malformed) {
            return JsonValue.ofNull();
        }
    }

    /**
     * Convert one Gson element and everything below it.
     *
     * @param element Gson element, possibly {@code null}
     * @return the equivalent neutral node
     */
    private static JsonValue convert(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return JsonValue.ofNull();
        }
        if (element.isJsonObject()) {
            JsonValue object = JsonValue.newObject();
            for (Map.Entry<String, JsonElement> member : ((JsonObject) element).entrySet()) {
                object.members.put(member.getKey(), convert(member.getValue()));
            }
            return object;
        }
        if (element.isJsonArray()) {
            JsonValue array = JsonValue.newArray();
            for (JsonElement item : (JsonArray) element) {
                array.items.add(convert(item));
            }
            return array;
        }
        JsonPrimitive primitive = (JsonPrimitive) element;
        if (primitive.isBoolean()) {
            return JsonValue.ofBoolean(primitive.getAsBoolean());
        }
        if (primitive.isNumber()) {
            return JsonValue.ofNumber(primitive.getAsDouble());
        }
        return JsonValue.ofString(primitive.getAsString());
    }
}
