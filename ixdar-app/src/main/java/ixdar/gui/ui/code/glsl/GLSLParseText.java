package ixdar.gui.ui.code.glsl;

import java.util.ArrayList;
import java.util.Map;

import org.joml.Vector4f;

import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.text.ColorText;
import ixdar.scenes.Scene;

public class GLSLParseText extends ColorText<Vector4f> {
    public static final int VEC4_COMPONENTS = 4;
    public static final int DECIMAL_BASE = 10;
    public static final int VECTOR_X = 0;
    public static final int VECTOR_Y = 1;
    public static final int VECTOR_Z = 2;
    public static final int VECTOR_W = 3;
    // Its one word is empty, so it carries no visible colour and needs no palette at class load.
    public static final GLSLParseText BLANK = new GLSLParseText("", Color.TRANSPARENT, new Vector4f(), -1,
            "BLANK");
    private static final String COMPONENT_LETTERS = "xyzw";

    String key;
    int vectorLength;

    /**
     * Primary constructor: store text/color/data and re-render the colored token
     * stream from the vector value when {@code 1 <= vectorLength <= 4}.
     *
     * @param text plain initial label (overwritten by the vector renderer)
     * @param color fallback color for the initial label
     * @param data vector value, accessed up to {@code vectorLength} components
     * @param vectorLength 1..4 to render as scalar/vec; values outside skip rendering
     * @param key environment binding name for this token
     */
    public GLSLParseText(String text, Color color, Vector4f data, int vectorLength, String key) {
        super(text, color, data);
        transformVecText(data, vectorLength);
        this.vectorLength = vectorLength;
        this.key = key;
    }

    /**
     * Convenience overload that defaults the data vector to {@code new Vector4f()}.
     *
     * @param text plain initial label
     * @param color fallback color for the initial label
     * @param vectorLength 1..4 to render as scalar/vec; values outside skip rendering
     * @param key environment binding name for this token
     */
    public GLSLParseText(String text, Color color, int vectorLength, String key) {
        this(text, color, new Vector4f(), vectorLength, key);
    }

    /**
     * Convenience overload that defaults color to the code palette's annotation role and
     * data to {@code new Vector4f()}.
     *
     * @param text plain initial label
     * @param vectorLength 1..4 to render as scalar/vec; values outside skip rendering
     * @param key environment binding name for this token
     */
    public GLSLParseText(String text, int vectorLength, String key) {
        this(text, annotationColor(), new Vector4f(), vectorLength, key);
    }

    /**
     * Convenience overload that defaults color to the code palette's annotation role.
     *
     * @param text plain initial label
     * @param data vector value, accessed up to {@code vectorLength} components
     * @param vectorLength 1..4 to render as scalar/vec; values outside skip rendering
     * @param key environment binding name for this token
     */
    public GLSLParseText(String text, Vector4f data, int vectorLength, String key) {
        this(text, annotationColor(), data, vectorLength, key);
    }

    GLSLParseText(GLSLParseText text, Vector4f data, int vectorLength, String key) {
        super(text, data);
        transformVecText(data, vectorLength);
        this.vectorLength = vectorLength;
        this.key = key;
    }

    /**
     * Empty token; fields are populated later (used as a builder for {@link #join}).
     */
    public GLSLParseText() {
        super();
    }

    /**
     * Convenience overload that uses {@code text} as both label and binding key.
     *
     * @param text plain initial label, also used as the environment key
     * @param color fallback color for the initial label
     * @param vectorLength 1..4 to render as scalar/vec; values outside skip rendering
     */
    public GLSLParseText(String text, Color color, int vectorLength) {
        this(text, color, new Vector4f(), vectorLength, text);
    }

    /**
     * Build a labeled scalar token with the float value packed into x.
     *
     * @param text plain initial label
     * @param val scalar value stored at component x
     */
    public GLSLParseText(String text, Float val) {
        this(text, annotationColor(), new Vector4f(val, 0f, 0f, 0f), 1, "");
    }

    /**
     * Build an unlabeled scalar token with the float value packed into x.
     *
     * @param val scalar value stored at component x
     */
    public GLSLParseText(Float val) {
        this("", annotationColor(), new Vector4f(val, 0f, 0f, 0f), 1, "");
    }

    /**
     * Build a non-vector token (no data) with an explicit binding key.
     *
     * @param text plain label
     * @param key environment binding name for this token
     */
    public GLSLParseText(String text, String key) {
        this(text, annotationColor(), null, -1, key);
    }

    /**
     * Build a plain label-only token with no vector data and no binding key.
     *
     * @param text plain label
     */
    public GLSLParseText(String text) {
        this(text, annotationColor(), null, -1, null);
    }

    /**
     * Build a plain colored label-only token with no vector data.
     *
     * @param text plain label
     * @param color color for the label
     */
    public GLSLParseText(String text, Color color) {
        this(text, color, null, -1, null);
    }

    /**
     * Convenience overload with default color and no binding key.
     *
     * @param text plain initial label
     * @param vec vector value, accessed up to {@code vectorLength} components
     * @param vectorLength 1..4 to render as scalar/vec; values outside skip rendering
     */
    public GLSLParseText(String text, Vector4f vec, int vectorLength) {
        this(text, annotationColor(), vec, vectorLength, null);
    }

