package ixdar.gui.ui.code;
import java.util.regex.Matcher;
import java.util.List;

import java.util.Arrays;

import java.util.ArrayList;
import java.util.regex.Pattern;

import ixdar.graphics.render.sdf.ShaderDrawable;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.parsing.glsl.GLSLExpressionParser;

/**
 * Handles injection of debug preview assignments into shader source code for
 * interactive debugging.
 */
public class ShaderBranchInjector {
    public static final String N = "\n";
    public static final String FLOAT = "), float(";
    public static final String VEC4 = "vec4(";
    public static final String STR = ", ";
    public static final String STR_1_0 = ", 1.0)";
    public static final String VEC4_0_0_0_0_0_0_1_0 = "vec4(0.0, 0.0, 0.0, 1.0)";
    public static final String IF = "if";
    public static final String ELSE = "else";
    public static final String STR_2 = " =";
    public static final String VEC4_0_0_0_0_0_0_1_0_2 = " = vec4(0.0, 0.0, 0.0, 1.0);";
    public static final String STR_3 = " = ";
    public static final String STR_4 = ";";
    public static final String STR_5 = "}";
    public static final int NUM_4 = 4;
    String originalFragmentSource;

    private ShaderProgram targetShader;
    private ShaderDrawable uniformProvider;

    /**
     * Capture the unmodified fragment source plus the shader and uniform provider that
     * subsequent {@link #injectAndReload(int)} calls will edit and recompile.
     *
     * @param uniformProvider supplier of runtime uniform values used during evaluation
     * @param originalFragmentSource pristine fragment shader text to use as the injection base
     * @param targetShader shader program whose fragment source will be replaced on injection
     */
    public ShaderBranchInjector(ShaderDrawable uniformProvider, String originalFragmentSource,
            ShaderProgram targetShader) {
        this.uniformProvider = uniformProvider;
        this.originalFragmentSource = originalFragmentSource;
        this.targetShader = targetShader;
    }

