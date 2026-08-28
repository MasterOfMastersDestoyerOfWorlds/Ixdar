package ixdar.geometry.mesh.quadlayout.seamless;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.seamless.exact.ExactArithmetic;

public class ParameterizationMetrics {

    public static final float HALF = 0.5f;
    public static final float SVD_DET_FACTOR = 4.0f;
    /**
     * Indices into the 4-element {@code [u_p, v_p, u_q, v_q]} array returned by
     * {@link SeamlessUv#lookupCorners}.
     */
    public static final int IDX_UQ = 2;
    /** {@link #IDX_UQ} sibling. */
    public static final int IDX_VQ = 3;

    public final SeamlessParameterization seamless;
    public final HalfEdgeMesh mesh;
    public final int faceCount;
    public final int edgeCount;
    public int flippedTriangleCount;
    public double maxTransitionResidual;
    public double meanTransitionResidual;
    public double meanDistortion;
    public int disconnectedChartCount;
    public int validBranchConsistency;

    /**
     * Build all metric summaries by running each measurement against the freshly
     * solved {@code seamless} state and storing the result in the corresponding
     * public field.
     *
     * @param seamless the solved parameterization to measure
     * @param mesh     the mesh the parameterization was built on
     */
    public ParameterizationMetrics(SeamlessParameterization seamless, HalfEdgeMesh mesh) {
        this.seamless = seamless;
        this.mesh = mesh;
        this.faceCount = mesh.faceCount();
        this.edgeCount = mesh.edgeCount();
        this.flippedTriangleCount = countFlippedTriangles();
        this.maxTransitionResidual = computeMaxTransitionResidual();
        this.meanTransitionResidual = computeMeanTransitionResidual();
        this.meanDistortion = computeMeanDistortion();
        this.disconnectedChartCount = countDisconnectedCharts();
        this.validBranchConsistency = branchConsistencyViolations();
    }

    /**
     * Counts the number of flipped triangles.
     *
     * @return the number of flipped triangles
     */
    public int countFlippedTriangles() {
        int flipped = 0;
        for (int af = 0; af < faceCount; af++) {
            int faceId = mesh.faceIdAt(af);
            if (seamless.uv.uvSignedArea(faceId) <= 0.0f) {
                flipped++;
            }
        }
        return flipped;
    }

    /**
     * Computes the maximum transition residual.
     *
     * @return the maximum transition residual
     */
    public double computeMaxTransitionResidual() {
        double worst = 0.0;
        for (int ae = 0; ae < edgeCount; ae++) {
            if (!seamless.uv.cutGraph.isCutEdge[ae])
                continue;
            int eId = mesh.edgeIdAt(ae);
            if (mesh.isBoundaryEdge(eId))
                continue; // boundary cut: no transition

            int hCanon = mesh.edgeHalfEdge(eId);
            int twin = mesh.halfEdgeTwin(hCanon);
            int faceA = mesh.halfEdgeFace(hCanon);
            int faceB = mesh.halfEdgeFace(twin);
            int vStart = mesh.halfEdgeVertex(hCanon);
            int vEnd = mesh.halfEdgeEndVertex(hCanon);

            double[] coordsA = seamless.uv.lookupCorners(mesh, faceA, vStart, vEnd);
            double[] coordsB = seamless.uv.lookupCorners(mesh, faceB, vStart, vEnd);

            int r = seamless.uv.cutGraph.cutRotation[ae];
            float cr = ExactArithmetic.integerCosine(r);
            float sr = ExactArithmetic.integerSine(r);
            double s = seamless.uv.cutTranslationS[ae];
            double t = seamless.uv.cutTranslationT[ae];

            double upGexpected = cr * coordsA[0] - sr * coordsA[1] + s;
            double vpGexpected = sr * coordsA[0] + cr * coordsA[1] + t;
            double uqGexpected = cr * coordsA[IDX_UQ] - sr * coordsA[IDX_VQ] + s;
            double vqGexpected = sr * coordsA[IDX_UQ] + cr * coordsA[IDX_VQ] + t;

            worst = Math.max(worst, Math.abs(upGexpected - coordsB[0]));
            worst = Math.max(worst, Math.abs(vpGexpected - coordsB[1]));
            worst = Math.max(worst, Math.abs(uqGexpected - coordsB[IDX_UQ]));
            worst = Math.max(worst, Math.abs(vqGexpected - coordsB[IDX_VQ]));
        }
        return worst;
    }

