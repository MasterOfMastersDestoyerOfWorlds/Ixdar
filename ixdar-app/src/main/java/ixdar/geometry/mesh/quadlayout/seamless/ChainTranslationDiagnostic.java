package ixdar.geometry.mesh.quadlayout.seamless;

import java.util.HashSet;
import java.util.Set;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;

/**
 * Walk every chain in the cut graph and compute the per-edge implied chain
 * translation {@code (s, t) = chart_minus − R_{r_chain} · chart_plus}. For a
 * mathematically-consistent chain, this value is constant across all edges
 * in the chain (the BZK09 seam equation forces it at every shared vertex).
 *
 * <p>If our chains have non-constant implied translations, the per-edge
 * {@code cutEdgeSDof/cutEdgeTDof} integer DOFs were rounded independently
 * to inconsistent integers — this is exactly the failure mode that
 * produces ribbons whose thickness scales with chain length (the per-edge
 * mismatch accumulates as drift along the chain). In that case, sharing
 * one {@code (s, t)} DOF per chain (Path 1) would force consistency and
 * remove the drift.
 *
 * <p>If implied translations are already constant per chain, Path 1 cannot
 * help — the bug is elsewhere.
 */
public final class ChainTranslationDiagnostic {

    private static final int CORNERS_PER_FACE = SeamlessParameterization.CORNERS_PER_FACE;
    private static final int ROSY_ROTATION_COUNT = 4;
    private static final double CONSISTENT_TOLERANCE = 1.0e-6;

    private ChainTranslationDiagnostic() {
    }

    /**
     * Walk every chain in {@code seamless.cutGraph}, compute the implied
     * chain translation at every edge of every chain, and print a summary
     * stating whether Path 1 (per-chain DOF sharing) would resolve the
     * observed ribbons.
     *
     * @param seamless a built {@link SeamlessParameterization} whose
     *                 {@code uCorner}/{@code vCorner} arrays have already
     *                 been populated (i.e. after
     *                 {@code writeChartVerticesFromSolution})
     */
    public static void report(SeamlessParameterization seamless) {
        CutGraph cutGraph = seamless.cutGraph;
        CrossField crossField = seamless.crossField;
        HalfEdgeMesh mesh = seamless.mesh;
        Set<Integer> singularityVertexIds = new HashSet<>();
        for (Singularity s : crossField.singularities) {
            singularityVertexIds.add(s.vertexId());
        }

        boolean[] walkedCutEdge = new boolean[seamless.edgeCount];
        int chainCount = 0;
        int chainsConsistent = 0;
        int chainsInconsistent = 0;
        int chainsWithABFlip = 0;
        double maxSpread = 0.0;
        double sumInconsistentSpread = 0.0;
        int maxSpreadChainLength = 0;

        for (int seedEdge = 0; seedEdge < seamless.edgeCount; seedEdge++) {
            if (!cutGraph.isCutEdge[seedEdge] || walkedCutEdge[seedEdge]) {
                continue;
            }
            if (seamless.edgeFaceA[seedEdge] < 0 || seamless.edgeFaceB[seedEdge] < 0) {
                continue;
            }
            ChainStats stats = walkAndMeasure(seamless, singularityVertexIds, walkedCutEdge, seedEdge);
            chainCount++;
            if (stats.aFlippedSomewhere) {
                chainsWithABFlip++;
            }
            if (stats.maxComponentSpread < CONSISTENT_TOLERANCE) {
                chainsConsistent++;
            } else {
                chainsInconsistent++;
                sumInconsistentSpread += stats.maxComponentSpread;
                if (stats.maxComponentSpread > maxSpread) {
                    maxSpread = stats.maxComponentSpread;
                    maxSpreadChainLength = stats.edgeCount;
                }
            }
        }

        double meanInconsistentSpread = chainsInconsistent == 0 ? 0.0
                : sumInconsistentSpread / chainsInconsistent;
        System.out.printf(
                "[chain-diag] chains=%d  consistent=%d  inconsistent=%d  withABFlip=%d%n",
                chainCount, chainsConsistent, chainsInconsistent, chainsWithABFlip);
        System.out.printf(
                "[chain-diag] inconsistent-spread: max=%.4f (chainLen=%d)  mean=%.4f%n",
                maxSpread, maxSpreadChainLength, meanInconsistentSpread);
        String verdict;
        if (chainsInconsistent == 0) {
            verdict = "Path-1 would NOT help — every chain already has consistent (s, t) across its edges";
        } else if (chainsConsistent == 0) {
            verdict = "Path-1 WOULD help — every multi-edge chain shows per-edge translation drift";
        } else {
            verdict = String.format("Path-1 would help on %d/%d chains", chainsInconsistent, chainCount);
        }
        System.out.println("[chain-diag] verdict: " + verdict);
    }

