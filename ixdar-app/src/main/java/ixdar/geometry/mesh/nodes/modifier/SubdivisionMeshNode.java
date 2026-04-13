package ixdar.geometry.mesh.nodes.modifier;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;
import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.QuadMeshTopologyHelper;

@MeshNodeAnnotation(id = "subdivision_surface")
public class SubdivisionMeshNode implements MeshNode {

    private static final InputPort MESH_IN = new InputPort("mesh", PortType.MESH, null);
    private static final InputPort GEOMETRY_IN = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort LEVELS = new InputPort("levels", PortType.INT, 1, 0f, 6f);
    private static final OutputPort MESH_OUT = new OutputPort("mesh", PortType.MESH);
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(MESH_IN, GEOMETRY_IN, LEVELS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH_OUT, GEOMETRY_OUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        // Accept geometry bundle (with crease weights) or plain mesh
        GeometryBundle bundle = null;
        MeshTopology mesh = null;
        Object geoObj = ctx.getInputValue("geometry");
        if (geoObj != null) {
            bundle = GeometryBundles.requireBundle(geoObj);
            mesh = bundle.mesh();
        }
        if (mesh == null) {
            mesh = ctx.getInput("mesh", MeshTopology.class);
        }

        Number levelsInput = ctx.getInput("levels", Number.class);
        int levels = levelsInput == null ? 1 : Math.max(0, levelsInput.intValue());

        if (mesh == null) {
            ctx.setOutput("mesh", null);
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }

        if (levels == 0) {
            ctx.setOutput("mesh", mesh);
            ctx.setOutput("geometry", bundle != null ? bundle : GeometryBundle.ofMesh(mesh));
            return;
        }

        // Extract crease weights from bundle if available
        float[] creaseWeights = null;
        if (bundle != null) {
            Object cw = bundle.slots().get(MarkCreaseNode.CREASE_WEIGHTS_SLOT);
            if (cw instanceof float[] weights) {
                creaseWeights = weights;
            }
        }

        float[] positions;
        int[] quadIndices;
        int nv;
        int remainingLevels = levels;

        // If mesh has non-quad faces (e.g. triangles from cap closure), the first CC
        // level uses a generalized N-gon algorithm. After one CC step all faces become
        // quads, so subsequent levels use the fast quad-only path.
        if (mesh instanceof HalfEdgeMesh hem && hasNonQuadFaces(hem)) {
            DensePolyMesh poly = extractDensePolyMesh(hem);
            CatmullClarkResult first = applyMixedCatmullClarkLevel(
                    poly.positions, poly.faceIndices, poly.faceOffsets,
                    poly.vertexCount, poly.faceCount, creaseWeights);
            positions = first.positions;
            quadIndices = first.quadIndices;
            creaseWeights = first.creaseWeights;
            nv = positions.length / 3;
            remainingLevels--;
        } else {
            DenseQuadMesh dq = extractDenseQuadMesh(mesh);
            positions = dq.positions;
            quadIndices = dq.quadIndices;
            nv = dq.vertexCount;
        }

        for (int i = 0; i < remainingLevels; i++) {
            int nf = quadIndices.length / 4;
            CatmullClarkResult step = applyCatmullClarkLevel(positions, quadIndices, nv, nf, creaseWeights);
            positions = step.positions;
            quadIndices = step.quadIndices;
            creaseWeights = step.creaseWeights;
            nv = positions.length / 3;
        }

        ArrayMesh out = ArrayMesh.fromQuads(positions, quadIndices);
        out.computeNormals();

        ctx.setOutput("mesh", out);
        GeometryBundle outBundle = GeometryBundle.ofMesh(out);
        if (creaseWeights != null) {
            outBundle = outBundle.withSlot(MarkCreaseNode.CREASE_WEIGHTS_SLOT, creaseWeights);
        }
        ctx.setOutput("geometry", outBundle);
    }

    private static final class CatmullClarkResult {
        final float[] positions;
        final int[] quadIndices;
        final float[] creaseWeights;

        CatmullClarkResult(float[] positions, int[] quadIndices, float[] creaseWeights) {
            this.positions = positions;
            this.quadIndices = quadIndices;
            this.creaseWeights = creaseWeights;
        }
    }

    private static final class DenseQuadMesh {
        final float[] positions;
        final int[] quadIndices;
        final int vertexCount;