    /**
     * Mean per-component absolute residual of the seam transition equation across
     * all interior cut edges. Averages all four components (two endpoint corners ×
     * (u, v)) per edge.
     *
     * @return the average |R·u_A + t − u_B|, or 0 if there are no interior cut
     *         edges
     */
    public double computeMeanTransitionResidual() {
        double sum = 0.0;
        int componentCount = 0;
        for (int activeEdge = 0; activeEdge < edgeCount; activeEdge++) {
            if (!seamless.uv.cutGraph.isCutEdge[activeEdge])
                continue;
            int edgeId = mesh.edgeIdAt(activeEdge);
            if (mesh.isBoundaryEdge(edgeId))
                continue;

            int halfEdge = mesh.edgeHalfEdge(edgeId);
            int twin = mesh.halfEdgeTwin(halfEdge);
            int faceA = mesh.halfEdgeFace(halfEdge);
            int faceB = mesh.halfEdgeFace(twin);
            int vStart = mesh.halfEdgeVertex(halfEdge);
            int vEnd = mesh.halfEdgeEndVertex(halfEdge);

            double[] coordsA = seamless.uv.lookupCorners(mesh, faceA, vStart, vEnd);
            double[] coordsB = seamless.uv.lookupCorners(mesh, faceB, vStart, vEnd);

            int rotation = seamless.uv.cutGraph.cutRotation[activeEdge];
            int cosR = ExactArithmetic.integerCosine(rotation);
            int sinR = ExactArithmetic.integerSine(rotation);
            double translationS = seamless.uv.cutTranslationS[activeEdge];
            double translationT = seamless.uv.cutTranslationT[activeEdge];

            double expectedUp = cosR * coordsA[0] - sinR * coordsA[1] + translationS;
            double expectedVp = sinR * coordsA[0] + cosR * coordsA[1] + translationT;
            double expectedUq = cosR * coordsA[IDX_UQ] - sinR * coordsA[IDX_VQ] + translationS;
            double expectedVq = sinR * coordsA[IDX_UQ] + cosR * coordsA[IDX_VQ] + translationT;

            sum += Math.abs(expectedUp - coordsB[0]);
            sum += Math.abs(expectedVp - coordsB[1]);
            sum += Math.abs(expectedUq - coordsB[IDX_UQ]);
            sum += Math.abs(expectedVq - coordsB[IDX_VQ]);
            componentCount += coordsA.length;
        }
        return componentCount == 0 ? 0.0f : sum / componentCount;
    }

