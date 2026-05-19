package ixdar.geometry.mesh.quadlayout.crossfield;

import java.util.Arrays;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh.EdgeFaceIds;

public class VornoiForest {

    public final HalfEdgeMesh mesh;

    public int faceCount;
    public int edgeCount;

    /**
     * Per-edge flag indicating whether the period jump is fixed.
     */
    public boolean[] periodFixed;

    /**
     * Per-edge value of the period jump.
     */
    public int[] periodValue;

    public CrossField crossField;

    /*
     * A3. Voronoi spanning forest in the dual graph
     */
    public VornoiForest(HalfEdgeMesh mesh, CrossField crossField) {
        this.mesh = mesh;
        this.faceCount = mesh.faceCount();
        this.edgeCount = mesh.edgeCount();
        this.crossField = crossField;
        this.periodFixed = new boolean[edgeCount];
        this.periodValue = new int[edgeCount];
    }

    /**
     * Multi-source Dijkstra over the dual graph rooted at every constrained face;
     * the shortest-parent edge of each non-constrained face becomes a forest edge
     * whose period jump is fixed to zero in BZK09 §A3.
     *
     * @param faceConstrained per-face flag indicating dual-graph sources
     * @return active edge ids of the spanning-forest edges
     */
    public void buildVoronoiSpanningForest() {

        float[] dist = new float[faceCount];
        int[] parentEdgeAi = new int[faceCount];
        Arrays.fill(dist, Float.POSITIVE_INFINITY);
        Arrays.fill(parentEdgeAi, -1);

        PriorityQueue<DijkstraNode> pq = new PriorityQueue<>();
        for (int fAi = 0; fAi < faceCount; fAi++) {
            if (crossField.faceConstrained[fAi]) {
                dist[fAi] = 0f;
                pq.offer(new DijkstraNode(0f, fAi));
            }
        }
        Vector3f va = new Vector3f();
        Vector3f vb = new Vector3f();

        while (!pq.isEmpty()) {
            DijkstraNode node = pq.poll();
            int fAi = node.vertexOrFace;
            if (node.distance > dist[fAi] + CrossField.EPSILON)
                continue;
            int fId = mesh.faceIdAt(fAi);
            int adj = mesh.faceHalfEdgeCount(fId);
            for (int i = 0; i < adj; i++) {
                int he = mesh.faceHalfEdgeAt(fId, i);
                int twin = mesh.halfEdgeTwin(he);
                int gId = mesh.halfEdgeFace(twin);
                if (gId == MeshTopology.NONE)
                    continue;
                int gAi = crossField.faceIdToActive.get(gId);
                int eId = mesh.halfEdgeEdge(he);
                int eAi = crossField.edgeIdToActive.get(eId);

                int v0 = mesh.halfEdgeVertex(he);
                int v1 = mesh.halfEdgeEndVertex(he);
                mesh.vertexPosition(v0, va);
                mesh.vertexPosition(v1, vb);
                float w = (float) Math.sqrt(
                        (vb.x - va.x) * (vb.x - va.x) +
                                (vb.y - va.y) * (vb.y - va.y) +
                                (vb.z - va.z) * (vb.z - va.z));
                float nd = node.distance + w;
                if (nd < dist[gAi]) {
                    dist[gAi] = nd;
                    parentEdgeAi[gAi] = eAi;
                    pq.offer(new DijkstraNode(nd, gAi));
                }
            }
        }

        Set<Integer> forest = new HashSet<>();
        for (int fAi = 0; fAi < faceCount; fAi++) {
            if (parentEdgeAi[fAi] >= 0 && !crossField.faceConstrained[fAi]) {
                forest.add(parentEdgeAi[fAi]);
            }
        }
        for (int edge : forest) {
            periodFixed[edge] = true;
            periodValue[edge] = 0;
        }
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            if (periodFixed[eAi])
                continue;
            EdgeFaceIds edgeFaceIds = mesh.edgeFaceIds(eAi);
            if (mesh.isBoundaryEdge(edgeFaceIds.edgeId)) {
                periodFixed[eAi] = true;
                periodValue[eAi] = 0;
                continue;
            }
            if (crossField.faceConstrained[crossField.faceIdToActive.get(edgeFaceIds.faceA)]
                    && crossField.faceConstrained[crossField.faceIdToActive.get(edgeFaceIds.faceB)]) {
                float diff = crossField.faceConstraintAngle[crossField.faceIdToActive.get(edgeFaceIds.faceB)]
                        - crossField.faceConstraintAngle[crossField.faceIdToActive.get(edgeFaceIds.faceA)]
                        - crossField.kappa[eAi];
                int p = Math.round(diff / (float) (Math.PI / 2.0));
                periodFixed[eAi] = true;
                periodValue[eAi] = p;
            }
        }

    }
}
