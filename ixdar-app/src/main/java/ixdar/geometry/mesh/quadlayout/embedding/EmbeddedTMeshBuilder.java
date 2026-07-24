package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TMeshNode;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TMeshPatch;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceArc;

/**
 * Assembles an {@link EmbeddedTMesh} from the motorcycle-graph T-mesh, the ILP quantization and
 * the carve. Zero arcs and zero patches survive for {@link EmbeddedContraction} to remove.
 *
 * <p>Reuses the carve's working copy, re-keying its claims from source to embedded ids, so
 * exactly the arcs the patches bound must be added.
 */
public final class EmbeddedTMeshBuilder {

    public final LayoutEmbedding embedding;
    public final MotorcycleGraph motorcycleGraph;
    public final EmbeddedTMesh tmesh;

    /** Embedded node id for each source node id, or {@link EmbeddedTMesh#NONE} until added. */
    public final int[] embeddedNodeBySource;

    /** Embedded arc id for each source arc id, or {@link EmbeddedTMesh#NONE} until added. */
    public final int[] embeddedArcBySource;

    /** Surface Euler characteristic the assembled T-mesh must match. */
    public final int expectedEulerCharacteristic;

    /**
     * Prepares a builder over a finished carve. Call {@link #build()} to assemble the T-mesh.
     *
     * @param embedding the carve, with its topology, paths, quantization, and classifications
     */
    public EmbeddedTMeshBuilder(LayoutEmbedding embedding) {
        this.embedding = embedding;
        this.motorcycleGraph = embedding.motorcycleGraph;
        this.tmesh = new EmbeddedTMesh(embedding.topology);
        this.embeddedNodeBySource = new int[motorcycleGraph.nodes.size()];
        this.embeddedArcBySource = new int[motorcycleGraph.arcs.size()];
        Arrays.fill(embeddedNodeBySource, EmbeddedTMesh.NONE);
        Arrays.fill(embeddedArcBySource, EmbeddedTMesh.NONE);
        HalfEdgeMesh source = embedding.topology.sourceMesh;
        this.expectedEulerCharacteristic =
                source.vertexCount() - source.edgeCount() + source.faceCount();
    }

    /**
     * Assembles the T-mesh and validates it against the surface's Euler characteristic.
     *
     * @throws IllegalStateException when a patch is not a valid rectangle, an arc in a patch was
     *                               not carved, a node in an arc was not placed, or the assembled
     *                               complex is not a cell decomposition of the surface
     * @return the assembled, validated {@link EmbeddedTMesh}
     */
    public EmbeddedTMesh build() {
        for (TMeshPatch patch : motorcycleGraph.patches) {
            requireValidRectangle(patch);
            for (List<Integer> side : patch.sides) {
                for (int sourceArcId : side) {
                    ensureArc(sourceArcId);
                }
            }
        }
        for (TMeshPatch patch : motorcycleGraph.patches) {
            List<List<Integer>> sideArcIds = new ArrayList<>(EmbeddedPatch.SIDES);
            for (List<Integer> side : patch.sides) {
                List<Integer> embeddedSide = new ArrayList<>(side.size());
                for (int sourceArcId : side) {
                    embeddedSide.add(embeddedArcBySource[sourceArcId]);
                }
                sideArcIds.add(embeddedSide);
            }
            tmesh.addPatch(patch.patchId, sideArcIds, firstCorner(patch));
        }
        tmesh.resolveWalkOrientation();
        tmesh.validate(expectedEulerCharacteristic);
        return tmesh;
    }

    /**
     * Adds an arc and its two endpoint nodes if not already added.
     *
     * @param sourceArcId source arc id to add
     * @throws IllegalStateException when the arc was not carved or its carved path does not run
     *                               between its two nodes' vertices
     * @return the embedded arc id
     */
    private int ensureArc(int sourceArcId) {
        if (embeddedArcBySource[sourceArcId] != EmbeddedTMesh.NONE) {
            return embeddedArcBySource[sourceArcId];
        }
        TraceArc arc = motorcycleGraph.arcs.get(sourceArcId);
        int startNode = ensureNode(arc.startNodeId);
        int endNode = ensureNode(arc.endNodeId);
        ArcEdgePath carved = embedding.pathByArc[sourceArcId];
        if (carved == null) {
            throw new IllegalStateException("arc " + sourceArcId + " bounds a patch but was never"
                    + " carved; the carve and the patch structure disagree");
        }
        List<Integer> vertexPath = orientedPath(sourceArcId, carved, arc);
        int quantizedLength = embedding.quantization.quantizedLengthByArc[sourceArcId];
        boolean feature = embedding.featureByArc[sourceArcId];
        int embeddedArcId = tmesh.addArc(sourceArcId, startNode, endNode, quantizedLength, feature,
                vertexPath);
        embeddedArcBySource[sourceArcId] = embeddedArcId;
        return embeddedArcId;
    }

