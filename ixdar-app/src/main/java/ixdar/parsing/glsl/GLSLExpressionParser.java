package ixdar.parsing.glsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.function.TriFunction;
import org.joml.Vector4f;

import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.graphics.render.text.SpecialGlyphs;

public class GLSLExpressionParser {
    public static final String STR = "//";
    public static final String STR_2 = ";";
    public static final String IF = "if";
    public static final String STR_3 = "==";
    public static final String IN = "in ";
    public static final String OUT = "out ";
    public static final String UNIFORM = "uniform ";
    public static final String PRECISION = "precision ";
    public static final String VOID = "void ";
    public static final String TRUE = "true";
    public static final String FALSE = "false";
    public static final String STR_4 = " = ";
    public static final String STR_5 = "{";
    public static final String ELSE = "else";
    public static final String SKIP = "SKIP";
    public static final String ELSE_2 = "else{";
    public static final String ELSE_3 = "else {";
    public static final String STR_6 = "}";
    public static final String STR_7 = "!=";
    public static final String STR_8 = ">=";
    public static final String STR_9 = "<=";
    public static final String STR_10 = ".";
    public static final String QUARTERPI = "quarterPI";
    public static final String PI = "pi";
    public static final String HALFPI = "halfpi";
    public static final String TAU = "TAU";
    public static final String E = "e";
    public static final String FRAGCOLOR = "FragColor";
    public static final int NUM_4 = 4;
    public static final double NUM_1e_6 = 1e-6;
    public static final float NUM_4_2 = 4f;
    public static final float NUM_2 = 2f;
    public static final float NUM_2_0 = 2.0f;
    public static final double NUM_0_5 = 0.5;
    public static final float NUM_0 = 0f;
    public static final double NUM_3_0 = 3.0;
    public static final double NUM_2_0_2 = 2.0;
    public static final int NUM_3 = 3;

    public static final GLSLParseText MISSING = new GLSLParseText("?Missing?", Color.PINK, -1);

    private final String s;
    private int pos;
    private final Map<String, GLSLParseText> env;

    GLSLExpressionParser(String s, Map<String, GLSLParseText> env) {
        this.s = s;
        this.env = env;
        this.pos = 0;
    }

    /**
     * Evaluate a single GLSL line, optionally assigning into {@code env}. Handles
     * inline {@code if/else} expressions, swizzle assignments, {@code vecN(...)}
     * constructors, and bare arithmetic. Declarations and lines containing
     * {@code ==}, {@code ?}, layout/uniform/etc. prefixes are skipped.
     *
     * @param line raw source line (comment and trailing semicolon are stripped)
     * @param env  mutable variable environment; written for assignments
     * @return computed value, or {@code null} if the line was skipped or failed
     */
    public static GLSLParseText evaluateAndAssign(String line, Map<String, GLSLParseText> env) {

        String s = line;
        int cidx = s.indexOf(STR);
        if (cidx >= 0) {
            s = s.substring(0, cidx);
        }
        s = s.trim();
        if (s.endsWith(STR_2)) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return null;
        }

        String sTrimLower = s.trim().toLowerCase();
        if (sTrimLower.startsWith(IF)) {
            try {
                return evaluateIfElse(s.trim(), env);
            } catch (Exception ignore) {
                return null;
            }
        }

        String sl = s.toLowerCase();
        if (s.contains(STR_3) || s.contains("?") || s.contains(":") || s.startsWith("#")
                || sl.startsWith(IN) || sl.startsWith(OUT) || sl.startsWith(UNIFORM)
                || sl.startsWith("layout") || sl.startsWith(PRECISION) || sl.startsWith(VOID)
                || sl.startsWith("struct ") || sl.startsWith("attribute ") || sl.startsWith("varying ")) {
            return null;
        }

