package unit.mesh;

import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.CanonicalPortNames;
import ixdar.annotations.meshnode.CanonicalPortNames.InputRole;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

/**
 * Enforces {@link CanonicalPortNames}: every registered node's single output
 * per port type must use the canonical name (or a listed role name).
 *
 * <p>Nodes with multiple outputs of the same type are exempt — they're
 * expected to use descriptive names. See {@link CanonicalPortNames} for the
 * rule definition.
 */
public class MeshNodeCanonicalOutputNamesTest {

    @Test
    @SuppressWarnings("unchecked")
    public void everySingleTypedOutputUsesCanonicalName() throws Exception {
        Class<?> registryClass = Class.forName("ixdar.annotations.meshnode.MeshNodeRegistry_MeshNodes");
        Field mapField = registryClass.getField("MAP");
        Map<String, Supplier<? extends MeshNode>> map =
                (Map<String, Supplier<? extends MeshNode>>) mapField.get(null);

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Supplier<? extends MeshNode>> e : map.entrySet()) {
            String nodeId = e.getKey();
            MeshNode node;
            try {
                node = e.getValue().get();
            } catch (Exception ex) {
                continue;  // instantiation failure is a separate test's concern
            }

            // Group outputs by port type. Only enforce canonical naming when
            // a node has exactly one output of a given type AND the canonical
            // name for that type doesn't collide with a sibling output's
            // canonical — otherwise multi-typed nodes like random_value
            // (FLOAT + INT, both canonical to "result") can't satisfy the rule.
            Map<PortType, List<OutputPort>> byType = new EnumMap<>(PortType.class);
            for (OutputPort p : node.outputs()) {
                byType.computeIfAbsent(p.type(), t -> new ArrayList<>()).add(p);
            }

            // Count how many distinct types in this node share the same
            // canonical output name (e.g. FLOAT+INT both → "result").
            Map<String, Long> canonicalUsage = new java.util.HashMap<>();
            for (PortType t : byType.keySet()) {
                String c = CanonicalPortNames.canonicalFor(t);
                canonicalUsage.merge(c, 1L, Long::sum);
            }

            for (Map.Entry<PortType, List<OutputPort>> typed : byType.entrySet()) {
                if (typed.getValue().size() != 1) {
                    continue;  // multi-output-per-type nodes exempt for v1
                }
                PortType type = typed.getKey();
                String canonical = CanonicalPortNames.canonicalFor(type);
                if (canonicalUsage.get(canonical) > 1) {
                    continue;  // canonical-name collision → descriptive names OK
                }
                OutputPort only = typed.getValue().get(0);
                if (!CanonicalPortNames.isAllowed(only.type(), only.name())) {
                    violations.add(String.format(
                            "  - %s outputs %s:%s — canonical for %s is '%s'%s",
                            nodeId,
                            only.name(),
                            only.type(),
                            only.type(),
                            canonical,
                            CanonicalPortNames.allowedRoleNames(only.type()).isEmpty()
                                    ? ""
                                    : " (or role names " + CanonicalPortNames.allowedRoleNames(only.type()) + ")"));
                }
            }
        }

        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(violations.size())
                    .append(" node(s) violate canonical output naming:\n");
            for (String v : violations) {
                sb.append(v).append("\n");
            }
            sb.append("\nRename the OutputPort(\"...\", PortType.X) in the offending node class ");
            sb.append("and update its socketDocs() key accordingly.\n");
            sb.append("See ixdar.annotations.meshnode.CanonicalPortNames for the full rule.\n");
            fail(sb.toString());
        }
    }

    /**
     * Enforces role-based input port naming via
     * {@link CanonicalPortNames#roleOf(String, InputPort)}. Any input port
     * that matches a known role signature (currently: operation-selector
     * STRING+ModeConstraint on a {@code *_math} node) MUST be named using
     * {@link CanonicalPortNames#canonicalForRole(InputRole)}.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void everyClassifiableInputUsesCanonicalRoleName() throws Exception {
        Class<?> registryClass = Class.forName("ixdar.annotations.meshnode.MeshNodeRegistry_MeshNodes");
        Field mapField = registryClass.getField("MAP");
        Map<String, Supplier<? extends MeshNode>> map =
                (Map<String, Supplier<? extends MeshNode>>) mapField.get(null);

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Supplier<? extends MeshNode>> e : map.entrySet()) {
            String nodeId = e.getKey();
            MeshNode node;
            try {
                node = e.getValue().get();
            } catch (Exception ex) {
                continue;
            }
            for (InputPort p : node.inputs()) {
                CanonicalPortNames.roleOf(nodeId, p).ifPresent(role -> {
                    String canonical = CanonicalPortNames.canonicalForRole(role);
                    if (!p.name().equals(canonical)) {
                        violations.add(String.format(
                                "  - %s inputs %s:%s classified as %s — canonical is '%s'",
                                nodeId, p.name(), p.type(), role, canonical));
                    }
                });
            }
        }

        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(violations.size())
                    .append(" node(s) violate canonical input role naming:\n");
            for (String v : violations) sb.append(v).append("\n");
            sb.append("\nRename the InputPort(\"...\", ...) and update socketDocs() + ");
            sb.append("any downstream ctx.getInput(...) lookups accordingly.\n");
            sb.append("See ixdar.annotations.meshnode.CanonicalPortNames.InputRole for recognized roles.\n");
            fail(sb.toString());
        }
    }
}
