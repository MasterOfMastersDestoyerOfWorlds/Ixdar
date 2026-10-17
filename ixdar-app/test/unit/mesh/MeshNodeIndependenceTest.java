package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.graph.NodeGroup;
import ixdar.geometry.mesh.nodes.api.MeshNode;

/**
 * No registered {@link MeshNode} builds or evaluates another one except through the group
 * builder: a {@link NodeGroup} hands constructors to {@code group.node(...)}, and the runtime
 * evaluates them. Scans every registered node's source.
 */
class MeshNodeIndependenceTest {

    private static final Path MODULE_SOURCE_ROOT = Path.of("src", "main", "java");

    private static final List<String> NODE_EVALUATION_TYPES = List.of("MapNodeContext",
            "GraphNodeContext", "NodeGraphRuntime", "supplierFor");

    // A constructor reference handed straight to the builder: node("id", Other::new).
    private static final String BUILDER_NODE_CALL = "\\.node\\(\\s*[^,()]+,\\s*$";

    @Test
    void registeredNodesBuildOtherNodesOnlyThroughTheGroupBuilder() throws IOException {
        Map<String, Class<? extends MeshNode>> registry =
                new TreeMap<>(NodeGraphRuntime.annotationRegistryClasses());
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Class<? extends MeshNode>> entry : registry.entrySet()) {
            Class<? extends MeshNode> nodeClass = entry.getValue();
            Path source = MODULE_SOURCE_ROOT.resolve(nodeClass.getName().replace('.', '/') + ".java");
            String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8)
                    .replaceAll("(?m)^import .*$", "");
            String node = entry.getKey() + " (" + nodeClass.getSimpleName() + ")";
            boolean group = NodeGroup.class.isAssignableFrom(nodeClass);
            for (String type : NODE_EVALUATION_TYPES) {
                // A group may name the runtime's path and port separators, never evaluate.
                Pattern use = Pattern.compile("\\b" + type + "\\b"
                        + (group ? "(?!\\.(PORT_SEPARATOR|PATH_SEPARATOR)\\b)" : ""));
                if (use.matcher(text).find()) {
                    violations.add(node + " uses " + type);
                }
            }
            if (!group && text.contains("GroupBuilder")) {
                violations.add(node + " uses the group builder without being a NodeGroup");
            }
            for (Class<? extends MeshNode> other : registry.values()) {
                if (other == nodeClass) {
                    continue;
                }
                String name = other.getSimpleName();
                if (Pattern.compile("new\\s+" + name + "\\s*\\(").matcher(text).find()) {
                    violations.add(node + " constructs " + name);
                }
                Matcher reference = Pattern.compile("\\b" + name + "::new\\b").matcher(text);
                while (reference.find()) {
                    String before = text.substring(0, reference.start());
                    if (!group || !Pattern.compile(BUILDER_NODE_CALL).matcher(before).find()) {
                        violations.add(node + " takes " + name + "::new outside group.node(...)");
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "a MeshNode builds or evaluates another MeshNode;"
                + " a NodeGroup declares it through group.node(...) instead:\n  "
                + String.join("\n  ", violations));
    }
}
