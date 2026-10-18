package ixdar.geometry.mesh.quadlayout.embedding.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.EarClipping;
import ixdar.numeric.exact.ExactBarycentricOrient;

/**
 * Working copy of the input mesh for the re-embedding, rebuilt into a fresh
 * {@link HalfEdgeMesh}; the source is never mutated.
 *
 * <p>
 * Provenance and ownership are layers of the copy; this class adds the barycentrics
 * and child lists, so all remeshing goes through it.
 *
 * <p>
 * See also: LCBK19 Section 6
 */
public final class EmbeddedMeshTopology {

    public static final int UNCLAIMED = -1;

    private static final int CORNERS = 3;

    private static final double EDGE_MIDPOINT = 0.5;

    public final HalfEdgeMesh sourceMesh;
    public final HalfEdgeMesh copy;

    /**
     * Copy vertices below this id are originals of the source mesh; the rest are
     * minted.
     */
    public final int originalVertexBound;

    /** Face layer of {@link #copy} holding each copy face's source active face index. */
    public final int sourceFaceLayer;

    /** Face layer of {@link #copy} holding the patch covering each copy face. */
    public final int patchLayer;

    /** Edge layer of {@link #copy} holding the source active edge each copy edge lies on. */
    public final int sourceEdgeLayer;

    /** Edge layer of {@link #copy} holding the arc owning each copy edge. */
    public final int ownerArcEdgeLayer;

    /** Vertex layer of {@link #copy} holding the T-mesh node owning each copy vertex. */
    public final int ownerNodeLayer;

    /**
     * Vertex layer of {@link #copy} holding the arc owning each interior path vertex; a vertex
     * minted on a split edge takes that edge's owner.
     */
    public final int ownerArcVertexLayer;

    /**
     * Whether the contraction has labeled the patch covers; until then, and after a labeling
     * it dropped as unusable, every face reads {@link #UNCLAIMED}.
     */
    public boolean patchCoversLabeled;

    /**
     * Union-find parent per patch id, so a patch absorbed by a neighbour keeps its
     * face labels and resolves to the survivor instead of being relabeled.
     */
    public int[] patchAliasByPatch = new int[0];

    /**
     * Copy face ids descending from each source active face, in insertion order,
     * which the child-face search relies on.
     */
    public final List<Set<Integer>> copyFacesBySourceFace;

    /** Edges a second arc tried to claim while another held them (the existing claim is kept). */
    public int claimConflictCount;

    /** The first such conflict, described for the report. */
    public String firstClaimConflict;

    /** Arc already holding the edge of {@link #firstClaimConflict}. */
    public int firstClaimConflictHolder = UNCLAIMED;

    /** Arc that wanted the edge of {@link #firstClaimConflict} as well. */
    public int firstClaimConflictClaimant = UNCLAIMED;

    private final Map<Integer, Integer> copyVertexBySourceVertexId = new HashMap<>();

    /**
     * Source active faces carrying a barycentric for each copy vertex, parallel
     * entry-for-entry to {@link #barycentricTriplesByVertex}. A vertex has one
     * entry per source face whose closure it lies in — a handful at most — so
     * lookups scan linearly with no hashing or boxing.
     */
    private final List<int[]> barycentricFacesByVertex = new ArrayList<>();

    /** Barycentric triples of each copy vertex, one per registered source face. */
    private final List<double[][]> barycentricTriplesByVertex = new ArrayList<>();

