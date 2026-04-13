package ixdar.cli;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.graph.GraphAnalyzer;
import ixdar.geometry.mesh.graph.GraphAnalyzer.SeamAnalysis;
import ixdar.geometry.mesh.graph.GraphAnalyzer.SeamNode;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

/**
 * AnalyzeGraph CLI - Analyzes DSL graphs for seam nodes.
 * 
 * Usage: java ... ixdar.cli.AnalyzeGraph --input script.dsl --output analysis.json
 */
public class AnalyzeGraph {

    public static void main(String[] args) {
        String inputPath = null;
        String outputPath = null;

        // Parse arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--input" -> inputPath = args[++i];
                case "--output" -> outputPath = args[++i];
                default -> {
                    System.err.println("Usage: java ... ixdar.cli.AnalyzeGraph --input <file.dsl> --output <file.json>");
                    System.exit(1);
                }
            }
        }

        if (inputPath == null || outputPath == null) {
            System.err.println("Usage: java ... ixdar.cli.AnalyzeGraph --input <file.dsl> --output <file.json>");
            System.exit(1);
        }

        try {
            // Read DSL file
            String dslCode = Files.readString(Paths.get(inputPath));
            System.out.println("[AnalyzeGraph] Loaded DSL: " + inputPath + " (" + dslCode.length() + " chars)");

            // Parse DSL
            PythonLexer lexer = new PythonLexer(dslCode);
            PythonParser parser = new PythonParser(lexer);
            List<PythonParser.ParsedNode> ast = parser.parseGraph();
            System.out.println("[AnalyzeGraph] Parsed " + ast.size() + " nodes");

            // Analyze for seams
            SeamAnalysis analysis = GraphAnalyzer.analyze(ast);
            System.out.println("[AnalyzeGraph] Found " + analysis.seams.size() + " seam nodes");

            // Output JSON
            String json = toJson(analysis);
            Files.writeString(Paths.get(outputPath), json);
            System.out.println("[AnalyzeGraph] Wrote analysis to: " + outputPath);

        } catch (Exception e) {
            System.err.println("[AnalyzeGraph] ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Convert SeamAnalysis to JSON string.
     */
    private static String toJson(SeamAnalysis analysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"seams\": [\n");
        
        for (int i = 0; i < analysis.seams.size(); i++) {
            SeamNode seam = analysis.seams.get(i);
            sb.append("    {\n");
            sb.append("      \"nodeId\": \"").append(escapeJson(seam.nodeId)).append("\",\n");
            sb.append("      \"nodeType\": \"").append(escapeJson(seam.nodeType)).append("\",\n");
            sb.append("      \"dominatedNodeIds\": [");
            for (int j = 0; j < seam.dominatedNodeIds.size(); j++) {
                if (j > 0) sb.append(", ");
                sb.append("\"").append(escapeJson(seam.dominatedNodeIds.get(j))).append("\"");
            }
            sb.append("],\n");
            sb.append("      \"boundaryPorts\": {");
            boolean first = true;
            for (Map.Entry<String, ?> entry : seam.boundaryPorts.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append("\"").append(escapeJson(entry.getKey())).append("\": \"").append(entry.getValue()).append("\"");
            }
            sb.append("},\n");
            sb.append("      \"dslText\": \"").append(escapeDslText(seam.dslText)).append("\"\n");
            sb.append("    }");
            if (i < analysis.seams.size() - 1) sb.append(",");
            sb.append("\n");
        }
        
        sb.append("  ]\n");
        sb.append("}\n");
        
        return sb.toString();
    }

    /**
     * Escape string for JSON.
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Escape DSL text for JSON (preserve newlines).
     */
    private static String escapeDslText(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