    /**
     * Walk one chain starting at {@code seedEdge}, accumulating per-edge
     * implied chain translations and reporting spread + A/B-flip status.
     *
     * @param seamless              built parametrization to read chart values from
     * @param singularityVertexIds  mesh vertex ids that are singularities (chain nodes)
     * @param walkedCutEdge         per-active-edge marker, updated as edges are visited
     * @param seedEdge              an unwalked interior cut edge to seed the chain at
     * @return per-chain statistics including translation spread and edge count
     */
    private static ChainStats walkAndMeasure(SeamlessParameterization seamless,
            Set<Integer> singularityVertexIds, boolean[] walkedCutEdge, int seedEdge) {
        CutGraph cutGraph = seamless.cutGraph;
        HalfEdgeMesh mesh = seamless.mesh;

        // Walk backward to find chain start (singularity or cut-degree ≠ 2 vertex).
        int[] seedEndpoints = edgeEndpoints(seamless, seedEdge);
        int backwardEdge = seedEdge;
        int backwardVertex = seedEndpoints[0];
        while (!isNode(cutGraph, mesh, backwardVertex, singularityVertexIds)) {
            int nextEdge = otherCutEdgeAtActiveVertex(seamless, backwardVertex, backwardEdge);
            if (nextEdge < 0 || nextEdge == seedEdge) {
                break;
            }
            int[] endpoints = edgeEndpoints(seamless, nextEdge);
            backwardVertex = (endpoints[0] == backwardVertex) ? endpoints[1] : endpoints[0];
            backwardEdge = nextEdge;
        }
        int startNodeActiveVertex = backwardVertex;
        int firstEdge = backwardEdge;

        // Forward walk: at each edge compute implied chain translation,
        // tracking the chain's chosen plus side via chart-vertex continuity.
        int currentVertex = startNodeActiveVertex;
        int currentEdge = firstEdge;
        int currentPlusFace = seamless.edgeFaceA[firstEdge];
        int chainRotation = -1;
        boolean aFlippedSomewhere = false;
        double minS = Double.POSITIVE_INFINITY;
        double maxS = Double.NEGATIVE_INFINITY;
        double minT = Double.POSITIVE_INFINITY;
        double maxT = Double.NEGATIVE_INFINITY;
        int edgeCount = 0;

        while (true) {
            edgeCount++;
            walkedCutEdge[currentEdge] = true;
            int faceA = seamless.edgeFaceA[currentEdge];
            int edgeRotation = cutGraph.cutRotation[currentEdge];
            boolean aIsPlus = (currentPlusFace == faceA);
            if (!aIsPlus) {
                aFlippedSomewhere = true;
            }
            int effectiveRotation = aIsPlus ? edgeRotation
                    : (ROSY_ROTATION_COUNT - edgeRotation) % ROSY_ROTATION_COUNT;
            if (chainRotation < 0) {
                chainRotation = effectiveRotation;
            }

            double[] chainST = impliedChainTranslation(seamless, currentEdge,
                    currentPlusFace, chainRotation, currentVertex);
            if (chainST != null) {
                if (chainST[0] < minS) {
                    minS = chainST[0];
                }
                if (chainST[0] > maxS) {
                    maxS = chainST[0];
                }
                if (chainST[1] < minT) {
                    minT = chainST[1];
                }
                if (chainST[1] > maxT) {
                    maxT = chainST[1];
                }
            }

            int[] endpoints = edgeEndpoints(seamless, currentEdge);
            int nextVertex = (endpoints[0] == currentVertex) ? endpoints[1] : endpoints[0];
            if (isNode(cutGraph, mesh, nextVertex, singularityVertexIds)) {
                break;
            }
            int nextEdge = otherCutEdgeAtActiveVertex(seamless, nextVertex, currentEdge);
            if (nextEdge < 0 || walkedCutEdge[nextEdge]) {
                break;
            }
            int plusChartAtNext = chartVertexOfFaceAtActiveVertex(seamless, currentPlusFace, nextVertex);
            currentPlusFace = pickPlusFaceForNextEdge(seamless, nextEdge, nextVertex, plusChartAtNext);
            currentEdge = nextEdge;
            currentVertex = nextVertex;
        }

        ChainStats stats = new ChainStats();
        stats.edgeCount = edgeCount;
        stats.aFlippedSomewhere = aFlippedSomewhere;
        stats.maxComponentSpread = (edgeCount <= 1) ? 0.0
                : Math.max(maxS - minS, maxT - minT);
        return stats;
    }