    /**
     * The colour a value annotation takes before its tokens are coloured: the active palette's muted text.
     *
     * @return the annotation colour of the active scene's palette
     */
    public static Color annotationColor() {
        return Scene.activePalette().mutedText;
    }

    private void transformVecText(Vector4f data, int vectorLength) {
        if (vectorLength < 1 || vectorLength > VEC4_COMPONENTS) {
            return;
        }
        super.resetText();
        Palette palette = Scene.activePalette();
        if (vectorLength == 1) {
            super.addWord(formatFixed(data.x), palette.headingText);
        } else {
            super.addWord(String.format("vec%s", vectorLength), palette.linkText);
            super.addWord("(", palette.mutedText);
            for (int i = 0; i < vectorLength; i++) {
                Color componentColor = switch (COMPONENT_LETTERS.charAt(i)) {
                case 'x' -> palette.categories[VECTOR_X];
                case 'y' -> palette.categories[VECTOR_Y];
                case 'z' -> palette.categories[VECTOR_Z];
                default -> palette.categories[VECTOR_W];
                };
                super.addWord(formatFixed(data.get(i)), componentColor);
                if (i != vectorLength - 1) {
                    super.addWord(",", palette.mutedText);
                } else {
                    super.addWord(")", palette.mutedText);
                }
            }
        }
    }

    /**
     * Concatenate {@code v}'s text/color streams onto a copy of this token's
     * streams; data, key, and the larger of the two vector lengths are kept.
     *
     * @param v token whose tokens to append
     * @return new combined token
     */
    public GLSLParseText join(GLSLParseText v) {
        GLSLParseText result = new GLSLParseText();
        result.text = new ArrayList<>(this.text);
        result.color = new ArrayList<>(this.color);
        result.data = this.data;
        result.text.addAll(v.text);
        result.color.addAll(v.color);
        result.key = this.key;
        result.vectorLength = Math.max(this.vectorLength, v.vectorLength);
        return result;
    }

    /**
     * Render a float with exactly two fractional digits, padding or truncating as
     * needed (no scientific notation).
     *
     * @param val value to format
     * @return string with exactly two digits after the decimal point
     */
    public static String formatFixed(Float val) {
        int digits = 2;
        Float pow = (float) Math.pow(DECIMAL_BASE, digits);
        Float rounded = Math.round(val * pow) / pow;
        String s = Float.toString(rounded);
        int dot = s.indexOf('.');
        if (dot < 0) {
            StringBuilder sb = new StringBuilder(s);
            sb.append('.');
            for (int i = 0; i < digits; i++)
                sb.append('0');
            return sb.toString();
        }
        int need = digits - (s.length() - dot - 1);
        if (need > 0) {
            StringBuilder sb = new StringBuilder(s);
            for (int i = 0; i < need; i++)
                sb.append('0');
            return sb.toString();
        }
        if (need < 0) {
            return s.substring(0, dot + 1 + digits);
        }
        return s;
    }

    /**
     * Bind {@code var} in {@code env} to a token whose vector packs the given
     * components; 1 component yields a scalar, 2..4 yield a colored vec literal.
     *
     * @param env environment map to mutate
     * @param var variable name to bind
     * @param dv 1..4 component values, in xyzw order
     */
    public static void put(Map<String, GLSLParseText> env, String var, Float... dv) {
        if (dv == null || dv.length == 0) {
            return;
        }
        if (dv.length == 1) {
            Float value = dv[0];
            env.put(var, new GLSLParseText("", Scene.activePalette().headingText, new Vector4f(value, 0f, 0f, 0f), 1,
                    var));
            return;
        }
        float[] xyzw = new float[VEC4_COMPONENTS];
        for (int i = 0; i < dv.length; i++) {
            xyzw[i] = dv[i];
        }
        Vector4f result = new Vector4f(xyzw);
        GLSLParseText vectorString = new GLSLParseText("", Scene.activePalette().headingText, result, dv.length,
                var);

        env.put(var, vectorString);
    }

    /**
     * Concatenate the components of each entry in {@code dv} (up to 4 floats total)
     * and bind the result via {@link #put}. Aborts silently if the combined length
     * exceeds 4 or any entry has an invalid {@code vectorLength}.
     *
     * @param env environment map to mutate
     * @param var variable name to bind
     * @param dv ordered list of scalar/vector tokens whose components are packed
     */
    public static void putVec(Map<String, GLSLParseText> env, String var, ArrayList<GLSLParseText> dv) {
        Float[] data = new Float[VEC4_COMPONENTS];
        int vectorLength = 0;
        for (int i = 0; i < dv.size(); i++) {
            GLSLParseText pt = dv.get(i);
            Vector4f vec = dv.get(i).getData();
            for(int k = vectorLength; k < vectorLength + pt.vectorLength; k ++){
                data[k] = vec.get(k - vectorLength);
            }
            vectorLength += pt.vectorLength;

            if(pt.vectorLength < 1 || vectorLength > VEC4_COMPONENTS){
                return;
            }
        }
        Float[] finalData = new Float[vectorLength];
        for (int i = 0; i < finalData.length; i++) {
            finalData[i] = data[i];
        }
        put(env, var, finalData);
    }
}
