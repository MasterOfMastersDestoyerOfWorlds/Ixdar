package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;

/**
 * The dense live-id set underneath {@code vertexCount()}/{@code faceIdAt()} and friends, exercised
 * through a real {@link HalfEdgeMesh} because that is the only way it is reachable — it is package
 * private, and deliberately so.
 *
 * <p>The property that matters is not the order of the dense walk but its <em>contents</em>: after
 * any sequence of additions and removals, walking {@code [0, count())} must enumerate exactly the
 * live ids, each once. Removal fills the vacated slot with the last id rather than shifting the
 * tail down, so positions move and the walk is no longer ascending in id; these tests are written
 * against the contract {@code MeshTopology} actually makes — a stable <em>id</em> at a dense
 * position — and would fail if anything reintroduced an ordering assumption.
 */
class ActiveIdSetTest {

    private static final int GRID = 6;

    @Test
    void denseWalkEnumeratesExactlyTheLiveFacesAfterInterleavedRemoval() {
        HalfEdgeMesh mesh = buildGrid();
        Set<Integer> live = new HashSet<>(faceIdsInDenseOrder(mesh));
        assertEquals(mesh.faceCount(), live.size(), "the initial walk lists each face once");

        List<Integer> ordered = new ArrayList<>(live);
        for (int index = 0; index < ordered.size(); index += 3) {
            int faceId = ordered.get(index);
            mesh.deactivateFace(faceId);
            live.remove(faceId);

            List<Integer> walk = faceIdsInDenseOrder(mesh);
            assertEquals(live.size(), mesh.faceCount(),
                    "the count follows the removals");
            assertEquals(live.size(), walk.size(),
                    "the dense walk is exactly as long as the live set");
            assertEquals(live, new HashSet<>(walk),
                    "the dense walk enumerates exactly the live faces");
            assertFalse(mesh.hasFace(faceId), "a removed face is no longer live");
        }
    }

    @Test
    void removingTheOnlyAndThenTheLastElementEmptiesTheWalk() {
        HalfEdgeMesh mesh = buildGrid();
        List<Integer> faces = faceIdsInDenseOrder(mesh);
        for (int faceId : faces) {
            mesh.deactivateFace(faceId);
        }
        assertEquals(0, mesh.faceCount(), "removing every face empties the set");
        assertTrue(faceIdsInDenseOrder(mesh).isEmpty(), "an emptied set walks nothing");
        assertThrows(IndexOutOfBoundsException.class, () -> mesh.faceIdAt(0),
                "an emptied set has no position zero");
    }

    @Test
    void idsAddedBeyondTheInitialCapacityStayAddressable() {
        HalfEdgeMesh mesh = new HalfEdgeMesh();
        Set<Integer> created = new HashSet<>();
        for (int index = 0; index < 200; index++) {
            created.add(mesh.createVertexSlot(index, 0f, 0f));
        }
        assertEquals(created.size(), mesh.vertexCount(),
                "every vertex minted past the initial capacity is live");

        Set<Integer> walk = new HashSet<>();
        for (int activeIndex = 0; activeIndex < mesh.vertexCount(); activeIndex++) {
            walk.add(mesh.vertexIdAt(activeIndex));
        }
        assertEquals(created, walk, "the dense walk lists every minted vertex once");

        for (int vertexId : new ArrayList<>(created)) {
            if (vertexId % 2 == 0) {
                mesh.deactivateVertex(vertexId);
                created.remove(vertexId);
            }
        }
        walk.clear();
        for (int activeIndex = 0; activeIndex < mesh.vertexCount(); activeIndex++) {
            walk.add(mesh.vertexIdAt(activeIndex));
        }
        assertEquals(created, walk, "the walk still lists exactly the survivors");
    }

    /**
     * The face ids reachable by walking the dense active index.
     *
     * @param mesh mesh to walk
     * @return the face ids in dense order
     */
    private List<Integer> faceIdsInDenseOrder(HalfEdgeMesh mesh) {
        List<Integer> faces = new ArrayList<>(mesh.faceCount());
        for (int activeIndex = 0; activeIndex < mesh.faceCount(); activeIndex++) {
            faces.add(mesh.faceIdAt(activeIndex));
        }
        return faces;
    }

    /**
     * Builds a small triangulated grid on the z = 0 plane.
     *
     * @return the grid as a half-edge mesh
     */
    private HalfEdgeMesh buildGrid() {
        float[] positions = new float[GRID * GRID * 3];
        for (int row = 0; row < GRID; row++) {
            for (int column = 0; column < GRID; column++) {
                int base = (row * GRID + column) * 3;
                positions[base] = column;
                positions[base + 1] = row;
                positions[base + 2] = 0f;
            }
        }
        int[] faces = new int[(GRID - 1) * (GRID - 1) * 2 * 3];
        int cursor = 0;
        for (int row = 0; row < GRID - 1; row++) {
            for (int column = 0; column < GRID - 1; column++) {
                int lowerLeft = row * GRID + column;
                int lowerRight = lowerLeft + 1;
                int upperLeft = lowerLeft + GRID;
                int upperRight = upperLeft + 1;
                faces[cursor++] = lowerLeft;
                faces[cursor++] = lowerRight;
                faces[cursor++] = upperRight;
                faces[cursor++] = lowerLeft;
                faces[cursor++] = upperRight;
                faces[cursor++] = upperLeft;
            }
        }
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faces);
    }
}
