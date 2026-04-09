package ixdar.graphics.render.tag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector4f;

import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorRGB;

/**
 * Maps tag names to distinct colors and computes per-vertex colors based on
 * tag membership. When multiple tags overlap on a vertex, uses the most-specific
 * (deepest hierarchy) tag. Tag hierarchy is determined by the order tags are
 * defined - later tags take precedence.
 */
public class TagColorMapper {

    // Categorical palette of distinct, visually separable colors
    private static final List<Color> CATEGORICAL_PALETTE = List.of(
            new ColorRGB(255, 99, 71, "Tomato"),        // Red-Orange
            new ColorRGB(60, 179, 113, "Medium Sea Green"), // Green
            new ColorRGB(30, 144, 255, "Dodger Blue"),  // Blue
            new ColorRGB(255, 215, 0, "Gold"),          // Yellow
            new ColorRGB(148, 0, 211, "Purple"),        // Purple
            new ColorRGB(255, 127, 80, "Coral"),        // Orange-Red
            new ColorRGB(72, 209, 204, "Turquoise"),    // Teal
            new ColorRGB(199, 21, 133, "Medium Violet Red"), // Pink
            new ColorRGB(255, 165, 0, "Orange"),        // Orange
            new ColorRGB(0, 128, 128, "Teal"),          // Dark Teal
            new ColorRGB(128, 0, 128, "Purple"),        // Deep Purple
            new ColorRGB(218, 112, 214, "Orchid"),      // Light Purple
            new ColorRGB(178, 34, 34, "Fire Brick"),    // Dark Red
            new ColorRGB(75, 0, 130, "Indigo"),         // Indigo
            new ColorRGB(238, 130, 238, "Violet"),      // Violet
            new ColorRGB(0, 206, 209, "Dark Turquoise"), // Turquoise
            new ColorRGB(205, 92, 92, "Indian Red"),    // Red
            new ColorRGB(64, 224, 208, "Turquoise"),    // Aquamarine
            new ColorRGB(255, 182, 193, "Light Pink"),  // Pink
            new ColorRGB(138, 43, 226, "Blue Violet")   // Blue Violet
    );

    // Map from tag name to color
    private final Map<String, Color> tagColors = new HashMap<>();
    // List of tag names in order (later = more specific/deeper hierarchy)
    private final List<String> tagOrder = new ArrayList<>();

    public TagColorMapper() {
    }

    /**
     * Register tags from a tag map (Map<String, boolean[]>).
     * Tags are registered in the order they appear in the map's keySet.
     * Later tags in the order take precedence for overlapping vertices.
     */
    public void registerTags(Map<String, boolean[]> tags) {
        // Clear existing registrations
        tagColors.clear();
        tagOrder.clear();

        // Register each tag with a distinct color
        for (Map.Entry<String, boolean[]> entry : tags.entrySet()) {
            String tagName = entry.getKey();
            if (!tagName.isBlank() && !tagColors.containsKey(tagName)) {
                tagColors.put(tagName, getOrCreateColor(tagName));
                tagOrder.add(tagName);
            }
        }
    }

    /**
     * Get the color for a specific tag. Returns a default gray if tag is not found.
     */
    public Color getColor(String tagName) {
        Color color = tagColors.get(tagName);
        return color != null ? color : new ColorRGB(128, 128, 128, "Default Gray");
    }

    /**
     * Get the list of registered tag names.
     */
    public List<String> getTagNames() {
        return new ArrayList<>(tagOrder);
    }

    /**
     * Compute per-vertex colors based on tag membership.
     * For each vertex, finds all tags that include it and uses the most-specific
     * (last registered) tag's color.
     */
    public float[] computeVertexColors(Map<String, boolean[]> tags, int vertexCount) {
        float[] colors = new float[vertexCount * 4]; // RGBA per vertex

        // Build a reverse mapping: vertex -> list of tags that include it
        // Order matters: later tags in tagOrder have higher priority
        for (int v = 0; v < vertexCount; v++) {
            String bestTag = null;

            // Find all tags that include this vertex
            for (String tagName : tags.keySet()) {
                boolean[] mask = tags.get(tagName);
                if (mask != null && v < mask.length && mask[v]) {
                    bestTag = tagName;
                }
            }

            // Use the most-specific tag (last in registration order)
            if (bestTag != null) {
                Color color = tagColors.get(bestTag);
                if (color != null) {
                    Vector4f c = color.toVector4f();
                    colors[v * 4 + 0] = c.x();
                    colors[v * 4 + 1] = c.y();
                    colors[v * 4 + 2] = c.z();
                    colors[v * 4 + 3] = c.w();
                } else {
                    // Default color (white)
                    colors[v * 4 + 0] = 1.0f;
                    colors[v * 4 + 1] = 1.0f;
                    colors[v * 4 + 2] = 1.0f;
                    colors[v * 4 + 3] = 1.0f;
                }
            } else {
                // No tag assigned - use neutral gray
                colors[v * 4 + 0] = 0.5f;
                colors[v * 4 + 1] = 0.5f;
                colors[v * 4 + 2] = 0.5f;
                colors[v * 4 + 3] = 1.0f;
            }
        }

        return colors;
    }

    /**
     * Get a distinct color for a tag name using a hash-based hue.
     * This ensures the same tag always gets the same color.
     */
    private Color getOrCreateColor(String tagName) {
        if (tagColors.containsKey(tagName)) {
            return tagColors.get(tagName);
        }

        // Use hash of tag name to get a hue in [0, 1)
        int hash = tagName.hashCode();
        float hue = Math.abs(hash % 360) / 360.0f;

        // Use consistent saturation and brightness for categorical colors
        float saturation = 0.75f;
        float brightness = 0.9f;

        Color color = Color.getHSBColor(hue, saturation, brightness);
        tagColors.put(tagName, color);
        return color;
    }
}