    /**
     * Build the working copy from the source mesh's active vertices and faces.
     *
     * @param sourceMesh input triangle mesh (read-only; never mutated)
     */
    public EmbeddedMeshTopology(HalfEdgeMesh sourceMesh) {
        this.sourceMesh = sourceMesh;
        int vertexCount = sourceMesh.vertexCount();
        this.originalVertexBound = vertexCount;
        int faceCount = sourceMesh.faceCount();
        float[] positions = new float[vertexCount * HalfEdgeMesh.FLOATS_PER_VERTEX];
        Map<Integer, Integer> denseBySourceVertexId = new HashMap<>(vertexCount * 2);
        Vector3f position = new Vector3f();
        for (int dense = 0; dense < vertexCount; dense++) {
            int sourceVertexId = sourceMesh.vertexIdAt(dense);
            sourceMesh.vertexPosition(sourceVertexId, position);
            positions[dense * HalfEdgeMesh.FLOATS_PER_VERTEX] = position.x;
            positions[dense * HalfEdgeMesh.FLOATS_PER_VERTEX + 1] = position.y;
            positions[dense * HalfEdgeMesh.FLOATS_PER_VERTEX + 2] = position.z;
            denseBySourceVertexId.put(sourceVertexId, dense);
            copyVertexBySourceVertexId.put(sourceVertexId, dense);
        }
        int[] faceIndices = new int[faceCount * HalfEdgeMesh.TRIANGLE_CORNERS];
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int sourceFaceId = sourceMesh.faceIdAt(activeFace);
            for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
                faceIndices[activeFace * HalfEdgeMesh.TRIANGLE_CORNERS + corner] = denseBySourceVertexId
                        .get(sourceMesh.faceVertexAt(sourceFaceId, corner));
            }
        }
        this.copy = HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faceIndices);
        sourceFaceLayer = copy.declareFaceLayer(UNCLAIMED);
        patchLayer = copy.declareFaceLayer(UNCLAIMED);
        sourceEdgeLayer = copy.declareEdgeLayer(UNCLAIMED);
        ownerArcEdgeLayer = copy.declareEdgeLayer(UNCLAIMED);
        ownerNodeLayer = copy.declareVertexLayer(UNCLAIMED, UNCLAIMED);
        ownerArcVertexLayer = copy.declareVertexLayer(UNCLAIMED, ownerArcEdgeLayer);

        int[] sourceFaceByCopyFace = sourceFaceByCopyFace();
        copyFacesBySourceFace = new ArrayList<>(faceCount);
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            sourceFaceByCopyFace[activeFace] = activeFace;
            Set<Integer> children = new LinkedHashSet<>();
            children.add(activeFace);
            copyFacesBySourceFace.add(children);
        }

        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            for (int corner = 0; corner < CORNERS; corner++) {
                double[] barycentric = new double[CORNERS];
                barycentric[corner] = 1.0;
                registerBarycentric(activeFace, copy.faceVertexAt(activeFace, corner), barycentric);
            }
        }
    }

    /**
     * Source active face index per raw copy face id, the {@link #sourceFaceLayer} array.
     *
     * @return the layer, indexed by copy face id
     */
    public int[] sourceFaceByCopyFace() {
        return copy.faceLayers[sourceFaceLayer];
    }

    /**
     * Patch id covering each raw copy face, or {@link #UNCLAIMED}, the {@link #patchLayer}
     * array; meaningful once {@link #patchCoversLabeled}.
     *
     * @return the layer, indexed by copy face id
     */
    public int[] patchByCopyFace() {
        return copy.faceLayers[patchLayer];
    }

    /**
     * Source active edge index per raw copy edge id, or {@link #UNCLAIMED} for a minted
     * interior edge, the {@link #sourceEdgeLayer} array.
     *
     * @return the layer, indexed by copy edge id
     */
    public int[] sourceEdgeByCopyEdge() {
        return copy.edgeLayers[sourceEdgeLayer];
    }

    /**
     * Owning arc id per raw copy edge id, or {@link #UNCLAIMED}, the {@link #ownerArcEdgeLayer}
     * array.
     *
     * @return the layer, indexed by copy edge id
     */
    public int[] ownerArcByCopyEdge() {
        return copy.edgeLayers[ownerArcEdgeLayer];
    }

    /**
     * Owning T-mesh node id per raw copy vertex id, or {@link #UNCLAIMED}, the
     * {@link #ownerNodeLayer} array.
     *
     * @return the layer, indexed by copy vertex id
     */
    public int[] ownerNodeByCopyVertex() {
        return copy.vertexLayers[ownerNodeLayer];
    }

    /**
     * Owning arc id per raw copy vertex id, or {@link #UNCLAIMED}, the
     * {@link #ownerArcVertexLayer} array.
     *
     * @return the layer, indexed by copy vertex id
     */
    public int[] ownerArcByCopyVertex() {
        return copy.vertexLayers[ownerArcVertexLayer];
    }

    /**
     * Barycentric coordinate of a copy vertex within a source active face.
     *
     * @param sourceFace source active face index
     * @param copyVertex copy vertex lying in that face's closure
     * @return the barycentric triple, or {@code null} when the vertex does not lie
     *         in that face's closure
     */
    public double[] barycentricOf(int sourceFace, int copyVertex) {
        if (copyVertex >= barycentricFacesByVertex.size()) {
            return null;
        }
        int[] faces = barycentricFacesByVertex.get(copyVertex);
        if (faces == null) {
            return null;
        }
        for (int index = 0; index < faces.length; index++) {
            if (faces[index] == sourceFace) {
                return barycentricTriplesByVertex.get(copyVertex)[index];
            }
        }
        return null;
    }

    /**
     * Record a copy vertex's barycentric coordinate within a source active face.
     *
     * @param sourceFace  source active face index
     * @param copyVertex  copy vertex lying in that face's closure
     * @param barycentric barycentric triple against the source face's corners
     */
    public void registerBarycentric(int sourceFace, int copyVertex, double[] barycentric) {
        while (barycentricFacesByVertex.size() <= copyVertex) {
            barycentricFacesByVertex.add(null);
            barycentricTriplesByVertex.add(null);
        }
        int[] faces = barycentricFacesByVertex.get(copyVertex);
        if (faces == null) {
            barycentricFacesByVertex.set(copyVertex, new int[] { sourceFace });
            barycentricTriplesByVertex.set(copyVertex, new double[][] { barycentric });
            return;
        }
        double[][] triples = barycentricTriplesByVertex.get(copyVertex);
        for (int index = 0; index < faces.length; index++) {
            if (faces[index] == sourceFace) {
                triples[index] = barycentric;
                return;
            }
        }
        int[] grownFaces = Arrays.copyOf(faces, faces.length + 1);
        double[][] grownTriples = Arrays.copyOf(triples, triples.length + 1);
        grownFaces[faces.length] = sourceFace;
        grownTriples[faces.length] = barycentric;
        barycentricFacesByVertex.set(copyVertex, grownFaces);
        barycentricTriplesByVertex.set(copyVertex, grownTriples);
    }

    /**
     * Lift a barycentric coordinate of a source active face to its 3D position on
     * the source surface. Every minted vertex is positioned this way, so the copy
     * mesh stays exactly on the source triangles however deeply it is refined.
     *
     * @param sourceFace  source active face index
     * @param barycentric barycentric triple against the source face's corners
     * @param destination receives the lifted position
     * @return {@code destination}
     */
    public Vector3f positionFromBarycentric(int sourceFace, double[] barycentric,
            Vector3f destination) {
        int sourceFaceId = sourceMesh.faceIdAt(sourceFace);
        destination.zero();
        Vector3f corner = new Vector3f();
        for (int index = 0; index < CORNERS; index++) {
            sourceMesh.vertexPosition(sourceMesh.faceVertexAt(sourceFaceId, index), corner);
            destination.fma((float) barycentric[index], corner);
        }
        return destination;
    }

    /**
     * Split a copy edge at an exact parameter along it, measured from the start
     * vertex of the edge's canonical half-edge. The minted vertex's barycentric is
     * interpolated in every source face incident to the edge and its position is
     * lifted from one of them, so the split introduces no geometric error.
     *
     * @param copyEdgeId copy edge to split
     * @param parameter  position along the edge in {@code (0, 1)}
     * @throws IllegalStateException when neither incident source face carries
     *                               barycentric coordinates for both endpoints
     * @return the minted vertex id
     */
    public int splitEdgeAtParameter(int copyEdgeId, double parameter) {
        int halfEdge = copy.edgeHalfEdge(copyEdgeId);
        int vertexA = copy.halfEdgeVertex(halfEdge);
        int vertexB = copy.halfEdgeEndVertex(halfEdge);
        int faceA = copy.edgeFace(copyEdgeId, 0);
        int faceB = copy.edgeFace(copyEdgeId, 1);
        int sourceA = faceA >= 0 ? sourceFaceByCopyFace()[faceA] : UNCLAIMED;
        int sourceB = faceB >= 0 ? sourceFaceByCopyFace()[faceB] : UNCLAIMED;
        double[] barycentricA = interpolateBarycentric(sourceA, vertexA, vertexB, parameter);
        double[] barycentricB = sourceB == sourceA
                ? null
                : interpolateBarycentric(sourceB, vertexA, vertexB, parameter);
        if (barycentricA == null && barycentricB == null) {
            throw new IllegalStateException("copy edge " + copyEdgeId
                    + " has no source face with barycentric coordinates for both endpoints");
        }
        int carrier = barycentricA != null ? sourceA : sourceB;
        double[] carried = barycentricA != null ? barycentricA : barycentricB;
        int firstNewFace = copy.nextFaceId;
        int newVertex = copy.splitEdge(copyEdgeId,
                positionFromBarycentric(carrier, carried, new Vector3f()));
        recordReplacement(new int[0], firstNewFace);
        if (barycentricA != null) {
            registerBarycentric(sourceA, newVertex, barycentricA);
        }
        if (barycentricB != null) {
            registerBarycentric(sourceB, newVertex, barycentricB);
        }
        return newVertex;
    }

    /**
     * Split a copy edge at its middle, the refinement split every re-route and
     * lane opening uses.
     *
     * @param copyEdgeId copy edge to split
     * @return the minted vertex id
     */
    public int splitEdgeAtMidpoint(int copyEdgeId) {
        return splitEdgeAtParameter(copyEdgeId, EDGE_MIDPOINT);
    }

    /**
     * Split a copy face at an exact barycentric coordinate of its source face.
     *
     * <p>
     * The point must lie <em>strictly inside</em> the face, since the split
     * registers a barycentric in one source face only. Use
     * {@link #splitEdgeAtParameter} for a point on the boundary.
     *
     * @param copyFaceId  copy face to split
     * @param barycentric barycentric triple against the source face's corners
     * @throws IllegalStateException when the point is not strictly inside the face
     * @return the minted vertex id
     */
    public int splitFaceAtBarycentric(int copyFaceId, double[] barycentric) {
        int sourceFace = sourceFaceByCopyFace()[copyFaceId];
        requireStrictlyInside(copyFaceId, sourceFace, barycentric);
        int firstNewFace = copy.nextFaceId;
        int newVertex = copy.splitFace(copyFaceId,
                positionFromBarycentric(sourceFace, barycentric, new Vector3f()));
        recordReplacement(new int[] { copyFaceId }, firstNewFace);
        registerBarycentric(sourceFace, newVertex, barycentric);
        return newVertex;
    }

    /**
     * Assert that a barycentric point of a source face lies strictly inside one of
     * that face's children, i.e. off every one of its three edges.
     *
     * @param copyFaceId  child face the point should be interior to
     * @param sourceFace  source active face the coordinates are relative to
     * @param barycentric the point's barycentric triple
     * @throws IllegalStateException when the point lies on or outside an edge
     */
    private void requireStrictlyInside(int copyFaceId, int sourceFace, double[] barycentric) {
        if (!strictlyInside(copyFaceId, sourceFace, barycentric)) {
            throw new IllegalStateException("copy face " + copyFaceId
                    + " cannot be split at " + Arrays.toString(barycentric)
                    + ": the point is not strictly inside it");
        }
    }

    /**
     * Whether a barycentric point of a source face lies strictly inside one of that
     * face's children, decided by the exact orientation predicate.
     *
     * @param copyFaceId  child face the point is tested against
     * @param sourceFace  source active face the coordinates are relative to
     * @param barycentric the point's barycentric triple
     * @return true when the point is strictly off every edge, on the inner side
     */
    public boolean strictlyInside(int copyFaceId, int sourceFace, double[] barycentric) {
        for (int corner = 0; corner < CORNERS; corner++) {
            double[] from = barycentricOf(sourceFace, copy.faceVertexAt(copyFaceId, corner));
            double[] to = barycentricOf(sourceFace,
                    copy.faceVertexAt(copyFaceId, (corner + 1) % CORNERS));
            if (from == null || to == null) {
                return false;
            }
            if (ExactBarycentricOrient.sign(from, to, barycentric) <= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Barycentric coordinate of a point splitting a copy edge, within one source
     * face.
     *
     * @param sourceFace source active face, or {@link #UNCLAIMED}
     * @param vertexA    edge start vertex
     * @param vertexB    edge end vertex
     * @param parameter  position along the edge from {@code vertexA}
     * @return the interpolated triple, or {@code null} when either endpoint has no
     *         coordinate in that face
     */
    private double[] interpolateBarycentric(int sourceFace, int vertexA, int vertexB,
            double parameter) {
        if (sourceFace == UNCLAIMED) {
            return null;
        }
        double[] fromA = barycentricOf(sourceFace, vertexA);
        double[] fromB = barycentricOf(sourceFace, vertexB);
        if (fromA == null || fromB == null) {
            return null;
        }
        double[] blended = new double[CORNERS];
        for (int index = 0; index < CORNERS; index++) {
            blended[index] = fromA[index] + parameter * (fromB[index] - fromA[index]);
        }
        return blended;
    }

    /**
     * Copy vertex corresponding to a source mesh vertex.
     *
     * @param sourceVertexId raw source vertex id
     * @return copy vertex id, or {@link #UNCLAIMED} when unknown
     */
    public int copyVertexForSourceVertexId(int sourceVertexId) {
        Integer copyVertex = copyVertexBySourceVertexId.get(sourceVertexId);
        return copyVertex == null ? UNCLAIMED : copyVertex;
    }

    /**
     * Flip a free untagged diagonal shared by two children of one source face,
     * replacing it with the opposite diagonal (LCBK19 §6.1 chord insertion).
     *
     * @param copyEdgeId unclaimed, untagged interior diagonal to flip
     * @return the new diagonal's edge id
     */
    public int flipEdge(int copyEdgeId) {
        int[] retired = { copy.edgeFace(copyEdgeId, 0), copy.edgeFace(copyEdgeId, 1) };
        int firstNewFace = copy.nextFaceId;
        int diagonal = copy.flipEdge(copyEdgeId);
        recordReplacement(retired, firstNewFace);
        return diagonal;
    }

    /**
     * Brings the child lists in step with an operator on the copy: the retired faces leave
     * their source face's list and every face minted since {@code firstNewFace} joins its
     * own in id order, which is the order they were added.
     *
     * @param retiredFaceIds faces the operator removed
     * @param firstNewFace   the copy's next face id before the operator ran
     */
    private void recordReplacement(int[] retiredFaceIds, int firstNewFace) {
        int[] sourceFaceByCopyFace = sourceFaceByCopyFace();
        for (int retired : retiredFaceIds) {
            copyFacesBySourceFace.get(sourceFaceByCopyFace[retired]).remove(Integer.valueOf(retired));
        }
        for (int faceId = firstNewFace; faceId < copy.nextFaceId; faceId++) {
            copyFacesBySourceFace.get(sourceFaceByCopyFace[faceId]).add(faceId);
        }
    }

    /**
     * Join two vertices of one source face by an edge, retiring the strip of child faces
     * the segment crosses and rebuilding it with the segment as an edge (LCBK19 §6.1 arc
     * snapping). Mints no vertex and leaves the face count unchanged.
     *
     * @param sourceFace source active face both vertices lie in the closure of
     * @param fromVertex copy vertex the chord leaves
     * @param toVertex   copy vertex the chord reaches
     * @param arcId      arc the chord carries, claimed on every edge it lays down
     * @throws IllegalStateException when the segment would cross a source edge or a chord
     *                               another arc holds, so the chords crossing this face
     *                               are not a non-crossing family
     * @return the copy vertices from {@code fromVertex} to {@code toVertex} inclusive
     */
    public List<Integer> insertChord(int sourceFace, int fromVertex, int toVertex, int arcId) {
        List<Integer> chain = new ArrayList<>();
        chain.add(fromVertex);
        int head = fromVertex;
        while (head != toVertex) {
            head = layStraightSegment(sourceFace, head, toVertex, arcId);
            chain.add(head);
        }
        return chain;
    }

    /**
     * Lays the piece of a chord running from one vertex up to the first vertex the segment
     * meets, which is the target itself unless the segment passes exactly through another.
     *
     * @param sourceFace source active face the segment runs in
     * @param fromVertex copy vertex the piece leaves
     * @param toVertex   copy vertex the whole chord aims at
     * @param arcId      arc the chord carries
     * @return the copy vertex this piece reached
     */
    private int layStraightSegment(int sourceFace, int fromVertex, int toVertex, int arcId) {
        List<Integer> crossedFaces = new ArrayList<>();
        List<Integer> leftChain = new ArrayList<>();
        List<Integer> rightChain = new ArrayList<>();
        int reached = walkStrip(sourceFace, fromVertex, toVertex, crossedFaces, leftChain,
                rightChain);
        if (!crossedFaces.isEmpty()) {
            rebuildStrip(sourceFace, fromVertex, reached, crossedFaces, leftChain, rightChain);
        }
        claimEdgeBetween(fromVertex, reached, arcId);
        return reached;
    }

    /**
     * Walks the child faces the segment crosses, collecting them and the vertices it leaves
     * to either side. Every decision is an exact orientation, so a segment running exactly
     * through a vertex ends the walk there rather than being nudged past it.
     *
     * @param sourceFace  source active face the segment runs in
     * @param fromVertex  copy vertex the segment leaves
     * @param toVertex    copy vertex the segment aims at
     * @param crossedFaces receives the crossed child faces, in travel order
     * @param leftChain   receives the vertices left of the segment, in travel order
     * @param rightChain  receives the vertices right of the segment, in travel order
     * @throws IllegalStateException when the walk leaves the source face or runs longer
     *                               than the face has children
     * @return the copy vertex the walk reached
     */
    private int walkStrip(int sourceFace, int fromVertex, int toVertex,
            List<Integer> crossedFaces, List<Integer> leftChain, List<Integer> rightChain) {
        double[] from = requireBarycentric(sourceFace, fromVertex);
        double[] to = requireBarycentric(sourceFace, toVertex);
        if (copy.edgeBetween(fromVertex, toVertex) != UNCLAIMED) {
            return toVertex;
        }
        int face = enteredFace(sourceFace, fromVertex, from, to);
        int left = UNCLAIMED;
        int right = UNCLAIMED;
        for (int corner = 0; corner < CORNERS; corner++) {
            int vertexId = copy.faceVertexAt(face, corner);
            if (vertexId == fromVertex) {
                continue;
            }
            int side = ExactBarycentricOrient.sign(from, to,
                    requireBarycentric(sourceFace, vertexId));
            if (side == 0) {
                return vertexId;
            }
            left = side > 0 ? vertexId : left;
            right = side < 0 ? vertexId : right;
        }
        crossedFaces.add(face);
        leftChain.add(left);
        rightChain.add(right);
        int bound = copyFacesBySourceFace.get(sourceFace).size();
        while (crossedFaces.size() <= bound) {
            face = acrossFreeEdge(face, left, right, sourceFace);
            crossedFaces.add(face);
            int opposite = copy.faceOppositeCorner(face, left, right);
            if (opposite == toVertex) {
                return toVertex;
            }
            int side = ExactBarycentricOrient.sign(from, to,
                    requireBarycentric(sourceFace, opposite));
            if (side == 0) {
                return opposite;
            }
            if (side > 0) {
                left = opposite;
                leftChain.add(opposite);
            } else {
                right = opposite;
                rightChain.add(opposite);
            }
        }
        throw new IllegalStateException("the chord from copy vertex " + fromVertex + " to "
                + toVertex + " crossed more than the " + bound + " children of source face "
                + sourceFace + ", so the walk is not converging on its target");
    }

    /**
     * The child face at a vertex whose interior the segment enters, found by the wedge its
     * two other corners span.
     *
     * @param sourceFace source active face the segment runs in
     * @param fromVertex copy vertex the segment leaves
     * @param from       that vertex's barycentric
     * @param to         the target's barycentric
     * @throws IllegalStateException when no child face at the vertex opens toward the target
     * @return the child face the segment enters
     */
    private int enteredFace(int sourceFace, int fromVertex, double[] from, double[] to) {
        for (int index = 0; index < copy.vertexFaceCount(fromVertex); index++) {
            int face = copy.vertexFaceAt(fromVertex, index);
            if (sourceFaceByCopyFace()[face] != sourceFace) {
                continue;
            }
            int at = 0;
            while (copy.faceVertexAt(face, at) != fromVertex) {
                at++;
            }
            double[] ahead = requireBarycentric(sourceFace,
                    copy.faceVertexAt(face, (at + 1) % CORNERS));
            double[] behind = requireBarycentric(sourceFace,
                    copy.faceVertexAt(face, (at + 2) % CORNERS));
            if (ExactBarycentricOrient.sign(from, ahead, to) >= 0
                    && ExactBarycentricOrient.sign(from, to, behind) >= 0) {
                return face;
            }
        }
        throw new IllegalStateException("no child face of source face " + sourceFace + " at copy"
                + " vertex " + fromVertex + " opens toward the chord's target");
    }

    /**
     * The child face across an edge the chord may cross. A source edge or an edge another
     * arc holds may not be crossed: the chords through one face are a non-crossing family
     * by construction, so meeting one means the construction upstream is wrong.
     *
     * @param face       child face the chord is leaving
     * @param left       endpoint of the crossed edge left of the segment
     * @param right      endpoint of the crossed edge right of the segment
     * @param sourceFace source active face the segment runs in
     * @throws IllegalStateException when the edge is tagged, claimed, or has no far face
     * @return the child face on the far side
     */
    private int acrossFreeEdge(int face, int left, int right, int sourceFace) {
        int edgeId = copy.edgeBetween(left, right);
        if (sourceEdgeByCopyEdge()[edgeId] != UNCLAIMED) {
            throw new IllegalStateException("a chord in source face " + sourceFace + " would"
                    + " cross copy edge " + edgeId + ", which lies on source edge "
                    + sourceEdgeByCopyEdge()[edgeId]);
        }
        if (ownerArcByCopyEdge()[edgeId] != UNCLAIMED) {
            throw new IllegalStateException("a chord in source face " + sourceFace + " would"
                    + " cross copy edge " + edgeId + ", already held by arc "
                    + ownerArcByCopyEdge()[edgeId]);
        }
        int farSide = copy.faceAcrossEdge(face, edgeId);
        if (farSide == UNCLAIMED || sourceFaceByCopyFace()[farSide] != sourceFace) {
            throw new IllegalStateException("a chord in source face " + sourceFace + " would"
                    + " leave it across copy edge " + edgeId);
        }
        return farSide;
    }

    /**
     * Replaces the crossed strip with a triangulation of the two polygons either side of
     * the chord. The strip's union has {@code crossed + 2} vertices, so the rebuild yields
     * {@code crossed} triangles and the face count is unchanged.
     *
     * @param sourceFace   source active face the strip belongs to
     * @param fromVertex   copy vertex the chord leaves
     * @param toVertex     copy vertex the chord reaches
     * @param crossedFaces the crossed child faces, in travel order
     * @param leftChain    vertices left of the segment, in travel order
     * @param rightChain   vertices right of the segment, in travel order
     */
    private void rebuildStrip(int sourceFace, int fromVertex, int toVertex,
            List<Integer> crossedFaces, List<Integer> leftChain, List<Integer> rightChain) {
        List<Integer> copyVertices = new ArrayList<>();
        copyVertices.add(fromVertex);
        copyVertices.add(toVertex);
        copyVertices.addAll(rightChain);
        copyVertices.addAll(leftChain);
        double[][] barycentric = new double[copyVertices.size()][];
        for (int local = 0; local < copyVertices.size(); local++) {
            barycentric[local] = requireBarycentric(sourceFace, copyVertices.get(local));
        }
        List<Integer> rightSide = new ArrayList<>();
        rightSide.add(0);
        for (int index = 0; index < rightChain.size(); index++) {
            rightSide.add(2 + index);
        }
        rightSide.add(1);
        List<Integer> leftSide = new ArrayList<>();
        leftSide.add(1);
        for (int index = leftChain.size() - 1; index >= 0; index--) {
            leftSide.add(2 + rightChain.size() + index);
        }
        leftSide.add(0);
        List<int[]> triangles = new EarClipping(barycentric, rightSide).build().triangles;
        triangles.addAll(new EarClipping(barycentric, leftSide).build().triangles);
        int[] retired = new int[crossedFaces.size()];
        for (int index = 0; index < retired.length; index++) {
            retired[index] = crossedFaces.get(index);
        }
        int[] corners = new int[triangles.size() * CORNERS];
        for (int index = 0; index < corners.length; index++) {
            corners[index] = copyVertices.get(triangles.get(index / CORNERS)[index % CORNERS]);
        }
        int firstNewFace = copy.nextFaceId;
        copy.replaceFaces(retired[0], retired, corners);
        recordReplacement(retired, firstNewFace);
    }

    /**
     * The barycentric a copy vertex must have in a source face for the chord walk to place
     * it.
     *
     * @param sourceFace source active face
     * @param copyVertex copy vertex expected to lie in its closure
     * @throws IllegalStateException when the vertex carries no coordinate there
     * @return its barycentric triple
     */
    private double[] requireBarycentric(int sourceFace, int copyVertex) {
        double[] barycentric = barycentricOf(sourceFace, copyVertex);
        if (barycentric == null) {
            throw new IllegalStateException("copy vertex " + copyVertex + " has no barycentric"
                    + " in source face " + sourceFace + ", so no chord there can reach it");
        }
        return barycentric;
    }

    /**
     * Claim an embedded path's edges and interior vertices for its arc. Endpoint
     * vertices are left alone: they belong to the T-mesh nodes the arc runs
     * between.
     *
     * @param arcId owning arc
     * @param path  the arc's embedded path
     */
    public void claimPath(int arcId, ArcEdgePath path) {
        int[] ownerArcByCopyEdge = ownerArcByCopyEdge();
        for (int edgeId : path.copyEdgePath) {
            ownerArcByCopyEdge[edgeId] = arcId;
        }
        int[] ownerArcByCopyVertex = ownerArcByCopyVertex();
        for (int index = 1; index < path.copyVertexPath.size() - 1; index++) {
            ownerArcByCopyVertex[path.copyVertexPath.get(index)] = arcId;
        }
    }

    /**
     * Claim the edge between two copy vertices for an arc; an existing claim by a
     * different arc is kept and counted as a conflict.
     *
     * @param vertexA first endpoint
     * @param vertexB second endpoint
     * @param arcId   claiming arc
     */
    public void claimEdgeBetween(int vertexA, int vertexB, int arcId) {
        int edgeId = copy.edgeBetween(vertexA, vertexB);
        if (edgeId == UNCLAIMED) {
            return;
        }
        int[] ownerArcByCopyEdge = ownerArcByCopyEdge();
        if (ownerArcByCopyEdge[edgeId] != UNCLAIMED && ownerArcByCopyEdge[edgeId] != arcId) {
            claimConflictCount++;
            if (firstClaimConflict == null) {
                firstClaimConflictHolder = ownerArcByCopyEdge[edgeId];
                firstClaimConflictClaimant = arcId;
                firstClaimConflict = "copy edge " + edgeId + " from " + describeVertex(vertexA)
                        + " to " + describeVertex(vertexB) + " is held by arc "
                        + ownerArcByCopyEdge[edgeId] + " and arc " + arcId + " wants it too";
            }
            return;
        }
        ownerArcByCopyEdge[edgeId] = arcId;
    }

    /**
     * Names a copy vertex with its provenance and owner, for a conflict report.
     *
     * @param copyVertex vertex to describe
     * @return the description
     */
    public String describeVertex(int copyVertex) {
        return "vertex " + copyVertex
                + (copyVertex < originalVertexBound ? "(original" : "(minted")
                + ",node" + ownerNodeByCopyVertex()[copyVertex]
                + ",arc" + ownerArcByCopyVertex()[copyVertex] + ")";
    }

    /**
     * The source face holding the edge between two copy vertices, read from the faces on
     * either side of it, so the segment between them really is a chord of that face.
     *
     * @param fromVertex one endpoint
     * @param toVertex   the other endpoint
     * @return that source face, or {@link #UNCLAIMED} when they share no edge or no face
     *         registers a barycentric for both
     */
    public int sharedSourceFace(int fromVertex, int toVertex) {
        int copyEdge = copy.edgeBetween(fromVertex, toVertex);
        if (copyEdge == UNCLAIMED) {
            return UNCLAIMED;
        }
        for (int side = 0; side < 2; side++) {
            int copyFace = copy.edgeFace(copyEdge, side);
            if (copyFace == UNCLAIMED) {
                continue;
            }
            int sourceFace = sourceFaceByCopyFace()[copyFace];
            if (barycentricOf(sourceFace, fromVertex) != null
                    && barycentricOf(sourceFace, toVertex) != null) {
                return sourceFace;
            }
        }
        return UNCLAIMED;
    }

    /**
     * The patch covering a copy face, or {@link #UNCLAIMED} when the covers are
     * unlabeled, the face lies off every patch interior, or there is no face.
     *
     * @param copyFaceId copy face to read, or {@link #UNCLAIMED}
     * @return its patch id, or {@link #UNCLAIMED}
     */
    public int patchLabelOf(int copyFaceId) {
        return copyFaceId == UNCLAIMED ? UNCLAIMED : patchByCopyFace()[copyFaceId];
    }

    /**
     * The live patch an absorbed patch id resolves to, with path halving.
     *
     * @param patchId patch id to resolve, or {@link #UNCLAIMED}
     * @return the surviving patch id, or {@link #UNCLAIMED}
     */
    public int resolvePatch(int patchId) {
        if (patchId < 0 || patchId >= patchAliasByPatch.length) {
            return patchId;
        }
        int walk = patchId;
        while (patchAliasByPatch[walk] != walk) {
            patchAliasByPatch[walk] = patchAliasByPatch[patchAliasByPatch[walk]];
            walk = patchAliasByPatch[walk];
        }
        return walk;
    }

    /**
     * Records that one patch's cover has been absorbed by another, so its face
     * labels resolve to the survivor without a re-flood.
     *
     * @param absorbedPatchId patch whose faces now belong to the survivor
     * @param survivorPatchId patch taking them over
     * @param patchCount      patch ids minted so far, which the table must span
     */
    public void aliasPatchInto(int absorbedPatchId, int survivorPatchId, int patchCount) {
        if (patchAliasByPatch.length == 0 || absorbedPatchId < 0 || survivorPatchId < 0) {
            return;
        }
        if (patchAliasByPatch.length < patchCount) {
            int oldLength = patchAliasByPatch.length;
            patchAliasByPatch = Arrays.copyOf(patchAliasByPatch, patchCount);
            for (int patchId = oldLength; patchId < patchCount; patchId++) {
                patchAliasByPatch[patchId] = patchId;
            }
        }
        int absorbed = resolvePatch(absorbedPatchId);
        int survivor = resolvePatch(survivorPatchId);
        if (absorbed != survivor) {
            patchAliasByPatch[absorbed] = survivor;
        }
    }

}
