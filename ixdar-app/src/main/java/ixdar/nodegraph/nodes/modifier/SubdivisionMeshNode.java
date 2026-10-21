package ixdar.nodegraph.nodes.modifier;

import java.util.Arrays;
import java.util.List;

import java.util.Map;
import org.joml.Vector3f;

import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.bundle.GeometryBundles;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;

@MeshNodeAnnotation(id = "subdivision_surface", category = NodeCategory.MODIFIERS)
public class SubdivisionMeshNode implements MeshNode {

    public static final InputPort MESH_IN = new InputPort("mesh", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort GEOMETRY_IN = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort LEVELS = new InputPort("levels", PortType.INT, 1, 0f, 6f);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY_IN.name, PortType.GEOMETRY_BUNDLE);

    private float[] levelCreaseWeights;

    @Override
    public List<InputPort> inputs() {
        return List.of(MESH_IN, GEOMETRY_IN, LEVELS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Applies Catmull-Clark subdivision surface smoothing, supporting crease weights for semi-sharp edges. Handles mixed tri/quad input.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                MESH_IN.name, "Plain topology input (alternative to geometry). Used when no crease weights need to be read.",
                GEOMETRY_IN.name, "Input/output geometry bundle. Carries crease weights written by mark_crease so semi-sharp edges subdivide smoothly.",
                LEVELS.name, "Catmull-Clark iterations. Each level quadruples face count AND smooths toward the limit surface."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        // Accept geometry bundle (with crease weights) or plain mesh
        GeometryBundle bundle = ctx.getInput(GEOMETRY_IN.name, GeometryBundle.class);
        MeshTopology mesh = GeometryBundles.meshPart(bundle);
        if (mesh == null) {
            mesh = GeometryBundles.meshPart(ctx.getInput(MESH_IN.name, GeometryBundle.class));
        }

        Number levelsInput = ctx.getInput(LEVELS.name, Number.class);
        int levels = levelsInput == null ? 1 : Math.max(0, levelsInput.intValue());

        if (mesh == null) {
            ctx.setOutput(GEOMETRY_IN.name, GeometryBundle.empty());
            return;
        }

        if (levels == 0) {
            ctx.setOutput(GEOMETRY_IN.name, bundle != null ? bundle : GeometryBundle.ofMesh(mesh));
            return;
        }

        // Crease weights are indexed by edge id; each level leaves the next level's in
        // levelCreaseWeights, indexed by the edge ids of the mesh it returns.
        float[] creaseWeights = bundle != null
                ? EdgeMarks.floats(bundle, MarkEdgesNode.CREASE_LABEL)
                : null;
        HalfEdgeMesh quads = applyCatmullClarkLevel(mesh, creaseWeights);
        creaseWeights = levelCreaseWeights;
        for (int level = 1; level < levels; level++) {
            quads = applyCatmullClarkLevel(quads, creaseWeights);
            creaseWeights = levelCreaseWeights;
        }

        quads.computeNormals();

        GeometryBundle outBundle = GeometryBundle.ofMesh(quads);
        if (creaseWeights != null) {
            outBundle = EdgeMarks.with(outBundle, MarkEdgesNode.CREASE_LABEL, creaseWeights);
        }
        ctx.setOutput(GEOMETRY_IN.name, outBundle);
    }

    private static float creaseWeight(float[] creaseWeights, int edgeId) {
        if (creaseWeights == null || edgeId < 0 || edgeId >= creaseWeights.length) {
            return 0f;
        }
        return creaseWeights[edgeId];
    }

    /**
     * One crease-aware Catmull-Clark level over faces of any corner count, read in place; leaves
     * the output's crease weights, each parent weight less one, in {@link #levelCreaseWeights}.
     *
     * @param mesh          source mesh
     * @param creaseWeights crease weight per source edge id, or null
     * @return the all-quad mesh: face points in active-face order, edge points in the order edges
     *         first appear walking the faces, vertex points in active-vertex order; normals not
     *         computed
     */
    private HalfEdgeMesh applyCatmullClarkLevel(MeshTopology mesh, float[] creaseWeights) {
        int nv = mesh.vertexCount();
        int nf = mesh.faceCount();
        int[] activeVertexOfId = new int[mesh.vertexIdBound()];
        float[] positions = new float[nv * 3];
        Vector3f position = new Vector3f();
        for (int activeVertex = 0; activeVertex < nv; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            activeVertexOfId[vertexId] = activeVertex;
            mesh.vertexPosition(vertexId, position);
            positions[activeVertex * 3] = position.x;
            positions[activeVertex * 3 + 1] = position.y;
            positions[activeVertex * 3 + 2] = position.z;
        }

        int cornerCount = 0;
        for (int activeFace = 0; activeFace < nf; activeFace++) {
            cornerCount += mesh.faceVertexCount(mesh.faceIdAt(activeFace));
        }

        // --- Face centers, and each edge's first and second face in order of appearance ---
        int[] faceStart = new int[nf + 1];
        int[] cornerVertex = new int[cornerCount];
        int[] cornerEdge = new int[cornerCount];
        int[] appearanceOfEdge = new int[mesh.edgeIdBound()];
        Arrays.fill(appearanceOfEdge, MeshTopology.NONE);
        int[] edgeIdOfAppearance = new int[cornerCount];
        int[] edgeStart = new int[cornerCount];
        int[] edgeEnd = new int[cornerCount];
        int[] edgeFirstFace = new int[cornerCount];
        int[] edgeSecondFace = new int[cornerCount];
        float[] faceCenter = new float[nf * 3];
        int ne = 0;
        int corner = 0;
        for (int activeFace = 0; activeFace < nf; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int corners = mesh.faceVertexCount(faceId);
            faceStart[activeFace] = corner;
            float sx = 0f, sy = 0f, sz = 0f;
            for (int slot = 0; slot < corners; slot++) {
                int vertexId = mesh.faceVertexAt(faceId, slot);
                int nextVertexId = mesh.faceVertexAt(faceId, (slot + 1) % corners);
                int activeVertex = activeVertexOfId[vertexId];
                sx += positions[activeVertex * 3];
                sy += positions[activeVertex * 3 + 1];
                sz += positions[activeVertex * 3 + 2];
                int edgeId = mesh.edgeBetween(vertexId, nextVertexId);
                int appearance = appearanceOfEdge[edgeId];
                if (appearance == MeshTopology.NONE) {
                    appearance = ne++;
                    appearanceOfEdge[edgeId] = appearance;
                    edgeIdOfAppearance[appearance] = edgeId;
                    edgeStart[appearance] = activeVertex;
                    edgeEnd[appearance] = activeVertexOfId[nextVertexId];
                    edgeFirstFace[appearance] = activeFace;
                    edgeSecondFace[appearance] = MeshTopology.NONE;
                } else {
                    edgeSecondFace[appearance] = activeFace;
                }
                cornerVertex[corner] = activeVertex;
                cornerEdge[corner++] = appearance;
            }
            float inv = 1.0f / corners;
            faceCenter[activeFace * 3] = sx * inv;
            faceCenter[activeFace * 3 + 1] = sy * inv;
            faceCenter[activeFace * 3 + 2] = sz * inv;
        }
        faceStart[nf] = corner;

        // --- Edge points (crease-aware); per-vertex edge sums accumulate in appearance order ---
        float[] edgePos = new float[ne * 3];
        boolean[] boundary = new boolean[nv];
        int[] creasedEdgeCount = new int[nv];
        float[] maxCreaseWeight = new float[nv];
        float[] ringSum = new float[nv * 3];
        int[] creaseNeighbours = new int[nv];
        float[] creaseNeighbourSum = new float[nv * 3];
        for (int i = 0; i < ne; i++) {
            int va = edgeStart[i];
            int vb = edgeEnd[i];
            int o1 = va * 3, o2 = vb * 3;

            float x1 = positions[o1], y1 = positions[o1 + 1], z1 = positions[o1 + 2];
            float x2 = positions[o2], y2 = positions[o2 + 1], z2 = positions[o2 + 2];

            int f1 = edgeFirstFace[i];
            int f2 = edgeSecondFace[i];

            float w = creaseWeight(creaseWeights, edgeIdOfAppearance[i]);
            int eo = i * 3;

            if (w >= 1.0f) {
                // Creased edge: use simple midpoint
                edgePos[eo] = (x1 + x2) * 0.5f;
                edgePos[eo + 1] = (y1 + y2) * 0.5f;
                edgePos[eo + 2] = (z1 + z2) * 0.5f;
            } else if (f2 != MeshTopology.NONE) {
                // Interior smooth edge: 4-point average
                int fc1 = f1 * 3, fc2 = f2 * 3;
                float sx = (x1 + x2 + faceCenter[fc1] + faceCenter[fc2]) * 0.25f;
                float sy = (y1 + y2 + faceCenter[fc1 + 1] + faceCenter[fc2 + 1]) * 0.25f;
                float sz = (z1 + z2 + faceCenter[fc1 + 2] + faceCenter[fc2 + 2]) * 0.25f;

                if (w > 0f) {
                    // Semi-sharp: blend between smooth and crease
                    float mx = (x1 + x2) * 0.5f;
                    float my = (y1 + y2) * 0.5f;
                    float mz = (z1 + z2) * 0.5f;
                    edgePos[eo] = sx + (mx - sx) * w;
                    edgePos[eo + 1] = sy + (my - sy) * w;
                    edgePos[eo + 2] = sz + (mz - sz) * w;
                } else {
                    edgePos[eo] = sx;
                    edgePos[eo + 1] = sy;
                    edgePos[eo + 2] = sz;
                }
            } else {
                // Boundary edge: midpoint
                edgePos[eo] = (x1 + x2) * 0.5f;
                edgePos[eo + 1] = (y1 + y2) * 0.5f;
                edgePos[eo + 2] = (z1 + z2) * 0.5f;
            }

            for (int end = 0; end < 2; end++) {
                int activeVertex = end == 0 ? va : vb;
                int otherVertex = end == 0 ? vb : va;
                int vo = activeVertex * 3;
                int oo = otherVertex * 3;
                if (f2 == MeshTopology.NONE) {
                    boundary[activeVertex] = true;
                } else if (w > 0f) {
                    creasedEdgeCount[activeVertex]++;
                    maxCreaseWeight[activeVertex] = Math.max(maxCreaseWeight[activeVertex], w);
                }
                ringSum[vo] += (x1 + x2) * 0.5f;
                ringSum[vo + 1] += (y1 + y2) * 0.5f;
                ringSum[vo + 2] += (z1 + z2) * 0.5f;
                if (w > 0f && creaseNeighbours[activeVertex] < 2) {
                    creaseNeighbourSum[vo] += positions[oo];
                    creaseNeighbourSum[vo + 1] += positions[oo + 1];
                    creaseNeighbourSum[vo + 2] += positions[oo + 2];
                    creaseNeighbours[activeVertex]++;
                }
            }
        }

        // --- Vertex points (crease-aware); face sums accumulate in active-face order ---
        int[] facesAtVertex = new int[nv];
        float[] faceSum = new float[nv * 3];
        for (int activeFace = 0; activeFace < nf; activeFace++) {
            int fo = activeFace * 3;
            for (int c = faceStart[activeFace]; c < faceStart[activeFace + 1]; c++) {
                facesAtVertex[cornerVertex[c]]++;
                int vo = cornerVertex[c] * 3;
                faceSum[vo] += faceCenter[fo];
                faceSum[vo + 1] += faceCenter[fo + 1];
                faceSum[vo + 2] += faceCenter[fo + 2];
            }
        }
        float[] vtxPos = new float[nv * 3];
        for (int i = 0; i < nv; i++) {
            int vo = i * 3;
            float ox = positions[vo], oy = positions[vo + 1], oz = positions[vo + 2];
            int n = facesAtVertex[i];
            if (boundary[i] || n == 0 || creasedEdgeCount[i] >= 3) {
                // Boundary, isolated, or corner (3+ creased edges): keep original position
                vtxPos[vo] = ox;
                vtxPos[vo + 1] = oy;
                vtxPos[vo + 2] = oz;
                continue;
            }

            // Smooth CC vertex position
            float invN = 1.0f / n;
            float fx = faceSum[vo] * invN;
            float fy = faceSum[vo + 1] * invN;
            float fz = faceSum[vo + 2] * invN;
            float rx = ringSum[vo] * invN;
            float ry = ringSum[vo + 1] * invN;
            float rz = ringSum[vo + 2] * invN;

            float smoothX = (fx + 2f * rx + (n - 3) * ox) * invN;
            float smoothY = (fy + 2f * ry + (n - 3) * oy) * invN;
            float smoothZ = (fz + 2f * rz + (n - 3) * oz) * invN;

            if (creasedEdgeCount[i] == 2) {
                // Crease vertex rule: (e1 + 6*P + e2) / 8, blended toward smooth by the weight
                float creaseX = (creaseNeighbourSum[vo] + 6f * ox) * 0.125f;
                float creaseY = (creaseNeighbourSum[vo + 1] + 6f * oy) * 0.125f;
                float creaseZ = (creaseNeighbourSum[vo + 2] + 6f * oz) * 0.125f;
                float blendW = Math.min(maxCreaseWeight[i], 1.0f);
                vtxPos[vo] = smoothX + (creaseX - smoothX) * blendW;
                vtxPos[vo + 1] = smoothY + (creaseY - smoothY) * blendW;
                vtxPos[vo + 2] = smoothZ + (creaseZ - smoothZ) * blendW;
            } else {
                // Smooth vertex (0-1 creased edges)
                vtxPos[vo] = smoothX;
                vtxPos[vo + 1] = smoothY;
                vtxPos[vo + 2] = smoothZ;
            }
        }

        // --- Build output topology: each n-gon produces n quads ---
        float[] newPositions = new float[(nf + ne + nv) * 3];
        System.arraycopy(faceCenter, 0, newPositions, 0, nf * 3);
        System.arraycopy(edgePos, 0, newPositions, nf * 3, ne * 3);
        System.arraycopy(vtxPos, 0, newPositions, (nf + ne) * 3, nv * 3);

        int[] newQuads = new int[cornerCount * 4];
        int qi = 0;
        for (int activeFace = 0; activeFace < nf; activeFace++) {
            int first = faceStart[activeFace];
            int corners = faceStart[activeFace + 1] - first;
            for (int k = 0; k < corners; k++) {
                int c = first + k;
                int previous = first + (k + corners - 1) % corners;
                newQuads[qi++] = nf + ne + cornerVertex[c];
                newQuads[qi++] = nf + cornerEdge[c];
                newQuads[qi++] = activeFace;
                newQuads[qi++] = nf + cornerEdge[previous];
            }
        }

        // --- Propagate crease weights (decrement by 1) ---
        // A child edge joins a parent's edge point, nf + appearance, to a vertex point, at or
        // past nf + ne, and inherits max(0, parent_weight - 1).
        HalfEdgeMesh subdivided = HalfEdgeMeshOps.bulkAllocate(newPositions, newQuads, 4);
        float[] newCreaseWeights = null;
        if (creaseWeights != null) {
            boolean hasCreases = false;
            newCreaseWeights = new float[subdivided.edgeIdBound()];
            for (int activeEdge = 0; activeEdge < subdivided.edgeCount(); activeEdge++) {
                int edgeId = subdivided.edgeIdAt(activeEdge);
                int he = subdivided.edgeHalfEdge(edgeId);
                int va = subdivided.halfEdgeVertex(he);
                int vb = subdivided.halfEdgeEndVertex(he);
                int epIdx = -1;
                if (va >= nf && va < nf + ne && vb >= nf + ne) {
                    epIdx = va - nf;
                } else if (vb >= nf && vb < nf + ne && va >= nf + ne) {
                    epIdx = vb - nf;
                }
                if (epIdx >= 0) {
                    float nw = Math.max(0f, creaseWeight(creaseWeights, edgeIdOfAppearance[epIdx]) - 1f);
                    if (nw > 0f) {
                        newCreaseWeights[edgeId] = nw;
                        hasCreases = true;
                    }
                }
            }
            if (!hasCreases) {
                newCreaseWeights = null;
            }
        }

        levelCreaseWeights = newCreaseWeights;
        return subdivided;
    }
}
