package shell.ui.code;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import shell.render.color.Color;
import shell.render.text.ColorText;

public final class GLSLColorizer {

    private GLSLColorizer() {
    }

    private static final Set<String> KEYWORDS = new HashSet<>();
    private static final Set<String> TYPES = new HashSet<>();
    static {
        // flow/qualifier keywords
        KEYWORDS.add("return");
        KEYWORDS.add("in");
        KEYWORDS.add("out");
        KEYWORDS.add("uniform");
        KEYWORDS.add("layout");
        KEYWORDS.add("precision");
        KEYWORDS.add("attribute");
        KEYWORDS.add("varying");

        // basic types
        TYPES.add("void");
        TYPES.add("float");
        TYPES.add("int");
        TYPES.add("bool");
        TYPES.add("vec");
    }

    public static List<ColorText<?>> colorize(String codeLine) {
        ArrayList<ColorText<?>> out = new ArrayList<>();
        if (codeLine == null || codeLine.isEmpty()) {
            return out;
        }
        int i = 0;
        int n = codeLine.length();
        while (i < n) {
            char c = codeLine.charAt(i);
            // whitespace (preserve spacing)
            if (Character.isWhitespace(c)) {
                int start = i;
                while (i < n && Character.isWhitespace(codeLine.charAt(i)))
                    i++;
                out.add(new ColorText<String>(codeLine.substring(start, i), Color.WHITE));
                continue;
            }
            // parentheses
            if (c == '(' || c == ')') {
                out.add(new ColorText<String>(String.valueOf(c), Color.GLSL_PARENTHESIS));
                i++;
                continue;
            }
            // curly braces
            if (c == '{' || c == '}') {
                out.add(new ColorText<String>(String.valueOf(c), Color.GLSL_BRACE));
                i++;
                continue;
            }
            // comma
            if (c == ',') {
                out.add(new ColorText<String>(",", Color.GLSL_COMMA));
                i++;
                continue;
            }
            // numbers (floats/ints) - color as float
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
            // identifiers
            if (isIdentStart(c)) {
                int start = i;
                i++;
                while (i < n && isIdentPart(codeLine.charAt(i)))
                    i++;
                // consume chained member access: .identPart repeatedly (e.g., variable.xyz)
                int k = i;
                while (k < n && codeLine.charAt(k) == '.' && (k + 1) < n && isIdentStart(codeLine.charAt(k + 1))) {
                    k++; // skip '.'
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

                // lookahead skipping whitespace
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
            // single char fallback (operators, semicolons, etc.)
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
