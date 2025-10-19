package shell.ui.code;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import shell.render.sdf.ShaderDrawable;
import shell.render.shaders.ShaderProgram;

/**
 * Handles injection of debug preview assignments into shader source code for
 * interactive debugging.
 */
public class ShaderBranchInjector {

    private ShaderProgram targetShader;
    String originalFragmentSource;
    private ShaderDrawable uniformProvider;

    public ShaderBranchInjector(ShaderDrawable uniformProvider, String originalFragmentSource,
            ShaderProgram targetShader) {
        this.uniformProvider = uniformProvider;
        this.originalFragmentSource = originalFragmentSource;
        this.targetShader = targetShader;
    }

    public void injectAndReload(int lineIndex) {
        if (originalFragmentSource == null || originalFragmentSource.isEmpty()) {
            return;
        }
        String[] lines = originalFragmentSource.split("\n", -1);
        if (lineIndex < 0 || lineIndex >= lines.length) {
            return;
        }

        if (!ExpressionParser.isAssignmentLine(lines[lineIndex])) {
            return;
        }

        String outName = ExpressionParser.detectOutName(lines);

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
            expr = "vec4(float(" + var + "), float(" + var + "), float(" + var + "), 1.0)";
            break;
        case "float":
            expr = "vec4(" + var + ", " + var + ", " + var + ", 1.0)";
            break;
        case "vec2":
            expr = "vec4(" + var + ".x, " + var + ".y, 0.0, 1.0)";
            break;
        case "vec3":
            expr = "vec4(" + var + ", 1.0)";
            break;
        case "vec4":
            expr = var;
            break;
        default:
            expr = "vec4(0.0, 0.0, 0.0, 1.0)";
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
            if (t.startsWith("if")) {
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

                    if (k + 4 <= s.length() && s.substring(k, k + 4).equals("else")) {


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
                int posElse = hdr.toLowerCase().indexOf("else");
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
                        int posIf = s.toLowerCase().indexOf("if");
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

                    if (!seenElseOpen && t.contains("else")) {

                        int posElse = s.toLowerCase().indexOf("else");
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

            java.util.List<String> edited = new java.util.ArrayList<>();

            for (int i = 0; i < ifHeader; i++) {
                String line = lines[i];
                if (line.contains(outName + " =") && !line.trim().startsWith("//") && !line.trim().startsWith("out ")) {
                    edited.add(indent + outName + " = vec4(0.0, 0.0, 0.0, 1.0);");
                } else {
                    edited.add(line);
                }
            }

            edited.add(lines[ifHeader]);
            int thenBodyEnd = (elseHeader > 0 ? elseHeader : thenCloseBrace);
            for (int i = ifHeader + 1; i < thenBodyEnd && i < lines.length; i++) {
                edited.add(lines[i]);
            }

            edited.add(inThen ? (indent + outName + " = " + expr + ";")
                    : (indent + outName + " = vec4(0.0, 0.0, 0.0, 1.0);"));

            edited.add("}");

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

            edited.add(inThen ? (indent + outName + " = vec4(0.0, 0.0, 0.0, 1.0);")
                    : (indent + outName + " = " + expr + ";"));

            edited.add("}");

            String elseLineContent = lines[elseHeader].trim();
            boolean hasElseIf = elseLineContent.contains("else") && elseLineContent.contains("if");
            if (hasElseIf) {

                edited.add(indent + "else {");
                edited.add(indent + "    " + outName + " = vec4(0.0, 0.0, 0.0, 1.0);");
                edited.add(indent + "}");
            }

            edited.add("}");
            String newSrc = String.join("\n", edited);

            targetShader.reloadWithFragmentSource(newSrc);
            return;
        }

        simpleTruncate(lines, lineIndex, mainStart, mainEnd, outName, expr, indent);
    }

    private void simpleTruncate(String[] lines, int lineIndex, int mainStart, int mainEnd, String outName, String expr,
            String indent) {
        java.util.List<Boolean> execFlags = ExpressionParser.wouldExecute(java.util.Arrays.asList(lines),
                uniformProvider.getUniformMap());
        java.util.List<String> newLines = new java.util.ArrayList<>();
        for (int i = 0; i <= lineIndex; i++)
            newLines.add(lines[i]);
        boolean clickedAssignsOut = lines[lineIndex].contains(outName + " =");
        boolean wouldExec = (lineIndex >= 0 && lineIndex < execFlags.size()) ? execFlags.get(lineIndex).booleanValue()
                : true;
        if (!clickedAssignsOut) {
            String outExpr = wouldExec ? expr : "vec4(0.0, 0.0, 0.0, 1.0)";
            newLines.add(indent + outName + " = " + outExpr + ";");
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
            newLines.add("}");
        }
        if (mainEnd >= 0) {
            for (int i = mainEnd + 1; i < lines.length; i++)
                newLines.add(lines[i]);
        }
        String newSrc = String.join("\n", newLines);

        targetShader.reloadWithFragmentSource(newSrc);
    }

}
