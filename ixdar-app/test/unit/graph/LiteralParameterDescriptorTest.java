package unit.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.MeshNodeRegistry_MeshNodes;
import ixdar.geometry.mesh.graph.LiteralParameterDescriptor;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;

/**
 * The optimizer's literal discovery resolves node ports through the runtime's lookup, so a node
 * registered only in the desktop partition contributes its float literals like any shared node.
 */
class LiteralParameterDescriptorTest {

    private static final String DESKTOP_NODE = "cross_field";

    private static final float CURVATURE_BIAS = 0.25f;

    private static final String GRAPH = "grid = mesh_grid(u_tiles=4, v_tiles=4)\n"
            + "field = " + DESKTOP_NODE + "(geometry=grid.mesh, curvature_bias=" + CURVATURE_BIAS
            + ")\n";

    @Test
    void aDesktopOnlyNodesFloatLiteralIsAnOptimizerParameter() {
        assertTrue(MeshNodeRegistry_MeshNodes.DESKTOP_ONLY_IDS.contains(DESKTOP_NODE),
                "the fixture node lives in the desktop partition");
        List<LiteralParameterDescriptor> found = LiteralParameterDescriptor.collect(
                NodeGraphRuntime.fromSource(GRAPH).statements);
        List<LiteralParameterDescriptor> desktop = found.stream()
                .filter(descriptor -> descriptor.nodeType().equals(DESKTOP_NODE)).toList();
        assertEquals(1, desktop.size(), "found " + found);
        assertEquals("field.curvature_bias", desktop.get(0).overrideKey());
        assertEquals(CURVATURE_BIAS, desktop.get(0).defaultValue());
    }
}
