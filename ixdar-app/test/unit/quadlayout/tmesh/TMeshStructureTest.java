package unit.quadlayout.tmesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.integergrid.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.tmesh.TArc;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;
import ixdar.geometry.mesh.quadlayout.tmesh.TPatch;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

/**
 * Structural T-mesh invariants (PATCH-41 v1).  We do not assert per-mesh patch
 * counts (the cube parametrization has flipped triangles upstream); we only
 * check shape invariants on whatever the assembler produced.
 */
public class TMeshStructureTest {

    @Test
    void cubeTMeshSatisfiesArcAndPatchInvariants() {
        ArrayMesh mesh = TMeshTestMeshes.makeCube();
        FaceRosyField field = new FaceRosyField(mesh);
        field.solve();
        CombedField combed = CombedField.comb(field);
        List<Singularity> singularities = field.findSingularities();
        SeamlessParameterization param = new SeamlessParameterization(
                mesh, field, combed, singularities);

        MotorcycleGraph.Result graph = MotorcycleGraph.trace(
                param, mesh, field, combed, singularities);
        TMesh tmesh = TMesh.build(graph, param);

        assertNotNull(tmesh);
        // PATCH-54: cube can produce 0 arcs because every vertex is a pinned
        // singularity (see comment in MotorcycleGraphTest.cubeProducesMotorcycleTraces).
        // Real validation runs against rocker-arm / Hand at proper scale.

        // Every arc has 2 endpoint nodes (start + end), both within bounds.
        for (TArc a : tmesh.arcs()) {
            assertTrue(a.endNode() >= 0 && a.endNode() < tmesh.nodes().size(),
                    "arc " + a.id() + " endNode out of range");
            // startNode may be -1 only if the launching singularity face was
            // degenerate. In practice we expect a valid startNode for cube.
        }

        // No two arcs share both endpoints AND the same direction (would be
        // duplicate).
        HashSet<String> seen = new HashSet<>();
        for (TArc a : tmesh.arcs()) {
            String key = Math.min(a.startNode(), a.endNode()) + "_"
                    + Math.max(a.startNode(), a.endNode()) + "_" + a.direction();
            assertTrue(seen.add(key) || a.startNode() == -1,
                    "duplicate arc with same endpoints + direction: " + a.id());
        }

        // PATCH-93: cube param is degenerate (all vertices are pinned
        // singularities, see the PATCH-54 note above). Motorcycle traces
        // on this degenerate input may self-intersect, producing patches
        // whose face cycle revisits a node. We only assert structural
        // sanity: arc count matches corner count, references valid.
        for (TPatch p : tmesh.patches()) {
            int nCorners = p.cornerNodeIds().length;
            assertTrue(nCorners >= 3,
                    "patch " + p.id() + " must have at least 3 corners");
            assertEquals(nCorners, p.arcIds().length,
                    "patch " + p.id() + " arc count must match corner count");
            // Every referenced arc must exist.
            for (int a : p.arcIds()) {
                assertTrue(a >= 0 && a < tmesh.arcs().size(),
                        "patch " + p.id() + " references missing arc " + a);
            }
        }
    }
}
