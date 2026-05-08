package ixdar.geometry.mesh.data;

import java.util.Arrays;

/**
 * Builds twin / edge / CSR adjacency for a uniform face mesh (all faces same vertex count).
 * Uses per-vertex outgoing half-edge CSR for O(HE) twin lookup, avoiding Integer boxing sorts.
 */
public final class QuadMeshTopologyHelper {

    public final int halfEdgeCount;
    public final int edgeCount;
    public final int[] halfEdgeTwin;
    public final int[] halfEdgeEdge;
    public final int[] edgeHalfEdge;
    public final int[] vertexFaceOffsets;
    public final int[] vertexFaces;
    public final int[] vertexEdgeOffsets;
    public final int[] vertexEdges;

    private QuadMeshTopologyHelper(
            int halfEdgeCount,
            int edgeCount,
            int[] halfEdgeTwin,
            int[] halfEdgeEdge,
            int[] edgeHalfEdge,
            int[] vertexFaceOffsets,
            int[] vertexFaces,
            int[] vertexEdgeOffsets,
            int[] vertexEdges) {
        this.halfEdgeCount = halfEdgeCount;
        this.edgeCount = edgeCount;
        this.halfEdgeTwin = halfEdgeTwin;
        this.halfEdgeEdge = halfEdgeEdge;
        this.edgeHalfEdge = edgeHalfEdge;
        this.vertexFaceOffsets = vertexFaceOffsets;
        this.vertexFaces = vertexFaces;
        this.vertexEdgeOffsets = vertexEdgeOffsets;
        this.vertexEdges = vertexEdges;
    }

    /**
     * Returns the index of the next half-edge within the same face.
     * For half-edge {@code he} in a face of size {@code vpf}, this is the successor
     * vertex in the face winding.
     *
     * @param he half-edge index
     * @param vpf vertices (and half-edges) per face
     * @return half-edge index of the successor within the same face
     */
    public static int nextHalfEdge(int he, int vpf) {
        int base = he - he % vpf;
        return base + (he - base + 1) % vpf;
    }

    /**
     * Build twin / edge / vertex-face / vertex-edge tables for a uniform face mesh.
     *
     * @param faceIndices flat per-face vertex index buffer ({@code vertsPerFace * faceCount} entries)
     * @param vertsPerFace vertices per face (e.g. 3 for tris, 4 for quads)
     * @param vertexCount total vertex count
     * @param faceCount total face count
     * @return a populated helper; for an empty mesh returns one with zeroed CSR offsets
     */
    public static QuadMeshTopologyHelper build(int[] faceIndices, int vertsPerFace, int vertexCount, int faceCount) {
        int HE = faceCount * vertsPerFace;
        if (HE == 0) {
            return new QuadMeshTopologyHelper(0, 0, new int[0], new int[0], new int[0],
                    new int[vertexCount + 1], new int[0], new int[vertexCount + 1], new int[0]);
        }

        // Per-vertex outgoing half-edge CSR — used for O(valence) twin lookup
        int[] voOff = new int[vertexCount + 1];
        for (int he = 0; he < HE; he++) {
            voOff[faceIndices[he] + 1]++;
        }
        for (int i = 1; i <= vertexCount; i++) {
            voOff[i] += voOff[i - 1];
        }
        int[] voData = new int[HE];
        int[] voWrite = Arrays.copyOf(voOff, vertexCount + 1);
        for (int he = 0; he < HE; he++) {
            voData[voWrite[faceIndices[he]]++] = he;
        }

        // Twin lookup: for half-edge a→b, find b→a via b's outgoing list
        int[] twin = new int[HE];
        Arrays.fill(twin, MeshTopology.NONE);
        for (int he = 0; he < HE; he++) {
            if (twin[he] != MeshTopology.NONE) {
                continue;
            }
            int a = faceIndices[he];
            int b = faceIndices[nextHalfEdge(he, vertsPerFace)];
            for (int j = voOff[b]; j < voOff[b + 1]; j++) {
                int cand = voData[j];
                if (faceIndices[nextHalfEdge(cand, vertsPerFace)] == a) {
                    twin[he] = cand;
                    twin[cand] = he;
                    break;
                }
            }
        }

        // Edge assignment from twin pairs — each unique undirected edge gets one ID
        int eCount = 0;
        for (int he = 0; he < HE; he++) {
            int tw = twin[he];
            if (tw == MeshTopology.NONE || he < tw) {
                eCount++;
            }
        }
        int[] heEdge = new int[HE];
        int[] eHalf = new int[eCount];
        int eid = 0;
        for (int he = 0; he < HE; he++) {
            int tw = twin[he];
            if (tw == MeshTopology.NONE || he < tw) {
                heEdge[he] = eid;
                eHalf[eid] = he;
                if (tw != MeshTopology.NONE) {
                    heEdge[tw] = eid;
                }
                eid++;
            }
        }

        // CSR vertex-face
        int[] vfOff = new int[vertexCount + 1];
        for (int fi = 0; fi < faceCount; fi++) {
            for (int k = 0; k < vertsPerFace; k++) {
                vfOff[faceIndices[fi * vertsPerFace + k] + 1]++;
            }
        }
        for (int i = 1; i <= vertexCount; i++) {
            vfOff[i] += vfOff[i - 1];
        }
        int[] vfData = new int[vfOff[vertexCount]];
        int[] vfWrite = Arrays.copyOf(vfOff, vertexCount + 1);
        for (int fi = 0; fi < faceCount; fi++) {
            for (int k = 0; k < vertsPerFace; k++) {
                int vid = faceIndices[fi * vertsPerFace + k];
                vfData[vfWrite[vid]++] = fi;
            }
        }

        // CSR vertex-edge
        int[] veOff = new int[vertexCount + 1];
        for (int e = 0; e < eCount; e++) {
            int he = eHalf[e];
            veOff[faceIndices[he] + 1]++;
            veOff[faceIndices[nextHalfEdge(he, vertsPerFace)] + 1]++;
        }
        for (int i = 1; i <= vertexCount; i++) {
            veOff[i] += veOff[i - 1];
        }
        int[] veData = new int[veOff[vertexCount]];
        int[] veWrite = Arrays.copyOf(veOff, vertexCount + 1);
        for (int e = 0; e < eCount; e++) {
            int he = eHalf[e];
            int a = faceIndices[he];
            int b = faceIndices[nextHalfEdge(he, vertsPerFace)];
            veData[veWrite[a]++] = e;
            veData[veWrite[b]++] = e;
        }

        return new QuadMeshTopologyHelper(HE, eCount, twin, heEdge, eHalf, vfOff, vfData, veOff, veData);
    }
}
