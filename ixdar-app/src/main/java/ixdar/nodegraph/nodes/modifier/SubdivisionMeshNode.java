package ixdar.nodegraph.nodes.modifier;

import java.util.Arrays;
import java.util.List;

import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.nodes.patch.AssignBezierHandlesNode;
import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.bundle.GeometryBundles;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;

/**
 * Face subdivision into quads, each n-gon into n per level: crease-aware Catmull-Clark, or with
 * {@code smooth} off, plain splitting at edge midpoints and face centroids that keeps the shape.
 */
@MeshNodeAnnotation(id = "subdivision_surface", category = NodeCategory.MODIFIERS)
public class SubdivisionMeshNode implements MeshNode {
    public static final int MAX_FACE_COUNT = 600_000;

    public static final InputPort MESH_IN = new InputPort("mesh", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort GEOMETRY_IN = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort LEVELS = new InputPort("levels", PortType.INT, 1, 0f, 6f);
    public static final InputPort SMOOTH = new InputPort("smooth", PortType.BOOLEAN, true);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY_IN.name, PortType.GEOMETRY_BUNDLE);

    private float[] levelCreaseWeights;

    @Override
    public List<InputPort> inputs() {
        return List.of(MESH_IN, GEOMETRY_IN, LEVELS, SMOOTH);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Subdivides every face into quads: Catmull-Clark smoothing with crease weights for semi-sharp edges, "
                + "or with smooth off, plain splitting at edge midpoints and face centroids that keeps the shape. "
                + "Handles mixed tri/quad input.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                MESH_IN.name, "Plain topology input (alternative to geometry). Used when no crease weights need to be read.",
                GEOMETRY_IN.name, "Input/output geometry bundle. Carries crease weights written by mark_crease so semi-sharp edges subdivide smoothly.",
                LEVELS.name, "Iterations, 0..N. Each level quadruples face count; levels past 600k output faces are dropped. "
                        + "DESTRUCTIVE: consumes bezier handle slots — use BEFORE assign_bezier_handles.",
                SMOOTH.name, "On: Catmull-Clark, smoothing toward the limit surface. Off: split only, every vertex kept, "
                        + "edges cut at their midpoints, creases ignored."
        );
    }

    @Override
    public boolean destructive() {
        return true;
    }

    @Override
    public List<String> consumes() {
        return List.of(
                AssignBezierHandlesNode.SLOT_HANDLES_START,
                AssignBezierHandlesNode.SLOT_HANDLES_END);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = ctx.getInput(GEOMETRY_IN.name, GeometryBundle.class);
        MeshTopology mesh = GeometryBundles.meshPart(bundle);
        if (mesh == null) {
            mesh = GeometryBundles.meshPart(ctx.getInput(MESH_IN.name, GeometryBundle.class));
        }

        Number levelsInput = ctx.getInput(LEVELS.name, Number.class);
        int levels = levelsInput == null ? 1 : Math.max(0, levelsInput.intValue());
        Boolean smoothInput = ctx.getInput(SMOOTH.name, Boolean.class);
        boolean linear = smoothInput != null && !smoothInput;

        if (mesh == null) {
            ctx.setOutput(GEOMETRY_IN.name, GeometryBundle.empty());
            return;
        }

        long estimated = mesh.faceCount();
        for (int level = 0; level < levels; level++) {
            estimated *= 4;
            if (estimated > MAX_FACE_COUNT) {
                levels = level;
                break;
            }
        }

        if (levels == 0) {
            ctx.setOutput(GEOMETRY_IN.name, bundle != null ? bundle : GeometryBundle.ofMesh(mesh));
            return;
        }

        float[] creaseWeights = bundle != null && !linear
                ? EdgeMarks.floats(bundle, MarkEdgesNode.CREASE_LABEL)
                : null;
        HalfEdgeMesh quads = applyCatmullClarkLevel(mesh, creaseWeights, linear);
        for (int level = 1; level < levels; level++) {
            quads = applyCatmullClarkLevel(quads, levelCreaseWeights, linear);
        }
        quads.computeNormals();

        GeometryBundle outBundle = GeometryBundle.ofMesh(quads);
        if (levelCreaseWeights != null) {
            outBundle = EdgeMarks.with(outBundle, MarkEdgesNode.CREASE_LABEL, levelCreaseWeights);
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
     * @param linear        split only: every edge cut at its midpoint, every vertex kept
     * @return the all-quad mesh: face points in active-face order, edge points in the order edges
     *         first appear walking the faces, vertex points in active-vertex order; normals not
     *         computed
     */
    private HalfEdgeMesh applyCatmullClarkLevel(MeshTopology mesh, float[] creaseWeights, boolean linear) {
        int nv = mesh.vertexCount();
        int nf = mesh.faceCount();
        int[] activeVertexOfId = new int[mesh.vertexIdBound()];
        PackedPoints positions = mesh.packedPositions(activeVertexOfId);

        int cornerCount = 0;
        for (int activeFace = 0; activeFace < nf; activeFace++) {
            cornerCount += mesh.faceVertexCount(mesh.faceIdAt(activeFace));
        }

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
        PackedPoints newPositions = new PackedPoints(nf);
        int ne = 0;
        int corner = 0;
        for (int activeFace = 0; activeFace < nf; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int corners = mesh.faceVertexCount(faceId);
            faceStart[activeFace] = corner;
            Vector3f sum = new Vector3f();
            for (int slot = 0; slot < corners; slot++) {
                int vertexId = mesh.faceVertexAt(faceId, slot);
                int nextVertexId = mesh.faceVertexAt(faceId, (slot + 1) % corners);
                int activeVertex = activeVertexOfId[vertexId];
                sum.add(positions.get(activeVertex, 0), positions.get(activeVertex, 1),
                        positions.get(activeVertex, 2));
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
            newPositions.set(activeFace, sum.mul(1.0f / corners));
        }
        faceStart[nf] = corner;

        // Face points are the first nf output points, edge points the next ne, vertex points the rest.
        newPositions.resize(nf + ne + nv);
        boolean[] boundary = new boolean[nv];
        int[] creasedEdgeCount = new int[nv];
        float[] maxCreaseWeight = new float[nv];
        PackedPoints ringSum = new PackedPoints(nv);
        int[] creaseNeighbours = new int[nv];
        PackedPoints creaseNeighbourSum = new PackedPoints(nv);
        Vector3f midpoint = new Vector3f();
        for (int i = 0; i < ne; i++) {
            int va = edgeStart[i];
            int vb = edgeEnd[i];
            float x1 = positions.get(va, 0), y1 = positions.get(va, 1), z1 = positions.get(va, 2);
            float x2 = positions.get(vb, 0), y2 = positions.get(vb, 1), z2 = positions.get(vb, 2);
            midpoint.set((x1 + x2) * 0.5f, (y1 + y2) * 0.5f, (z1 + z2) * 0.5f);

            int f1 = edgeFirstFace[i];
            int f2 = edgeSecondFace[i];

            float w = linear ? 1f : creaseWeight(creaseWeights, edgeIdOfAppearance[i]);
            int edgePoint = nf + i;

            if (w >= 1.0f || f2 == MeshTopology.NONE) {
                newPositions.set(edgePoint, midpoint);
            } else {
                float sx = (x1 + x2 + newPositions.get(f1, 0) + newPositions.get(f2, 0)) * 0.25f;
                float sy = (y1 + y2 + newPositions.get(f1, 1) + newPositions.get(f2, 1)) * 0.25f;
                float sz = (z1 + z2 + newPositions.get(f1, 2) + newPositions.get(f2, 2)) * 0.25f;
                newPositions.set(edgePoint, sx + (midpoint.x - sx) * w, sy + (midpoint.y - sy) * w,
                        sz + (midpoint.z - sz) * w);
            }

            for (int end = 0; end < 2; end++) {
                int activeVertex = end == 0 ? va : vb;
                int otherVertex = end == 0 ? vb : va;
                if (f2 == MeshTopology.NONE) {
                    boundary[activeVertex] = true;
                } else if (w > 0f) {
                    creasedEdgeCount[activeVertex]++;
                    maxCreaseWeight[activeVertex] = Math.max(maxCreaseWeight[activeVertex], w);
                }
                ringSum.add(activeVertex, midpoint);
                if (w > 0f && creaseNeighbours[activeVertex] < 2) {
                    creaseNeighbourSum.add(activeVertex, positions.at(otherVertex));
                    creaseNeighbours[activeVertex]++;
                }
            }
        }

        int[] facesAtVertex = new int[nv];
        PackedPoints faceSum = new PackedPoints(nv);
        for (int activeFace = 0; activeFace < nf; activeFace++) {
            Vector3f facePoint = newPositions.at(activeFace);
            for (int c = faceStart[activeFace]; c < faceStart[activeFace + 1]; c++) {
                facesAtVertex[cornerVertex[c]]++;
                faceSum.add(cornerVertex[c], facePoint);
            }
        }
        for (int i = 0; i < nv; i++) {
            int vertexPoint = nf + ne + i;
            float ox = positions.get(i, 0), oy = positions.get(i, 1), oz = positions.get(i, 2);
            int n = facesAtVertex[i];
            if (linear || boundary[i] || n == 0 || creasedEdgeCount[i] >= 3) {
                newPositions.set(vertexPoint, ox, oy, oz);
                continue;
            }

            float invN = 1.0f / n;
            float fx = faceSum.get(i, 0) * invN;
            float fy = faceSum.get(i, 1) * invN;
            float fz = faceSum.get(i, 2) * invN;
            float rx = ringSum.get(i, 0) * invN;
            float ry = ringSum.get(i, 1) * invN;
            float rz = ringSum.get(i, 2) * invN;

            float smoothX = (fx + 2f * rx + (n - 3) * ox) * invN;
            float smoothY = (fy + 2f * ry + (n - 3) * oy) * invN;
            float smoothZ = (fz + 2f * rz + (n - 3) * oz) * invN;

            if (creasedEdgeCount[i] == 2) {
                float creaseX = (creaseNeighbourSum.get(i, 0) + 6f * ox) * 0.125f;
                float creaseY = (creaseNeighbourSum.get(i, 1) + 6f * oy) * 0.125f;
                float creaseZ = (creaseNeighbourSum.get(i, 2) + 6f * oz) * 0.125f;
                float blendW = Math.min(maxCreaseWeight[i], 1.0f);
                newPositions.set(vertexPoint, smoothX + (creaseX - smoothX) * blendW,
                        smoothY + (creaseY - smoothY) * blendW, smoothZ + (creaseZ - smoothZ) * blendW);
            } else {
                newPositions.set(vertexPoint, smoothX, smoothY, smoothZ);
            }
        }

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
