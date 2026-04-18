package unit.mesh;

import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.OutputPort;

/**
 * Guards {@link MeshNode#socketDocs()} coverage: every port declared by a
 * registered node (via {@link MeshNode#inputs()} and {@link MeshNode#outputs()})
 * must have a non-empty description entry.
 *
 * <p>Missing entries fail the build with a list of (node id, port name) pairs
 * so migration is mechanical.
 */
public class MeshNodePortDocumentationTest {

    @Test
    @SuppressWarnings("unchecked")
    public void everyRegisteredPortHasDocumentation() throws Exception {
        Class<?> registryClass = Class.forName("ixdar.annotations.meshnode.MeshNodeRegistry_MeshNodes");
        Field mapField = registryClass.getField("MAP");
        Map<String, Supplier<? extends MeshNode>> map =
                (Map<String, Supplier<? extends MeshNode>>) mapField.get(null);

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Supplier<? extends MeshNode>> e : map.entrySet()) {
            String nodeId = e.getKey();
            MeshNode node;
            try {
                node = e.getValue().get();
            } catch (Exception ex) {
                missing.add(nodeId + ": (could not instantiate: " + ex.getMessage() + ")");
                continue;
            }
            Map<String, String> docs = node.socketDocs();
            for (InputPort p : node.inputs()) {
                String doc = docs.get(p.name());
                if (doc == null || doc.isBlank()) {
                    missing.add(nodeId + ".inputs." + p.name());
                }
            }
            for (OutputPort p : node.outputs()) {
                String doc = docs.get(p.name());
                if (doc == null || doc.isBlank()) {
                    missing.add(nodeId + ".outputs." + p.name());
                }
            }
        }

        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(missing.size()).append(" port(s) missing socketDocs() entries:\n");
            for (String m : missing) {
                sb.append("  - ").append(m).append("\n");
            }
            sb.append("\nAdd an override like:\n");
            sb.append("  @Override public Map<String, String> socketDocs() {\n");
            sb.append("      return Map.of(\"port_name\", \"one-line description\", ...);\n");
            sb.append("  }\n");
            fail(sb.toString());
        }
    }
}
