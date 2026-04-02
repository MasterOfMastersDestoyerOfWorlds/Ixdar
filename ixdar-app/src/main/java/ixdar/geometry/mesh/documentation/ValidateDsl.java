package ixdar.geometry.mesh.documentation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;

import ixdar.annotations.meshnode.MeshNode;
import ixdar.geometry.mesh.graph.GraphValidator;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

/**
 * CLI entry point that validates a .dsl file against the mesh node registry.
 * Outputs JSON with parse errors, validation errors, and warnings.
 *
 * Usage: java ixdar.geometry.mesh.documentation.ValidateDsl <dsl-path>
 * Exit code 0 = valid, 1 = errors found, 2 = usage/IO error
 */
public final class ValidateDsl {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: ValidateDsl <dsl-path>");
            System.exit(2);
        }

        Path dslPath = Path.of(args[0]);
        if (!Files.exists(dslPath)) {
            System.err.println("File not found: " + dslPath);
            System.exit(2);
        }

        String source = Files.readString(dslPath);
        Map<String, Class<? extends MeshNode>> registry = NodeGraphRuntime.annotationRegistryClasses();

        List<PythonParser.ParsedNode> parsed;
        try {
            parsed = new PythonParser(new PythonLexer(source)).parseGraph();
        } catch (RuntimeException e) {
            Map<String, Object> result = Map.of(
                    "valid", false,
                    "nodeCount", 0,
                    "parseError", e.getMessage(),
                    "errors", List.of(),
                    "warnings", List.of());
            System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(result));
            System.exit(1);
            return;
        }

        List<String> errors = GraphValidator.validate(parsed, registry);
        List<String> warnings = GraphValidator.validateWithRandomValueWarnings(parsed, registry);

        boolean valid = errors.isEmpty();
        Map<String, Object> result = Map.of(
                "valid", valid,
                "nodeCount", parsed.size(),
                "errors", errors,
                "warnings", warnings);
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(result));
        System.exit(valid ? 0 : 1);
    }
}