    /**
     * Mean Hormann-Lévy-Sheffer distortion {@code |σ₁/h − 1| + |σ₂/h − 1|} over all
     * non-degenerate faces, signed by the Jacobian's orientation so flipped
     * triangles inflate the result. {@code h} is
     * {@link SeamlessParameterization#targetEdgeLength}.
     *
     * @return the mean per-face distortion, or 0 if every face is degenerate
     */
    public double computeMeanDistortion() {
        double sum = 0.0;
        int counted = 0;
        float targetScale = seamless.uv.targetQuadEdgeLength;
        Vector3f position0 = new Vector3f();
        Vector3f position1 = new Vector3f();
        Vector3f position2 = new Vector3f();
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), position0);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 1), position1);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 2), position2);

            Vector3f edge01 = new Vector3f(position1).sub(position0);
            Vector3f edge02 = new Vector3f(position2).sub(position0);
            float length01 = edge01.length();
            if (length01 <= 0.0f)
                continue;
            float x2 = edge02.dot(edge01) / length01;
            float y2Squared = edge02.lengthSquared() - x2 * x2;
            if (y2Squared <= 0.0f)
                continue;
            float y2 = (float) Math.sqrt(y2Squared);

            int cornerBase = activeFace * SeamlessUv.CORNERS_PER_FACE;
            double u0 = seamless.uv.uCorner[cornerBase];
            double v0 = seamless.uv.vCorner[cornerBase];
            double u1 = seamless.uv.uCorner[cornerBase + 1];
            double v1 = seamless.uv.vCorner[cornerBase + 1];
            double u2 = seamless.uv.uCorner[cornerBase + 2];
            double v2 = seamless.uv.vCorner[cornerBase + 2];

            double duDx = (u1 - u0) / length01;
            double duDy = (u2 - u0 - duDx * x2) / y2;
            double dvDx = (v1 - v0) / length01;
            double dvDy = (v2 - v0 - dvDx * x2) / y2;

            double jacobianDet = duDx * dvDy - duDy * dvDx;
            double frobeniusSquared = duDx * duDx + duDy * duDy + dvDx * dvDx + dvDy * dvDy;

            double discriminant = Math.max(0.0,
                    frobeniusSquared * frobeniusSquared - SVD_DET_FACTOR * jacobianDet * jacobianDet);
            double discriminantSqrt = Math.sqrt(discriminant);
            double sigma1 = Math.sqrt(HALF * (frobeniusSquared + discriminantSqrt));
            double sigma2 = Math.sqrt(Math.max(0.0, HALF * (frobeniusSquared - discriminantSqrt)));
            double orientationSign = jacobianDet >= 0.0 ? 1.0 : -1.0;

            sum += Math.abs(orientationSign * sigma1 * targetScale - 1.0f)
                    + Math.abs(orientationSign * sigma2 * targetScale - 1.0f);
            counted++;
        }
        return counted == 0 ? 0.0 : sum / counted;
    }

    /**
     * Number of disconnected charts: connected components of the active-face graph
     * where two faces are joined iff they share a non-cut interior edge. More than
     * one means either a disconnected mesh or a cut graph that fragments the
     * surface.
     *
     * @return the chart-component count, ≥ 1 for any non-empty mesh
     */
    public int countDisconnectedCharts() {
        int[] parent = new int[faceCount];
        for (int i = 0; i < faceCount; i++)
            parent[i] = i;
        for (int activeEdge = 0; activeEdge < edgeCount; activeEdge++) {
            if (seamless.uv.cutGraph.isCutEdge[activeEdge])
                continue;
            int faceA = seamless.uv.edgeFaceA[activeEdge];
            int faceB = seamless.uv.edgeFaceB[activeEdge];
            if (faceA < 0 || faceB < 0)
                continue;
            int rootA = findRoot(parent, faceA);
            int rootB = findRoot(parent, faceB);
            if (rootA != rootB)
                parent[rootA] = rootB;
        }
        int components = 0;
        for (int i = 0; i < faceCount; i++) {
            if (findRoot(parent, i) == i)
                components++;
        }
        return components;
    }

    /**
     * Union-find find with path halving. Used by {@link #chartCount()} during
     * face-graph unioning and again when counting roots.
     *
     * @param parent in-place parent-pointer array
     * @param node   node whose root to locate
     * @return the root node id; {@code parent} is compacted along the way
     */
    private static int findRoot(int[] parent, int node) {
        while (parent[node] != node) {
            parent[node] = parent[parent[node]];
            node = parent[node];
        }
        return node;
    }

    /**
     * Count of interior edges where the BZK09 §5 coordinate-transition relation
     * {@code (faceBranch[A] − faceBranch[B] − periodJump) mod 4 == cutRotation[ae]}
     * is violated (with {@code cutRotation == 0} expected on non-cut edges). Zero
     * on a correctly built {@link CutGraph}; non-zero signals a bug in branch
     * propagation or rotation assignment.
     *
     * @return number of interior edges violating the branch relation
     */
    public int branchConsistencyViolations() {
        int violations = 0;
        int branchMask = SeamlessUv.BRANCH_COUNT - 1;
        int[] faceBranch = seamless.uv.cutGraph.faceBranch;
        int[] cutRotation = seamless.uv.cutGraph.cutRotation;
        boolean[] isCutEdge = seamless.uv.cutGraph.isCutEdge;
        int[] periodJump = seamless.field.periodJump;
        for (int activeEdge = 0; activeEdge < edgeCount; activeEdge++) {
            int faceA = seamless.uv.edgeFaceA[activeEdge];
            int faceB = seamless.uv.edgeFaceB[activeEdge];
            if (faceA < 0 || faceB < 0)
                continue;
            int relation = (faceBranch[faceA] - faceBranch[faceB] - periodJump[activeEdge]) & branchMask;
            int expected = isCutEdge[activeEdge] ? cutRotation[activeEdge] : 0;
            if (relation != expected)
                violations++;
        }
        return violations;
    }

    @Override
    public String toString() {
        return "ParameterizationMetrics [flippedTriangleCount=" + flippedTriangleCount + ", maxTransitionResidual=" + maxTransitionResidual + ", meanTransitionResidual=" + meanTransitionResidual + ", meanDistortion=" + meanDistortion + ", disconnectedChartCount=" + disconnectedChartCount + ", validBranchConsistency=" + validBranchConsistency + "]";
    }
}
