package shell.ui.code;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.function.TriFunction;
import org.joml.Vector4f;

import shell.render.color.Color;
import shell.render.color.ColorRGB;
import shell.render.text.SpecialGlyphs;

public class ExpressionParser {

    private final String s;
    private int pos;
    private final Map<String, ParseText> env;

    public static final ParseText MISSING = new ParseText("?Missing?", Color.PINK, -1);

    ExpressionParser(String s, Map<String, ParseText> env) {
        this.s = s;
        this.env = env;
        this.pos = 0;
    }

    public static ParseText evaluateAndAssign(String line, Map<String, ParseText> env) {

        String s = line;
        int cidx = s.indexOf("//");
        if (cidx >= 0) {
            s = s.substring(0, cidx);
        }
        s = s.trim();
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return null;
        }

        // Handle single-line if/else of the form:
        // if (cond) <then> else <else>
        // Both <then> and <else> can be expressions or assignments (optionally wrapped
        // in braces)
        String sTrimLower = s.trim().toLowerCase();
        if (sTrimLower.startsWith("if")) {
            try {
                return evaluateIfElse(s.trim(), env);
            } catch (Exception ignore) {
                return null;
            }
        }

        String sl = s.toLowerCase();
        if (s.contains("==") || s.contains("?") || s.contains(":") || s.startsWith("#")
                || sl.startsWith("in ") || sl.startsWith("out ") || sl.startsWith("uniform ")
                || sl.startsWith("layout") || sl.startsWith("precision ") || sl.startsWith("void ")
                || sl.startsWith("struct ") || sl.startsWith("attribute ") || sl.startsWith("varying ")) {
            return null;
        }

        int eq = s.indexOf('=');
        if (eq > 0 && s.indexOf('=', eq + 1) == -1) {
            String left = s.substring(0, eq).trim();
            String right = s.substring(eq + 1).trim();
            String var = extractVarName(left);
            if (var != null && !var.isEmpty()) {

                // Support assignment from an inline if-expression: var = if (cond) expr else
                // expr
                if (right.toLowerCase().startsWith("if")) {
                    ParseText ifVal = evaluateIfElse(right, env);
                    if (ifVal != null && ifVal.data != null) {
                        env.put(var, ifVal);
                        return ifVal;
                    }
                }

                if (isSwizzle(right)) {
                    String base = right.substring(0, right.indexOf('.'));
                    String sw = right.substring(right.indexOf('.') + 1);
                    ArrayList<ParseText> comps = resolveSwizzleVector(base, sw, env);
                    if (comps != null && comps.size() > 0) {
                        ParseText.putVec(env, var, comps);
                        return env.get(var);
                    }
                }

                if (right.startsWith("vec") && right.contains("(") && right.endsWith(")")) {
                    ArrayList<ParseText> vec = parseVec(right, env);
                    if (vec != null && vec.size() > 0) {
                        ParseText.putVec(env, var, vec);
                        return env.get(var);
                    } else {
                        env.put(var, new ParseText(right, Color.BLUE_WHITE, -1));
                        return env.get(var);
                    }
                }
                try {
                    ParseText val = new ExpressionParser(right, env).parse();
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
                return new ExpressionParser(s, env).parse();
            } catch (Exception ex) {
                return null;
            }
        }
        return null;
    }

