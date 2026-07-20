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
 * Assembles an {@link EmbeddedTMesh} from the real pipeline: the motorcycle-graph T-mesh (nodes,
 * arcs, four-sided patches), the ILP quantization (an integer length per arc), and the carve (an
 * {@link ArcEdgePath} per arc, plus the node→copy-vertex correspondence and per-node/per-arc
 * classification). This is the raw quantized T-mesh — zero arcs and zero patches present — that
 * the {@link EmbeddedContraction} operators then re-embed, so it replaces the old combinatorial
 * extraction/T-junction/contraction fork with the LCBK19 §6.1 operators instead.
 *
 * <p>It reuses the carve's own working copy ({@link LayoutEmbedding#topology}) rather than a fresh
 * one, because the carved paths reference copy vertices the carve created by splitting, which only
 * exist in that copy. The carve claimed the copy keyed by source ids; as each arc is added the
 * claims are re-keyed to the embedded ids, so every arc in a patch must be added or a stale claim
 * would remain — which is why the builder adds exactly the arcs the patches bound, and their
 * endpoint nodes, and nothing dangling.
 *
 * <p>Every patch must be a valid rectangle (one boundary cycle, four corners). A patch that is not
 * throws rather than being dropped: a dropped patch is a hole in the partition that the region
 * flood-fill would later report as a tear, far from its cause.
 */
public final class EmbeddedTMeshBuilder {

    /** Message fragment naming an arc. */
    private static final String ARC = "arc ";

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
     * @return the assembled, validated {@link EmbeddedTMesh}
     * @throws IllegalStateException when a patch is not a valid rectangle, an arc in a patch was
     *                               not carved, a node in an arc was not placed, or the assembled
     *                               complex is not a cell decomposition of the surface
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
        tmesh.validate(expectedEulerCharacteristic);
        return tmesh;
    }

    /**
     * Adds an arc and its two endpoint nodes if not already added.
     *
     * @param sourceArcId source arc id to add
     * @return the embedded arc id
     * @throws IllegalStateException when the arc was not carved or its carved path does not run
     *                               between its two nodes' vertices
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
            throw new IllegalStateException(ARC + sourceArcId + " bounds a patch but was never"
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
     * @return the path's copy vertices, reversed if the carve laid them end-to-start
     * @throws IllegalStateException when the path's ends are not the arc's two node vertices
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
        throw new IllegalStateException(ARC + sourceArcId + " carved path runs " + first + ".."
                + last + " but its nodes sit on vertices " + startVertex + " and " + endVertex);
    }

    /**
     * Adds a node if not already added.
     *
     * @param sourceNodeId source node id to add
     * @return the embedded node id
     * @throws IllegalStateException when the node was never placed on a copy vertex
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
