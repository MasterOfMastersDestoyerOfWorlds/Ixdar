package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.extraction.ExtractedPatchGrids;
import ixdar.geometry.mesh.quadlayout.extraction.ExtractedQuadMesh;
import ixdar.geometry.mesh.quadlayout.gridmap.GlobalGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.IntegerGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.LayoutPatchMaps;
import ixdar.geometry.mesh.quadlayout.gridmap.PatchRegions;

/**
 * A patch whose relaxed corner wraps past a full turn gives two ports one direction; the
 * regrouping leaves that arc end to the ring walk, unlike block's node 10 at bias -1.
 */
class WrappedCornerPortMatchTest {

    /** Spokes at each pole, which is the poles' valence. */
    private static final int SPOKES = 5;

    /** Ring vertices round each cap: a spoke end, then a valence-3 corner, alternating. */
    private static final int RING = 2 * SPOKES;

    /** The top pole, the valence-5 node whose ports the test labels. */
    private static final int TOP_POLE = 0;

    /** First vertex of the top ring. */
    private static final int TOP_RING = TOP_POLE + 1;

    /** The bottom pole. */
    private static final int BOTTOM_POLE = TOP_RING + RING;

    /** First vertex of the bottom ring. */
    private static final int BOTTOM_RING = BOTTOM_POLE + 1;

    /** Layout nodes, which are also the quad mesh's vertices. */
    private static final int VERTICES = BOTTOM_RING + RING;

    /** The patch whose corner at the top pole wraps: the first top cap quad. */
    private static final int WRAPPED_PATCH = 0;

    /** Directions of the integer grid. */
    private static final int QUARTER_TURNS = 4;

    /** Arcs the ring walk would take from the verified spoke round the top pole. */
    private static final int DIRECT_MATCHES = 1;

    /**
     * The first cap patch spans all five top-pole ports, so spokes 0 and 4 share its
     * direction; the list starts at spoke 4, which a first-found match wrongly hands spoke 0.
     */
    @Test
    void anAmbiguousPortPairIsLeftToTheRingWalk() {
        List<int[]> quads = sphereQuads();
        HalfEdgeMesh surface = HalfEdgeMesh.buildFromIndexedMesh(positions(), triangles(quads));
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(surface);
        ArcNetwork layout = new ArcNetwork(topology);
        for (int vertex = 0; vertex < VERTICES; vertex++) {
            boolean pole = vertex == TOP_POLE || vertex == BOTTOM_POLE;
            boolean corner = !pole && ringIndex(vertex) % 2 == 1;
            layout.addNode(ArcNetwork.NONE, topology.copyVertexForSourceVertexId(vertex),
                    pole || corner, false);
        }
        int[][] arcBetween = new int[VERTICES][VERTICES];
        for (int[] quad : quads) {
            for (int corner = 0; corner < ExtractedQuadMesh.QUAD_CORNERS; corner++) {
                int from = quad[corner];
                int to = quad[(corner + 1) % ExtractedQuadMesh.QUAD_CORNERS];
                if (from < to) {
                    int arcId = layout.addArc(ArcNetwork.NONE, from, to, 1, false,
                            List.of(topology.copyVertexForSourceVertexId(from),
                                    topology.copyVertexForSourceVertexId(to)));
                    layout.arcs.get(arcId).quadCount = 1;
                    arcBetween[from][to] = arcId;
                    arcBetween[to][from] = arcId;
                }
            }
        }
        for (int[] quad : quads) {
            List<List<Integer>> sides = new ArrayList<>();
            for (int corner = 0; corner < ExtractedQuadMesh.QUAD_CORNERS; corner++) {
                sides.add(List.of(arcBetween[quad[corner]][quad[(corner + 1)
                        % ExtractedQuadMesh.QUAD_CORNERS]]));
            }
            layout.addPatch(ArcNetwork.NONE, sides, quad[0]);
        }
        IntegerGridMap frames = new IntegerGridMap(layout).build();
        LayoutPatchMaps patchMaps = new LayoutPatchMaps(layout, null, 1.0);
        patchMaps.regions = new PatchRegions(layout).build();
        GlobalGridMap gridMap = new GlobalGridMap(patchMaps, frames, null);
        gridMap.denseByCopyVertexByPatchId = poleOnlyDenseMaps(layout.patches.size(),
                topology.copyVertexForSourceVertexId(TOP_POLE));
        gridMap.uvByPatchId = new double[layout.patches.size()][GlobalGridMap.GRID_COORDINATES];
        int firstSpoke = arcBetween[TOP_POLE][ringVertex(TOP_RING, 0)];
        int[] start = new int[2];
        int[] end = new int[2];
        int[] gridStart = new int[2];
        int[] gridEnd = new int[2];
        frames.arcLocalCoordinates(WRAPPED_PATCH, firstSpoke, start, end);
        frames.toGrid(WRAPPED_PATCH, start, gridStart);
        frames.toGrid(WRAPPED_PATCH, end, gridEnd);
        int firstSpokeTurns = turns(gridEnd[0] - gridStart[0], gridEnd[1] - gridStart[1]);
        int wrappedFace = patchMaps.regions.copyFacesByPatch.get(WRAPPED_PATCH).get(0);
        ExtractedQuadMesh quadMesh = layoutQuadMesh(quads, topology, wrappedFace,
                firstSpokeTurns);

        ExtractedPatchGrids grids = new ExtractedPatchGrids(quadMesh, gridMap).build();

        assertEquals(DIRECT_MATCHES, grids.directMatchCount,
                "only the spoke with one port in its direction is matched directly");
        for (int quad = 0; quad < quads.size(); quad++) {
            assertEquals(quad, grids.patchIdByQuad[quad], "quad " + quad + " lands in its patch");
        }
        for (int vertex = 0; vertex < VERTICES; vertex++) {
            assertEquals(vertex, grids.quadVertexByNodeId[vertex],
                    "node " + vertex + " sits on its own quad vertex");
        }
    }

