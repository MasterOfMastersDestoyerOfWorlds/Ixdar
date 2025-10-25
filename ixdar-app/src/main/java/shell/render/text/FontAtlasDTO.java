package shell.render.text;

import com.google.gson.annotations.SerializedName;

public class FontAtlasDTO {
    public AtlasInfo atlas;
    public Metrics metrics;
    public GlyphEntry[] glyphs;
    public KerningEntry[] kerning;

    public static class AtlasInfo {
        public String type;
        public double distanceRange;
        public double distanceRangeMiddle;
        public double size;
        public int width;
        public int height;
        public String yorigin;
    }

    public static class Metrics {
        public double emSize;
        public double lineHeight;
        public double ascender;
        public double descender;
        public double underlineY;
        public double underlineThickness;
    }

    public static class Rect {
        public double left;
        public double bottom;
        public double right;
        public double top;
    }

    public static class GlyphEntry {
        public int unicode;
        public double advance;
        @SerializedName("planeBounds")
        public Rect planeBounds;
        @SerializedName("atlasBounds")
        public Rect atlasBounds;
    }

    public static class KerningEntry {
        public int unicode1;
        public int unicode2;
        public double advance;
    }
}
