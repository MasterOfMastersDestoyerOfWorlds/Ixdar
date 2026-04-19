package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.CanonicalPortNames;
import ixdar.annotations.meshnode.CanonicalPortNames.InputRole;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.ModeConstraint;
import ixdar.annotations.meshnode.PortType;

/**
 * Enforces {@link CanonicalPortNames}: every registered node's input port
 * that matches a known {@link InputRole} signature must use the canonical
 * name for that role.
 *
 * <p>Today only {@link InputRole#OPERATION_SELECTOR} is covered: STRING
 * inputs with a ModeConstraint must be named {@code "operation"}. Other
 * roles can be added as they emerge.
 */
public class MeshNodeCanonicalInputNamesTest {

    @Test
    @SuppressWarnings("unchecked")
    public void everyRoleBearingInputUsesCanonicalName() throws Exception {
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

            for (InputPort port : node.inputs()) {
                Optional<InputRole> role = CanonicalPortNames.roleOf(nodeId, port);
                if (role.isEmpty()) {
                    continue;
                }
                String canonical = CanonicalPortNames.canonicalForRole(role.get());
                if (!canonical.equals(port.name())) {
                    violations.add(String.format(
                            "  - %s input %s:%s plays role %s — canonical name is '%s'",
                            nodeId,
                            port.name(),
                            port.type(),
                            role.get(),
                            canonical));
                }
            }
        }

        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(violations.size())
                    .append(" node(s) violate canonical input naming:\n");
            for (String v : violations) {
                sb.append(v).append("\n");
            }
            sb.append("\nRename the InputPort(\"...\", PortType.X, ...) in the offending node class ");
            sb.append("and update its socketDocs() key accordingly.\n");
            sb.append("See ixdar.annotations.meshnode.CanonicalPortNames for the full rule.\n");
            fail(sb.toString());
        }
    }

    /**
     * Regression path: verifies the predicate itself flags a known-bad port
     * on a {@code *_math} node, without having to inject a fixture node into
     * the live registry.
     */
    @Test
    public void roleOfFlagsModeNamedPortOnMathNode() {
        ModeConstraint modes = new ModeConstraint(
                "ADD",
                List.of("ADD", "OR"),
                Map.of());
        InputPort badPort = new InputPort("mode", PortType.STRING, "ADD", modes);
        Optional<InputRole> role = CanonicalPortNames.roleOf("regression_math", badPort);
        assertTrue(role.isPresent(),
                "roleOf should classify a STRING+ModeConstraint input on *_math node");
        assertEquals(InputRole.OPERATION_SELECTOR, role.get());
        assertEquals("operation", CanonicalPortNames.canonicalForRole(role.get()),
                "canonical name for OPERATION_SELECTOR must be 'operation'");
    }

    /**
     * Boundary: the same shape on a non-math node (e.g. {@code compare},
     * {@code map_range}) is NOT classified as an operation selector —
     * those inputs have their own semantic names.
     */
    @Test
    public void roleOfIgnoresModeNamedPortOnNonMathNode() {
        ModeConstraint modes = new ModeConstraint(
                "LESS",
                List.of("LESS", "EQUAL"),
                Map.of());
        InputPort comparePort = new InputPort("mode", PortType.STRING, "LESS", modes);
        Optional<InputRole> role = CanonicalPortNames.roleOf("compare", comparePort);
        assertTrue(role.isEmpty(),
                "roleOf should not classify mode inputs on non-*_math nodes");
    }
}
