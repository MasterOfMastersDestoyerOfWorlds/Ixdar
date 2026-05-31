package ixdar.geometry.mesh.quadlayout.crossfield;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ixdar.geometry.mesh.data.load.CrossFieldLoader;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * Cross-field quality regression for fandisk. The actual failure mode we are
 * chasing is not raw singularity count (which can drop in misleading ways) but
 * **cluster pairs**: opposite-sign singularities sitting a handful of primal
 * edges apart, both anchored near curvature-constrained faces. These are the
 * artifacts the curvature pass emits along sharp creases when multiple nearby
 * vertices' shape operators disagree slightly and the smoothness energy
 * expresses the disagreement as a ± period-jump pair.
 *
 * <p>
 * A clean cross field on fandisk should have zero such cluster pairs. The
 * paper's quad layout has roughly a dozen singularities on this model, all at
 * geometric corners (no nearby opposite-sign neighbors).
 */
public final class CrossFieldFandiskTest {

    private static final String FANDISK_TRI = "test/resources/quadlayout/figure_10/fandisk_in_tri.off";
    private static final String FANDISK_REF_NDF = "test/resources/quadlayout/figure_10/fandisk_in_cf.ndf";
    private static final String ELK_TRI = "test/resources/quadlayout/figure_8/elk_in_tri.off";
    private static final String ELK_REF_NDF = "test/resources/quadlayout/figure_8/elk_in_cf.ndf";
    private static final double HALF_PI_D = Math.PI / 2.0;
    private static final double QUARTER_PI_D = Math.PI / 4.0;
    private static final double CLOSE_ANGLE_THRESHOLD = Math.PI / 18.0;
    /**
     * Maximum primal-edge hop distance at which two opposite-sign singularities are
     * considered to form a "cluster pair." Anything within this distance is
     * unlikely to be a real singularity-layout choice and far more likely to be a
     * curvature-induced ± artifact.
     */
    private static final int CLUSTER_HOP_LIMIT = 8;