        DenseQuadMesh(float[] positions, int[] quadIndices, int vertexCount) {
            this.positions = positions;
            this.quadIndices = quadIndices;
            this.vertexCount = vertexCount;
        }
    }

    private static DenseQuadMesh extractDenseQuadMesh(MeshTopology mesh) {
        if (mesh instanceof ArrayMesh am) {
            if (am.getVertsPerFace() != 4) {
                throw new IllegalArgumentException("subdivision_surface: ArrayMesh must be all quads");
            }
            return new DenseQuadMesh(am.copyPositions(), am.copyFaceIndices(), am.vertexCount());
        }
        if (mesh instanceof HalfEdgeMesh hem) {
            return extractDenseQuads(hem);
        }
        throw new IllegalArgumentException("subdivision_surface: unsupported mesh type " + mesh.getClass().getName());
    }

    private static DenseQuadMesh extractDenseQuads(HalfEdgeMesh mesh) {
        int nv = mesh.vertexCount();
        int maxVid = 0;
        for (int i = 0; i < nv; i++) {
            maxVid = Math.max(maxVid, mesh.vertexIdAt(i));
        }
        int[] oldToDense = new int[maxVid + 1];
        Arrays.fill(oldToDense, MeshTopology.NONE);

        int nf = mesh.faceCount();
        float[] pos = new float[nv * 3];
        Vector3f p = new Vector3f();
        for (int i = 0; i < nv; i++) {
            int vid = mesh.vertexIdAt(i);
            oldToDense[vid] = i;
            mesh.vertexPosition(vid, p);
            pos[i * 3] = p.x; pos[i * 3 + 1] = p.y; pos[i * 3 + 2] = p.z;
        }
        int[] quads = new int[nf * 4];
        for (int fi = 0; fi < nf; fi++) {
            int f = mesh.faceIdAt(fi);
            if (mesh.faceVertexCount(f) != 4) {
                throw new IllegalArgumentException(
                        "subdivision_surface: all faces must be quads, got " + mesh.faceVertexCount(f) + "-gon");
            }
            for (int k = 0; k < 4; k++) quads[fi * 4 + k] = oldToDense[mesh.faceVertexAt(f, k)];
        }
        return new DenseQuadMesh(pos, quads, nv);
    }

    private static int nextVertex(int[] quads, int he) {
        return quads[(he & ~3) | ((he + 1) & 3)];
    }

    private static float creaseWeight(float[] creaseWeights, int edgeIndex) {
        if (creaseWeights == null || edgeIndex < 0 || edgeIndex >= creaseWeights.length) {
            return 0f;
        }
        return creaseWeights[edgeIndex];
    }