    /**
     * The carve's vertex path for an arc, oriented from the arc's start node's vertex to its end
     * node's vertex.
     *
     * @param sourceArcId arc the path belongs to, for the error message
     * @param carved      the carved path
     * @param arc         the source arc, for its node ids
     * @throws IllegalStateException when the path's ends are not the arc's two node vertices
     * @return the path's copy vertices, reversed if the carve laid them end-to-start
     */
    private List<Integer> orientedPath(int sourceArcId, ArcEdgePath carved, TraceArc arc) {
        int startVertex = embedding.vertexIdByNode[arc.startNodeId];
        int endVertex = embedding.vertexIdByNode[arc.endNodeId];
        List<Integer> path = carved.copyVertexPath;
        int first = path.get(0);
        int last = path.get(path.size() - 1);
        if (first == startVertex && last == endVertex) {
            return path;
        }
        if (first == endVertex && last == startVertex) {
            List<Integer> reversed = new ArrayList<>(path);
            Collections.reverse(reversed);
            return reversed;
        }
        throw new IllegalStateException("arc " + sourceArcId + " carved path runs " + first + ".."
                + last + " but its nodes sit on vertices " + startVertex + " and " + endVertex);
    }

    /**
     * Adds a node if not already added.
     *
     * @param sourceNodeId source node id to add
     * @throws IllegalStateException when the node was never placed on a copy vertex
     * @return the embedded node id
     */
    private int ensureNode(int sourceNodeId) {
        if (embeddedNodeBySource[sourceNodeId] != EmbeddedTMesh.NONE) {
            return embeddedNodeBySource[sourceNodeId];
        }
        int copyVertex = embedding.vertexIdByNode[sourceNodeId];
        if (copyVertex < 0) {
            throw new IllegalStateException("node " + sourceNodeId + " bounds an arc but was never"
                    + " placed on a copy vertex");
        }
        TMeshNode node = motorcycleGraph.nodes.get(sourceNodeId);
        boolean critical = embedding.criticalByNode[sourceNodeId];
        boolean border = node.type == TMeshNode.Type.BOUNDARY;
        int embeddedNodeId = tmesh.addNode(sourceNodeId, copyVertex, critical, border);
        embeddedNodeBySource[sourceNodeId] = embeddedNodeId;
        return embeddedNodeId;
    }

    /**
     * The node at which a patch's boundary walk starts — the corner shared by the last arc of the
     * final side and the first arc of the first side.
     *
     * @param patch patch to find the starting corner of
     * @return the embedded node id of that corner
     */
    private int firstCorner(TMeshPatch patch) {
        int firstSideFirstArc = embeddedArcBySource[patch.sides.get(0).get(0)];
        List<Integer> lastSide = patch.sides.get(EmbeddedPatch.SIDES - 1);
        int lastSideLastArc = embeddedArcBySource[lastSide.get(lastSide.size() - 1)];
        EmbeddedArc entering = tmesh.arcs.get(firstSideFirstArc);
        EmbeddedArc leaving = tmesh.arcs.get(lastSideLastArc);
        if (entering.startNodeId == leaving.startNodeId
                || entering.startNodeId == leaving.endNodeId) {
            return entering.startNodeId;
        }
        return entering.endNodeId;
    }

    /**
     * Requires a patch to be a valid rectangle with four sides.
     *
     * @param patch patch to check
     * @throws IllegalStateException when the patch is not a valid rectangle
     */
    private void requireValidRectangle(TMeshPatch patch) {
        if (!patch.validRectangle || patch.sides.size() != EmbeddedPatch.SIDES) {
            throw new IllegalStateException("patch " + patch.patchId + " is not a valid rectangle:"
                    + " validRectangle=" + patch.validRectangle + " sides=" + patch.sides.size());
        }
    }
}