    /**
     * Compute the chain translation implied by one edge of a chain,
     * evaluated at one of its endpoint vertices.
     *
     * @param seamless         built parametrization
     * @param activeEdge       the edge whose implied chain translation to compute
     * @param plusActiveFace   active face on the chain's plus side at this edge
     * @param chainRotation    chain's plus-to-minus rotation r ∈ {0..3}
     * @param activeVertex     vertex at which to evaluate
     * @return {@code [s_chain, t_chain]} implied at this (edge, vertex), or
     *         {@code null} if either chart vertex couldn't be located
     */
    private static double[] impliedChainTranslation(SeamlessParameterization seamless,
            int activeEdge, int plusActiveFace, int chainRotation, int activeVertex) {
        int faceA = seamless.edgeFaceA[activeEdge];
        int faceB = seamless.edgeFaceB[activeEdge];
        int minusActiveFace = (plusActiveFace == faceA) ? faceB : faceA;
        int plusCorner = cornerInFaceAtVertex(seamless, plusActiveFace, activeVertex);
        int minusCorner = cornerInFaceAtVertex(seamless, minusActiveFace, activeVertex);
        if (plusCorner < 0 || minusCorner < 0) {
            return null;
        }
        double uPlus = seamless.uCorner[plusActiveFace * CORNERS_PER_FACE + plusCorner];
        double vPlus = seamless.vCorner[plusActiveFace * CORNERS_PER_FACE + plusCorner];
        double uMinus = seamless.uCorner[minusActiveFace * CORNERS_PER_FACE + minusCorner];
        double vMinus = seamless.vCorner[minusActiveFace * CORNERS_PER_FACE + minusCorner];
        int cos = integerCosine(chainRotation);
        int sin = integerSine(chainRotation);
        double rotatedPlusU = cos * uPlus - sin * vPlus;
        double rotatedPlusV = sin * uPlus + cos * vPlus;
        return new double[] { uMinus - rotatedPlusU, vMinus - rotatedPlusV };
    }