    private static CatmullClarkResult applyCatmullClarkLevel(float[] positions, int[] quadIndices,
                                                              int nv, int nf, float[] creaseWeights) {
        QuadMeshTopologyHelper topo = QuadMeshTopologyHelper.build(quadIndices, 4, nv, nf);
        int ne = topo.edgeCount;

        // --- Face centers (unchanged by creases) ---
        final float[] faceCenter = new float[nf * 3];
        parallelRange(nf, fi -> {
            float sx = 0f, sy = 0f, sz = 0f;
            for (int k = 0; k < 4; k++) {
                int v = quadIndices[fi * 4 + k];
                int o = v * 3;
                sx += positions[o];
                sy += positions[o + 1];
                sz += positions[o + 2];
            }
            int fo = fi * 3;
            faceCenter[fo] = sx * 0.25f;
            faceCenter[fo + 1] = sy * 0.25f;
            faceCenter[fo + 2] = sz * 0.25f;
        });

        // --- Edge points (crease-aware) ---
        final float[] edgePos = new float[ne * 3];
        final float[] cw = creaseWeights;
        parallelRange(ne, i -> {
            int he = topo.edgeHalfEdge[i];
            int tw = topo.halfEdgeTwin[he];
            int va = quadIndices[he];
            int vb = nextVertex(quadIndices, he);
            int o1 = va * 3, o2 = vb * 3;

            float x1 = positions[o1], y1 = positions[o1 + 1], z1 = positions[o1 + 2];
            float x2 = positions[o2], y2 = positions[o2 + 1], z2 = positions[o2 + 2];

            int f1 = he / 4;
            int f2 = tw == MeshTopology.NONE ? MeshTopology.NONE : tw / 4;

            float w = creaseWeight(cw, i);
            int eo = i * 3;

            if (w >= 1.0f) {
                // Creased edge: use simple midpoint
                edgePos[eo] = (x1 + x2) * 0.5f;
                edgePos[eo + 1] = (y1 + y2) * 0.5f;
                edgePos[eo + 2] = (z1 + z2) * 0.5f;
            } else if (f1 != MeshTopology.NONE && f2 != MeshTopology.NONE) {
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
        });

        // --- Vertex points (crease-aware) ---
        final float[] vtxPos = new float[nv * 3];
        final float[] oldPos = positions;
        parallelRange(nv, i -> {
            int vo = i * 3;
            float ox = oldPos[vo], oy = oldPos[vo + 1], oz = oldPos[vo + 2];

            // Count boundary edges and creased edges
            boolean boundary = false;
            int creasedEdgeCount = 0;
            float maxCreaseW = 0f;
            for (int j = topo.vertexEdgeOffsets[i]; j < topo.vertexEdgeOffsets[i + 1]; j++) {
                int e = topo.vertexEdges[j];
                int he = topo.edgeHalfEdge[e];
                if (topo.halfEdgeTwin[he] == MeshTopology.NONE) {
                    boundary = true;
                    break;
                }
                float ew = creaseWeight(cw, e);
                if (ew > 0f) {
                    creasedEdgeCount++;
                    maxCreaseW = Math.max(maxCreaseW, ew);
                }
            }

            if (boundary) {
                vtxPos[vo] = ox;
                vtxPos[vo + 1] = oy;
                vtxPos[vo + 2] = oz;
                return;
            }

            int n = topo.vertexFaceOffsets[i + 1] - topo.vertexFaceOffsets[i];
            if (n == 0) {
                vtxPos[vo] = ox;
                vtxPos[vo + 1] = oy;
                vtxPos[vo + 2] = oz;
                return;
            }

            // Smooth CC vertex position
            float fx = 0f, fy = 0f, fz = 0f;
            for (int j = topo.vertexFaceOffsets[i]; j < topo.vertexFaceOffsets[i + 1]; j++) {
                int faceId = topo.vertexFaces[j];
                int fo = faceId * 3;
                fx += faceCenter[fo];
                fy += faceCenter[fo + 1];
                fz += faceCenter[fo + 2];
            }
            float invN = 1.0f / n;
            fx *= invN;
            fy *= invN;
            fz *= invN;

            float rx = 0f, ry = 0f, rz = 0f;
            for (int j = topo.vertexEdgeOffsets[i]; j < topo.vertexEdgeOffsets[i + 1]; j++) {
                int edge = topo.vertexEdges[j];
                int he = topo.edgeHalfEdge[edge];
                int a = quadIndices[he];
                int b = nextVertex(quadIndices, he);
                int ao = a * 3, bo = b * 3;
                rx += (oldPos[ao] + oldPos[bo]) * 0.5f;
                ry += (oldPos[ao + 1] + oldPos[bo + 1]) * 0.5f;
                rz += (oldPos[ao + 2] + oldPos[bo + 2]) * 0.5f;
            }
            rx *= invN;
            ry *= invN;
            rz *= invN;

            float smoothX = (fx + 2f * rx + (n - 3) * ox) * invN;
            float smoothY = (fy + 2f * ry + (n - 3) * oy) * invN;
            float smoothZ = (fz + 2f * rz + (n - 3) * oz) * invN;

            if (creasedEdgeCount >= 3) {
                // Corner: keep original position (3+ creased edges)
                vtxPos[vo] = ox;
                vtxPos[vo + 1] = oy;
                vtxPos[vo + 2] = oz;
            } else if (creasedEdgeCount == 2) {
                // Crease vertex rule: (e1 + 6*P + e2) / 8
                // Find the two creased edge endpoints (the OTHER vertex of each creased edge)
                float cx = 0f, cy = 0f, cz = 0f;
                int found = 0;
                for (int j = topo.vertexEdgeOffsets[i]; j < topo.vertexEdgeOffsets[i + 1]; j++) {
                    int e = topo.vertexEdges[j];
                    float ew = creaseWeight(cw, e);
                    if (ew > 0f && found < 2) {
                        int he = topo.edgeHalfEdge[e];
                        int a = quadIndices[he];
                        int b = nextVertex(quadIndices, he);
                        int other = (a == i) ? b : a;
                        int oo = other * 3;
                        cx += oldPos[oo];
                        cy += oldPos[oo + 1];
                        cz += oldPos[oo + 2];
                        found++;
                    }
                }
                float creaseX = (cx + 6f * ox) * 0.125f;
                float creaseY = (cy + 6f * oy) * 0.125f;
                float creaseZ = (cz + 6f * oz) * 0.125f;

                // For semi-sharp: blend between smooth and crease by min weight
                float blendW = Math.min(maxCreaseW, 1.0f);
                vtxPos[vo] = smoothX + (creaseX - smoothX) * blendW;
                vtxPos[vo + 1] = smoothY + (creaseY - smoothY) * blendW;
                vtxPos[vo + 2] = smoothZ + (creaseZ - smoothZ) * blendW;
            } else {
                // Smooth vertex (0-1 creased edges)
                vtxPos[vo] = smoothX;
                vtxPos[vo + 1] = smoothY;
                vtxPos[vo + 2] = smoothZ;
            }
        });

        // --- Build output topology ---
        int totalNewFaces = nf * 4;
        float[] newPositions = new float[(nf + ne + nv) * 3];
        int pi = 0;
        for (int i = 0; i < nf; i++) {
            int fo = i * 3;
            newPositions[pi++] = faceCenter[fo];
            newPositions[pi++] = faceCenter[fo + 1];
            newPositions[pi++] = faceCenter[fo + 2];
        }
        for (int i = 0; i < ne; i++) {
            int eo = i * 3;
            newPositions[pi++] = edgePos[eo];
            newPositions[pi++] = edgePos[eo + 1];
            newPositions[pi++] = edgePos[eo + 2];
        }
        for (int i = 0; i < nv; i++) {
            int voo = i * 3;
            newPositions[pi++] = vtxPos[voo];
            newPositions[pi++] = vtxPos[voo + 1];
            newPositions[pi++] = vtxPos[voo + 2];
        }

        int[] newQuads = new int[totalNewFaces * 4];
        int qi = 0;
        for (int fi = 0; fi < nf; fi++) {
            int fp = fi;
            for (int k = 0; k < 4; k++) {
                int he = fi * 4 + k;
                int prevHe = fi * 4 + (k + 3) % 4;
                int vx = quadIndices[he];
                int vp = nf + ne + vx;
                int eOut = topo.halfEdgeEdge[he];
                int eIn = topo.halfEdgeEdge[prevHe];
                int epOut = nf + eOut;
                int epIn = nf + eIn;

                newQuads[qi++] = vp;
                newQuads[qi++] = epOut;
                newQuads[qi++] = fp;
                newQuads[qi++] = epIn;
            }
        }

        // --- Propagate crease weights (decrement by 1) ---
        // In the subdivided mesh, each original edge becomes 2 child edges.
        // Each child edge inherits max(0, parent_weight - 1).
        float[] newCreaseWeights = null;
        if (cw != null) {
            boolean hasCreases = false;
            // Count edges in new topology: each face contributes 4 half-edges,
            // new edge count = nf * 4 (interior) + ne (from original edges split)
            // The new topology needs to be built to get accurate edge mapping.
            // Simpler approach: build new topology, map parent edges to child edges.
            QuadMeshTopologyHelper newTopo = QuadMeshTopologyHelper.build(newQuads, 4,
                    nf + ne + nv, totalNewFaces);
            int newNe = newTopo.edgeCount;
            newCreaseWeights = new float[newNe];

            // For each new edge, determine if it came from an original creased edge.
            // In the CC split, a new edge connecting an edge-point to a vertex-point
            // is a "child" of the original edge. The edge-point index is (nf + parentEdgeIdx).
            // The vertex-point index is (nf + ne + parentVertexIdx).
            for (int ei = 0; ei < newNe; ei++) {
                int he = newTopo.edgeHalfEdge[ei];
                int va = newQuads[he];
                int vb = newQuads[(he & ~3) | ((he + 1) & 3)];

                // Check if this edge connects an edge-point to a vertex-point
                // Edge-points are in range [nf, nf+ne), vertex-points in [nf+ne, nf+ne+nv)
                int epIdx = -1;
                if (va >= nf && va < nf + ne && vb >= nf + ne) {
                    epIdx = va - nf;
                } else if (vb >= nf && vb < nf + ne && va >= nf + ne) {
                    epIdx = vb - nf;
                }

                if (epIdx >= 0 && epIdx < (cw != null ? cw.length : 0)) {
                    float pw = cw.length > epIdx ? cw[epIdx] : 0f;
                    float nw = Math.max(0f, pw - 1f);
                    if (nw > 0f) {
                        newCreaseWeights[ei] = nw;
                        hasCreases = true;
                    }
                }
            }

            if (!hasCreases) {
                newCreaseWeights = null;
            }
        }

        return new CatmullClarkResult(newPositions, newQuads, newCreaseWeights);
    }

    private static void parallelRange(int n, IntConsumer body) {
        for (int i = 0; i < n; i++) {
            body.accept(i);
        }
    }

    // ---- Mixed tri/quad CC subdivision support ----

    private static boolean hasNonQuadFaces(HalfEdgeMesh mesh) {
        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            if (mesh.faceVertexCount(mesh.faceIdAt(fi)) != 4) return true;
        }
        return false;
    }