    /**
     * Build the cross field on fandisk and assert it produces zero cluster pairs
     * (opposite-sign singularities within {@link #CLUSTER_HOP_LIMIT} primal-edge
     * hops of each other, with either's 1-ring touching a curvature-pinned face).
     *
     * @throws Exception when loading the mesh fails
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void crossFieldOnFandiskHasNoCurvatureInducedClusterPairs() throws Exception {
        assertNoCurvatureClusterPairs("fandisk", FANDISK_TRI);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void crossFieldOnElkHasNoCurvatureInducedClusterPairs() throws Exception {
        assertNoCurvatureClusterPairs("elk", ELK_TRI);
    }

    /**
     * Diagnostic-only: load the BCEAK13 reference ELK cross field and print the
     * per-vertex singularity set difference (extras we have that reference doesn't,
     * missing ones reference has that we don't), each annotated with the curvature
     * source vertex pinning its 1-ring if any. Not an assertion — gives
     * ground-truth visibility into where our 20 excess singularities are coming
     * from so the next fix can be informed by data, not guessing.
     *
     * @throws Exception when loading the mesh or reference NDF fails
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void elkSingularityDiffAgainstReference() throws Exception {
        ArrayMesh arrayMesh = MeshLoader.load(ELK_TRI);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());

        CrossField ours = new CrossField(mesh).build();
        CrossField reference = CrossFieldLoader.load(ELK_REF_NDF, mesh);

        Set<Integer> oursVertices = new HashSet<>();
        for (Singularity singularity : ours.singularities) {
            oursVertices.add(singularity.vertexId());
        }
        Set<Integer> referenceVertices = new HashSet<>();
        for (Singularity singularity : reference.singularities) {
            referenceVertices.add(singularity.vertexId());
        }

        Set<Integer> extras = new HashSet<>(oursVertices);
        extras.removeAll(referenceVertices);
        Set<Integer> missing = new HashSet<>(referenceVertices);
        missing.removeAll(oursVertices);

        System.out.printf(
                "[elk-diff] ours=%d reference=%d shared=%d extras-in-ours=%d missing-from-ours=%d%n",
                oursVertices.size(), referenceVertices.size(),
                oursVertices.size() - extras.size(), extras.size(), missing.size());
        int extrasNearCurvature = 0;
        for (int vertexId : extras) {
            int sourceVertex = curvatureSourceForVertex(mesh, ours, vertexId);
            int oneRing = mesh.vertexFaceCount(vertexId);
            int curvatureFaces = 0;
            for (int i = 0; i < oneRing; i++) {
                int faceId = mesh.vertexFaceAt(vertexId, i);
                int faceActive = ours.faceIdToActive.get(faceId);
                if (ours.curvatureSourceVertex[faceActive] >= 0) {
                    curvatureFaces++;
                }
            }
            int idx4 = signedIndexOf(ours, vertexId);
            if (sourceVertex >= 0) {
                extrasNearCurvature++;
            }
            System.out.printf(
                    "[elk-diff] EXTRA v=%d idx4=%+d curvFacesInRing=%d/%d firstSrc=%d%n",
                    vertexId, idx4, curvatureFaces, oneRing, sourceVertex);
        }
        for (int vertexId : missing) {
            int idx4 = signedIndexOf(reference, vertexId);
            System.out.printf("[elk-diff] MISSING v=%d idx4=%+d (reference has it; we don't)%n",
                    vertexId, idx4);
        }
        System.out.printf("[elk-diff] of %d extras, %d have curvature constraints in 1-ring (%.0f%%)%n",
                extras.size(), extrasNearCurvature,
                extras.isEmpty() ? 0 : 100.0 * extrasNearCurvature / extras.size());
    }

    /**
     * Diagnostic-only: compare our ELK face angles with the BCEAK13 reference
     * modulo cross symmetry, including ±45° shifts. If either shifted variant has
     * far smaller error, the discrepancy is probably a convention bug rather than
     * just constraint placement.
     *
     * @throws Exception when loading the mesh or reference NDF fails
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void elkThetaAlignmentAgainstReference() throws Exception {
        ArrayMesh arrayMesh = MeshLoader.load(ELK_TRI);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());

        CrossField ours = new CrossField(mesh).build();
        CrossField reference = CrossFieldLoader.load(ELK_REF_NDF, mesh);

        printThetaAlignment("raw", ours, reference, 0.0);
        printThetaAlignment("plus45", ours, reference, QUARTER_PI_D);
        printThetaAlignment("minus45", ours, reference, -QUARTER_PI_D);
        printThetaShiftPreference(ours, reference);
    }

    /**
     * Diagnostic-only: test whether the reference NDF is self-consistent under our
     * local frame, transport, and edge-orientation conventions. If reference
     * smoothness or singularity extraction fails here, the mismatch is likely in
     * the loader/frame convention rather than the solver constraints.
     *
     * @throws Exception when loading the mesh or reference NDF fails
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void elkReferenceConventionDiagnostic() throws Exception {
        ArrayMesh arrayMesh = MeshLoader.load(ELK_TRI);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());

        CrossField ours = new CrossField(mesh).build();
        CrossField reference = CrossFieldLoader.load(ELK_REF_NDF, mesh);

        printSmoothnessResidual("ours", mesh, ours, ours.theta, ours.periodJump, false, false);
        printSmoothnessResidual("reference-p", mesh, ours, reference.theta, reference.periodJump, false, false);
        printSmoothnessResidual("reference-neg-p", mesh, ours, reference.theta, reference.periodJump, true, false);
        printSmoothnessResidual("reference-neg-kappa", mesh, ours, reference.theta, reference.periodJump, false, true);
        printSmoothnessResidual("reference-neg-p-neg-kappa", mesh, ours, reference.theta, reference.periodJump, true,
                true);
        int[] negatedReferencePeriods = negated(reference.periodJump);
        float[] negatedKappa = negated(ours.kappa);
        int[] centeredReferencePeriods = centeredModFour(reference.periodJump);
        System.out.printf("[elk-ref-convention] negPRecomputed=%d negKappaRecomputed=%d negBothRecomputed=%d%n",
                recomputedSingularityCount(mesh, ours, reference.theta, negatedReferencePeriods),
                recomputedSingularityCount(mesh, ours, reference.theta, reference.periodJump, negatedKappa),
                recomputedSingularityCount(mesh, ours, reference.theta, negatedReferencePeriods, negatedKappa));
        printSmoothnessResidual("reference-centered-p", mesh, ours, reference.theta,
                centeredReferencePeriods, false, false);
        System.out.printf("[elk-ref-convention] centeredPRecomputed=%d%n",
                recomputedSingularityCount(mesh, ours, reference.theta, centeredReferencePeriods));
        for (int faceReferenceCorner = 0; faceReferenceCorner < 3; faceReferenceCorner++) {
            float[] alternateKappa = alternateKappaForFaceReferenceCorner(mesh, ours, faceReferenceCorner);
            printSmoothnessResidual("reference-face-corner-" + faceReferenceCorner, mesh, ours,
                    reference.theta, reference.periodJump, alternateKappa, false, false);
            int recomputed = recomputedSingularityCount(mesh, ours, reference.theta,
                    reference.periodJump, alternateKappa);
            System.out.printf("[elk-ref-convention] faceCorner=%d recomputedSingularities=%d%n",
                    faceReferenceCorner, recomputed);
        }
        float[][][] cornerKappa = cornerPairKappa(mesh, ours);
        int[] faceReferenceCorners = optimizeFaceReferenceCorners(mesh, ours, reference.theta,
                reference.periodJump, cornerKappa);
        float[] selectedKappa = selectedCornerKappa(mesh, ours, cornerKappa, faceReferenceCorners);
        printSmoothnessResidual("reference-selected-face-corners", mesh, ours, reference.theta,
                reference.periodJump, selectedKappa, false, false);
        System.out.printf("[elk-ref-convention] selectedFaceCornersRecomputed=%d%n",
                recomputedSingularityCount(mesh, ours, reference.theta, reference.periodJump, selectedKappa));
        int[] minMaxSortedPeriods = periodsMappedFromSortedEndpoints(mesh, reference.periodJump, true);
        int[] maxMinSortedPeriods = periodsMappedFromSortedEndpoints(mesh, reference.periodJump, false);
        printSmoothnessResidual("reference-minmax-edge-order", mesh, ours, reference.theta,
                minMaxSortedPeriods, false, false);
        printSmoothnessResidual("reference-maxmin-edge-order", mesh, ours, reference.theta,
                maxMinSortedPeriods, false, false);
        System.out.printf("[elk-ref-convention] minMaxOrderRecomputed=%d maxMinOrderRecomputed=%d%n",
                recomputedSingularityCount(mesh, ours, reference.theta, minMaxSortedPeriods),
                recomputedSingularityCount(mesh, ours, reference.theta, maxMinSortedPeriods));
        int[] referenceBestPeriods = bestPeriodsForTheta(mesh, ours, reference.theta, 0.0);
        printPeriodDifferenceHistogram("reference-vs-best", reference.periodJump, referenceBestPeriods);
        printSmoothnessResidual("reference-best-p", mesh, ours, reference.theta, referenceBestPeriods, false, false);
        int bestSingularities = recomputedSingularityCount(mesh, ours, reference.theta, referenceBestPeriods);
        int[] referenceShiftedBestPeriods = bestPeriodsForTheta(mesh, ours, reference.theta, QUARTER_PI_D);
        float[] shiftedReferenceTheta = shiftedTheta(reference.theta, QUARTER_PI_D);
        printSmoothnessResidual("reference-plus45-best-p", mesh, ours, shiftedReferenceTheta,
                referenceShiftedBestPeriods, false, false);
        int shiftedBestSingularities = recomputedSingularityCount(mesh, ours,
                shiftedReferenceTheta, referenceShiftedBestPeriods);

        int referenceLoadedCount = reference.singularities.size();
        reference.faceX = ours.faceX;
        reference.faceY = ours.faceY;
        reference.faceIdToActive = ours.faceIdToActive;
        reference.edgeIdToActive = ours.edgeIdToActive;
        reference.kappa = ours.kappa;
        reference.extractSingularities();
        System.out.printf("[elk-ref-convention] loadedSingularities=%d recomputedWithOurKappa=%d%n",
                referenceLoadedCount, reference.singularities.size());
        System.out.printf("[elk-ref-convention] bestPRecomputed=%d plus45BestPRecomputed=%d%n",
                bestSingularities, shiftedBestSingularities);
    }

    /**
     * Diagnostic-only: run the reference fandisk NDF through our seamless solve and
     * measure whether solved UV gradients actually follow the supplied theta.
     *
     * @throws Exception when loading or solving fails
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void fandiskReferenceSeamlessThetaDiagnostic() throws Exception {
        ArrayMesh arrayMesh = MeshLoader.load(FANDISK_TRI);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());

        CrossField frameField = new CrossField(mesh).build();
        CrossField reference = CrossFieldLoader.load(FANDISK_REF_NDF, mesh);
        CrossFieldLoader.alignPeriodJumpsToFrame(reference, frameField);
        frameField.theta = reference.theta;
        frameField.periodJump = reference.periodJump;
        frameField.singularities.clear();
        frameField.singularities.addAll(reference.singularities);
        printAlignmentEdgeThetaPreference("fandisk-reference-raw", mesh, frameField);
        CrossFieldLoader.convertBceak13ThetaToQuadAxes(frameField);
        printAlignmentEdgeThetaPreference("fandisk-reference-quad-axis", mesh, frameField);

        SeamlessParameterization seamless = new SeamlessParameterization(frameField);
        try {
            seamless.build();
        } catch (IllegalStateException ex) {
            System.out.println("[seamless-theta] build reported: " + ex.getMessage());
        }
        printSeamlessThetaAlignment("fandisk-reference", seamless);
    }

    /**
     * For each interior edge, choose the period jump that best matches
     * {@code theta + shift} under our transport convention.
     *
     * @param mesh       host mesh
     * @param frameField field whose maps/kappa define transport
     * @param theta      per-face theta values
     * @param shift      constant angle shift to apply
     * @return per-edge best integer period jumps
     */
    private static int[] bestPeriodsForTheta(HalfEdgeMesh mesh, CrossField frameField,
            float[] theta, double shift) {
        int[] periods = new int[mesh.edgeCount()];
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            int edgeId = mesh.edgeIdAt(activeEdge);
            if (mesh.isBoundaryEdge(edgeId)) {
                continue;
            }
            HalfEdgeMesh.EdgeFaceIds edgeFaces = mesh.edgeFaceIds(activeEdge);
            int faceA = frameField.faceIdToActive.get(edgeFaces.faceA);
            int faceB = frameField.faceIdToActive.get(edgeFaces.faceB);
            double target = theta[faceB] + shift - theta[faceA] - shift - frameField.kappa[activeEdge];
            periods[activeEdge] = (int) Math.round(target / HALF_PI_D);
        }
        return periods;
    }

    /**
     * Print aggregate alignment of solved UV gradients against the cross-field
     * targets.
     *
     * @param label    diagnostic label
     * @param seamless solved parameterization
     */
    private static void printSeamlessThetaAlignment(String label, SeamlessParameterization seamless) {
        double sumRaw = 0.0;
        double sumShifted = 0.0;
        double maxRaw = 0.0;
        double maxShifted = 0.0;
        int counted = 0;
        for (int activeFace = 0; activeFace < seamless.faceCount; activeFace++) {
            int cornerBase = activeFace * SeamlessParameterization.CORNERS_PER_FACE;
            double u0 = seamless.uCorner[cornerBase];
            double u1 = seamless.uCorner[cornerBase + 1];
            double u2 = seamless.uCorner[cornerBase + 2];
            double v0 = seamless.vCorner[cornerBase];
            double v1 = seamless.vCorner[cornerBase + 1];
            double v2 = seamless.vCorner[cornerBase + 2];
            double duDx = seamless.faceShapeB[cornerBase] * u0
                    + seamless.faceShapeB[cornerBase + 1] * u1
                    + seamless.faceShapeB[cornerBase + 2] * u2;
            double duDy = seamless.faceShapeC[cornerBase] * u0
                    + seamless.faceShapeC[cornerBase + 1] * u1
                    + seamless.faceShapeC[cornerBase + 2] * u2;
            double dvDx = seamless.faceShapeB[cornerBase] * v0
                    + seamless.faceShapeB[cornerBase + 1] * v1
                    + seamless.faceShapeB[cornerBase + 2] * v2;
            double dvDy = seamless.faceShapeC[cornerBase] * v0
                    + seamless.faceShapeC[cornerBase + 1] * v1
                    + seamless.faceShapeC[cornerBase + 2] * v2;
            double gradientLength = Math.hypot(duDx, duDy) + Math.hypot(dvDx, dvDy);
            if (gradientLength <= 0.0) {
                continue;
            }
            double theta = seamless.crossField.theta[activeFace]
                    + seamless.cutGraph.faceBranch[activeFace] * HALF_PI_D;
            double uAngle = Math.atan2(duDy, duDx);
            double vAngle = Math.atan2(dvDy, dvDx) - HALF_PI_D;
            double raw = 0.5 * (angleDifferenceModHalfPi(uAngle - theta)
                    + angleDifferenceModHalfPi(vAngle - theta));
            double shifted = 0.5 * (angleDifferenceModHalfPi(uAngle - theta - QUARTER_PI_D)
                    + angleDifferenceModHalfPi(vAngle - theta - QUARTER_PI_D));
            sumRaw += raw;
            sumShifted += shifted;
            maxRaw = Math.max(maxRaw, raw);
            maxShifted = Math.max(maxShifted, shifted);
            counted++;
        }
        System.out.printf("[seamless-theta] %s rawMean=%.6f rawMax=%.6f plus45Mean=%.6f plus45Max=%.6f faces=%d%n",
                label, sumRaw / counted, maxRaw, sumShifted / counted, maxShifted, counted);
    }

    /**
     * Print whether alignment edges are closer to theta or theta plus 45 degrees.
     *
     * @param label diagnostic label
     * @param mesh  host mesh
     * @param field cross field with alignment edge ids
     */
    private static void printAlignmentEdgeThetaPreference(String label, HalfEdgeMesh mesh,
            CrossField field) {
        double rawSum = 0.0;
        double shiftedSum = 0.0;
        int rawCloser = 0;
        int shiftedCloser = 0;
        int counted = 0;
        Vector3f start = new Vector3f();
        Vector3f end = new Vector3f();
        for (int edgeId : field.alignmentEdgeIds) {
            int activeEdge = field.edgeIdToActive.get(edgeId);
            HalfEdgeMesh.EdgeFaceIds edgeFaces = mesh.edgeFaceIds(activeEdge);
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            int startVertex = mesh.halfEdgeVertex(halfEdge);
            int endVertex = mesh.halfEdgeEndVertex(halfEdge);
            mesh.vertexPosition(startVertex, start);
            mesh.vertexPosition(endVertex, end);
            Vector3f edgeDirection = end.sub(start, new Vector3f());
            for (int faceId : new int[] { edgeFaces.faceA, edgeFaces.faceB }) {
                if (faceId < 0) {
                    continue;
                }
                int activeFace = field.faceIdToActive.get(faceId);
                double edgeAngle = mesh.projectDirectionToFaceAngle(edgeDirection, activeFace,
                        field.faceY[activeFace], field.faceX[activeFace]);
                double raw = angleDifferenceModHalfPi(edgeAngle - field.theta[activeFace]);
                double shifted = angleDifferenceModHalfPi(edgeAngle - field.theta[activeFace] - QUARTER_PI_D);
                rawSum += raw;
                shiftedSum += shifted;
                if (raw <= shifted) {
                    rawCloser++;
                } else {
                    shiftedCloser++;
                }
                counted++;
            }
        }
        System.out.printf(
                "[alignment-theta] %s rawMean=%.6f plus45Mean=%.6f rawCloser=%d shiftedCloser=%d samples=%d%n",
                label, rawSum / counted, shiftedSum / counted, rawCloser, shiftedCloser, counted);
    }

    /**
     * Print a histogram of period differences.
     *
     * @param label    diagnostic label
     * @param source   source periods
     * @param expected expected periods
     */
    private static void printPeriodDifferenceHistogram(String label, int[] source, int[] expected) {
        Map<Integer, Integer> histogram = new HashMap<>();
        int exact = 0;
        for (int i = 0; i < source.length; i++) {
            int difference = source[i] - expected[i];
            if (difference == 0) {
                exact++;
            }
            histogram.merge(difference, 1, Integer::sum);
        }
        System.out.printf("[elk-ref-convention] %s exact=%d/%d diffHistogram=%s%n",
                label, exact, source.length, histogram);
    }

    /**
     * Shift every theta value by a constant.
     *
     * @param theta source theta array
     * @param shift shift in radians
     * @return shifted theta array
     */
    private static float[] shiftedTheta(float[] theta, double shift) {
        float[] shifted = new float[theta.length];
        for (int i = 0; i < theta.length; i++) {
            shifted[i] = (float) (theta[i] + shift);
        }
        return shifted;
    }

    /**
     * Negate an integer array.
     *
     * @param values source values
     * @return negated copy
     */
    private static int[] negated(int[] values) {
        int[] negated = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            negated[i] = -values[i];
        }
        return negated;
    }

    /**
     * Center modulo-four period jumps around zero.
     *
     * @param values source values
     * @return centered copy
     */
    private static int[] centeredModFour(int[] values) {
        int[] centered = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            int value = values[i] % 4;
            if (value < 0) {
                value += 4;
            }
            if (value > 1) {
                value -= 4;
            }
            centered[i] = value;
        }
        return centered;
    }

    /**
     * Negate a float array.
     *
     * @param values source values
     * @return negated copy
     */
    private static float[] negated(float[] values) {
        float[] negated = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            negated[i] = -values[i];
        }
        return negated;
    }

    /**
     * Recompute singularities for a synthetic field using our kappa/maps.
     *
     * @param mesh       host mesh
     * @param frameField field whose maps/kappa define transport
     * @param theta      per-face theta values
     * @param periods    per-edge period jumps
     * @return recomputed singularity count
     */
    private static int recomputedSingularityCount(HalfEdgeMesh mesh, CrossField frameField,
            float[] theta, int[] periods) {
        return recomputedSingularityCount(mesh, frameField, theta, periods, frameField.kappa);
    }

    /**
     * Recompute singularities for a synthetic field using supplied kappa/maps.
     *
     * @param mesh       host mesh
     * @param frameField field whose maps define active ids
     * @param theta      per-face theta values
     * @param periods    per-edge period jumps
     * @param kappa      per-edge transport angles
     * @return recomputed singularity count
     */
    private static int recomputedSingularityCount(HalfEdgeMesh mesh, CrossField frameField,
            float[] theta, int[] periods, float[] kappa) {
        CrossField synthetic = new CrossField(mesh);
        synthetic.faceIdToActive = frameField.faceIdToActive;
        synthetic.edgeIdToActive = frameField.edgeIdToActive;
        synthetic.faceX = frameField.faceX;
        synthetic.faceY = frameField.faceY;
        synthetic.kappa = kappa;
        synthetic.theta = theta;
        synthetic.periodJump = periods;
        synthetic.extractSingularities();
        return synthetic.singularities.size();
    }

    /**
     * Compute transport angles when each face's local x-axis is a different corner
     * half-edge.
     *
     * @param mesh                host mesh
     * @param frameField          field providing active-id maps
     * @param faceReferenceCorner corner half-edge index used as the local x-axis
     * @return per-edge kappa values
     */
    private static float[] alternateKappaForFaceReferenceCorner(HalfEdgeMesh mesh,
            CrossField frameField, int faceReferenceCorner) {
        Vector3f[] faceX = new Vector3f[mesh.faceCount()];
        Vector3f[] faceY = new Vector3f[mesh.faceCount()];
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int halfEdge = mesh.faceHalfEdgeAt(faceId, faceReferenceCorner);
            int startVertex = mesh.halfEdgeVertex(halfEdge);
            int endVertex = mesh.halfEdgeEndVertex(halfEdge);
            Vector3f position0 = mesh.vertexPosition(startVertex);
            Vector3f position1 = mesh.vertexPosition(endVertex);
            Vector3f normal = mesh.faceNormal(faceId);
            Vector3f xAxis = new Vector3f(position1).sub(position0);
            float normalPart = xAxis.dot(normal);
            xAxis.x -= normalPart * normal.x;
            xAxis.y -= normalPart * normal.y;
            xAxis.z -= normalPart * normal.z;
            if (xAxis.length() > 0f) {
                xAxis.normalize();
            } else {
                CrossField.arbitraryTangent(normal, xAxis);
            }
            Vector3f yAxis = new Vector3f();
            normal.cross(xAxis, yAxis).normalize();
            faceX[activeFace] = xAxis;
            faceY[activeFace] = yAxis;
        }
        return alternateKappa(mesh, frameField, faceX, faceY);
    }

    /**
     * Compute kappa for every pair of source/target face reference corners.
     *
     * @param mesh       host mesh
     * @param frameField field providing active-id maps
     * @return indexed by active edge, source corner, target corner
     */
    private static float[][][] cornerPairKappa(HalfEdgeMesh mesh, CrossField frameField) {
        Vector3f[][] faceX = new Vector3f[3][mesh.faceCount()];
        Vector3f[][] faceY = new Vector3f[3][mesh.faceCount()];
        for (int corner = 0; corner < 3; corner++) {
            computeCornerFrames(mesh, corner, faceX[corner], faceY[corner]);
        }
        float[][][] kappa = new float[mesh.edgeCount()][3][3];
        for (int sourceCorner = 0; sourceCorner < 3; sourceCorner++) {
            for (int targetCorner = 0; targetCorner < 3; targetCorner++) {
                float[] pairKappa = alternateKappa(mesh, frameField,
                        faceX[sourceCorner], faceY[targetCorner]);
                for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
                    kappa[activeEdge][sourceCorner][targetCorner] = pairKappa[activeEdge];
                }
            }
        }
        return kappa;
    }

    /**
     * Compute face frames for one triangle-corner convention.
     *
     * @param mesh                host mesh
     * @param faceReferenceCorner corner half-edge index used as x-axis
     * @param faceX               output x axes
     * @param faceY               output y axes
     */
    private static void computeCornerFrames(HalfEdgeMesh mesh, int faceReferenceCorner,
            Vector3f[] faceX, Vector3f[] faceY) {
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int halfEdge = mesh.faceHalfEdgeAt(faceId, faceReferenceCorner);
            int startVertex = mesh.halfEdgeVertex(halfEdge);
            int endVertex = mesh.halfEdgeEndVertex(halfEdge);
            Vector3f position0 = mesh.vertexPosition(startVertex);
            Vector3f position1 = mesh.vertexPosition(endVertex);
            Vector3f normal = mesh.faceNormal(faceId);
            Vector3f xAxis = new Vector3f(position1).sub(position0);
            float normalPart = xAxis.dot(normal);
            xAxis.x -= normalPart * normal.x;
            xAxis.y -= normalPart * normal.y;
            xAxis.z -= normalPart * normal.z;
            if (xAxis.length() > 0f) {
                xAxis.normalize();
            } else {
                CrossField.arbitraryTangent(normal, xAxis);
            }
            Vector3f yAxis = new Vector3f();
            normal.cross(xAxis, yAxis).normalize();
            faceX[activeFace] = xAxis;
            faceY[activeFace] = yAxis;
        }
    }

    /**
     * Choose a triangle reference corner per face by local coordinate descent.
     *
     * @param mesh        host mesh
     * @param frameField  field providing active-id maps
     * @param theta       per-face theta values
     * @param periodJump  per-edge period jumps
     * @param cornerKappa kappa table by edge and face-corner pair
     * @return selected corner per active face
     */
    private static int[] optimizeFaceReferenceCorners(HalfEdgeMesh mesh, CrossField frameField,
            float[] theta, int[] periodJump, float[][][] cornerKappa) {
        int[] faceReferenceCorners = new int[mesh.faceCount()];
        for (int pass = 0; pass < 12; pass++) {
            int changed = 0;
            double energy = 0.0;
            for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
                double bestEnergy = Double.POSITIVE_INFINITY;
                int bestCorner = faceReferenceCorners[activeFace];
                for (int corner = 0; corner < 3; corner++) {
                    double candidateEnergy = localCornerEnergy(mesh, frameField, theta,
                            periodJump, cornerKappa, faceReferenceCorners, activeFace, corner);
                    if (candidateEnergy < bestEnergy) {
                        bestEnergy = candidateEnergy;
                        bestCorner = corner;
                    }
                }
                if (bestCorner != faceReferenceCorners[activeFace]) {
                    faceReferenceCorners[activeFace] = bestCorner;
                    changed++;
                }
                energy += bestEnergy;
            }
            System.out.printf("[elk-ref-convention] faceCornerPass=%d changed=%d localEnergy=%.6f%n",
                    pass, changed, energy);
            if (changed == 0) {
                break;
            }
        }
        return faceReferenceCorners;
    }

    /**
     * Compute incident-edge energy for one candidate face corner.
     *
     * @param mesh                 host mesh
     * @param frameField           field providing active-id maps
     * @param theta                per-face theta values
     * @param periodJump           per-edge period jumps
     * @param cornerKappa          kappa table by edge and face-corner pair
     * @param faceReferenceCorners current selected corners
     * @param activeFace           face being updated
     * @param candidateCorner      tested corner for activeFace
     * @return sum of squared residuals on incident interior edges
     */
    private static double localCornerEnergy(HalfEdgeMesh mesh, CrossField frameField,
            float[] theta, int[] periodJump, float[][][] cornerKappa, int[] faceReferenceCorners,
            int activeFace, int candidateCorner) {
        double energy = 0.0;
        int faceId = mesh.faceIdAt(activeFace);
        for (int i = 0; i < mesh.faceEdgeCount(faceId); i++) {
            int edgeId = mesh.faceEdgeAt(faceId, i);
            if (mesh.isBoundaryEdge(edgeId)) {
                continue;
            }
            int activeEdge = frameField.edgeIdToActive.get(edgeId);
            HalfEdgeMesh.EdgeFaceIds edgeFaces = mesh.edgeFaceIds(activeEdge);
            int faceA = frameField.faceIdToActive.get(edgeFaces.faceA);
            int faceB = frameField.faceIdToActive.get(edgeFaces.faceB);
            int cornerA = faceA == activeFace ? candidateCorner : faceReferenceCorners[faceA];
            int cornerB = faceB == activeFace ? candidateCorner : faceReferenceCorners[faceB];
            double residual = theta[faceA] + cornerKappa[activeEdge][cornerA][cornerB]
                    + HALF_PI_D * periodJump[activeEdge] - theta[faceB];
            energy += residual * residual;
        }
        return energy;
    }

    /**
     * Select the kappa entry for each edge using optimized face corners.
     *
     * @param mesh                 host mesh
     * @param frameField           field providing active-id maps
     * @param cornerKappa          kappa table by edge and face-corner pair
     * @param faceReferenceCorners selected corner per face
     * @return selected kappa per edge
     */
    private static float[] selectedCornerKappa(HalfEdgeMesh mesh, CrossField frameField,
            float[][][] cornerKappa, int[] faceReferenceCorners) {
        float[] selected = new float[mesh.edgeCount()];
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            HalfEdgeMesh.EdgeFaceIds edgeFaces = mesh.edgeFaceIds(activeEdge);
            if (mesh.isBoundaryEdge(edgeFaces.edgeId)) {
                continue;
            }
            int faceA = frameField.faceIdToActive.get(edgeFaces.faceA);
            int faceB = frameField.faceIdToActive.get(edgeFaces.faceB);
            selected[activeEdge] = cornerKappa[activeEdge][faceReferenceCorners[faceA]][faceReferenceCorners[faceB]];
        }
        return selected;
    }

    /**
     * Interpret the source period array as sorted by edge endpoint ids.
     *
     * @param mesh       host mesh
     * @param periods    source period jumps
     * @param minMaxSort true for ascending min/max, false for descending max/min
     * @return periods mapped back into active edge order
     */
    private static int[] periodsMappedFromSortedEndpoints(HalfEdgeMesh mesh, int[] periods,
            boolean minMaxSort) {
        Integer[] activeEdges = new Integer[mesh.edgeCount()];
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            activeEdges[activeEdge] = activeEdge;
        }
        Arrays.sort(activeEdges, (a, b) -> compareEdgeEndpoints(mesh, a, b, minMaxSort));
        int[] mapped = new int[periods.length];
        for (int sourceEdgeIndex = 0; sourceEdgeIndex < activeEdges.length; sourceEdgeIndex++) {
            mapped[activeEdges[sourceEdgeIndex]] = periods[sourceEdgeIndex];
        }
        return mapped;
    }

    /**
     * Compare two active edges by endpoint ids.
     *
     * @param mesh       host mesh
     * @param edgeA      first active edge
     * @param edgeB      second active edge
     * @param minMaxSort true for ascending min/max, false for descending max/min
     * @return comparison result
     */
    private static int compareEdgeEndpoints(HalfEdgeMesh mesh, int edgeA, int edgeB,
            boolean minMaxSort) {
        HalfEdgeMesh.EdgeFaceIds faceA = mesh.edgeFaceIds(edgeA);
        HalfEdgeMesh.EdgeFaceIds faceB = mesh.edgeFaceIds(edgeB);
        int minA = Math.min(faceA.edgeStartVertex, faceA.edgeEndVertex);
        int maxA = Math.max(faceA.edgeStartVertex, faceA.edgeEndVertex);
        int minB = Math.min(faceB.edgeStartVertex, faceB.edgeEndVertex);
        int maxB = Math.max(faceB.edgeStartVertex, faceB.edgeEndVertex);
        if (minMaxSort) {
            int byMin = Integer.compare(minA, minB);
            return byMin != 0 ? byMin : Integer.compare(maxA, maxB);
        }
        int byMax = Integer.compare(maxB, maxA);
        return byMax != 0 ? byMax : Integer.compare(minB, minA);
    }

    /**
     * Compute transport angles for supplied face frames.
     *
     * @param mesh       host mesh
     * @param frameField field providing active-id maps
     * @param faceX      per-face x-axis
     * @param faceY      per-face y-axis
     * @return per-edge kappa values
     */
    private static float[] alternateKappa(HalfEdgeMesh mesh, CrossField frameField,
            Vector3f[] faceX, Vector3f[] faceY) {
        float[] kappa = new float[mesh.edgeCount()];
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            HalfEdgeMesh.EdgeFaceIds edgeFaceIds = mesh.edgeFaceIds(activeEdge);
            if (mesh.isBoundaryEdge(edgeFaceIds.edgeId)) {
                continue;
            }
            Vector3f position0 = mesh.vertexPosition(edgeFaceIds.edgeStartVertex);
            Vector3f position1 = mesh.vertexPosition(edgeFaceIds.edgeEndVertex);
            Vector3f edgeDir = new Vector3f(position1).sub(position0);
            float edgeLen = edgeDir.length();
            if (edgeLen == 0f) {
                continue;
            }
            edgeDir.div(edgeLen);
            int faceA = frameField.faceIdToActive.get(edgeFaceIds.faceA);
            int faceB = frameField.faceIdToActive.get(edgeFaceIds.faceB);
            Vector3f faceNormalA = mesh.faceNormal(edgeFaceIds.faceA);
            Vector3f faceNormalB = mesh.faceNormal(edgeFaceIds.faceB);
            Vector3f cross = new Vector3f(faceNormalA).cross(faceNormalB);
            float dihedral = (float) Math.atan2(cross.dot(edgeDir),
                    Math.max(-1f, Math.min(1f, faceNormalA.dot(faceNormalB))));
            float dihedralCos = (float) Math.cos(dihedral);
            float dihedralSin = (float) Math.sin(dihedral);
            Vector3f transported = new Vector3f(faceX[faceA]);
            Vector3f kCrossV = new Vector3f(edgeDir).cross(transported);
            float kDotV = edgeDir.dot(transported);
            float oneMinusC = 1f - dihedralCos;
            transported.x = transported.x * dihedralCos + kCrossV.x * dihedralSin
                    + edgeDir.x * kDotV * oneMinusC;
            transported.y = transported.y * dihedralCos + kCrossV.y * dihedralSin
                    + edgeDir.y * kDotV * oneMinusC;
            transported.z = transported.z * dihedralCos + kCrossV.z * dihedralSin
                    + edgeDir.z * kDotV * oneMinusC;
            float crossDirX = transported.dot(faceX[faceB]);
            float crossDirY = transported.dot(faceY[faceB]);
            kappa[activeEdge] = (float) Math.atan2(crossDirY, crossDirX);
        }
        return kappa;
    }

    /**
     * Print smooth-energy residual stats for a field under our edge transport
     * convention.
     *
     * @param label        diagnostic label
     * @param mesh         host mesh
     * @param frameField   field whose active-id maps and kappa define transport
     * @param theta        per-face angles
     * @param periodJump   per-edge period jumps
     * @param negatePeriod whether to negate each period jump before evaluation
     * @param negateKappa  whether to negate kappa before evaluation
     */
    private static void printSmoothnessResidual(String label, HalfEdgeMesh mesh,
            CrossField frameField, float[] theta, int[] periodJump, boolean negatePeriod,
            boolean negateKappa) {
        printSmoothnessResidual(label, mesh, frameField, theta, periodJump, frameField.kappa,
                negatePeriod, negateKappa);
    }

    /**
     * Print smooth-energy residual stats for a field under supplied edge transport.
     *
     * @param label        diagnostic label
     * @param mesh         host mesh
     * @param frameField   field whose active-id maps define face ids
     * @param theta        per-face angles
     * @param periodJump   per-edge period jumps
     * @param kappa        per-edge transport angles
     * @param negatePeriod whether to negate each period jump before evaluation
     * @param negateKappa  whether to negate kappa before evaluation
     */
    private static void printSmoothnessResidual(String label, HalfEdgeMesh mesh,
            CrossField frameField, float[] theta, int[] periodJump, float[] kappa,
            boolean negatePeriod, boolean negateKappa) {
        double sumAbs = 0.0;
        double sumSquared = 0.0;
        double maxAbs = 0.0;
        int rowCount = 0;
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            int edgeId = mesh.edgeIdAt(activeEdge);
            if (mesh.isBoundaryEdge(edgeId)) {
                continue;
            }
            HalfEdgeMesh.EdgeFaceIds edgeFaces = mesh.edgeFaceIds(activeEdge);
            int faceA = frameField.faceIdToActive.get(edgeFaces.faceA);
            int faceB = frameField.faceIdToActive.get(edgeFaces.faceB);
            int p = negatePeriod ? -periodJump[activeEdge] : periodJump[activeEdge];
            double transport = negateKappa ? -kappa[activeEdge] : kappa[activeEdge];
            double residual = theta[faceA] + transport + HALF_PI_D * p - theta[faceB];
            double abs = Math.abs(residual);
            sumAbs += abs;
            sumSquared += residual * residual;
            maxAbs = Math.max(maxAbs, abs);
            rowCount++;
        }
        System.out.printf("[elk-ref-convention] %s meanAbs=%.6f rms=%.6f maxAbs=%.6f rows=%d%n",
                label, sumAbs / rowCount, Math.sqrt(sumSquared / rowCount), maxAbs, rowCount);
    }

    /**
     * Print aggregate face-angle disagreement for {@code ours + shift} against
     * {@code reference}, measured modulo {@code pi/2}.
     *
     * @param label     diagnostic label
     * @param ours      generated cross field
     * @param reference reference cross field
     * @param shift     angle shift applied to our theta before comparison
     */
    private static void printThetaAlignment(String label, CrossField ours,
            CrossField reference, double shift) {
        double sum = 0.0;
        double sumSquared = 0.0;
        double max = 0.0;
        int faceCount = ours.theta.length;
        for (int faceActive = 0; faceActive < faceCount; faceActive++) {
            double difference = angleDifferenceModHalfPi(
                    ours.theta[faceActive] + shift - reference.theta[faceActive]);
            sum += difference;
            sumSquared += difference * difference;
            max = Math.max(max, difference);
        }
        double mean = sum / faceCount;
        double rms = Math.sqrt(sumSquared / faceCount);
        System.out.printf("[elk-theta] %s mean=%.6f rms=%.6f max=%.6f%n",
                label, mean, rms, max);
    }

    /**
     * Count how often the raw field is closer to the reference than the 45-degree
     * shifted field, and how many faces are tightly aligned in each convention.
     *
     * @param ours      generated cross field
     * @param reference reference cross field
     */
    private static void printThetaShiftPreference(CrossField ours, CrossField reference) {
        int rawCloser = 0;
        int shiftedCloser = 0;
        int rawClose = 0;
        int shiftedClose = 0;
        int faceCount = ours.theta.length;
        for (int faceActive = 0; faceActive < faceCount; faceActive++) {
            double raw = angleDifferenceModHalfPi(
                    ours.theta[faceActive] - reference.theta[faceActive]);
            double shifted = angleDifferenceModHalfPi(
                    ours.theta[faceActive] + QUARTER_PI_D - reference.theta[faceActive]);
            if (raw < shifted) {
                rawCloser++;
            } else if (shifted < raw) {
                shiftedCloser++;
            }
            if (raw < CLOSE_ANGLE_THRESHOLD) {
                rawClose++;
            }
            if (shifted < CLOSE_ANGLE_THRESHOLD) {
                shiftedClose++;
            }
        }
        System.out.printf(
                "[elk-theta] preference rawCloser=%d shiftedCloser=%d rawClose=%d shiftedClose=%d faces=%d%n",
                rawCloser, shiftedCloser, rawClose, shiftedClose, faceCount);
    }

    /**
     * Smallest absolute angle difference under cross-field quarter-turn symmetry.
     *
     * @param angleDifference raw angle difference
     * @return difference in {@code [0, pi/4]}
     */
    private static double angleDifferenceModHalfPi(double angleDifference) {
        double wrapped = angleDifference - HALF_PI_D * Math.floor(angleDifference / HALF_PI_D);
        if (wrapped < 0.0) {
            wrapped += HALF_PI_D;
        }
        if (wrapped > QUARTER_PI_D) {
            wrapped = HALF_PI_D - wrapped;
        }
        return Math.abs(wrapped);
    }

    /**
     * The (first) curvature source vertex pinning any face in the vertex's 1-ring,
     * or {@code -1} when no face in the 1-ring is curvature-pinned.
     *
     * @param mesh       host mesh
     * @param crossField built cross field
     * @param vertexId   mesh vertex id
     * @return first curvature source vertex found in the 1-ring, or {@code -1}
     */
    private static int curvatureSourceForVertex(HalfEdgeMesh mesh, CrossField crossField, int vertexId) {
        int oneRing = mesh.vertexFaceCount(vertexId);
        for (int i = 0; i < oneRing; i++) {
            int faceId = mesh.vertexFaceAt(vertexId, i);
            int faceActive = crossField.faceIdToActive.get(faceId);
            int sourceVertex = crossField.curvatureSourceVertex[faceActive];
            if (sourceVertex >= 0) {
                return sourceVertex;
            }
        }
        return -1;
    }

    /**
     * Signed index4 of the singularity at the given vertex in the supplied cross
     * field, or 0 when none.
     *
     * @param crossField cross field whose singularity list is consulted
     * @param vertexId   mesh vertex id
     * @return signed index4 ({@code +1}, {@code -1}, etc.), or 0 when missing
     */
    private static int signedIndexOf(CrossField crossField, int vertexId) {
        for (Singularity singularity : crossField.singularities) {
            if (singularity.vertexId() == vertexId) {
                return singularity.index4();
            }
        }
        return 0;
    }

    /**
     * Build the cross field on {@code offPath} and assert zero curvature-induced
     * cluster pairs.
     *
     * @param label   short tag printed alongside the per-mesh metric line
     * @param offPath path to the input triangle mesh
     * @throws Exception when loading the mesh fails
     */
    private static void assertNoCurvatureClusterPairs(String label, String offPath) throws Exception {
        ArrayMesh arrayMesh = MeshLoader.load(offPath);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());

        CrossField ours = new CrossField(mesh).build();
        int clusterPairCount = countCurvatureClusterPairs(mesh, ours, CLUSTER_HOP_LIMIT);
        System.out.printf(
                "[crossfield-test] %s singularities=%d cluster-pairs(<= %d hops, curvature-touching)=%d%n",
                label, ours.singularities.size(), CLUSTER_HOP_LIMIT, clusterPairCount);
        assertEquals(0, clusterPairCount,
                label + " cross-field emitted " + clusterPairCount
                        + " curvature-induced ± cluster pairs (opposite-sign singularities within "
                        + CLUSTER_HOP_LIMIT + " hops, near a curvature-pinned face). These are the"
                        + " artifacts BZK09 §3 'sparse constraints' guidance is meant to avoid.");
    }

    /**
     * Count pairs of opposite-sign singularities within {@code hopLimit} primal
     * edges of each other where at least one endpoint's 1-ring touches a
     * curvature-pinned face. Symmetric pairs counted once.
     *
     * @param mesh       host mesh
     * @param crossField built cross field with populated singularities and
     *                   {@code curvatureSourceVertex}
     * @param hopLimit   maximum primal-edge distance for "close"
     * @return number of curvature-anchored cluster pairs
     */
    private static int countCurvatureClusterPairs(HalfEdgeMesh mesh, CrossField crossField, int hopLimit) {
        int clusters = 0;
        for (int i = 0; i < crossField.singularities.size(); i++) {
            Singularity sa = crossField.singularities.get(i);
            boolean aNearCurvature = vertexTouchesCurvatureConstraint(mesh, crossField, sa.vertexId());
            for (int j = i + 1; j < crossField.singularities.size(); j++) {
                Singularity sb = crossField.singularities.get(j);
                if (sa.index4() + sb.index4() != 0) {
                    continue;
                }
                int hops = shortestHopDistance(mesh, sa.vertexId(), sb.vertexId(), hopLimit);
                if (hops < 0 || hops > hopLimit) {
                    continue;
                }
                boolean bNearCurvature = vertexTouchesCurvatureConstraint(mesh, crossField, sb.vertexId());
                if (!aNearCurvature && !bNearCurvature) {
                    continue;
                }
                clusters++;
            }
        }
        return clusters;
    }

    /**
     * Whether any face in the vertex's 1-ring was pinned by a curvature constraint
     * (i.e. its {@code curvatureSourceVertex} entry is &ge; 0).
     *
     * @param mesh       host mesh
     * @param crossField built cross field
     * @param vertexId   mesh vertex id
     * @return true when the 1-ring of {@code vertexId} contains a curvature-pinned
     *         face
     */
    private static boolean vertexTouchesCurvatureConstraint(HalfEdgeMesh mesh, CrossField crossField, int vertexId) {
        int oneRing = mesh.vertexFaceCount(vertexId);
        for (int i = 0; i < oneRing; i++) {
            int faceId = mesh.vertexFaceAt(vertexId, i);
            int faceActive = crossField.faceIdToActive.get(faceId);
            if (crossField.curvatureSourceVertex[faceActive] >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * BFS over the primal vertex graph from {@code source} to {@code target},
     * capped at {@code hopLimit}. Returns {@code -1} if unreachable within the cap.
     *
     * @param mesh     host mesh
     * @param source   source vertex id
     * @param target   target vertex id
     * @param hopLimit maximum hop count to explore
     * @return primal-edge hop distance, or {@code -1} if &gt; {@code hopLimit}
     */
    private static int shortestHopDistance(HalfEdgeMesh mesh, int source, int target, int hopLimit) {
        if (source == target) {
            return 0;
        }
        Set<Integer> visited = new HashSet<>();
        Deque<int[]> frontier = new ArrayDeque<>();
        visited.add(source);
        frontier.add(new int[] { source, 0 });
        while (!frontier.isEmpty()) {
            int[] entry = frontier.pollFirst();
            int vertex = entry[0];
            int distance = entry[1];
            if (distance >= hopLimit) {
                continue;
            }
            int outCount = mesh.vertexOutgoingHalfEdgeCount(vertex);
            for (int i = 0; i < outCount; i++) {
                int halfEdge = mesh.vertexOutgoingHalfEdgeAt(vertex, i);
                int neighbor = mesh.halfEdgeEndVertex(halfEdge);
                if (neighbor == target) {
                    return distance + 1;
                }
                if (visited.add(neighbor)) {
                    frontier.add(new int[] { neighbor, distance + 1 });
                }
            }
        }
        return -1;
    }
}