    /**
     * The layout's quads, counter-clockwise seen from outside: the top cap, the band, the
     * bottom cap. Patch ids follow this order.
     *
     * @return one four-vertex cycle per quad
     */
    private static List<int[]> sphereQuads() {
        List<int[]> quads = new ArrayList<>();
        for (int spoke = 0; spoke < SPOKES; spoke++) {
            quads.add(new int[] { TOP_POLE, ringVertex(TOP_RING, 2 * spoke),
                    ringVertex(TOP_RING, 2 * spoke + 1), ringVertex(TOP_RING, 2 * spoke + 2) });
        }
        for (int step = 0; step < RING; step++) {
            quads.add(new int[] { ringVertex(BOTTOM_RING, step), ringVertex(BOTTOM_RING, step + 1),
                    ringVertex(TOP_RING, step + 1), ringVertex(TOP_RING, step) });
        }
        for (int spoke = 0; spoke < SPOKES; spoke++) {
            quads.add(new int[] { BOTTOM_POLE, ringVertex(BOTTOM_RING, 2 * spoke + 2),
                    ringVertex(BOTTOM_RING, 2 * spoke + 1), ringVertex(BOTTOM_RING, 2 * spoke) });
        }
        return quads;
    }

    /**
     * The layout itself as an extracted quad mesh, every node on its own copy vertex, each
     * vertex's ports clockwise from outside. Only the top pole's ports carry a face and a
     * direction, those of a first cap patch wrapped round the whole pole.
     *
     * @param quads           the layout's quads
     * @param topology        the carrier whose copy vertices anchor the nodes
     * @param wrappedFace     a copy face of the wrapped patch
     * @param firstSpokeTurns direction of spoke 0 in the wrapped patch's chart
     * @return the quad mesh
     */
    private static ExtractedQuadMesh layoutQuadMesh(List<int[]> quads,
            EmbeddedMeshTopology topology, int wrappedFace, int firstSpokeTurns) {
        int[][] clockwiseAfter = new int[VERTICES][VERTICES];
        int[] firstNeighbor = new int[VERTICES];
        for (int[] quad : quads) {
            for (int corner = 0; corner < ExtractedQuadMesh.QUAD_CORNERS; corner++) {
                int previous = quad[(corner + ExtractedQuadMesh.QUAD_CORNERS - 1)
                        % ExtractedQuadMesh.QUAD_CORNERS];
                int next = quad[(corner + 1) % ExtractedQuadMesh.QUAD_CORNERS];
                clockwiseAfter[quad[corner]][previous] = next;
                firstNeighbor[quad[corner]] = next;
            }
        }
        firstNeighbor[TOP_POLE] = ringVertex(TOP_RING, 2 * (SPOKES - 1));
        ExtractedQuadMesh mesh = new ExtractedQuadMesh();
        mesh.quadVertexCount = VERTICES;
        mesh.positions = new float[VERTICES * ExtractedQuadMesh.POSITION_FLOATS];
        mesh.vertexKind = new int[VERTICES];
        mesh.anchorEntityId = new int[VERTICES];
        mesh.portStart = new int[VERTICES + 1];
        List<Integer> owners = new ArrayList<>();
        List<Integer> targets = new ArrayList<>();
        Map<Long, Integer> portByDirectedEdge = new HashMap<>();
        for (int vertex = 0; vertex < VERTICES; vertex++) {
            mesh.vertexKind[vertex] = ExtractedQuadMesh.KIND_MESH_VERTEX;
            mesh.anchorEntityId[vertex] = topology.copyVertexForSourceVertexId(vertex);
            mesh.portStart[vertex] = owners.size();
            int neighbor = firstNeighbor[vertex];
            do {
                portByDirectedEdge.put(directedEdge(vertex, neighbor), owners.size());
                owners.add(vertex);
                targets.add(neighbor);
                neighbor = clockwiseAfter[vertex][neighbor];
            } while (neighbor != firstNeighbor[vertex]);
        }
        mesh.portCount = owners.size();
        mesh.portStart[VERTICES] = mesh.portCount;
        mesh.portOwner = new int[mesh.portCount];
        mesh.portFace = new int[mesh.portCount];
        mesh.portDirectionTurns = new int[mesh.portCount];
        mesh.portConnection = new int[mesh.portCount];
        for (int port = 0; port < mesh.portCount; port++) {
            int owner = owners.get(port);
            int target = targets.get(port);
            mesh.portOwner[port] = owner;
            mesh.portConnection[port] = portByDirectedEdge.get(directedEdge(target, owner));
            mesh.portFace[port] = ExtractedQuadMesh.NONE;
            if (owner == TOP_POLE) {
                int spoke = (target - TOP_RING) / 2;
                mesh.portFace[port] = wrappedFace;
                mesh.portDirectionTurns[port] = (firstSpokeTurns + spoke) % QUARTER_TURNS;
            }
        }
        mesh.quadCount = quads.size();
        mesh.quadCorner = new int[quads.size() * ExtractedQuadMesh.QUAD_CORNERS];
        for (int quad = 0; quad < quads.size(); quad++) {
            System.arraycopy(quads.get(quad), 0, mesh.quadCorner,
                    quad * ExtractedQuadMesh.QUAD_CORNERS, ExtractedQuadMesh.QUAD_CORNERS);
        }
        mesh.quadEdgeCount = mesh.portCount / 2;
        return mesh;
    }