    /**
     * Find which corner index in {@code activeFace} sits at
     * {@code activeVertex}.
     *
     * @param seamless     parametrization
     * @param activeFace   dense face index
     * @param activeVertex dense vertex index
     * @return corner index in {@code [0, 3)} or {@code -1} if the vertex is
     *         not a corner of the face
     */
    private static int cornerInFaceAtVertex(SeamlessParameterization seamless,
            int activeFace, int activeVertex) {
        HalfEdgeMesh mesh = seamless.mesh;
        int faceId = mesh.faceIdAt(activeFace);
        int vertexId = mesh.vertexIdAt(activeVertex);
        for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
            if (mesh.faceVertexAt(faceId, corner) == vertexId) {
                return corner;
            }
        }
        return -1;
    }

    /**
     * Look up the chart vertex of face {@code activeFace} at the corner
     * sitting on {@code activeVertex}.
     *
     * @param seamless     parametrization
     * @param activeFace   dense face index
     * @param activeVertex dense vertex index
     * @return chart vertex id, or {@code -1} if the corner is missing
     */
    private static int chartVertexOfFaceAtActiveVertex(SeamlessParameterization seamless,
            int activeFace, int activeVertex) {
        int corner = cornerInFaceAtVertex(seamless, activeFace, activeVertex);
        if (corner < 0) {
            return -1;
        }
        return seamless.cutGraph.cornerToChartVertex[activeFace * CORNERS_PER_FACE + corner];
    }

    /**
     * Decide which of {@code activeEdge}'s two faces continues the chain's
     * plus side at {@code activeVertex}, by matching chart-vertex continuity.
     *
     * @param seamless          parametrization
     * @param activeEdge        the edge entering {@code activeVertex}
     * @param activeVertex      vertex at the chain bend
     * @param plusChartAtVertex chart vertex on the plus side at this vertex
     * @return active face index that should be labelled plus on {@code activeEdge},
     *         or {@code edgeFaceA[activeEdge]} as a safe fallback if no match found
     */
    private static int pickPlusFaceForNextEdge(SeamlessParameterization seamless,
            int activeEdge, int activeVertex, int plusChartAtVertex) {
        int faceA = seamless.edgeFaceA[activeEdge];
        int faceB = seamless.edgeFaceB[activeEdge];
        int chartA = chartVertexOfFaceAtActiveVertex(seamless, faceA, activeVertex);
        if (chartA == plusChartAtVertex) {
            return faceA;
        }
        int chartB = chartVertexOfFaceAtActiveVertex(seamless, faceB, activeVertex);
        if (chartB == plusChartAtVertex) {
            return faceB;
        }
        return faceA;
    }

    /**
     * Whether an active vertex is a chain node (cut-degree ≠ 2 or
     * singularity).
     *
     * @param cutGraph             cut graph
     * @param mesh                 mesh providing vertex id lookup
     * @param activeVertex         dense vertex index
     * @param singularityVertexIds set of singularity mesh vertex ids
     * @return {@code true} iff this vertex terminates a chain walk
     */
    private static boolean isNode(CutGraph cutGraph, HalfEdgeMesh mesh,
            int activeVertex, Set<Integer> singularityVertexIds) {
        if (cutGraph.cutDegree[activeVertex] != 2) {
            return true;
        }
        return singularityVertexIds.contains(mesh.vertexIdAt(activeVertex));
    }

    /**
     * Return the two dense vertex indices that an active edge connects.
     *
     * @param seamless    parametrization providing the edge/mesh
     * @param activeEdge  dense edge index
     * @return length-2 array of active vertex indices
     */
    private static int[] edgeEndpoints(SeamlessParameterization seamless, int activeEdge) {
        HalfEdgeMesh mesh = seamless.mesh;
        int edgeId = mesh.edgeIdAt(activeEdge);
        int halfEdge = mesh.edgeHalfEdge(edgeId);
        int vStartId = mesh.halfEdgeVertex(halfEdge);
        int vEndId = mesh.halfEdgeEndVertex(halfEdge);
        return new int[] {
                seamless.cutGraph.activeVertexIndex(vStartId),
                seamless.cutGraph.activeVertexIndex(vEndId),
        };
    }

    /**
     * Return the interior cut edge incident to {@code activeVertex} that is
     * not {@code currentActiveEdge}.
     *
     * @param seamless          parametrization
     * @param activeVertex      dense vertex index (cut-degree 2)
     * @param currentActiveEdge edge to exclude
     * @return the other interior cut edge or {@code -1} if absent
     */
    private static int otherCutEdgeAtActiveVertex(SeamlessParameterization seamless,
            int activeVertex, int currentActiveEdge) {
        HalfEdgeMesh mesh = seamless.mesh;
        CrossField crossField = seamless.crossField;
        CutGraph cutGraph = seamless.cutGraph;
        int vertexId = mesh.vertexIdAt(activeVertex);
        int incidentCount = mesh.vertexEdgeCount(vertexId);
        for (int i = 0; i < incidentCount; i++) {
            int edgeId = mesh.vertexEdgeAt(vertexId, i);
            if (mesh.isBoundaryEdge(edgeId)) {
                continue;
            }
            int activeEdge = crossField.edgeIdToActive.get(edgeId);
            if (activeEdge == currentActiveEdge) {
                continue;
            }
            if (!cutGraph.isCutEdge[activeEdge]) {
                continue;
            }
            if (seamless.edgeFaceA[activeEdge] < 0 || seamless.edgeFaceB[activeEdge] < 0) {
                continue;
            }
            return activeEdge;
        }
        return -1;
    }

    /**
     * Integer cosine table for the four cardinal RoSy rotations.
     *
     * @param rotation rotation in {0..3} (input modulo 4)
     * @return {@code cos(rotation · π / 2)} as one of {0, ±1}
     */
    private static int integerCosine(int rotation) {
        switch (rotation & (ROSY_ROTATION_COUNT - 1)) {
            case 0: return 1;
            case 1: return 0;
            case 2: return -1;
            default: return 0;
        }
    }

    /**
     * Integer sine table for the four cardinal RoSy rotations.
     *
     * @param rotation rotation in {0..3} (input modulo 4)
     * @return {@code sin(rotation · π / 2)} as one of {0, ±1}
     */
    private static int integerSine(int rotation) {
        switch (rotation & (ROSY_ROTATION_COUNT - 1)) {
            case 0: return 0;
            case 1: return 1;
            case 2: return 0;
            default: return -1;
        }
    }
}
