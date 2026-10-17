package unit.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import ixdar.graphics.render.color.Color;

/**
 * {@link Color}'s constants are named {@code BASE[_LEAN][_LIGHTNESS][_<percent>]} and sorted by
 * hue in the source: neutrals black to white, then hue angle from red, ties by lightness, alpha
 * variants after their base. {@code IXDAR} (the logo red), {@code IXDAR_DARK} and
 * {@code TRANSPARENT} are the names exempt from that grammar.
 */
class ColorOrderTest {

    private static final Path SOURCE = Path.of("src/main/java/ixdar/graphics/render/color/Color.java");

    private static final Pattern CONSTANT = Pattern.compile("public static final Color (\\w+) =");

    private static final Pattern ALPHA_VARIANT = Pattern.compile("(.+)_(\\d+)");

    private static final double HUE_TIE_DEGREES = 1e-3;

    private static final double PERCENT = 100.0;

    private static final double DEGREES_PER_SEXTANT = 60.0;

    private static final Set<String> EXCEPTIONS = Set.of("IXDAR", "IXDAR_DARK", "TRANSPARENT");

    private static final Set<String> BASE_WORDS = Set.of("BLACK", "GREY", "WHITE", "RED",
            "ORANGE", "BROWN", "YELLOW", "GREEN", "TEAL", "BLUE", "PURPLE", "PINK");

    private static final Set<String> LIGHTNESS_WORDS = Set.of("DARK", "LIGHT", "PASTEL");

    @Test
    void constantsAreColourNamesSortedByHue() throws Exception {
        Matcher matcher = CONSTANT.matcher(Files.readString(SOURCE, StandardCharsets.UTF_8));
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        assertTrue(names.size() > 1, "no constants read from " + SOURCE);
        Map<String, Vector4f> channels = new HashMap<>();
        for (String name : names) {
            channels.put(name, ((Color) Color.class.getField(name).get(null)).toVector4f());
        }
        // Neutrals before chromatic colours, neutrals by lightness, chromatic ones by hue then
        // lightness, and at equal channels the more opaque first.
        for (int index = 1; index < names.size(); index++) {
            String before = names.get(index - 1);
            String after = names.get(index);
            Vector4f first = channels.get(before);
            Vector4f second = channels.get(after);
            boolean firstNeutral = first.x == first.y && first.y == first.z;
            boolean secondNeutral = second.x == second.y && second.y == second.z;
            int order;
            double hueGap = firstNeutral || secondNeutral ? 0 : hue(first) - hue(second);
            if (firstNeutral != secondNeutral) {
                order = firstNeutral ? -1 : 1;
            } else if (Math.abs(hueGap) > HUE_TIE_DEGREES) {
                order = hueGap < 0 ? -1 : 1;
            } else {
                int byLightness = Double.compare(lightness(first), lightness(second));
                order = byLightness != 0 ? byLightness : Float.compare(second.w, first.w);
            }
            assertTrue(order <= 0, before + " must come after " + after);
        }
        Map<String, String> byChannels = new HashMap<>();
        for (String name : names) {
            Vector4f rgba = channels.get(name);
            String previous = byChannels.put(rgba.toString(), name);
            assertFalse(previous != null, previous + " and " + name + " are the same colour");
            Matcher variant = ALPHA_VARIANT.matcher(name);
            boolean translucent = rgba.w > 0f && rgba.w < 1f;
            if (translucent) {
                assertTrue(variant.matches(), name + " is translucent but not named BASE_<percent>");
                assertEquals(Math.round(rgba.w * PERCENT), Integer.parseInt(variant.group(2)), name);
                Vector4f base = channels.get(variant.group(1));
                assertTrue(base == null || base.x == rgba.x && base.y == rgba.y && base.z == rgba.z,
                        name + " is not " + variant.group(1) + " with alpha");
            }
            String colourName = translucent ? variant.group(1) : name;
            // BASE[_LEAN][_LIGHTNESS]: a leading colour word, an optional second colour word
            // disambiguating a lean, then an optional lightness word (DARK, VERY_DARK, LIGHT,
            // VERY_LIGHT or PASTEL); the alpha suffix was already stripped above.
            List<String> words = new ArrayList<>(List.of(colourName.split("_")));
            boolean wellFormed = !words.isEmpty() && BASE_WORDS.contains(words.remove(0));
            if (wellFormed && !words.isEmpty() && BASE_WORDS.contains(words.get(0))) {
                words.remove(0);
            }
            if (wellFormed && !words.isEmpty() && "VERY".equals(words.get(0)) && words.size() > 1) {
                words.remove(0);
            }
            if (wellFormed && !words.isEmpty() && LIGHTNESS_WORDS.contains(words.get(0))) {
                words.remove(0);
            }
            assertTrue(EXCEPTIONS.contains(name) || (wellFormed && words.isEmpty()),
                    name + " is not named BASE[_LEAN][_LIGHTNESS]");
        }
    }

    private static double lightness(Vector4f rgba) {
        double max = Math.max(rgba.x, Math.max(rgba.y, rgba.z));
        double min = Math.min(rgba.x, Math.min(rgba.y, rgba.z));
        return (max + min) / 2;
    }

    private static double hue(Vector4f rgba) {
        double red = rgba.x;
        double green = rgba.y;
        double blue = rgba.z;
        double max = Math.max(red, Math.max(green, blue));
        double chroma = max - Math.min(red, Math.min(green, blue));
        double sextant;
        if (max == red) {
            sextant = ((green - blue) / chroma + 6) % 6;
        } else if (max == green) {
            sextant = (blue - red) / chroma + 2;
        } else {
            sextant = (red - green) / chroma + 4;
        }
        return sextant * DEGREES_PER_SEXTANT;
    }
}
