package unit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.platform.Platforms;
import ixdar.platform.json.JsonValue;

/**
 * {@code Platform.parseJson} builds the neutral {@link JsonValue} tree the glTF reader and the
 * scan-settings reader consume: document order preserved, scalars readable as text, malformed input
 * answered with a null node rather than an exception.
 */
class PlatformJsonTest {

    private static final double EPSILON = 1e-9;

    @Test
    void objectsKeepDocumentOrderAndExposeTypedMembers() {
        JsonValue document = Platforms.get().parseJson(
                "{\"backend\": \"trellis2\", \"steps\": 12, \"scale\": 0.5,"
                        + " \"texture\": true, \"missing\": null}");

        assertTrue(document.isObject(), "the document is an object");
        assertEquals(List.of("backend", "steps", "scale", "texture", "missing"),
                new ArrayList<>(document.members.keySet()), "members keep document order");
        assertEquals("trellis2", document.getString("backend", ""), "strings read back");
        assertEquals(12, document.getInt("steps", -1), "integers read back");
        assertEquals(0.5, document.getDouble("scale", 0.0), EPSILON, "fractions read back");
        assertTrue(document.getBoolean("texture", false), "booleans read back");
        assertTrue(document.get("missing").isNull(), "an explicit null is a null node");
        assertTrue(document.has("missing"), "an explicit null is still a member");
        assertTrue(document.get("absent").isNull(), "an absent member reads as a null node");
        assertFalse(document.has("absent"), "an absent member is not a member");
    }

    @Test
    void scalarsRenderAsTheDocumentSpelledThem() {
        JsonValue document = Platforms.get().parseJson(
                "{\"quoted\": \"1024\", \"bare\": 1024, \"fraction\": 1.5, \"flag\": false}");

        assertEquals("1024", document.get("quoted").asString(""), "a quoted number keeps its text");
        assertEquals("1024", document.get("bare").asString(""),
                "an integral number renders without a trailing .0");
        assertEquals("1.5", document.get("fraction").asString(""), "a fraction keeps its point");
        assertEquals("false", document.get("flag").asString(""), "booleans render as words");
    }

    @Test
    void arraysAreIndexedAndNestingIsPreserved() {
        JsonValue document = Platforms.get().parseJson(
                "{\"nodes\": [{\"mesh\": 0}, {\"mesh\": 1}], \"matrix\": [1, 0, 0, 2]}");

        JsonValue nodes = document.get("nodes");
        assertTrue(nodes.isArray(), "nodes is an array");
        assertEquals(2, nodes.size(), "two elements");
        assertEquals(1, nodes.item(1).getInt("mesh", -1), "elements keep their own members");
        assertEquals(2.0, document.get("matrix").item(3).numberValue, EPSILON, "numbers are indexable");
        assertTrue(nodes.item(9).isNull(), "an out-of-range index reads as a null node");
    }

    @Test
    void malformedAndEmptyInputBecomeNullNodes() {
        assertTrue(Platforms.get().parseJson("").isNull(), "empty input");
        assertTrue(Platforms.get().parseJson("{ not json").isNull(), "malformed input");
        assertEquals(0, Platforms.get().parseJson("{ not json").size(), "a null node has no children");
    }
}
