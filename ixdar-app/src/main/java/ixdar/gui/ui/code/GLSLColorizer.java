package ixdar.gui.ui.code;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.ColorText;

/**
 * Lightweight syntax colorizer for a single line of GLSL: recognises
 * whitespace,
 * brackets/braces/commas, numeric literals, identifiers (with member-access
 * chains),
 * and a small palette of operators, mapping each token to its highlight
 * {@link Color}.
 */
public final class GLSLColorizer {

    private static final Set<String> KEYWORDS = new HashSet<>();
    private static final Set<String> TYPES = new HashSet<>();
    static {

        KEYWORDS.add("return");
        KEYWORDS.add("in");
        KEYWORDS.add("out");
        KEYWORDS.add("uniform");
        KEYWORDS.add("layout");
        KEYWORDS.add("precision");
        KEYWORDS.add("attribute");
        KEYWORDS.add("varying");

        TYPES.add("void");
        TYPES.add("float");
        TYPES.add("int");
        TYPES.add("bool");
        TYPES.add("vec");
    }

    private GLSLColorizer() {
    }

    /**
     * Walk {@code codeLine} character by character, classify each token
     * (whitespace,
     * brackets, numeric literal, identifier with optional member-access tail,
     * operator),
     * and emit one {@link ColorText} per token tagged with its highlight
     * {@link Color}.
     * Identifiers are resolved against the keyword/type sets and against a
     * parenthesis
     * lookahead to flag function calls.
     *
     * @param codeLine single shader line to tokenise; {@code null} or empty returns
     *                 an empty list
     * @return ordered list of coloured token spans that, concatenated, reproduce
     *         {@code codeLine}
     */
    public static List<ColorText<?>> colorize(String codeLine) {
        ArrayList<ColorText<?>> out = new ArrayList<>();
        if (codeLine == null || codeLine.isEmpty()) {
            return out;
        }
        int i = 0;
        int n = codeLine.length();
        while (i < n) {
            char c = codeLine.charAt(i);

            if (Character.isWhitespace(c)) {
                int start = i;
                while (i < n && Character.isWhitespace(codeLine.charAt(i)))
                    i++;
                out.add(new ColorText<String>(codeLine.substring(start, i), Color.WHITE));
                continue;
            }

            if (c == '(' || c == ')') {
                out.add(new ColorText<String>(String.valueOf(c), Color.GLSL_PARENTHESIS));
                i++;
                continue;
            }

            if (c == '{' || c == '}') {
                out.add(new ColorText<String>(String.valueOf(c), Color.GLSL_BRACE));
                i++;
                continue;
            }

            if (c == ',') {
                out.add(new ColorText<String>(",", Color.GLSL_COMMA));
                i++;
                continue;
            }

            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(codeLine.charAt(i + 1)))) {
                int start = i;
                boolean sawDot = (c == '.');
                i++;
                while (i < n) {
                    char d = codeLine.charAt(i);
                    if (Character.isDigit(d)) {
                        i++;
                        continue;
                    }
                    if (d == '.' && !sawDot) {
                        sawDot = true;
                        i++;
                        continue;
                    }
                    break;
                }
                out.add(new ColorText<String>(codeLine.substring(start, i), Color.GLSL_FLOAT));
                continue;
            }

            if (isIdentStart(c)) {
                int start = i;
                i++;
                while (i < n && isIdentPart(codeLine.charAt(i)))
                    i++;

                int k = i;
                while (k < n && codeLine.charAt(k) == '.' && (k + 1) < n && isIdentStart(codeLine.charAt(k + 1))) {
                    k++;
                    while (k < n && isIdentPart(codeLine.charAt(k)))
                        k++;
                }
                String ident = codeLine.substring(start, k);
                i = k;

                String base = ident;
                int dot = ident.indexOf('.');
                if (dot >= 0)
                    base = ident.substring(0, dot);
                String lower = base.toLowerCase();

                int j = i;
                while (j < n && Character.isWhitespace(codeLine.charAt(j)))
                    j++;
                boolean isCall = (j < n && codeLine.charAt(j) == '(');

                if (KEYWORDS.contains(lower)) {
                    out.add(new ColorText<String>(ident, Color.GLSL_KEYWORD));
                } else if (lower.matches("vec[234]")) {
                    out.add(new ColorText<String>(ident, Color.GLSL_VECN));
                } else if (TYPES.contains(lower)) {
                    out.add(new ColorText<String>(ident, Color.GLSL_TYPE));
                } else if (isCall) {
                    out.add(new ColorText<String>(ident, Color.GLSL_FUNCTION));
                } else {
                    out.add(new ColorText<String>(ident, Color.WHITE));
                }
                continue;
            }

            if (c == '+' || c == '-' || c == '*' || c == '/') {
                out.add(new ColorText<String>(String.valueOf(c), Color.GLSL_OPERATOR));
            } else if (c == '=') {
                out.add(new ColorText<String>("=", Color.GLSL_EQUALS));
            } else {
                out.add(new ColorText<String>(String.valueOf(c), Color.WHITE));
            }
            i++;
        }
        return out;
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