    private static final class DensePolyMesh {
        final float[] positions;
        final int[] faceIndices;
        final int[] faceOffsets; // faceOffsets[i] = start of face i; length = faceCount + 1
        final int vertexCount;
        final int faceCount;

        DensePolyMesh(float[] positions, int[] faceIndices, int[] faceOffsets,
                      int vertexCount, int faceCount) {
            this.positions = positions;
            this.faceIndices = faceIndices;
            this.faceOffsets = faceOffsets;
            this.vertexCount = vertexCount;
            this.faceCount = faceCount;
        }
    }

    private static DensePolyMesh extractDensePolyMesh(HalfEdgeMesh mesh) {
        int nv = mesh.vertexCount();
        int nf = mesh.faceCount();
        int maxVid = 0;
        for (int i = 0; i < nv; i++) maxVid = Math.max(maxVid, mesh.vertexIdAt(i));
        int[] oldToDense = new int[maxVid + 1];
        Arrays.fill(oldToDense, MeshTopology.NONE);

        float[] pos = new float[nv * 3];
        Vector3f p = new Vector3f();
        for (int i = 0; i < nv; i++) {
            int vid = mesh.vertexIdAt(i);
            oldToDense[vid] = i;
            mesh.vertexPosition(vid, p);
            pos[i * 3] = p.x; pos[i * 3 + 1] = p.y; pos[i * 3 + 2] = p.z;
        }

        int[] faceOffsets = new int[nf + 1];
        int totalHE = 0;
        for (int fi = 0; fi < nf; fi++) {
            faceOffsets[fi] = totalHE;
            int f = mesh.faceIdAt(fi);
            int vc = mesh.faceVertexCount(f);
            if (vc < 3) throw new IllegalArgumentException(
                    "subdivision_surface: degenerate face with " + vc + " vertices");
            totalHE += vc;
        }
        faceOffsets[nf] = totalHE;

        int[] faceIndices = new int[totalHE];
        for (int fi = 0; fi < nf; fi++) {
            int f = mesh.faceIdAt(fi);
            int vc = mesh.faceVertexCount(f);
            int offset = faceOffsets[fi];
            for (int k = 0; k < vc; k++) {
                faceIndices[offset + k] = oldToDense[mesh.faceVertexAt(f, k)];
            }
        }
        return new DensePolyMesh(pos, faceIndices, faceOffsets, nv, nf);
    }

