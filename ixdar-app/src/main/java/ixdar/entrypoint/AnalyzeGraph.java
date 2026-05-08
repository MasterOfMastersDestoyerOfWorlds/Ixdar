package ixdar.entrypoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;

import ixdar.annotations.meshnode.MeshNode;
import ixdar.geometry.mesh.graph.GraphAnalyzer;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

/**
 * CLI that runs DAG analysis on a .dsl file and outputs JSON.
 * Finds seam nodes (natural abstraction boundaries) for skill extraction.
 *
 * Usage: java -cp ... ixdar.entrypoint.AnalyzeGraph &lt;file.dsl&gt; [options]
 *
 * Options:
 *   --output &lt;path&gt;      Write JSON to file (default: stdout)
 *   --min-subgraph &lt;N&gt;  Minimum subgraph size for seams (default: 3)
 */
public class AnalyzeGraph {
    public static final String NODECOUNT = "nodeCount";
    public static final String EDGES = "edges";
    public static final String SEAMS = "seams";
    public static final String STR = ".";
    public static final int NUM_3 = 3;

    /**
     * TODO: document {@code main}.
     *
     * @param args TODO: describe
     * @throws IOException TODO: describe
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: AnalyzeGraph <file.dsl> [--output <path>] [--min-subgraph <N>]");
            System.exit(2);
        }

        String dslPath = args[0];
        String outputPath = null;
        int minSubgraph = NUM_3;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--output" -> outputPath = args[++i];
                case "--min-subgraph" -> minSubgraph = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    System.exit(2);
                }
            }
        }

        Path path = Path.of(dslPath);
        if (!Files.exists(path)) {
            System.err.println("File not found: " + dslPath);
            System.exit(2);
        }

        String source = Files.readString(path);
        Map<String, Class<? extends MeshNode>> registry = NodeGraphRuntime.annotationRegistryClasses();

        List<PythonParser.ParsedNode> parsed;
        Map<String, PythonParser.FunctionDef> funcDefs;
        try {
            PythonParser parser = new PythonParser(new PythonLexer(source));
            parsed = parser.parseGraph();
            funcDefs = parser.functionDefs();
        } catch (RuntimeException e) {
            Map<String, Object> result = Map.of(
                    "parseError", e.getMessage(),
                    NODECOUNT, 0,
                    EDGES, List.of(),
                    SEAMS, List.of());
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(result);
            writeOutput(json, outputPath);
            System.exit(1);
            return;
        }

        GraphAnalyzer.AnalysisResult analysis = GraphAnalyzer.analyze(parsed, registry, minSubgraph);

        // Build JSON output
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", dslPath);
        result.put(NODECOUNT, parsed.size());
        result.put("functionCount", funcDefs.size());
        result.put("edgeCount", analysis.edges.size());

        // Node list with adjacency info
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (PythonParser.ParsedNode n : parsed) {
            Map<String, Object> nodeJson = new LinkedHashMap<>();
            nodeJson.put("id", n.id);
            nodeJson.put("type", n.type);
            nodeJson.put("predecessors", analysis.predecessors.get(n.id));
            nodeJson.put("successors", analysis.successors.get(n.id));
            nodeJson.put("idom", analysis.idom.get(n.id));
            nodes.add(nodeJson);
        }
        result.put("nodes", nodes);

        // Edges
        List<Map<String, String>> edgesJson = new ArrayList<>();
        for (GraphAnalyzer.Edge e : analysis.edges) {
            Map<String, String> edgeJson = new LinkedHashMap<>();
            edgeJson.put("source", e.sourceId + STR + e.sourcePort);
            edgeJson.put("target", e.targetId + STR + e.targetPort);
            edgesJson.add(edgeJson);
        }
        result.put(EDGES, edgesJson);

        // Seams
        List<Map<String, Object>> seamsJson = new ArrayList<>();
        for (GraphAnalyzer.SeamNode seam : analysis.seams) {
            Map<String, Object> seamJson = new LinkedHashMap<>();
            seamJson.put("nodeId", seam.nodeId);
            seamJson.put("nodeType", seam.nodeType);
            seamJson.put("subgraphSize", seam.subgraphNodeIds.size());
            seamJson.put("subgraphNodeIds", seam.subgraphNodeIds);
            seamJson.put("outputPortTypes", seam.outputPortTypes);

            List<Map<String, String>> extInputs = new ArrayList<>();
            for (GraphAnalyzer.ExternalInput ext : seam.externalInputs) {
                Map<String, String> extJson = new LinkedHashMap<>();
                extJson.put("from", ext.sourceNodeId + STR + ext.sourcePort);
                extJson.put("to", ext.consumedByNodeId + STR + ext.consumedByPort);
                extInputs.add(extJson);
            }
            seamJson.put("externalInputs", extInputs);
            seamsJson.add(seamJson);
        }
        result.put(SEAMS, seamsJson);

        String json = new GsonBuilder().setPrettyPrinting().create().toJson(result);
        writeOutput(json, outputPath);
    }

    private static void writeOutput(String json, String outputPath) throws IOException {
        if (outputPath != null) {
            Path out = Path.of(outputPath);
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.writeString(out, json);
            System.err.println("[AnalyzeGraph] Written: " + outputPath);
        } else {
            System.out.println(json);
        }
    }
}