    // Evaluate a whole set of code lines with control flow (if/else with braces)
    // and write per-line suffixes into cachedSuffixes. This avoids evaluating
    // assignments inside non-taken branches.
    public static void evaluateAndAssign(List<String> lines, Map<String, ParseText> env,
            List<ParseText> cachedSuffixes) {
        if (lines == null || cachedSuffixes == null) {
            return;
        }
        // Execution stack: true means current block is executing. Root is true.
        ArrayList<Boolean> execStack = new ArrayList<>();
        execStack.add(Boolean.TRUE);
        // Track if-blocks to connect an 'else' with the most recent 'if'
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
            ParseText out = ParseText.BLANK;

            String s = original;
            int cidx = s.indexOf("//");
            if (cidx >= 0)
                s = s.substring(0, cidx);
            String decl = s.trim();

            // Handle closing brace(s): may be '}' alone or start of a line like '} else {'
            if (!decl.isEmpty() && decl.charAt(0) == '}') {
                // Pop one or more blocks for each leading '}' but never pop the root
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
                // Do NOT set suffixes or continue; allow the remainder (e.g. 'else {') to be
                // processed
            }

            // if (cond) { ... }
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
                ParseText boolVal = new ParseText(doExecThen ? "true" : "false", Color.GLSL_BOOLEAN);
                cachedSuffixes.set(i, commentStart(boolVal).join(new ParseText(" = ")).join(boolVal));
                continue;
            }

