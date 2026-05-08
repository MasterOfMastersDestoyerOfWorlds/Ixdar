package ixdar.parsing.glsl;

import java.util.ArrayList;
import java.util.Map;

import org.joml.Vector4f;

import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.ColorText;

public class GLSLParseText extends ColorText<Vector4f> {
    public static final int NUM_4 = 4;
    public static final float NUM_0 = 0f;
    public static final int NUM_10 = 10;
    public static final GLSLParseText BLANK = new GLSLParseText("", Color.BLUE_WHITE, new Vector4f(), -1, "BLANK");

    static final Color[] vecColors = new Color[] { Color.GLSL_VECTOR_FLOAT_X, Color.GLSL_VECTOR_FLOAT_Y,
            Color.GLSL_VECTOR_FLOAT_Z, Color.GLSL_VECTOR_FLOAT_W };
    String key;
    int vectorLength;

    /**
     * TODO: document {@code GLSLParseText}.
     *
     * @param text TODO: describe
     * @param color TODO: describe
     * @param data TODO: describe
     * @param vectorLength TODO: describe
     * @param key TODO: describe
     */
    public GLSLParseText(String text, Color color, Vector4f data, int vectorLength, String key) {
        super(text, color, data);
        transformVecText(data, vectorLength);
        this.vectorLength = vectorLength;
        this.key = key;
    }

    /**
     * TODO: document {@code GLSLParseText}.
     *
     * @param text TODO: describe
     * @param color TODO: describe
     * @param vectorLength TODO: describe
     * @param key TODO: describe
     */
    public GLSLParseText(String text, Color color, int vectorLength, String key) {
        this(text, color, new Vector4f(), vectorLength, key);
    }

    /**
     * TODO: document {@code GLSLParseText}.
     *
     * @param text TODO: describe
     * @param vectorLength TODO: describe
     * @param key TODO: describe
     */
    public GLSLParseText(String text, int vectorLength, String key) {
        this(text, Color.BLUE_WHITE, new Vector4f(), vectorLength, key);
    }

    /**
     * TODO: document {@code GLSLParseText}.
     *
     * @param text TODO: describe
     * @param data TODO: describe
     * @param vectorLength TODO: describe
     * @param key TODO: describe
     */
    public GLSLParseText(String text, Vector4f data, int vectorLength, String key) {
        this(text, Color.BLUE_WHITE, data, vectorLength, key);
    }

    GLSLParseText(GLSLParseText text, Vector4f data, int vectorLength, String key) {
        super(text, data);
        transformVecText(data, vectorLength);
        this.vectorLength = vectorLength;
        this.key = key;
    }

    /**
     * TODO: document {@code GLSLParseText}.
     */
    public GLSLParseText() {
        super();
    }

    /**
     * TODO: document {@code GLSLParseText}.
     *
     * @param text TODO: describe
     * @param color TODO: describe
     * @param vectorLength TODO: describe
     */
    public GLSLParseText(String text, Color color, int vectorLength) {
        this(text, color, new Vector4f(), vectorLength, text);
    }

    /**
     * TODO: document {@code GLSLParseText}.
     *
     * @param text TODO: describe
     * @param val TODO: describe
     */
    public GLSLParseText(String text, Float val) {
        this(text, Color.BLUE_WHITE, new Vector4f(val, NUM_0, NUM_0, NUM_0), 1, "");
    }

    /**
     * TODO: document {@code GLSLParseText}.
     *
     * @param val TODO: describe
     */
    public GLSLParseText(Float val) {
        this("", Color.BLUE_WHITE, new Vector4f(val, NUM_0, NUM_0, NUM_0), 1, "");
    }

    /**
     * TODO: document {@code GLSLParseText}.
     *
     * @param text TODO: describe
     * @param key TODO: describe
     */
    public GLSLParseText(String text, String key) {
        this(text, Color.BLUE_WHITE, null, -1, key);
    }

    /**
     * TODO: document {@code GLSLParseText}.
     *
     * @param text TODO: describe
     */
    public GLSLParseText(String text) {
        this(text, Color.BLUE_WHITE, null, -1, null);
    }

    /**
     * TODO: document {@code GLSLParseText}.
     *
     * @param text TODO: describe
     * @param color TODO: describe
     */
    public GLSLParseText(String text, Color color) {
        this(text, color, null, -1, null);
    }

    /**
     * TODO: document {@code GLSLParseText}.
     *
     * @param text TODO: describe
     * @param vec TODO: describe
     * @param vectorLength TODO: describe
     */
    public GLSLParseText(String text, Vector4f vec, int vectorLength) {
        this(text, Color.BLUE_WHITE, vec, vectorLength, null);
    }

    private void transformVecText(Vector4f data, int vectorLength) {
        if (vectorLength < 1 || vectorLength > NUM_4) {
            return;
        }
        super.resetText();
        if (vectorLength == 1) {
            super.addWord(formatFixed(data.x), Color.GLSL_FLOAT);
        } else {
            super.addWord(String.format("vec%s", vectorLength), Color.GLSL_VECTOR);
            super.addWord("(", Color.GLSL_PARENTHESIS);
            for (int i = 0; i < vectorLength; i++) {
                super.addWord(formatFixed(data.get(i)), vecColors[i]);
                if (i != vectorLength - 1) {
                    super.addWord(",", Color.GLSL_COMMA);
                } else {
                    super.addWord(")", Color.GLSL_PARENTHESIS);
                }
            }
        }
    }

    /**
     * TODO: document {@code join}.
     *
     * @param v TODO: describe
     * @return TODO: describe
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
     * TODO: document {@code formatFixed}.
     *
     * @param val TODO: describe
     * @return TODO: describe
     */
    public static String formatFixed(Float val) {
        int digits = 2;
        Float pow = (float) Math.pow(NUM_10, digits);
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
     * TODO: document {@code put}.
     *
     * @param env TODO: describe
     * @param var TODO: describe
     * @param dv TODO: describe
     */
    public static void put(Map<String, GLSLParseText> env, String var, Float... dv) {
        if (dv == null || dv.length == 0) {
            return;
        }
        if (dv.length == 1) {
            Float value = dv[0];
            env.put(var, new GLSLParseText("", vecColors[0], new Vector4f(value, NUM_0, NUM_0, NUM_0), 1, var));
            return;
        }
        float[] xyzw = new float[NUM_4];
        for (int i = 0; i < dv.length; i++) {
            xyzw[i] = dv[i];
        }
        Vector4f result = new Vector4f(xyzw);
        GLSLParseText vectorString = new GLSLParseText("", Color.PINK, result, dv.length, var);

        env.put(var, vectorString);
    }

    /**
     * TODO: document {@code putVec}.
     *
     * @param env TODO: describe
     * @param var TODO: describe
     * @param dv TODO: describe
     */
    public static void putVec(Map<String, GLSLParseText> env, String var, ArrayList<GLSLParseText> dv) {
        Float[] data = new Float[NUM_4];
        int vectorLength = 0;
        for (int i = 0; i < dv.size(); i++) {
            GLSLParseText pt = dv.get(i);
            Vector4f vec = dv.get(i).getData();
            for(int k = vectorLength; k < vectorLength + pt.vectorLength; k ++){
                data[k] = vec.get(k - vectorLength);
            }
            vectorLength += pt.vectorLength;

            if(pt.vectorLength < 1 || vectorLength > NUM_4){
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
