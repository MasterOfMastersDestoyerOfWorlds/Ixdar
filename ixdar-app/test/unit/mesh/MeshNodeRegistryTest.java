package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.CanonicalPortNames;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeRegistry_MeshNodes;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;

/**
 * The registry test the {@link MeshNode#socketDocs()} contract names: every registered node must
 * describe itself and every port, and port names must follow {@link CanonicalPortNames} where the
 * policy binds (single outputs of a type; role-matched inputs).
 */
class MeshNodeRegistryTest {

    @Test
    void everyNodeDocumentsItselfAndItsPorts() {
        List<String> violations = new ArrayList<>();
        forEachNode((id, node) -> {
            if (node.description() == null || node.description().isBlank()) {
                violations.add(id + ": blank description()");
            }
            Map<String, String> docs = node.socketDocs();
            for (InputPort port : node.inputs()) {
                checkDoc(violations, docs, id, "input", port.name);
            }
            for (OutputPort port : node.outputs()) {
                checkDoc(violations, docs, id, "output", port.name);
            }
        });
        assertTrue(violations.isEmpty(), () -> report("undocumented ports", violations));
    }

    @Test
    void portNamesFollowTheCanonicalPolicy() {
        List<String> violations = new ArrayList<>();
        forEachNode((id, node) -> {
            Map<PortType, List<OutputPort>> byType = new HashMap<>();
            for (OutputPort port : node.outputs()) {
                byType.computeIfAbsent(port.type, unused -> new ArrayList<>()).add(port);
            }
            for (Map.Entry<PortType, List<OutputPort>> group : byType.entrySet()) {
                if (group.getValue().size() != 1) {
                    continue;
                }
                String name = group.getValue().get(0).name;
                if (hasCanonical(group.getKey()) && !CanonicalPortNames.isAllowed(group.getKey(), name)) {
                    violations.add(id + ": single " + group.getKey() + " output named '" + name
                            + "', expected '" + CanonicalPortNames.canonicalFor(group.getKey())
                            + "' or one of " + CanonicalPortNames.allowedRoleNames(group.getKey()));
                }
            }
            for (InputPort port : node.inputs()) {
                CanonicalPortNames.roleOf(id, port).ifPresent(role -> {
                    String expected = CanonicalPortNames.canonicalForRole(role);
                    if (!expected.equals(port.name)) {
                        violations.add(id + ": " + role + " input named '" + port.name
                                + "', expected '" + expected + "'");
                    }
                });
            }
        });
        assertTrue(violations.isEmpty(), () -> report("non-canonical port names", violations));
    }

    private static void checkDoc(List<String> violations, Map<String, String> docs, String id,
            String kind, String portName) {
        String doc = docs.get(portName);
        if (doc == null || doc.isBlank()) {
            violations.add(id + ": " + kind + " port '" + portName + "' has no socketDocs entry");
        }
    }

    private static boolean hasCanonical(PortType type) {
        try {
            CanonicalPortNames.canonicalFor(type);
            return true;
        } catch (IllegalArgumentException undefined) {
            return false;
        }
    }

    private static void forEachNode(NodeCheck check) {
        Map<String, Supplier<? extends MeshNode>> all = new HashMap<>(MeshNodeRegistry_MeshNodes.MAP);
        all.putAll(NodeGraphRuntime.desktopRegistryMap());
        for (Map.Entry<String, Supplier<? extends MeshNode>> entry : all.entrySet()) {
            check.accept(entry.getKey(), entry.getValue().get());
        }
    }

    private static String report(String title, List<String> violations) {
        return violations.size() + " " + title + ":\n  " + String.join("\n  ", violations);
    }

    private interface NodeCheck {
        void accept(String id, MeshNode node);
    }
}