            // else if (cond) { ... }
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
                    awaitingElseExec = false; // else-if taken: no further else
                }
                ParseText boolVal = new ParseText(doExec ? "true" : "false", Color.GLSL_BOOLEAN);
                cachedSuffixes.set(i, commentStart(boolVal).join(new ParseText(" = ")).join(boolVal));
                continue;
            }

            // else { ... }
            if (isElseOpenBrace(decl)) {
                boolean parentExec = execStack.get(execStack.size() - 1);
                boolean doExec = awaitingElseExec && parentExec && (braceDepth == awaitingElseDepth);
                execStack.add(Boolean.valueOf(doExec));
                isIfStack.add(Boolean.FALSE);
                elseShouldExecStack.add(Boolean.FALSE);
                braceDepth++;
                awaitingElseExec = false; // consumed

                ParseText boolVal = new ParseText(doExec ? "true" : "false", Color.GLSL_BOOLEAN);
                cachedSuffixes.set(i, commentStart(boolVal).join(new ParseText(" = ")).join(boolVal));
                continue;
            }

            // else single-line: else <statement>
            if (startsWithElse(decl) && !decl.endsWith("{")) {
                boolean parentExec = execStack.get(execStack.size() - 1);
                boolean doExec = awaitingElseExec && parentExec && (braceDepth == awaitingElseDepth);
                String afterElse = decl.substring(decl.toLowerCase().indexOf("else") + 4).trim();
                if (!afterElse.isEmpty() && afterElse.startsWith("if")) {
                    // else if (...) single-line
                    IfHeaderPos posHdr = parseIfHeaderWithPos(afterElse);
                    boolean condVal = false;
                    if (doExec && posHdr != null) {
                        condVal = evaluateCondition(posHdr.condition, env);
                    }
                    boolean runThen = doExec && condVal;
                    if (runThen && posHdr != null) {
                        String thenStmt = afterElse.substring(posHdr.closeIndex + 1).trim();
                        if (!thenStmt.isEmpty() && !thenStmt.startsWith("{")) {
                            ParseText res = evaluateAndAssign(thenStmt, env);
                            if (res != null) {
                                out = commentStart(res).join(new ParseText(" = ")).join(res);
                            }
                        } else {
                            out = ParseText.BLANK;
                        }
                    } else {
                        out = ParseText.BLANK;
                    }
                    // Only consume else chain if the else-if branch is taken
                    if (runThen) {
                        awaitingElseExec = false;
                    }
                    // Add boolean suffix for else-if decision
                    ParseText boolVal = new ParseText(runThen ? "true" : "false", Color.GLSL_BOOLEAN);
                    if (!runThen) {
                        ParseText skip = new ParseText("SKIP", Color.GLSL_SKIP);
                        cachedSuffixes.set(i, commentStart(skip).join(new ParseText(" = ")).join(skip));
                    } else {
                        cachedSuffixes.set(i, commentStart(boolVal).join(new ParseText(" = ")).join(boolVal));
                    }
                    continue;
                } else {
                    if (doExec && !afterElse.isEmpty()) {
                        ParseText res = evaluateAndAssign(afterElse, env);
                        if (res != null) {
                            out = commentStart(res).join(new ParseText(" = ")).join(res);
                        }
                    } else if (!afterElse.isEmpty()) {
                        ParseText skip = new ParseText("SKIP", Color.GLSL_SKIP);
                        out = commentStart(skip).join(new ParseText(" = ")).join(skip);
                    } else {
                        out = ParseText.BLANK;
                    }
                    awaitingElseExec = false; // consumed
                }
                cachedSuffixes.set(i, out);
                continue;
            }

            // Normal line: evaluate only if current block is executing
            boolean executing = execStack.get(execStack.size() - 1);
            if (executing && !skipControlOnlyLine(decl)) {
                if (decl.startsWith("uniform") || decl.startsWith("in")) {
                    String name = extractUniformName(decl);
                    if (name != null) {
                        ParseText v = env.get(name);
                        if (v != null) {
                            out = commentStart(v).join(new ParseText(" = ")).join(v);
                        }
                    }
                } else {
                    ParseText res = evaluateAndAssign(decl, env);
                    if (res != null) {
                        out = commentStart(res).join(new ParseText(" = ")).join(res);
                    }
                }
            } else if (!executing && !skipControlOnlyLine(decl)) {
                ParseText skip = new ParseText("SKIP", Color.GLSL_SKIP);
                out = commentStart(skip).join(new ParseText(" = ")).join(skip);
            } else {
                out = ParseText.BLANK;
            }

            cachedSuffixes.set(i, out);
        }
    }

    /**
     * Compute, for each line, whether it would execute under the current env,
     * without mutating env or suffixes. Control-flow only (if/else/braces).
     */
    public static java.util.List<Boolean> wouldExecute(List<String> lines, Map<String, ParseText> envSnapshot) {
        java.util.ArrayList<Boolean> execFlags = new java.util.ArrayList<>();
        if (lines == null) {
            return execFlags;
        }
        java.util.ArrayList<Boolean> execStack = new java.util.ArrayList<>();
        execStack.add(Boolean.TRUE);
        java.util.ArrayList<Boolean> isIfStack = new java.util.ArrayList<>();
        isIfStack.add(Boolean.FALSE);
        java.util.ArrayList<Boolean> elseShouldExecStack = new java.util.ArrayList<>();
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
            if (lower.startsWith("if")) {
                String condStr = extractCondition(decl);
                boolean cond = evaluateCondition(condStr, envSnapshot);
                isIfStack.add(Boolean.TRUE);
                elseShouldExecStack.add(Boolean.valueOf(!cond));
                execStack.add(Boolean.valueOf(cond && doExec));
                execFlags.add(Boolean.valueOf(cond && doExec));
            } else if (lower.startsWith("else")) {
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
        int cidx = s.indexOf("//");
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
        return t.equals("else{") || t.equals("else {");
    }

    private static boolean startsWithElse(String decl) {
        String t = decl.toLowerCase();
        return t.startsWith("else");
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

    private static IfHeader parseIfHeader(String decl) {
        String t = decl.trim();
        if (!t.startsWith("if"))
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
        // normalize: remove leading 'else'
        String rest = decl.trim();
        int idx = rest.toLowerCase().indexOf("if");
        if (idx < 0)
            return null;
        String afterElse = rest.substring(idx);
        return parseIfHeader(afterElse);
    }

    private static IfHeaderPos parseIfHeaderWithPos(String decl) {
        String t = decl.trim();
        if (!t.startsWith("if"))
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
        if (t.equals("{") || t.equals("}"))
            return true;
        if (t.equalsIgnoreCase("else") || t.equalsIgnoreCase("else{") || t.equalsIgnoreCase("else {"))
            return true;
        // lines like: if (cond) {
        IfHeader h = parseIfHeader(t);
        if (h != null && h.hasOpenBrace)
            return true;
        return false;
    }

    private static ParseText commentStart(ParseText res) {
        if (res.vectorLength == 4) {
            return new ParseText(SpecialGlyphs.COLOR_TRACKER.getChar() + "",
                    new ColorRGB(res.data.x, res.data.y, res.data.z, res.data.w));
        } else {
            return new ParseText("//");
        }
    }

    private static ParseText evaluateIfElse(String s, Map<String, ParseText> env) {
        // Expect: if (cond) thenPart [else elsePart]
        if (s == null) {
            return null;
        }
        int i = 0;
        int n = s.length();
        // skip leading ws
        while (i < n && Character.isWhitespace(s.charAt(i)))
            i++;
        if (i + 1 >= n || s.charAt(i) != 'i' || s.charAt(i + 1) != 'f') {
            return null;
        }
        i += 2;
        // next non-ws must be '('
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
        // After ')', the then part starts
        int thenStart = condEnd + 1;
        // Find top-level 'else' after thenStart
        int elseIdx = findTopLevelElse(s, thenStart);
        String thenPart;
        String elsePart = null;
        if (elseIdx >= 0) {
            thenPart = s.substring(thenStart, elseIdx).trim();
            elsePart = s.substring(elseIdx + 4).trim();
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
        // Evaluate chosen branch: allow either assignment or bare expression
        ParseText res = evaluateAndAssign(chosen, env);
        if (res != null) {
            return res;
        }
        try {
            return new ExpressionParser(chosen, env).parse();
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean evaluateCondition(String cond, Map<String, ParseText> env) {
        if (cond == null) {
            return false;
        }
        // Find a top-level comparator outside parentheses
        int[] hit = findTopLevelComparator(cond);
        try {
            if (hit != null) {
                int idx = hit[0];
                int len = hit[1];
                String op = cond.substring(idx, idx + len);
                String left = cond.substring(0, idx).trim();
                String right = cond.substring(idx + len).trim();
                ParseText lv = new ExpressionParser(left, env).parse();
                ParseText rv = new ExpressionParser(right, env).parse();
                double l = lv.data.x;
                double r = rv.data.x;
                switch (op) {
                case "==":
                    return l == r;
                case "!=":
                    return l != r;
                case ">=":
                    return l >= r;
                case "<=":
                    return l <= r;
                case ">":
                    return l > r;
                case "<":
                    return l < r;
                default:
                    return false;
                }
            } else {
                // No comparator: non-zero is true
                ParseText v = new ExpressionParser(cond, env).parse();
                return Math.abs(v.data.x) > 1e-6;
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
        // Returns {index, length} or null
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
                if (two.equals("==") || two.equals("!=") || two.equals(">=") || two.equals("<=")) {
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
        for (int i = start; i <= s.length() - 4; i++) {
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
                // check "else" word at i
                if ((s.charAt(i) == 'e' || s.charAt(i) == 'E') && s.regionMatches(true, i, "else", 0, 4)) {
                    // Ensure word boundary before and after
                    boolean beforeOk = (i == 0) || !Character.isLetterOrDigit(s.charAt(i - 1));
                    int j = i + 4;
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
        if (t.startsWith("{") && t.endsWith("}")) {
            t = t.substring(1, t.length() - 1).trim();
        }
        if (t.endsWith(";")) {
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

    public static String extractAssignedVar(String line) {
        if (line == null) {
            return null;
        }
        String s = line;
        int cidx = s.indexOf("//");
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

    ParseText parse() {
        ParseText v = parseExpr();
        skipWs();
        return v;
    }

    private ParseText parseExpr() {
        ParseText v = parseTerm();
        while (true) {
            skipWs();
            if (match('+')) {
                ParseText r = parseTerm();
                List<ParseText> list = new ArrayList<>();
                list.add(v);
                list.add(r);
                v = applyTwoArgFunc((x, y) -> x + y, list);
            } else if (match('-')) {
                ParseText r = parseTerm();
                List<ParseText> list = new ArrayList<>();
                list.add(v);
                list.add(r);
                v = applyTwoArgFunc((x, y) -> x - y, list);
            } else {
                return v;
            }
        }
    }

    private ParseText parseTerm() {
        ParseText v = parseFactor();
        while (true) {
            skipWs();
            if (match('*')) {
                ParseText r = parseFactor();
                List<ParseText> list = new ArrayList<>();
                list.add(v);
                list.add(r);
                v = applyTwoArgFunc((x, y) -> x * y, list);
            } else if (match('/')) {
                ParseText r = parseFactor();
                List<ParseText> list = new ArrayList<>();
                list.add(v);
                list.add(r);
                v = applyTwoArgFunc((x, y) -> x / y, list);
            } else {
                return v;
            }
        }
    }

    private ParseText parseFactor() {
        skipWs();
        if (match('+')) {
            return parseFactor();
        }
        if (match('-')) {
            ParseText f = parseFactor();
            Vector4f out = new Vector4f(f.data).negate();
            return new ParseText("", Color.GLSL_FLOAT, out, f.vectorLength, "");
        }
        if (match('(')) {
            ParseText v = parseExpr();
            expect(')');
            return v;
        }
        if (isAlpha(peek())) {
            String ident = parseIdent();
            skipWs();
            if (match('(')) {
                List<ParseText> args = new ArrayList<>();
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
                        String name = ident + "." + sw;
                        return resolveVar(name);
                    }
                }
                return resolveVar(ident);
            }
        }
        return parseNumberOrParenExpr();
    }

    private ParseText parseNumberOrParenExpr() {
        skipWs();
        int start = pos;
        // Allow nested parenthesis and unary before numbers
        if (match('(')) {
            ParseText inner = parseExpr();
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
        return new ParseText(val);
    }

    private String parseIdent() {
        int start = pos;
        while (pos < s.length() && (isAlphaNum(s.charAt(pos)) || s.charAt(pos) == '_'))
            pos++;
        return s.substring(start, pos);
    }

    private ParseText resolveVar(String name) {
        if ("quarterPI".equalsIgnoreCase(name))
            return new ParseText("quarterPI", (float) Math.PI / 4f);
        if ("pi".equalsIgnoreCase(name))
            return new ParseText("pi", (float) Math.PI);
        if ("halfpi".equalsIgnoreCase(name))
            return new ParseText("halfpi", (float) Math.PI / 2f);
        if ("TAU".equalsIgnoreCase(name))
            return new ParseText("TAU", (float) (Math.PI * 2.0f));
        if ("e".equalsIgnoreCase(name))
            return new ParseText("e", (float) Math.E);

        int dotIdx = name.indexOf('.');
        if (dotIdx > 0 && dotIdx == name.lastIndexOf('.')) {
            String base = name.substring(0, dotIdx);
            String sw = name.substring(dotIdx + 1);
            int vectorLength = sw.length();
            float[] xyzw = new float[4];
            if (isValidSwizzle(sw)) {
                Vector4f org = env.get(base).getData();
                for (int i = 0; i < vectorLength; i++) {
                    int component = componentSuffix(sw.charAt(i));
                    xyzw[i] = org.get(component);
                }
                Vector4f vec = new Vector4f(xyzw);
                return new ParseText(name, vec, vectorLength);
            }
        }
        ParseText v = env.get(name);
        if (v == null) {
            throw new RuntimeException("Unknown variable: " + name);
        }
        return v;
    }

    private ParseText applyFunc(String name, List<ParseText> a) {
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
            return applyOneArgFunc((x) -> x - (x.intValue()) < 0.5 ? Math.floor(x) : Math.ceil(x), a);
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
            // GLSL float(x): cast to float; we just forward the value (use first component)
            ParseText arg = a.get(0);
            Vector4f v = arg.data;
            Vector4f res = new Vector4f(v.x, 0f, 0f, 0f);
            return new ParseText(s, res, 1, "");
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
                return t * t * (3.0 - 2.0 * t);
            }, a);

        }
        case "clamp": {
            return applyThreeArgFunc((x, lo, hi) -> Math.max(lo, Math.min(hi, x)), a);
        }
        case "vec2":
            return constructVecN(2, a);
        case "vec3":
            return constructVecN(3, a);
        case "vec4":
            return constructVecN(4, a);
        default:
            return ParseText.BLANK;
        }
    }

    private ParseText constructVecN(int n, List<ParseText> args) {
        float[] out = new float[4];
        int filled = 0;
        for (int i = 0; i < args.size() && filled < n; i++) {
            ParseText a = args.get(i);
            int len = Math.max(1, a.vectorLength);
            for (int k = 0; k < len && filled < n; k++) {
                out[filled++] = a.data.get(Math.min(k, len - 1));
            }
        }
        while (filled < n)
            out[filled++] = 0f;
        Vector4f result = new Vector4f(out);
        return new ParseText(s, result, n, "");
    }

    private ParseText applyOneArgFunc(Function<Double, Double> func, List<ParseText> a) {
        ParseText arg = a.get(0);
        Vector4f data = arg.data;
        float[] result = new float[4];
        for (int i = 0; i < arg.vectorLength; i++) {
            result[i] = func.apply((double) data.get(i)).floatValue();
        }
        Vector4f resultVec = new Vector4f(result);
        return new ParseText(s, resultVec, arg.vectorLength, "");
    }

    private ParseText applyTwoArgFunc(BiFunction<Double, Double, Double> func, List<ParseText> a) {
        ParseText lhs = a.get(0);
        ParseText rhs = a.get(1);
        Vector4f l = lhs.data;
        Vector4f r = rhs.data;
        int len = Math.max(lhs.vectorLength, rhs.vectorLength);
        if (len < 1)
            len = 1;
        float[] result = new float[4];
        for (int i = 0; i < len; i++) {
            int li = Math.min(i, Math.max(0, lhs.vectorLength - 1));
            int ri = Math.min(i, Math.max(0, rhs.vectorLength - 1));
            result[i] = func.apply((double) l.get(li), (double) r.get(ri)).floatValue();
        }
        Vector4f resultVec = new Vector4f(result);
        return new ParseText(s, resultVec, len, "");
    }

    private ParseText applyTwoArgFuncSum(BiFunction<Double, Double, Double> func, List<ParseText> a) {
        ParseText lhs = a.get(0);
        ParseText rhs = a.get(1);
        Vector4f l = lhs.data;
        Vector4f r = rhs.data;
        int len = Math.max(lhs.vectorLength, rhs.vectorLength);
        float sum = 0f;
        for (int i = 0; i < len; i++) {
            int li = Math.min(i, Math.max(0, lhs.vectorLength - 1));
            int ri = Math.min(i, Math.max(0, rhs.vectorLength - 1));
            sum += func.apply((double) l.get(li), (double) r.get(ri)).floatValue();
        }
        float[] result = new float[4];
        result[0] = sum;
        Vector4f resultVec = new Vector4f(result);
        return new ParseText(s, resultVec, 1, "");
    }

    private ParseText applyThreeArgFunc(TriFunction<Double, Double, Double, Double> func, List<ParseText> a) {
        ParseText arg = a.get(0);
        Vector4f data = arg.data;
        ParseText arg2 = a.get(1);
        Vector4f data2 = arg2.data;
        ParseText arg3 = a.get(2);
        Vector4f data3 = arg3.data;
        float[] result = new float[4];
        for (int i = 0; i < arg.vectorLength; i++) {
            result[i] = func.apply((double) data.get(i), (double) data2.get(i), (double) data3.get(i)).floatValue();
        }
        Vector4f resultVec = new Vector4f(result);
        return new ParseText(s, resultVec, arg.vectorLength, "");
    }

    private ParseText mixFunc(List<ParseText> a) {
        ParseText x = a.get(0);
        ParseText y = a.get(1);
        ParseText t = a.get(2);
        Vector4f xv = x.data;
        Vector4f yv = y.data;
        Vector4f tv = t.data;
        int len = Math.max(x.vectorLength, y.vectorLength);
        if (len < 1)
            len = 1;
        float[] result = new float[4];
        for (int i = 0; i < len; i++) {
            int xi = Math.min(i, Math.max(0, x.vectorLength - 1));
            int yi = Math.min(i, Math.max(0, y.vectorLength - 1));
            // GLSL mix allows scalar or vector t; support both
            int ti = Math.min(i, Math.max(0, t.vectorLength - 1));
            double tt = tv.get(t.vectorLength == 1 ? 0 : ti);
            result[i] = (float) (xv.get(xi) * (1.0 - tt) + yv.get(yi) * tt);
        }
        Vector4f resultVec = new Vector4f(result);
        return new ParseText(s, resultVec, len, "");
    }

    private ParseText distanceFunc(List<ParseText> a) {
        ParseText arg = a.get(0);
        Vector4f data = arg.data;
        ParseText arg2 = a.get(1);
        Vector4f data2 = arg2.data;
        float result = 0.0f;
        for (int i = 0; i < arg.vectorLength; i++) {
            result += Math.pow(data.get(i) - data2.get(i), 2);
        }

        Vector4f resultVec = new Vector4f((float) Math.sqrt(result), 0f, 0f, 0f);
        return new ParseText(s, resultVec, 1, "");
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
            if (parts.length >= 3) {
                String cand = parts[2];

                cand = cand.replaceAll("[;,]", "");
                return cand;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static ArrayList<ParseText> parseVec(String expr, Map<String, ParseText> env) {
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
                    expanded.add(base + "." + sw.charAt(i));
                }
            } else {
                expanded.add(t);
            }
        }
        ArrayList<ParseText> vals = new ArrayList<>();
        for (String p : expanded) {
            ParseText v = evalSimple(p.trim(), env);
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
        if (sw == null || sw.isEmpty() || sw.length() > 4)
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
            return 3;
        default:
            return 4;
        }
    }

    private static ArrayList<ParseText> resolveSwizzleVector(String base, String sw,
            Map<String, ParseText> env) {
        if (!isValidSwizzle(sw))
            return null;
        ArrayList<ParseText> list = new ArrayList<>();
        for (int i = 0; i < sw.length(); i++) {
            int component = componentSuffix(sw.charAt(i));
            ParseText e = MISSING;
            if (env.get(base) != null) {
                e = new ParseText(env.get(base).getData().get(component));
            }
            list.add(e);
        }
        return list;
    }

    private static ParseText evalSimple(String token, Map<String, ParseText> env) {
        try {
            return new ExpressionParser(token, env).parse();
        } catch (Exception e) {
            return MISSING;
        }
    }

    public static boolean isAssignmentLine(String line) {
        if (line == null) {
            return false;
        }
        String s = line.trim();
        if (s.isEmpty())
            return false;
        if (s.startsWith("//"))
            return false;
        if (s.startsWith("uniform "))
            return false;
        if (s.startsWith("out "))
            return false;
        if (s.startsWith("in "))
            return false;
        if (s.startsWith("void "))
            return false;
        if (s.startsWith("precision "))
            return false;
        if (s.startsWith("layout "))
            return false;
        // Simple assignment detect: has '=' and ends with ';', avoid '==' '>=', '<='
        // etc.
        int eq = s.indexOf('=');
        if (eq < 0)
            return false;
        if (eq + 1 < s.length() && s.charAt(eq + 1) == '=')
            return false;
        if (!s.endsWith(";"))
            return false;
        return true;
    }

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
        if (src.contains("FragColor"))
            return "FragColor";
        return outName;
    }
}