    /**
     * Catmull-Clark subdivision step for meshes with mixed face sizes (triangles + quads).
     * After one step, all output faces are quads — subsequent levels use the fast quad-only path.
     * Each N-gon face produces N sub-quads: (vertex_point, edge_point_out, face_point, edge_point_in).
     */
    private static CatmullClarkResult applyMixedCatmullClarkLevel(
            float[] positions, int[] faceIndices, int[] faceOffsets,
            int nv, int nf, float[] creaseWeights) {

        int HE = faceOffsets[nf];

        // Precompute per-half-edge face index and next/prev navigation
        int[] heFace = new int[HE];
        int[] nextHE = new int[HE];
        int[] prevHE = new int[HE];
        for (int fi = 0; fi < nf; fi++) {
            int s = faceOffsets[fi];
            int sz = faceOffsets[fi + 1] - s;
            for (int k = 0; k < sz; k++) {
                heFace[s + k] = fi;
                nextHE[s + k] = s + (k + 1) % sz;
                prevHE[s + k] = s + (k + sz - 1) % sz;
            }
        }

        // --- Build adjacency: twin, edge, vertex-face CSR, vertex-edge CSR ---

        // Per-vertex outgoing half-edge CSR (for twin lookup)
        int[] voOff = new int[nv + 1];
        for (int he = 0; he < HE; he++) voOff[faceIndices[he] + 1]++;
        for (int i = 1; i <= nv; i++) voOff[i] += voOff[i - 1];
        int[] voData = new int[HE];
        int[] voWrite = Arrays.copyOf(voOff, nv + 1);
        for (int he = 0; he < HE; he++) voData[voWrite[faceIndices[he]]++] = he;

        // Twin lookup: for half-edge a→b, find b→a
        int[] twin = new int[HE];
        Arrays.fill(twin, MeshTopology.NONE);
        for (int he = 0; he < HE; he++) {
            if (twin[he] != MeshTopology.NONE) continue;
            int a = faceIndices[he];
            int b = faceIndices[nextHE[he]];
            for (int j = voOff[b]; j < voOff[b + 1]; j++) {
                int cand = voData[j];
                if (faceIndices[nextHE[cand]] == a) {
                    twin[he] = cand;
                    twin[cand] = he;
                    break;
                }
            }
        }

        // Edge assignment
        int ne = 0;
        for (int he = 0; he < HE; he++) {
            int tw = twin[he];
            if (tw == MeshTopology.NONE || he < tw) ne++;
        }
        int[] heEdge = new int[HE];
        int[] eHalf = new int[ne];
        int eid = 0;
        for (int he = 0; he < HE; he++) {
            int tw = twin[he];
            if (tw == MeshTopology.NONE || he < tw) {
                heEdge[he] = eid;
                eHalf[eid] = he;
                if (tw != MeshTopology.NONE) heEdge[tw] = eid;
                eid++;
            }
        }

        // Vertex-face CSR
        int[] vfOff = new int[nv + 1];
        for (int fi = 0; fi < nf; fi++) {
            for (int h = faceOffsets[fi]; h < faceOffsets[fi + 1]; h++) {
                vfOff[faceIndices[h] + 1]++;
            }
        }
        for (int i = 1; i <= nv; i++) vfOff[i] += vfOff[i - 1];
        int[] vfData = new int[vfOff[nv]];
        int[] vfWrite = Arrays.copyOf(vfOff, nv + 1);
        for (int fi = 0; fi < nf; fi++) {
            for (int h = faceOffsets[fi]; h < faceOffsets[fi + 1]; h++) {
                vfData[vfWrite[faceIndices[h]]++] = fi;
            }
        }

        // Vertex-edge CSR
        int[] veOff = new int[nv + 1];
        for (int e = 0; e < ne; e++) {
            int he = eHalf[e];
            veOff[faceIndices[he] + 1]++;
            veOff[faceIndices[nextHE[he]] + 1]++;
        }
        for (int i = 1; i <= nv; i++) veOff[i] += veOff[i - 1];
        int[] veData = new int[veOff[nv]];
        int[] veWrite = Arrays.copyOf(veOff, nv + 1);
        for (int e = 0; e < ne; e++) {
            int he = eHalf[e];
            veData[veWrite[faceIndices[he]]++] = e;
            veData[veWrite[faceIndices[nextHE[he]]]++] = e;
        }

        // --- Face centers (average of face vertices, generalized for N-gons) ---
        float[] faceCenter = new float[nf * 3];
        for (int fi = 0; fi < nf; fi++) {
            int s = faceOffsets[fi];
            int sz = faceOffsets[fi + 1] - s;
            float sx = 0f, sy = 0f, szz = 0f;
            for (int k = 0; k < sz; k++) {
                int v = faceIndices[s + k];
                sx += positions[v * 3];
                sy += positions[v * 3 + 1];
                szz += positions[v * 3 + 2];
            }
            float inv = 1.0f / sz;
            faceCenter[fi * 3] = sx * inv;
            faceCenter[fi * 3 + 1] = sy * inv;
            faceCenter[fi * 3 + 2] = szz * inv;
        }

        // --- Edge points (crease-aware, same formula as quad CC) ---
        float[] edgePos = new float[ne * 3];
        for (int i = 0; i < ne; i++) {
            int he = eHalf[i];
            int tw = twin[he];
            int va = faceIndices[he];
            int vb = faceIndices[nextHE[he]];
            int o1 = va * 3, o2 = vb * 3;

            float x1 = positions[o1], y1 = positions[o1 + 1], z1 = positions[o1 + 2];
            float x2 = positions[o2], y2 = positions[o2 + 1], z2 = positions[o2 + 2];

            int f1 = heFace[he];
            int f2 = tw == MeshTopology.NONE ? MeshTopology.NONE : heFace[tw];

            float w = creaseWeight(creaseWeights, i);
            int eo = i * 3;

            if (w >= 1.0f) {
                edgePos[eo] = (x1 + x2) * 0.5f;
                edgePos[eo + 1] = (y1 + y2) * 0.5f;
                edgePos[eo + 2] = (z1 + z2) * 0.5f;
            } else if (f1 != MeshTopology.NONE && f2 != MeshTopology.NONE) {
                int fc1 = f1 * 3, fc2 = f2 * 3;
                float sx = (x1 + x2 + faceCenter[fc1] + faceCenter[fc2]) * 0.25f;
                float sy = (y1 + y2 + faceCenter[fc1 + 1] + faceCenter[fc2 + 1]) * 0.25f;
                float sz = (z1 + z2 + faceCenter[fc1 + 2] + faceCenter[fc2 + 2]) * 0.25f;
                if (w > 0f) {
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
                edgePos[eo] = (x1 + x2) * 0.5f;
                edgePos[eo + 1] = (y1 + y2) * 0.5f;
                edgePos[eo + 2] = (z1 + z2) * 0.5f;
            }
        }

        // --- Vertex points (crease-aware, same rules as quad CC) ---
        float[] vtxPos = new float[nv * 3];
        for (int i = 0; i < nv; i++) {
            int vo = i * 3;
            float ox = positions[vo], oy = positions[vo + 1], oz = positions[vo + 2];

            boolean boundary = false;
            int creasedEdgeCount = 0;
            float maxCreaseW = 0f;
            for (int j = veOff[i]; j < veOff[i + 1]; j++) {
                int e = veData[j];
                int he = eHalf[e];
                if (twin[he] == MeshTopology.NONE) { boundary = true; break; }
                float ew = creaseWeight(creaseWeights, e);
                if (ew > 0f) { creasedEdgeCount++; maxCreaseW = Math.max(maxCreaseW, ew); }
            }

            if (boundary) {
                vtxPos[vo] = ox; vtxPos[vo + 1] = oy; vtxPos[vo + 2] = oz;
                continue;
            }

            int n = vfOff[i + 1] - vfOff[i];
            if (n == 0) {
                vtxPos[vo] = ox; vtxPos[vo + 1] = oy; vtxPos[vo + 2] = oz;
                continue;
            }

            float fx = 0f, fy = 0f, fz = 0f;
            for (int j = vfOff[i]; j < vfOff[i + 1]; j++) {
                int fo = vfData[j] * 3;
                fx += faceCenter[fo]; fy += faceCenter[fo + 1]; fz += faceCenter[fo + 2];
            }
            float invN = 1.0f / n;
            fx *= invN; fy *= invN; fz *= invN;

            float rx = 0f, ry = 0f, rz = 0f;
            for (int j = veOff[i]; j < veOff[i + 1]; j++) {
                int edge = veData[j];
                int he = eHalf[edge];
                int a = faceIndices[he];
                int b = faceIndices[nextHE[he]];
                rx += (positions[a * 3] + positions[b * 3]) * 0.5f;
                ry += (positions[a * 3 + 1] + positions[b * 3 + 1]) * 0.5f;
                rz += (positions[a * 3 + 2] + positions[b * 3 + 2]) * 0.5f;
            }
            rx *= invN; ry *= invN; rz *= invN;

            float smoothX = (fx + 2f * rx + (n - 3) * ox) * invN;
            float smoothY = (fy + 2f * ry + (n - 3) * oy) * invN;
            float smoothZ = (fz + 2f * rz + (n - 3) * oz) * invN;

            if (creasedEdgeCount >= 3) {
                vtxPos[vo] = ox; vtxPos[vo + 1] = oy; vtxPos[vo + 2] = oz;
            } else if (creasedEdgeCount == 2) {
                float cx = 0f, cy = 0f, cz = 0f;
                int found = 0;
                for (int j = veOff[i]; j < veOff[i + 1]; j++) {
                    int e = veData[j];
                    float ew = creaseWeight(creaseWeights, e);
                    if (ew > 0f && found < 2) {
                        int he = eHalf[e];
                        int a = faceIndices[he];
                        int b = faceIndices[nextHE[he]];
                        int other = (a == i) ? b : a;
                        cx += positions[other * 3]; cy += positions[other * 3 + 1]; cz += positions[other * 3 + 2];
                        found++;
                    }
                }
                float creaseX = (cx + 6f * ox) * 0.125f;
                float creaseY = (cy + 6f * oy) * 0.125f;
                float creaseZ = (cz + 6f * oz) * 0.125f;
                float blendW = Math.min(maxCreaseW, 1.0f);
                vtxPos[vo] = smoothX + (creaseX - smoothX) * blendW;
                vtxPos[vo + 1] = smoothY + (creaseY - smoothY) * blendW;
                vtxPos[vo + 2] = smoothZ + (creaseZ - smoothZ) * blendW;
            } else {
                vtxPos[vo] = smoothX; vtxPos[vo + 1] = smoothY; vtxPos[vo + 2] = smoothZ;
            }
        }

        // --- Build output topology: each N-gon produces N quads (all output is quads) ---
        int totalNewFaces = HE; // one sub-quad per half-edge
        float[] newPositions = new float[(nf + ne + nv) * 3];
        int pi = 0;
        for (int i = 0; i < nf; i++) {
            newPositions[pi++] = faceCenter[i * 3];
            newPositions[pi++] = faceCenter[i * 3 + 1];
            newPositions[pi++] = faceCenter[i * 3 + 2];
        }
        for (int i = 0; i < ne; i++) {
            newPositions[pi++] = edgePos[i * 3];
            newPositions[pi++] = edgePos[i * 3 + 1];
            newPositions[pi++] = edgePos[i * 3 + 2];
        }
        for (int i = 0; i < nv; i++) {
            newPositions[pi++] = vtxPos[i * 3];
            newPositions[pi++] = vtxPos[i * 3 + 1];
            newPositions[pi++] = vtxPos[i * 3 + 2];
        }

        int[] newQuads = new int[totalNewFaces * 4];
        int qi = 0;
        for (int fi = 0; fi < nf; fi++) {
            int fStart = faceOffsets[fi];
            int fSize = faceOffsets[fi + 1] - fStart;
            int fp = fi; // face point index
            for (int k = 0; k < fSize; k++) {
                int he = fStart + k;
                int phe = prevHE[he];
                int vx = faceIndices[he];
                int vp = nf + ne + vx;     // vertex point
                int epOut = nf + heEdge[he]; // edge point (outgoing)
                int epIn = nf + heEdge[phe]; // edge point (incoming/previous)

                newQuads[qi++] = vp;
                newQuads[qi++] = epOut;
                newQuads[qi++] = fp;
                newQuads[qi++] = epIn;
            }
        }

        // --- Propagate crease weights (same logic as quad CC) ---
        float[] newCreaseWeights = null;
        if (creaseWeights != null) {
            QuadMeshTopologyHelper newTopo = QuadMeshTopologyHelper.build(newQuads, 4,
                    nf + ne + nv, totalNewFaces);
            int newNe = newTopo.edgeCount;
            newCreaseWeights = new float[newNe];
            boolean hasCreases = false;

            for (int ei = 0; ei < newNe; ei++) {
                int he = newTopo.edgeHalfEdge[ei];
                int va = newQuads[he];
                int vb = newQuads[(he & ~3) | ((he + 1) & 3)];
                int epIdx = -1;
                if (va >= nf && va < nf + ne && vb >= nf + ne) {
                    epIdx = va - nf;
                } else if (vb >= nf && vb < nf + ne && va >= nf + ne) {
                    epIdx = vb - nf;
                }
                if (epIdx >= 0 && epIdx < creaseWeights.length) {
                    float nw = Math.max(0f, creaseWeights[epIdx] - 1f);
                    if (nw > 0f) { newCreaseWeights[ei] = nw; hasCreases = true; }
                }
            }
            if (!hasCreases) newCreaseWeights = null;
        }

        return new CatmullClarkResult(newPositions, newQuads, newCreaseWeights);
    }
}
