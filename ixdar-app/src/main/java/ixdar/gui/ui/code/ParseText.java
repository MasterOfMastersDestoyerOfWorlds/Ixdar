package ixdar.gui.ui.code;

import java.util.ArrayList;
import java.util.Map;

import org.joml.Vector4f;

import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.ColorText;

public class ParseText extends ColorText<Vector4f> {
    String key;
    int vectorLength;
    public static final ParseText BLANK = new ParseText("", Color.BLUE_WHITE, new Vector4f(), -1, "BLANK");

    public ParseText(String text, Color color, Vector4f data, int vectorLength, String key) {
        super(text, color, data);
        transformVecText(data, vectorLength);
        this.vectorLength = vectorLength;
        this.key = key;
    }

    static final Color[] vecColors = new Color[] { Color.GLSL_VECTOR_FLOAT_X, Color.GLSL_VECTOR_FLOAT_Y,
            Color.GLSL_VECTOR_FLOAT_Z, Color.GLSL_VECTOR_FLOAT_W };

    private void transformVecText(Vector4f data, int vectorLength) {
        if (vectorLength < 1 || vectorLength > 4) {
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

    public ParseText(String text, Color color, int vectorLength, String key) {
        this(text, color, new Vector4f(), vectorLength, key);
    }

    public ParseText(String text, int vectorLength, String key) {
        this(text, Color.BLUE_WHITE, new Vector4f(), vectorLength, key);
    }

    public ParseText(String text, Vector4f data, int vectorLength, String key) {
        this(text, Color.BLUE_WHITE, data, vectorLength, key);
    }

    ParseText(ParseText text, Vector4f data, int vectorLength, String key) {
        super(text, data);
        transformVecText(data, vectorLength);
        this.vectorLength = vectorLength;
        this.key = key;
    }

    public ParseText() {
        super();
    }

    public ParseText(String text, Color color, int vectorLength) {
        this(text, color, new Vector4f(), vectorLength, text);
    }

    public ParseText(String text, Float val) {
        this(text, Color.BLUE_WHITE, new Vector4f(val, 0f, 0f, 0f), 1, "");
    }

    public ParseText(Float val) {
        this("", Color.BLUE_WHITE, new Vector4f(val, 0f, 0f, 0f), 1, "");
    }

    public ParseText(String text, String key) {
        this(text, Color.BLUE_WHITE, null, -1, key);
    }

    public ParseText(String text) {
        this(text, Color.BLUE_WHITE, null, -1, null);
    }

    public ParseText(String text, Color color) {
        this(text, color, null, -1, null);
    }

    public ParseText(String text, Vector4f vec, int vectorLength) {
        this(text, Color.BLUE_WHITE, vec, vectorLength, null);
    }

    public ParseText join(ParseText v) {
        ParseText result = new ParseText();
        result.text = new ArrayList<>(this.text);
        result.color = new ArrayList<>(this.color);
        result.data = this.data;
        result.text.addAll(v.text);
        result.color.addAll(v.color);
        result.key = this.key;
        result.vectorLength = Math.max(this.vectorLength, v.vectorLength);
        return result;
    }

    public static String formatFixed(Float val) {
        int digits = 2;
        Float pow = (float) Math.pow(10, digits);
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

    public static void put(Map<String, ParseText> env, String var, Float... dv) {
        if (dv == null || dv.length == 0) {
            return;
        }
        if (dv.length == 1) {
            Float value = dv[0];
            env.put(var, new ParseText("", vecColors[0], new Vector4f(value, 0f, 0f, 0f), 1, var));
            return;
        }
        float[] xyzw = new float[4];
        for (int i = 0; i < dv.length; i++) {
            xyzw[i] = dv[i];
        }
        Vector4f result = new Vector4f(xyzw);
        ParseText vectorString = new ParseText("", Color.PINK, result, dv.length, var);

        env.put(var, vectorString);
    }

    public static void putVec(Map<String, ParseText> env, String var, ArrayList<ParseText> dv) {
        Float[] data = new Float[4];
        int vectorLength = 0;
        for (int i = 0; i < dv.size(); i++) {
            ParseText pt = dv.get(i);
            Vector4f vec = dv.get(i).getData();
            for(int k = vectorLength; k < vectorLength + pt.vectorLength; k ++){
                data[k] = vec.get(k - vectorLength);
            }
            vectorLength += pt.vectorLength;

            if(pt.vectorLength < 1 || vectorLength > 4){
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