    /**
     * Dense-index maps holding only the top pole, the one node the regrouping matches
     * directly.
     *
     * @param patchCount  patches in the layout
     * @param poleVertex  the top pole's copy vertex
     * @return one map per patch
     */
    @SuppressWarnings("unchecked")
    private static Map<Integer, Integer>[] poleOnlyDenseMaps(int patchCount, int poleVertex) {
        Map<Integer, Integer>[] maps = new Map[patchCount];
        for (int patchId = 0; patchId < patchCount; patchId++) {
            maps[patchId] = Map.of(poleVertex, 0);
        }
        return maps;
    }

    /**
     * Vertex positions: the poles above and below, each ring a pentagon of spoke ends with
     * the corners pushed outwards between them.
     *
     * @return packed xyz per vertex
     */
    private static float[] positions() {
        float[] positions = new float[VERTICES * ExtractedQuadMesh.POSITION_FLOATS];
        positions[TOP_POLE * ExtractedQuadMesh.POSITION_FLOATS + 2] = 2;
        positions[BOTTOM_POLE * ExtractedQuadMesh.POSITION_FLOATS + 2] = -2;
        for (int step = 0; step < RING; step++) {
            double angle = 2 * Math.PI * step / RING;
            int radius = step % 2 == 0 ? 1 : 2;
            for (int ringStart : new int[] { TOP_RING, BOTTOM_RING }) {
                int base = ringVertex(ringStart, step) * ExtractedQuadMesh.POSITION_FLOATS;
                positions[base] = (float) (radius * Math.cos(angle));
                positions[base + 1] = (float) (radius * Math.sin(angle));
                positions[base + 2] = ringStart == TOP_RING ? 1 : -1;
            }
        }
        return positions;
    }

    /**
     * Each quad split along its first diagonal, keeping its winding.
     *
     * @param quads the layout's quads
     * @return triangle vertex indices
     */
    private static int[] triangles(List<int[]> quads) {
        int[] indices = new int[quads.size() * 2 * HalfEdgeMesh.TRIANGLE_CORNERS];
        int cursor = 0;
        for (int[] quad : quads) {
            for (int corner : new int[] { quad[0], quad[1], quad[2], quad[0], quad[2], quad[3] }) {
                indices[cursor++] = corner;
            }
        }
        return indices;
    }

    /**
     * A ring vertex by its cyclic step round the ring.
     *
     * @param ringStart the ring's first vertex
     * @param step      steps round the ring, wrapping
     * @return the vertex
     */
    private static int ringVertex(int ringStart, int step) {
        return ringStart + Math.floorMod(step, RING);
    }

    /**
     * A ring vertex's step round its ring.
     *
     * @param vertex a ring vertex
     * @return its step
     */
    private static int ringIndex(int vertex) {
        return vertex < BOTTOM_POLE ? vertex - TOP_RING : vertex - BOTTOM_RING;
    }

    /**
     * The quarter turns of an axis-aligned grid step.
     *
     * @param stepU grid u component
     * @param stepV grid v component
     * @return quarter turns from {@code +u}
     */
    private static int turns(int stepU, int stepV) {
        if (stepV == 0) {
            return stepU > 0 ? 0 : 2;
        }
        return stepV > 0 ? 1 : QUARTER_TURNS - 1;
    }

    /**
     * A directed vertex pair packed into one key.
     *
     * @param from first vertex
     * @param to   second vertex
     * @return the key
     */
    private static long directedEdge(int from, int to) {
        return (long) from * VERTICES + to;
    }
}