        int eq = s.indexOf('=');
        if (eq > 0 && s.indexOf('=', eq + 1) == -1) {
            String left = s.substring(0, eq).trim();
            String right = s.substring(eq + 1).trim();
            String var = extractVarName(left);
            if (var != null && !var.isEmpty()) {

                if (right.toLowerCase().startsWith(IF)) {
                    GLSLParseText ifVal = evaluateIfElse(right, env);
                    if (ifVal != null && ifVal.data != null) {
                        env.put(var, ifVal);
                        return ifVal;
                    }
                }

                if (isSwizzle(right)) {
                    String base = right.substring(0, right.indexOf('.'));
                    String sw = right.substring(right.indexOf('.') + 1);
                    ArrayList<GLSLParseText> comps = resolveSwizzleVector(base, sw, env);
                    if (comps != null && comps.size() > 0) {
                        GLSLParseText.putVec(env, var, comps);
                        return env.get(var);
                    }
                }

                if (right.startsWith("vec") && right.contains("(") && right.endsWith(")")) {
                    ArrayList<GLSLParseText> vec = parseVec(right, env);
                    if (vec != null && vec.size() > 0) {
                        GLSLParseText.putVec(env, var, vec);
                        return env.get(var);
                    } else {
                        env.put(var, new GLSLParseText(right, Color.BLUE_WHITE, -1));
                        return env.get(var);
                    }
                }
                try {
                    GLSLParseText val = new GLSLExpressionParser(right, env).parse();
                    if (val != null && val.data != null)
                        env.put(var, val);
                    return val;
                } catch (Exception ex) {
                    return null;
                }
            }
        } else {

            if (!s.matches(".*([A-Za-z_][A-Za-z0-9_]*|[0-9]|sin|cos|tan|sqrt|abs|min|max|clamp|mix|distance|dot).*")) {
                return null;
            }
            try {
                return new GLSLExpressionParser(s, env).parse();
            } catch (Exception ex) {
                return null;
            }
        }
        return null;
    }

    /**
     * Walk a block of GLSL lines, tracking brace-scoped {@code if}/{@code else if}/
     * {@code else} execution, evaluating only assignments inside taken branches,
     * and writing each line's display suffix (computed value or {@code SKIP}) into
     * {@code cachedSuffixes} at the matching index.
     *
     * @param lines          source lines in original order; {@code null} entries
     *                       are tolerated
     * @param env            mutable variable environment; only updated for executed
     *                       assignments
     * @param cachedSuffixes per-line output slots, must already match
     *                       {@code lines.size()}
     */
    public static void evaluateAndAssign(List<String> lines, Map<String, GLSLParseText> env,
            List<GLSLParseText> cachedSuffixes) {
        if (lines == null || cachedSuffixes == null) {
            return;
        }

        ArrayList<Boolean> execStack = new ArrayList<>();
        execStack.add(Boolean.TRUE);

        ArrayList<Boolean> isIfStack = new ArrayList<>();
        isIfStack.add(Boolean.FALSE);
        ArrayList<Boolean> elseShouldExecStack = new ArrayList<>();
        elseShouldExecStack.add(Boolean.FALSE);
        int braceDepth = 0;
        boolean awaitingElseExec = false;
        int awaitingElseDepth = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String original = line != null ? line : "";
            GLSLParseText out = GLSLParseText.BLANK;

            String s = original;
            int cidx = s.indexOf(STR);
            if (cidx >= 0)
                s = s.substring(0, cidx);
            String decl = s.trim();

            if (!decl.isEmpty() && decl.charAt(0) == '}') {

                while (!decl.isEmpty() && decl.charAt(0) == '}') {
                    if (execStack.size() > 1) {
                        boolean poppedIsIf = isIfStack.remove(isIfStack.size() - 1);
                        boolean poppedElseShouldExec = elseShouldExecStack
                                .remove(elseShouldExecStack.size() - 1);
                        execStack.remove(execStack.size() - 1);
                        braceDepth = Math.max(0, braceDepth - 1);
                        if (poppedIsIf) {
                            awaitingElseExec = poppedElseShouldExec;
                            awaitingElseDepth = braceDepth;
                        }
                    }
                    decl = decl.substring(1).trim();
                }

            }

            IfHeader ifHdr = parseIfHeader(decl);
            if (ifHdr != null && ifHdr.hasOpenBrace) {
                boolean parentExec = execStack.get(execStack.size() - 1);
                boolean thenExec = false;
                boolean elseExec = false;
                if (parentExec) {
                    boolean condVal = evaluateCondition(ifHdr.condition, env);
                    thenExec = condVal;
                    elseExec = !condVal;
                }
                execStack.add(Boolean.valueOf(parentExec && thenExec));
                isIfStack.add(Boolean.TRUE);
                elseShouldExecStack.add(Boolean.valueOf(parentExec && elseExec));
                braceDepth++;

                boolean doExecThen = parentExec && thenExec;
                GLSLParseText boolVal = new GLSLParseText(doExecThen ? TRUE : FALSE, Color.GLSL_BOOLEAN);
                cachedSuffixes.set(i, commentStart(boolVal).join(new GLSLParseText(STR_4)).join(boolVal));
                continue;
            }

            IfHeader elseIfHdr = parseElseIfHeader(decl);
            if (elseIfHdr != null && elseIfHdr.hasOpenBrace) {
                boolean parentExec = execStack.get(execStack.size() - 1);
                boolean eligible = awaitingElseExec && parentExec && (braceDepth == awaitingElseDepth);
                boolean condVal = false;
                if (eligible) {
                    condVal = evaluateCondition(elseIfHdr.condition, env);
                }
                boolean doExec = eligible && condVal;
                execStack.add(Boolean.valueOf(doExec));
                isIfStack.add(Boolean.FALSE);
                elseShouldExecStack.add(Boolean.FALSE);
                braceDepth++;
                if (doExec) {
                    awaitingElseExec = false;
                }
                GLSLParseText boolVal = new GLSLParseText(doExec ? TRUE : FALSE, Color.GLSL_BOOLEAN);
                cachedSuffixes.set(i, commentStart(boolVal).join(new GLSLParseText(STR_4)).join(boolVal));
                continue;
            }

            if (isElseOpenBrace(decl)) {
                boolean parentExec = execStack.get(execStack.size() - 1);
                boolean doExec = awaitingElseExec && parentExec && (braceDepth == awaitingElseDepth);
                execStack.add(Boolean.valueOf(doExec));
                isIfStack.add(Boolean.FALSE);
                elseShouldExecStack.add(Boolean.FALSE);
                braceDepth++;
                awaitingElseExec = false;

                GLSLParseText boolVal = new GLSLParseText(doExec ? TRUE : FALSE, Color.GLSL_BOOLEAN);
                cachedSuffixes.set(i, commentStart(boolVal).join(new GLSLParseText(STR_4)).join(boolVal));
                continue;
            }

            if (startsWithElse(decl) && !decl.endsWith(STR_5)) {
                boolean parentExec = execStack.get(execStack.size() - 1);
                boolean doExec = awaitingElseExec && parentExec && (braceDepth == awaitingElseDepth);
                String afterElse = decl.substring(decl.toLowerCase().indexOf(ELSE) + NUM_4).trim();
                if (!afterElse.isEmpty() && afterElse.startsWith(IF)) {
                    IfHeaderPos posHdr = parseIfHeaderWithPos(afterElse);
                    boolean condVal = false;
                    if (doExec && posHdr != null) {
                        condVal = evaluateCondition(posHdr.condition, env);
                    }
                    boolean runThen = doExec && condVal;
                    if (runThen && posHdr != null) {
                        String thenStmt = afterElse.substring(posHdr.closeIndex + 1).trim();
                        if (!thenStmt.isEmpty() && !thenStmt.startsWith(STR_5)) {
                            GLSLParseText res = evaluateAndAssign(thenStmt, env);
                            if (res != null) {
                                out = commentStart(res).join(new GLSLParseText(STR_4)).join(res);
                            }
                        } else {
                            out = GLSLParseText.BLANK;
                        }
                    } else {
                        out = GLSLParseText.BLANK;
                    }
                    if (runThen) {
                        awaitingElseExec = false;
                    }
                    GLSLParseText boolVal = new GLSLParseText(runThen ? TRUE : FALSE, Color.GLSL_BOOLEAN);
                    if (!runThen) {
                        GLSLParseText skip = new GLSLParseText(SKIP, Color.GLSL_SKIP);
                        cachedSuffixes.set(i, commentStart(skip).join(new GLSLParseText(STR_4)).join(skip));
                    } else {
                        cachedSuffixes.set(i, commentStart(boolVal).join(new GLSLParseText(STR_4)).join(boolVal));
                    }
                    continue;
                } else {
                    if (doExec && !afterElse.isEmpty()) {
                        GLSLParseText res = evaluateAndAssign(afterElse, env);
                        if (res != null) {
                            out = commentStart(res).join(new GLSLParseText(STR_4)).join(res);
                        }
                    } else if (!afterElse.isEmpty()) {
                        GLSLParseText skip = new GLSLParseText(SKIP, Color.GLSL_SKIP);
                        out = commentStart(skip).join(new GLSLParseText(STR_4)).join(skip);
                    } else {
                        out = GLSLParseText.BLANK;
                    }
                    awaitingElseExec = false;
                }
                cachedSuffixes.set(i, out);
                continue;
            }

            boolean executing = execStack.get(execStack.size() - 1);
            if (executing && !skipControlOnlyLine(decl)) {
                if (decl.startsWith("uniform") || decl.startsWith("in")) {
                    String name = extractUniformName(decl);
                    if (name != null) {
                        GLSLParseText v = env.get(name);
                        if (v != null) {
                            out = commentStart(v).join(new GLSLParseText(STR_4)).join(v);
                        }
                    }
                } else {
                    GLSLParseText res = evaluateAndAssign(decl, env);
                    if (res != null) {
                        out = commentStart(res).join(new GLSLParseText(STR_4)).join(res);
                    }
                }
            } else if (!executing && !skipControlOnlyLine(decl)) {
                GLSLParseText skip = new GLSLParseText(SKIP, Color.GLSL_SKIP);
                out = commentStart(skip).join(new GLSLParseText(STR_4)).join(skip);
            } else {
                out = GLSLParseText.BLANK;
            }

            cachedSuffixes.set(i, out);
        }
    }

    /**
     * Compute, for each line, whether it would execute under the current env,
     * without mutating env or suffixes. Control-flow only (if/else/braces).
     *
     * @param lines       source lines in original order
     * @param envSnapshot read-only environment used when evaluating conditions
     * @return list of booleans, one per input line, in the same order
     */
    public static List<Boolean> wouldExecute(List<String> lines, Map<String, GLSLParseText> envSnapshot) {
        ArrayList<Boolean> execFlags = new ArrayList<>();
        if (lines == null) {
            return execFlags;
        }
        ArrayList<Boolean> execStack = new ArrayList<>();
        execStack.add(Boolean.TRUE);
        ArrayList<Boolean> isIfStack = new ArrayList<>();
        isIfStack.add(Boolean.FALSE);
        ArrayList<Boolean> elseShouldExecStack = new ArrayList<>();
        elseShouldExecStack.add(Boolean.FALSE);

        for (int i = 0; i < lines.size(); i++) {
            String decl = stripCommentsAndTrim(lines.get(i));
            String lower = decl.toLowerCase();
            if (decl.isEmpty()) {
                execFlags.add(Boolean.FALSE);
                continue;
            }
            int closes = countChar(decl, '}');
            boolean doExec = execStack.get(execStack.size() - 1);
            if (lower.startsWith(IF)) {
                String condStr = extractCondition(decl);
                boolean cond = evaluateCondition(condStr, envSnapshot);
                isIfStack.add(Boolean.TRUE);
                elseShouldExecStack.add(Boolean.valueOf(!cond));
                execStack.add(Boolean.valueOf(cond && doExec));
                execFlags.add(Boolean.valueOf(cond && doExec));
            } else if (lower.startsWith(ELSE)) {
                boolean wasIf = isIfStack.get(isIfStack.size() - 1);
                if (wasIf) {
                    execStack.remove(execStack.size() - 1);
                    boolean elseExec = elseShouldExecStack.get(elseShouldExecStack.size() - 1).booleanValue();
                    execStack.add(Boolean.valueOf(elseExec && doExec));
                    isIfStack.remove(isIfStack.size() - 1);
                    elseShouldExecStack.remove(elseShouldExecStack.size() - 1);
                }
                execFlags.add(execStack.get(execStack.size() - 1));
            } else {
                execFlags.add(Boolean.valueOf(doExec && !skipControlOnlyLine(decl)));
            }
            while (closes-- > 0 && execStack.size() > 1) {
                execStack.remove(execStack.size() - 1);
                isIfStack.remove(isIfStack.size() - 1);
                elseShouldExecStack.remove(elseShouldExecStack.size() - 1);
            }
        }
        return execFlags;
    }

    private static String stripCommentsAndTrim(String s) {
        if (s == null) {
            return "";
        }
        int cidx = s.indexOf(STR);
        if (cidx >= 0) {
            s = s.substring(0, cidx);
        }
        return s.trim();
    }

    private static int countChar(String s, char c) {
        if (s == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static String extractCondition(String decl) {
        IfHeader ih = parseIfHeader(decl);
        if (ih != null) {
            return ih.condition;
        }
        IfHeader eih = parseElseIfHeader(decl);
        if (eih != null) {
            return eih.condition;
        }
        return null;
    }

    private static boolean isElseOpenBrace(String decl) {
        String t = decl.toLowerCase();
        return t.equals(ELSE_2) || t.equals(ELSE_3);
    }

    private static boolean startsWithElse(String decl) {
        String t = decl.toLowerCase();
        return t.startsWith(ELSE);
    }

    private static IfHeader parseIfHeader(String decl) {
        String t = decl.trim();
        if (!t.startsWith(IF))
            return null;
        int i = 2;
        int n = t.length();
        while (i < n && Character.isWhitespace(t.charAt(i)))
            i++;
        if (i >= n || t.charAt(i) != '(')
            return null;
        int open = i;
        int close = findMatchingParen(t, open);
        if (close < 0)
            return null;
        String cond = t.substring(open + 1, close).trim();
        boolean hasBrace = false;
        for (int k = close + 1; k < n; k++) {
            char c = t.charAt(k);
            if (Character.isWhitespace(c))
                continue;
            hasBrace = (c == '{');
            break;
        }
        return new IfHeader(cond, hasBrace);
    }

    private static IfHeader parseElseIfHeader(String decl) {
        String t = decl.trim().toLowerCase();
        if (!t.startsWith("else if") && !t.startsWith("elseif"))
            return null;

        String rest = decl.trim();
        int idx = rest.toLowerCase().indexOf(IF);
        if (idx < 0)
            return null;
        String afterElse = rest.substring(idx);
        return parseIfHeader(afterElse);
    }

    private static IfHeaderPos parseIfHeaderWithPos(String decl) {
        String t = decl.trim();
        if (!t.startsWith(IF))
            return null;
        int i = 2;
        int n = t.length();
        while (i < n && Character.isWhitespace(t.charAt(i)))
            i++;
        if (i >= n || t.charAt(i) != '(')
            return null;
        int open = i;
        int close = findMatchingParen(t, open);
        if (close < 0)
            return null;
        String cond = t.substring(open + 1, close).trim();
        boolean hasBrace = false;
        for (int k = close + 1; k < n; k++) {
            char c = t.charAt(k);
            if (Character.isWhitespace(c))
                continue;
            hasBrace = (c == '{');
            break;
        }
        return new IfHeaderPos(cond, hasBrace, close);
    }

    private static boolean skipControlOnlyLine(String decl) {
        String t = decl.trim();
        if (t.isEmpty())
            return true;
        if (t.equals(STR_5) || t.equals(STR_6))
            return true;
        if (t.equalsIgnoreCase(ELSE) || t.equalsIgnoreCase(ELSE_2) || t.equalsIgnoreCase(ELSE_3))
            return true;

        IfHeader h = parseIfHeader(t);
        if (h != null && h.hasOpenBrace)
            return true;
        return false;
    }

    private static GLSLParseText commentStart(GLSLParseText res) {
        if (res.vectorLength == NUM_4) {
            return new GLSLParseText(SpecialGlyphs.COLOR_TRACKER.getChar() + "",
                    new ColorRGB(res.data.x, res.data.y, res.data.z, res.data.w));
        } else {
            return new GLSLParseText(STR);
        }
    }

    private static GLSLParseText evaluateIfElse(String s, Map<String, GLSLParseText> env) {

        if (s == null) {
            return null;
        }
        int i = 0;
        int n = s.length();

        while (i < n && Character.isWhitespace(s.charAt(i)))
            i++;
        if (i + 1 >= n || s.charAt(i) != 'i' || s.charAt(i + 1) != 'f') {
            return null;
        }
        i += 2;

        while (i < n && Character.isWhitespace(s.charAt(i)))
            i++;
        if (i >= n || s.charAt(i) != '(') {
            return null;
        }
        int condStart = i + 1;
        int condEnd = findMatchingParen(s, i);
        if (condEnd < 0) {
            return null;
        }
        String condStr = s.substring(condStart, condEnd).trim();

        int thenStart = condEnd + 1;

        int elseIdx = findTopLevelElse(s, thenStart);
        String thenPart;
        String elsePart = null;
        if (elseIdx >= 0) {
            thenPart = s.substring(thenStart, elseIdx).trim();
            elsePart = s.substring(elseIdx + NUM_4).trim();
        } else {
            thenPart = s.substring(thenStart).trim();
        }
        thenPart = stripBracesAndSemicolon(thenPart);
        if (elsePart != null) {
            elsePart = stripBracesAndSemicolon(elsePart);
        }

        boolean cond = evaluateCondition(condStr, env);
        String chosen = cond ? thenPart : elsePart;
        if (chosen == null || chosen.isEmpty()) {
            return null;
        }

        GLSLParseText res = evaluateAndAssign(chosen, env);
        if (res != null) {
            return res;
        }
        try {
            return new GLSLExpressionParser(chosen, env).parse();
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean evaluateCondition(String cond, Map<String, GLSLParseText> env) {
        if (cond == null) {
            return false;
        }

        int[] hit = findTopLevelComparator(cond);
        try {
            if (hit != null) {
                int idx = hit[0];
                int len = hit[1];
                String op = cond.substring(idx, idx + len);
                String left = cond.substring(0, idx).trim();
                String right = cond.substring(idx + len).trim();
                GLSLParseText lv = new GLSLExpressionParser(left, env).parse();
                GLSLParseText rv = new GLSLExpressionParser(right, env).parse();
                double l = lv.data.x;
                double r = rv.data.x;
                switch (op) {
                    case STR_3:
                        return l == r;
                    case STR_7:
                        return l != r;
                    case STR_8:
                        return l >= r;
                    case STR_9:
                        return l <= r;
                    case ">":
                        return l > r;
                    case "<":
                        return l < r;
                    default:
                        return false;
                }
            } else {

                GLSLParseText v = new GLSLExpressionParser(cond, env).parse();
                return Math.abs(v.data.x) > NUM_1e_6;
            }
        } catch (Exception ex) {
            return false;
        }
    }

    private static int findMatchingParen(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(')
                depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0)
                    return i;
            }
        }
        return -1;
    }

    private static int[] findTopLevelComparator(String s) {

        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth--;
                continue;
            }
            if (depth != 0)
                continue;
            if (i + 1 < s.length()) {
                String two = s.substring(i, i + 2);
                if (two.equals(STR_3) || two.equals(STR_7) || two.equals(STR_8) || two.equals(STR_9)) {
                    return new int[] { i, 2 };
                }
            }
            if (c == '>' || c == '<') {
                return new int[] { i, 1 };
            }
        }
        return null;
    }

    private static int findTopLevelElse(String s, int start) {
        int depthParen = 0;
        int depthBrace = 0;
        for (int i = start; i <= s.length() - NUM_4; i++) {
            char c = s.charAt(i);
            if (c == '(')
                depthParen++;
            else if (c == ')')
                depthParen--;
            else if (c == '{')
                depthBrace++;
            else if (c == '}')
                depthBrace--;
            if (depthParen == 0 && depthBrace == 0) {

                if ((s.charAt(i) == 'e' || s.charAt(i) == 'E') && s.regionMatches(true, i, ELSE, 0, NUM_4)) {

                    boolean beforeOk = (i == 0) || !Character.isLetterOrDigit(s.charAt(i - 1));
                    int j = i + NUM_4;
                    boolean afterOk = (j >= s.length()) || !Character.isLetterOrDigit(s.charAt(j));
                    if (beforeOk && afterOk) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private static String stripBracesAndSemicolon(String part) {
        if (part == null)
            return null;
        String t = part.trim();
        if (t.startsWith(STR_5) && t.endsWith(STR_6)) {
            t = t.substring(1, t.length() - 1).trim();
        }
        if (t.endsWith(STR_2)) {
            t = t.substring(0, t.length() - 1).trim();
        }
        return t;
    }

    private static String extractVarName(String left) {

        String cleaned = left.replaceAll("^(const\\s+)?(uniform\\s+|varying\\s+)?(float|Float|int)\\s+", "").trim();

        String[] parts = cleaned.split("[^A-Za-z0-9_]+");
        if (parts.length == 0) {
            return null;
        }
        return parts[parts.length - 1];
    }

    /**
     * Return the variable name on the left-hand side of a single-{@code =} GLSL
     * assignment after stripping comments, or {@code null} if no simple assignment
     * is present.
     *
     * @param line raw source line
     * @return assigned variable name, or {@code null}
     */
    public static String extractAssignedVar(String line) {
        if (line == null) {
            return null;
        }
        String s = line;
        int cidx = s.indexOf(STR);
        if (cidx >= 0) {
            s = s.substring(0, cidx);
        }
        int eq = s.indexOf('=');
        if (eq > 0 && s.indexOf('=', eq + 1) == -1) {
            String left = s.substring(0, eq).trim();
            return extractVarName(left);
        }
        return null;
    }

    GLSLParseText parse() {
        GLSLParseText v = parseExpr();
        skipWs();
        return v;
    }

    private GLSLParseText parseExpr() {
        GLSLParseText v = parseTerm();
        while (true) {
            skipWs();
            if (match('+')) {
                GLSLParseText r = parseTerm();
                List<GLSLParseText> list = new ArrayList<>();
                list.add(v);
                list.add(r);
                v = applyTwoArgFunc((x, y) -> x + y, list);
            } else if (match('-')) {
                GLSLParseText r = parseTerm();
                List<GLSLParseText> list = new ArrayList<>();
                list.add(v);
                list.add(r);
                v = applyTwoArgFunc((x, y) -> x - y, list);
            } else {
                return v;
            }
        }
    }

    private GLSLParseText parseTerm() {
        GLSLParseText v = parseFactor();
        while (true) {
            skipWs();
            if (match('*')) {
                GLSLParseText r = parseFactor();
                List<GLSLParseText> list = new ArrayList<>();
                list.add(v);
                list.add(r);
                v = applyTwoArgFunc((x, y) -> x * y, list);
            } else if (match('/')) {
                GLSLParseText r = parseFactor();
                List<GLSLParseText> list = new ArrayList<>();
                list.add(v);
                list.add(r);
                v = applyTwoArgFunc((x, y) -> x / y, list);
            } else {
                return v;
            }
        }
    }

    private GLSLParseText parseFactor() {
        skipWs();
        if (match('+')) {
            return parseFactor();
        }
        if (match('-')) {
            GLSLParseText f = parseFactor();
            Vector4f out = new Vector4f(f.data).negate();
            return new GLSLParseText("", Color.GLSL_FLOAT, out, f.vectorLength, "");
        }
        if (match('(')) {
            GLSLParseText v = parseExpr();
            expect(')');
            return v;
        }
        if (isAlpha(peek())) {
            String ident = parseIdent();
            skipWs();
            if (match('(')) {
                List<GLSLParseText> args = new ArrayList<>();
                skipWs();
                if (!peekIs(')')) {
                    do {
                        args.add(parseExpr());
                        skipWs();
                    } while (match(','));
                }
                expect(')');
                return applyFunc(ident, args);
            } else {

                if (peekIs('.')) {
                    match('.');
                    StringBuilder sb = new StringBuilder();
                    while (pos < s.length() && isSwizzleChar(s.charAt(pos))) {
                        sb.append(s.charAt(pos++));
                    }
                    String sw = sb.toString();
                    if (sw.length() >= 1) {
                        String name = ident + STR_10 + sw;
                        return resolveVar(name);
                    }
                }
                return resolveVar(ident);
            }
        }
        return parseNumberOrParenExpr();
    }

    private GLSLParseText parseNumberOrParenExpr() {
        skipWs();
        int start = pos;

        if (match('(')) {
            GLSLParseText inner = parseExpr();
            expect(')');
            return inner;
        }
        boolean sawDigit = false;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (Character.isDigit(c) || c == '.') {
                sawDigit = true;
                pos++;
            } else {
                break;
            }
        }
        if (!sawDigit) {
            throw new RuntimeException("Expected number at " + pos);
        }
        Float val = Float.parseFloat(s.substring(start, pos));
        return new GLSLParseText(val);
    }

    private String parseIdent() {
        int start = pos;
        while (pos < s.length() && (isAlphaNum(s.charAt(pos)) || s.charAt(pos) == '_'))
            pos++;
        return s.substring(start, pos);
    }

    private GLSLParseText resolveVar(String name) {
        if (QUARTERPI.equalsIgnoreCase(name))
            return new GLSLParseText(QUARTERPI, (float) Math.PI / NUM_4_2);
        if (PI.equalsIgnoreCase(name))
            return new GLSLParseText(PI, (float) Math.PI);
        if (HALFPI.equalsIgnoreCase(name))
            return new GLSLParseText(HALFPI, (float) Math.PI / NUM_2);
        if (TAU.equalsIgnoreCase(name))
            return new GLSLParseText(TAU, (float) (Math.PI * NUM_2_0));
        if (E.equalsIgnoreCase(name))
            return new GLSLParseText(E, (float) Math.E);

        int dotIdx = name.indexOf('.');
        if (dotIdx > 0 && dotIdx == name.lastIndexOf('.')) {
            String base = name.substring(0, dotIdx);
            String sw = name.substring(dotIdx + 1);
            int vectorLength = sw.length();
            float[] xyzw = new float[NUM_4];
            if (isValidSwizzle(sw)) {
                Vector4f org = env.get(base).getData();
                for (int i = 0; i < vectorLength; i++) {
                    int component = componentSuffix(sw.charAt(i));
                    xyzw[i] = org.get(component);
                }
                Vector4f vec = new Vector4f(xyzw);
                return new GLSLParseText(name, vec, vectorLength);
            }
        }
        GLSLParseText v = env.get(name);
        if (v == null) {
            throw new RuntimeException("Unknown variable: " + name);
        }
        return v;
    }

    private GLSLParseText applyFunc(String name, List<GLSLParseText> a) {
        switch (name) {
            case "sin":
                return applyOneArgFunc(Math::sin, a);
            case "cos":
                return applyOneArgFunc(Math::cos, a);
            case "tan":
                return applyOneArgFunc(Math::tan, a);
            case "sqrt":
                return applyOneArgFunc(Math::sqrt, a);
            case "abs":
                return applyOneArgFunc(Math::abs, a);
            case "floor":
                return applyOneArgFunc(Math::floor, a);
            case "ceil":
                return applyOneArgFunc(Math::ceil, a);
            case "pow":
                return applyTwoArgFunc(Math::pow, a);
            case "sign":
                return applyOneArgFunc((x) -> {
                    if (x < 0) {
                        return -1.0;
                    } else if (x > 0) {
                        return 1.0;
                    }
                    return 0.0;
                }, a);
            case "round":
                return applyOneArgFunc((x) -> x - (x.intValue()) < NUM_0_5 ? Math.floor(x) : Math.ceil(x), a);
            case "min":
                return applyTwoArgFunc(Math::min, a);
            case "mod":
                return applyTwoArgFunc((x, y) -> x % y, a);
            case "max":
                return applyTwoArgFunc(Math::max, a);
            case "dot":
                return applyTwoArgFuncSum((x, y) -> x * y, a);
            case "distance":
                return distanceFunc(a);
            case "mix": {
                return mixFunc(a);
            }
            case "float": {

                GLSLParseText arg = a.get(0);
                Vector4f v = arg.data;
                Vector4f res = new Vector4f(v.x, NUM_0, NUM_0, NUM_0);
                return new GLSLParseText(s, res, 1, "");
            }
            case "smoothstep": {
                return applyThreeArgFunc((edge0, edge1, x) -> {
                    if (edge0 == edge1)
                        return x < edge0 ? 0.0 : 1.0;
                    double t = (x - edge0) / (edge1 - edge0);
                    if (t < 0.0)
                        t = 0.0;
                    if (t > 1.0)
                        t = 1.0;
                    return t * t * (NUM_3_0 - NUM_2_0_2 * t);
                }, a);

            }
            case "clamp": {
                return applyThreeArgFunc((x, lo, hi) -> Math.max(lo, Math.min(hi, x)), a);
            }
            case "vec2":
                return constructVecN(2, a);
            case "vec3":
                return constructVecN(NUM_3, a);
            case "vec4":
                return constructVecN(NUM_4, a);
            default:
                return GLSLParseText.BLANK;
        }
    }

    private GLSLParseText constructVecN(int n, List<GLSLParseText> args) {
        float[] out = new float[NUM_4];
        int filled = 0;
        for (int i = 0; i < args.size() && filled < n; i++) {
            GLSLParseText a = args.get(i);
            int len = Math.max(1, a.vectorLength);
            for (int k = 0; k < len && filled < n; k++) {
                out[filled++] = a.data.get(Math.min(k, len - 1));
            }
        }
        while (filled < n)
            out[filled++] = NUM_0;
        Vector4f result = new Vector4f(out);
        return new GLSLParseText(s, result, n, "");
    }

    private GLSLParseText applyOneArgFunc(Function<Double, Double> func, List<GLSLParseText> a) {
        GLSLParseText arg = a.get(0);
        Vector4f data = arg.data;
        float[] result = new float[NUM_4];
        for (int i = 0; i < arg.vectorLength; i++) {
            result[i] = func.apply((double) data.get(i)).floatValue();
        }
        Vector4f resultVec = new Vector4f(result);
        return new GLSLParseText(s, resultVec, arg.vectorLength, "");
    }

    private GLSLParseText applyTwoArgFunc(BiFunction<Double, Double, Double> func, List<GLSLParseText> a) {
        GLSLParseText lhs = a.get(0);
        GLSLParseText rhs = a.get(1);
        Vector4f l = lhs.data;
        Vector4f r = rhs.data;
        int len = Math.max(lhs.vectorLength, rhs.vectorLength);
        if (len < 1)
            len = 1;
        float[] result = new float[NUM_4];
        for (int i = 0; i < len; i++) {
            int li = Math.min(i, Math.max(0, lhs.vectorLength - 1));
            int ri = Math.min(i, Math.max(0, rhs.vectorLength - 1));
            result[i] = func.apply((double) l.get(li), (double) r.get(ri)).floatValue();
        }
        Vector4f resultVec = new Vector4f(result);
        return new GLSLParseText(s, resultVec, len, "");
    }

    private GLSLParseText applyTwoArgFuncSum(BiFunction<Double, Double, Double> func, List<GLSLParseText> a) {
        GLSLParseText lhs = a.get(0);
        GLSLParseText rhs = a.get(1);
        Vector4f l = lhs.data;
        Vector4f r = rhs.data;
        int len = Math.max(lhs.vectorLength, rhs.vectorLength);
        float sum = NUM_0;
        for (int i = 0; i < len; i++) {
            int li = Math.min(i, Math.max(0, lhs.vectorLength - 1));
            int ri = Math.min(i, Math.max(0, rhs.vectorLength - 1));
            sum += func.apply((double) l.get(li), (double) r.get(ri)).floatValue();
        }
        float[] result = new float[NUM_4];
        result[0] = sum;
        Vector4f resultVec = new Vector4f(result);
        return new GLSLParseText(s, resultVec, 1, "");
    }

    private GLSLParseText applyThreeArgFunc(TriFunction<Double, Double, Double, Double> func, List<GLSLParseText> a) {
        GLSLParseText arg = a.get(0);
        Vector4f data = arg.data;
        GLSLParseText arg2 = a.get(1);
        Vector4f data2 = arg2.data;
        GLSLParseText arg3 = a.get(2);
        Vector4f data3 = arg3.data;
        float[] result = new float[NUM_4];
        for (int i = 0; i < arg.vectorLength; i++) {
            result[i] = func.apply((double) data.get(i), (double) data2.get(i), (double) data3.get(i)).floatValue();
        }
        Vector4f resultVec = new Vector4f(result);
        return new GLSLParseText(s, resultVec, arg.vectorLength, "");
    }

    private GLSLParseText mixFunc(List<GLSLParseText> a) {
        GLSLParseText x = a.get(0);
        GLSLParseText y = a.get(1);
        GLSLParseText t = a.get(2);
        Vector4f xv = x.data;
        Vector4f yv = y.data;
        Vector4f tv = t.data;
        int len = Math.max(x.vectorLength, y.vectorLength);
        if (len < 1)
            len = 1;
        float[] result = new float[NUM_4];
        for (int i = 0; i < len; i++) {
            int xi = Math.min(i, Math.max(0, x.vectorLength - 1));
            int yi = Math.min(i, Math.max(0, y.vectorLength - 1));

            int ti = Math.min(i, Math.max(0, t.vectorLength - 1));
            double tt = tv.get(t.vectorLength == 1 ? 0 : ti);
            result[i] = (float) (xv.get(xi) * (1.0 - tt) + yv.get(yi) * tt);
        }
        Vector4f resultVec = new Vector4f(result);
        return new GLSLParseText(s, resultVec, len, "");
    }

    private GLSLParseText distanceFunc(List<GLSLParseText> a) {
        GLSLParseText arg = a.get(0);
        Vector4f data = arg.data;
        GLSLParseText arg2 = a.get(1);
        Vector4f data2 = arg2.data;
        float result = 0.0f;
        for (int i = 0; i < arg.vectorLength; i++) {
            result += Math.pow(data.get(i) - data2.get(i), 2);
        }

        Vector4f resultVec = new Vector4f((float) Math.sqrt(result), NUM_0, NUM_0, NUM_0);
        return new GLSLParseText(s, resultVec, 1, "");
    }

    private void skipWs() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos)))
            pos++;
    }

    private boolean match(char c) {
        if (pos < s.length() && s.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!match(c))
            throw new RuntimeException("Expected '" + c + "' at " + pos);
    }

    private char peek() {
        return pos < s.length() ? s.charAt(pos) : '\0';
    }

    private boolean peekIs(char c) {
        return pos < s.length() && s.charAt(pos) == c;
    }

    private boolean isAlpha(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private boolean isAlphaNum(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    static String extractUniformName(String decl) {

        try {
            int semi = decl.indexOf(';');
            String s = semi >= 0 ? decl.substring(0, semi) : decl;
            String[] parts = s.split("\\s+");
            if (parts.length >= NUM_3) {
                String cand = parts[2];

                cand = cand.replaceAll("[;,]", "");
                return cand;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static ArrayList<GLSLParseText> parseVec(String expr, Map<String, GLSLParseText> env) {
        int l = expr.indexOf('(');
        int r = expr.lastIndexOf(')');
        if (l < 0 || r < 0 || r <= l + 1)
            return null;
        String inside = expr.substring(l + 1, r);
        ArrayList<String> args = splitTopLevelArgs(inside);
        ArrayList<String> expanded = new ArrayList<>();
        for (String a : args) {
            String t = a.trim();
            if (isPureSwizzle(t)) {
                int dot = t.indexOf('.');
                String base = t.substring(0, dot).trim();
                String sw = t.substring(dot + 1).trim();
                for (int i = 0; i < sw.length(); i++) {
                    expanded.add(base + STR_10 + sw.charAt(i));
                }
            } else {
                expanded.add(t);
            }
        }
        ArrayList<GLSLParseText> vals = new ArrayList<>();
        for (String p : expanded) {
            GLSLParseText v = evalSimple(p.trim(), env);
            if (v == null) {
                return null;
            }
            vals.add(v);
        }
        return vals;
    }

    private static ArrayList<String> splitTopLevelArgs(String s) {
        ArrayList<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            parts.add(cur.toString());
        }
        return parts;
    }

    private static boolean isPureSwizzle(String token) {
        token = token.trim();
        int dot = token.indexOf('.');
        if (dot <= 0)
            return false;
        String left = token.substring(0, dot).trim();
        String right = token.substring(dot + 1).trim();
        if (left.isEmpty() || right.isEmpty())
            return false;
        if (right.matches(".*[+\\-*/].*"))
            return false;
        if (!left.matches("[A-Za-z_][A-Za-z0-9_]*"))
            return false;
        return isValidSwizzle(right);
    }

    private static boolean isSwizzle(String s) {
        int dot = s.indexOf('.');
        if (dot <= 0 || dot == s.length() - 1)
            return false;
        String sw = s.substring(dot + 1);
        return isValidSwizzle(sw);
    }

    private static boolean isValidSwizzle(String sw) {
        if (sw == null || sw.isEmpty() || sw.length() > NUM_4)
            return false;
        for (int i = 0; i < sw.length(); i++) {
            char c = sw.charAt(i);
            if (!isSwizzleChar(c))
                return false;
        }
        return true;
    }

    private static boolean isSwizzleChar(char c) {
        switch (Character.toLowerCase(c)) {
            case 'x':
            case 'y':
            case 'z':
            case 'w':
            case 'r':
            case 'g':
            case 'b':
            case 'a':
                return true;
            default:
                return false;
        }
    }

    private static int componentSuffix(char c) {
        switch (Character.toLowerCase(c)) {
            case 'x':
            case 'r':
                return 0;
            case 'y':
            case 'g':
                return 1;
            case 'z':
            case 'b':
                return 2;
            case 'w':
            case 'a':
                return NUM_3;
            default:
                return NUM_4;
        }
    }

    private static ArrayList<GLSLParseText> resolveSwizzleVector(String base, String sw,
            Map<String, GLSLParseText> env) {
        if (!isValidSwizzle(sw))
            return null;
        ArrayList<GLSLParseText> list = new ArrayList<>();
        for (int i = 0; i < sw.length(); i++) {
            int component = componentSuffix(sw.charAt(i));
            GLSLParseText e = MISSING;
            if (env.get(base) != null) {
                e = new GLSLParseText(env.get(base).getData().get(component));
            }
            list.add(e);
        }
        return list;
    }

    private static GLSLParseText evalSimple(String token, Map<String, GLSLParseText> env) {
        try {
            return new GLSLExpressionParser(token, env).parse();
        } catch (Exception e) {
            return MISSING;
        }
    }

    /**
     * Heuristic check for "simple assignment" lines: must end with {@code ;},
     * contain a single {@code =} (not {@code ==}), and not start with comment,
     * uniform/in/out/void/precision/layout keywords.
     *
     * @param line raw source line
     * @return {@code true} if the line looks like an evaluable assignment
     */
    public static boolean isAssignmentLine(String line) {
        if (line == null) {
            return false;
        }
        String s = line.trim();
        if (s.isEmpty())
            return false;
        if (s.startsWith(STR))
            return false;
        if (s.startsWith(UNIFORM))
            return false;
        if (s.startsWith(OUT))
            return false;
        if (s.startsWith(IN))
            return false;
        if (s.startsWith(VOID))
            return false;
        if (s.startsWith(PRECISION))
            return false;
        if (s.startsWith("layout "))
            return false;

        int eq = s.indexOf('=');
        if (eq < 0)
            return false;
        if (eq + 1 < s.length() && s.charAt(eq + 1) == '=')
            return false;
        if (!s.endsWith(STR_2))
            return false;
        return true;
    }

    /**
     * Find the fragment-output variable: the first {@code out vec4 <name>}
     * declaration (with optional {@code layout(...)} prefix), else
     * {@code FragColor}
     * if it appears anywhere, else {@code "fragColor"}.
     *
     * @param lines GLSL source split into lines
     * @return detected output variable name
     */
    public static String detectOutName(String[] lines) {
        String outName = "fragColor";
        Pattern p = Pattern
                .compile("(?:layout\\s*\\([^)]*\\)\\s*)?out\\s+vec4\\s+([a-zA-Z_][a-zA-Z0-9_]*)");
        for (String l : lines) {
            String s = l.trim();
            Matcher m = p.matcher(s);
            if (m.find()) {
                return m.group(1);
            }
        }
        String src = String.join("\n", lines);
        if (src.contains(FRAGCOLOR))
            return FRAGCOLOR;
        return outName;
    }

    private static class IfHeader {
        String condition;
        boolean hasOpenBrace;

        IfHeader(String condition, boolean hasOpenBrace) {
            this.condition = condition;
            this.hasOpenBrace = hasOpenBrace;
        }
    }

    private static class IfHeaderPos {
        String condition;
        int closeIndex;

        IfHeaderPos(String condition, boolean hasOpenBrace, int closeIndex) {
            this.condition = condition;
            this.closeIndex = closeIndex;
        }
    }
}