    /**
     * Rewrite {@link #originalFragmentSource} so the fragment output is replaced with a
     * preview expression derived from the variable assigned at {@code lineIndex}, then
     * push the rewritten source back into {@link #targetShader}. Bails out silently when
     * the line is not an assignment or {@code main} cannot be located. When the clicked
     * line lives inside an {@code if/else} pair, both branches are emitted so the active
     * branch shows the preview while the other clears to opaque black; otherwise the
     * function is truncated after the clicked line and a single preview assignment is
     * appended via {@link #simpleTruncate}.
     *
     * @param lineIndex zero-based index into the split source lines that the user clicked
     */
    public void injectAndReload(int lineIndex) {
        if (originalFragmentSource == null || originalFragmentSource.isEmpty()) {
            return;
        }
        String[] lines = originalFragmentSource.split(N, -1);
        if (lineIndex < 0 || lineIndex >= lines.length) {
            return;
        }

        if (!GLSLExpressionParser.isAssignmentLine(lines[lineIndex])) {
            return;
        }

        String outName = GLSLExpressionParser.detectOutName(lines);

        String clicked = lines[lineIndex];
        String indent = clicked.replaceAll("^(\\s*).*$", "$1");
        String type = null;
        String var = null;
        String decl = clicked.trim();
        Pattern pDecl = Pattern
                .compile(
                        "^(?:[a-zA-Z_][a-zA-Z0-9_]*\s+)*((?:float|int|vec[234]))\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*=.*;");
        Matcher m = pDecl.matcher(decl);
        if (m.find()) {
            type = m.group(1);
            var = m.group(2);
        } else {
            Pattern pAssign = Pattern
                    .compile("^([a-zA-Z_][a-zA-Z0-9_]*)\s*=.*;");
            Matcher m2 = pAssign.matcher(decl);
            if (m2.find()) {
                var = m2.group(1);

                for (int i = lineIndex; i >= 0; i--) {
                    String t = lines[i].trim();
                    Matcher m3 = pDecl.matcher(t);
                    if (m3.find() && m3.group(2).equals(var)) {
                        type = m3.group(1);
                        break;
                    }
                    Matcher m4 = Pattern
                            .compile("^(?:[a-zA-Z_][a-zA-Z0-9_]*\s+)*((?:float|int|vec[234]))\s+" + var + "[\s=;].*")
                            .matcher(t);
                    if (m4.find()) {
                        type = m4.group(1);
                        break;
                    }
                }
            }
        }
        if (var == null) {

            var = "";
        }
        if (type == null) {

        }
        String expr;
        switch (type) {
        case "int":
            expr = "vec4(float(" + var + FLOAT + var + FLOAT + var + "), 1.0)";
            break;
        case "float":
            expr = VEC4 + var + STR + var + STR + var + STR_1_0;
            break;
        case "vec2":
            expr = VEC4 + var + ".x, " + var + ".y, 0.0, 1.0)";
            break;
        case "vec3":
            expr = VEC4 + var + STR_1_0;
            break;
        case "vec4":
            expr = var;
            break;
        default:
            expr = VEC4_0_0_0_0_0_0_1_0;
        }

        int mainStart = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("void main")) {
                mainStart = i;
                break;
            }
        }
        if (mainStart < 0 || lineIndex < mainStart) {
            return;
        }
        int depthMainScan = 0;
        int mainEnd = -1;
        for (int i = mainStart; i < lines.length; i++) {
            String s = lines[i];
            for (int k = 0; k < s.length(); k++) {
                char ch = s.charAt(k);
                if (ch == '{')
                    depthMainScan++;
                if (ch == '}') {
                    depthMainScan--;
                    if (depthMainScan == 0) {
                        mainEnd = i;
                        break;
                    }
                }
            }
            if (mainEnd >= 0)
                break;
        }
        if (mainEnd < 0) {
            return;
        }

        int ifHeader = -1;
        for (int i = lineIndex; i >= mainStart; i--) {
            String t = lines[i].trim();
            if (t.startsWith(IF)) {
                ifHeader = i;
                break;
            }
        }

        int elseHeader = -1;
        if (ifHeader >= 0) {
            int baseDepth = 0;
            for (int i = mainStart; i < ifHeader; i++) {
                for (int k = 0; k < lines[i].length(); k++) {
                    char ch = lines[i].charAt(k);
                    if (ch == '{')
                        baseDepth++;
                    if (ch == '}')
                        baseDepth--;
                }
            }
            int depthScan = baseDepth;

            for (int i = ifHeader + 1; i <= mainEnd; i++) {
                String s = lines[i];

                int localDepth = depthScan;
                boolean foundElse = false;
                for (int k = 0; k < s.length(); k++) {
                    char ch = s.charAt(k);
                    if (ch == '{')
                        localDepth++;
                    else if (ch == '}')
                        localDepth--;

                    if (k + NUM_4 <= s.length() && s.substring(k, k + NUM_4).equals(ELSE)) {

                        if (localDepth == baseDepth - 1) {
                            elseHeader = i;
                            foundElse = true;
                            break;
                        }
                    }
                }
                if (foundElse)
                    break;
                depthScan = localDepth;
            }
        }

        if (ifHeader >= 0 && elseHeader >= 0) {

            int thenClose = -1;
            {
                int depthThen = 0;
                for (int k = 0; k < lines[ifHeader].length(); k++) {
                    char ch = lines[ifHeader].charAt(k);
                    if (ch == '{')
                        depthThen++;
                    if (ch == '}')
                        depthThen--;
                }
                for (int i = ifHeader + 1; i <= mainEnd; i++) {
                    if (depthThen == 0) {
                        thenClose = i;
                        break;
                    }
                    String s = lines[i];
                    for (int k = 0; k < s.length(); k++) {
                        char ch = s.charAt(k);
                        if (ch == '{')
                            depthThen++;
                        if (ch == '}')
                            depthThen--;
                    }
                }
                if (thenClose < 0)
                    thenClose = elseHeader;
            }

            int elseClose = -1;
            {
                int depthElse = 0;
                String hdr = lines[elseHeader];
                int posElse = hdr.toLowerCase().indexOf(ELSE);
                if (posElse < 0)

                    for (int k = posElse; k < hdr.length(); k++) {
                        char ch = hdr.charAt(k);
                        if (ch == '{')
                            depthElse++;
                        if (ch == '}')
                            depthElse--;
                    }
                for (int i = elseHeader + 1; i <= mainEnd; i++) {
                    String s = lines[i];
                    for (int k = 0; k < s.length(); k++) {
                        char ch = s.charAt(k);
                        if (ch == '{')
                            depthElse++;
                        if (ch == '}')
                            depthElse--;
                    }
                    if (depthElse == 0) {
                        elseClose = i;
                        break;
                    }
                }
                if (elseClose < 0)
                    elseClose = mainEnd;
            }

            {
                int ifElseBlockEndQuick = Math.max(thenClose, elseClose);
                if (lineIndex < ifHeader || lineIndex >= ifElseBlockEndQuick) {
                    simpleTruncate(lines, lineIndex, mainStart, mainEnd, outName, expr, indent);
                    return;
                }
            }

            int thenCloseBrace = -1;
            int elseCloseBrace = -1;
            int baseDepth = 0;
            for (int i = mainStart; i < ifHeader; i++) {
                String s = lines[i];
                for (int k = 0; k < s.length(); k++) {
                    char ch = s.charAt(k);
                    if (ch == '{')
                        baseDepth++;
                    if (ch == '}')
                        baseDepth--;
                }
            }

            int ifElseBlockEnd = -1;
            {
                int depth = baseDepth;
                boolean seenThenOpen = false;
                boolean seenElseOpen = false;
                for (int i = ifHeader; i <= mainEnd; i++) {
                    String s = lines[i];
                    String t = s.trim().toLowerCase();

                    if (!seenThenOpen) {
                        int posIf = s.toLowerCase().indexOf(IF);
                        if (posIf >= 0) {
                            for (int k = posIf; k < s.length(); k++) {
                                char ch = s.charAt(k);
                                if (ch == '{') {
                                    seenThenOpen = true;
                                    depth++;
                                    break;
                                }
                            }
                            if (!seenThenOpen)
                                continue;
                        }
                    }
                    if (i > ifHeader) {
                        for (int k = 0; k < s.length(); k++) {
                            char ch = s.charAt(k);
                            if (ch == '{')
                                depth++;
                            if (ch == '}')
                                depth--;
                        }
                    }

                    if (seenThenOpen && thenCloseBrace < 0 && depth == baseDepth) {
                        thenCloseBrace = i;
                    }

                    if (!seenElseOpen && t.contains(ELSE)) {

                        int posElse = s.toLowerCase().indexOf(ELSE);
                        for (int k = posElse; k < s.length(); k++) {
                            char ch = s.charAt(k);
                            if (ch == '{') {
                                seenElseOpen = true;
                                break;
                            }
                        }
                        if (!seenElseOpen)
                            continue;
                    }

                    if (seenElseOpen && elseCloseBrace < 0 && depth == baseDepth) {
                        elseCloseBrace = i;

                        ifElseBlockEnd = i + 1;
                        break;
                    }
                }
                if (ifElseBlockEnd < 0)
                    ifElseBlockEnd = Math.max(thenClose, elseClose);
                if (thenCloseBrace < 0)
                    thenCloseBrace = Math.min(elseHeader, ifElseBlockEnd - 1);
            }

            boolean inThen = (lineIndex >= ifHeader) && (elseHeader < 0 || lineIndex < elseHeader);

            List<String> edited = new ArrayList<>();

            for (int i = 0; i < ifHeader; i++) {
                String line = lines[i];
                if (line.contains(outName + STR_2) && !line.trim().startsWith("//") && !line.trim().startsWith("out ")) {
                    edited.add(indent + outName + VEC4_0_0_0_0_0_0_1_0_2);
                } else {
                    edited.add(line);
                }
            }

            edited.add(lines[ifHeader]);
            int thenBodyEnd = (elseHeader > 0 ? elseHeader : thenCloseBrace);
            for (int i = ifHeader + 1; i < thenBodyEnd && i < lines.length; i++) {
                edited.add(lines[i]);
            }

            edited.add(inThen ? (indent + outName + STR_3 + expr + STR_4)
                    : (indent + outName + VEC4_0_0_0_0_0_0_1_0_2));

            edited.add(STR_5);

            if (elseHeader >= 0) {
                String hdr = lines[elseHeader];
                int braceIdx = hdr.indexOf('}');
                String afterBrace = braceIdx >= 0 ? hdr.substring(braceIdx + 1).trim() : hdr.trim();
                if (!afterBrace.isEmpty()) {
                    edited.add(afterBrace);
                }
            }

            for (int i = elseHeader + 1; i < elseCloseBrace && i < lines.length; i++) {
                edited.add(lines[i]);
            }

            edited.add(inThen ? (indent + outName + VEC4_0_0_0_0_0_0_1_0_2)
                    : (indent + outName + STR_3 + expr + STR_4));

            edited.add(STR_5);

            String elseLineContent = lines[elseHeader].trim();
            boolean hasElseIf = elseLineContent.contains(ELSE) && elseLineContent.contains(IF);
            if (hasElseIf) {

                edited.add(indent + "else {");
                edited.add(indent + "    " + outName + VEC4_0_0_0_0_0_0_1_0_2);
                edited.add(indent + STR_5);
            }

            edited.add(STR_5);
            String newSrc = String.join(N, edited);

            targetShader.reloadWithFragmentSource(newSrc);
            return;
        }

        simpleTruncate(lines, lineIndex, mainStart, mainEnd, outName, expr, indent);
    }

    private void simpleTruncate(String[] lines, int lineIndex, int mainStart, int mainEnd, String outName, String expr,
            String indent) {
        List<Boolean> execFlags = GLSLExpressionParser.wouldExecute(Arrays.asList(lines),
                uniformProvider.getUniformMap());
        List<String> newLines = new ArrayList<>();
        for (int i = 0; i <= lineIndex; i++)
            newLines.add(lines[i]);
        boolean clickedAssignsOut = lines[lineIndex].contains(outName + STR_2);
        boolean wouldExec = (lineIndex >= 0 && lineIndex < execFlags.size()) ? execFlags.get(lineIndex).booleanValue()
                : true;
        if (!clickedAssignsOut) {
            String outExpr = wouldExec ? expr : VEC4_0_0_0_0_0_0_1_0;
            newLines.add(indent + outName + STR_3 + outExpr + STR_4);
        }
        int openDepthAtClicked = 0;
        {
            int d = 0;
            for (int i = mainStart; i <= lineIndex; i++) {
                String s = lines[i];
                for (int k = 0; k < s.length(); k++) {
                    char ch = s.charAt(k);
                    if (ch == '{')
                        d++;
                    if (ch == '}')
                        d--;
                }
            }
            openDepthAtClicked = Math.max(0, d);
        }
        for (int i = 0; i < openDepthAtClicked; i++) {
            newLines.add(STR_5);
        }
        if (mainEnd >= 0) {
            for (int i = mainEnd + 1; i < lines.length; i++)
                newLines.add(lines[i]);
        }
        String newSrc = String.join(N, newLines);

        targetShader.reloadWithFragmentSource(newSrc);
    }

}
