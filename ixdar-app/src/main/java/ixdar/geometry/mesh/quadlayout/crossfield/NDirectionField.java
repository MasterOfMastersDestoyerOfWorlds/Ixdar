package ixdar.geometry.mesh.quadlayout.crossfield;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh.EdgeFaceIds;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.quadlayout.solver.AdaptiveSolver;
import ixdar.geometry.mesh.quadlayout.solver.Preconditioner;
import ixdar.geometry.mesh.quadlayout.solver.matrix.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.system.DofSystem;
import ixdar.geometry.mesh.quadlayout.solver.system.PowerIteration;
import ixdar.platform.Platforms;

/**
 * Builds the quad-layout cross field over a triangle mesh: the Knöppel
 * n-direction field with curvature alignment and soft feature/boundary
 * guidance, with its cf.singularities extracted. As the registered
 * {@code cross_field} node, the registry instance is inert and evaluation
 * builds a fresh field.
 *
 * <p>See also: Lyon 2021 stages 1-2
 */
@MeshNodeAnnotation(id = "cross_field", desktopOnly = true)
public class NDirectionField implements MeshNode {

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final OutputPort FIELD = new OutputPort("field", PortType.CROSS_FIELD);
    public static final OutputPort SINGULARITY_COUNT = new OutputPort("singularity_count", PortType.INT);
    public static final OutputPort SINGULARITIES = new OutputPort("singularities",
            PortType.INT);
    public static final OutputPort FEATURE_EDGES = new OutputPort("feature_edges",
            PortType.BOOLEAN);
    public static final OutputPort DOFS = new OutputPort("dofs", PortType.DOF_SYSTEM);

    /**
     * Diagonal regularizer A <- A + shift*M so the closed-surface system is SPD
     * (paper Sec. 7).
     */
    public static final double DEFAULT_SHIFT = 1e-8;
    /** Fixed power-iteration count; the paper uses 20 for all examples (Sec. 7). */
    public static final int DEFAULT_POWER_ITERATIONS = 20;

    public static final double NANOS_PER_SECOND = 1.0e9;

    public static final float HALF_PI = (float) (Math.PI / 2.0);
    public static final double EPS = 1e-12;

    /** Shared suffix of the PCG diagnostics lines printed by the two solves. */
    public static final String PCG_CONVERGED_SUFFIX = " converged=";

    public HalfEdgeMesh mesh;

    public int vertexCount;

    /** The degree of the direction field. */
    public final int n = 4;

    /**
     * Smoothness parameter of where to put cf.singularities in [-1, 1]; 0 = Dirichlet.
     * -1 = holomorphic (at points of high Gaussian curvature), 1 = anti-holomorphic
     * (at points of low Gaussian curvature).
     */
    public final double curvatureBias = 0;

    // Per-vertex tangent frame (world space).
    public Vector3f[] vertexX;
    public Vector3f[] vertexY;

    // Solution: per-vertex n-th power coefficient u = uRe + i*uIm.
    /** The canonical solve state: the raw DOF vector of the last field solve. */
    public DofSystem system;

    public double[] uReal;
    public double[] uImaginary;
    /** Representative direction angle in the vertex frame: arg(u)/n. */
    public double[] fieldAngle;

    public NormalMatrix energyMatrix;
    public NormalMatrix massSystemMatrix;
    public double[] loadVector;
    public double[] hopfField;
    public double[] crossFieldGuidance;

    /**
     * Dual-form (already paired with the PL basis sections) alignment load from
     * feature and boundary edges, added verbatim to the aligned solve's right-hand
     * side; null when the mesh has no alignment edges.
     */
    public double[] featureAlignmentLoad;

    /**
     * Soft alignment strength for feature/boundary edges (KCP13 §5: the pointwise
     * guidance magnitude weights alignment against smoothness). Scaled by edge
     * length per contribution; raise it when separatrices must hug features harder.
     */
    public float featureAlignmentWeight = 1.0f;

    public boolean useCurvatureAlignment = true;

    /** The field being built; every durable product lands here. */
    private CrossField cf;

    private int[] vertexIdOf; // active index -> vertex id
    private final Map<Integer, Integer> activeOfVertexId = new HashMap<>();

    // angleInFrame[ packVH(vertexId, halfEdge) ] = rescaled angle of that outgoing
    // half-edge in the vertex's flattened tangent frame (paper Eq. 11/12).
    private final Map<Long, Double> angleInFrame = new HashMap<>();

    private Vector3f[] vertexNormal;

    private double[] angleDefect;

    /**
     * Builds the cross field over a mesh: local frames and transport angles,
     * alignment edges, the connection Laplacian solve, and the field conversion
     * with singularity extraction. All durable products land on the returned
     * {@link CrossField}; this stage instance holds only scratch.
     *
     * @param buildMesh half-edge mesh providing geometry, topology, and
     *                  active-id mapping
     * @return the built cross field
     */
    public CrossField build(HalfEdgeMesh buildMesh) {
        this.mesh = buildMesh;
        this.cf = new CrossField(buildMesh);
        this.vertexCount = buildMesh.vertexCount();
        this.system = new DofSystem(2 * vertexCount);
        this.system.assembler = x -> useCurvatureAlignment || cf.hasAlignmentEdges()
                ? massSystemMatrix
                : energyMatrix;
        this.system.energy = x -> energyMatrix.quadraticEnergy(x);
        this.system.solve = this::solveField;
        this.system.writeBack = this::populate;
        this.vertexIdOf = new int[vertexCount];
        for (int v = 0; v < vertexCount; v++) {
            int vId = buildMesh.vertexIdAt(v);
            vertexIdOf[v] = vId;
            activeOfVertexId.put(vId, v);
        }
        this.loadVector = new double[2 * vertexCount];
        this.crossFieldGuidance = new double[2 * vertexCount];
        buildFramesAndTransport();
        cf.system = system;

        long sectionStart = System.nanoTime();
        computeVertexFrames();
        Platforms.log("[cross-field timing] compute vertex frames %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        sectionStart = System.nanoTime();
        computeAngleRescaling();
        Platforms.log("[cross-field timing] compute angle rescaling %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        sectionStart = System.nanoTime();
        assemble();
        Platforms.log("[cross-field timing] assemble %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        solveField();
        return cf;
    }

    /**
     * BZK09 stages: active-id maps, local face frames, edge transport angles
     * cf.kappa, and alignment-edge detection, all written onto the field.
     */
    private void buildFramesAndTransport() {
        long sectionStart = System.nanoTime();

        cf.faceIdToActive = new HashMap<>(mesh.faceCount() * 2);
        for (int i = 0; i < mesh.faceCount(); i++) {
            cf.faceIdToActive.put(mesh.faceIdAt(i), i);
        }
        cf.edgeIdToActive = new HashMap<>(mesh.edgeCount() * 2);
        for (int i = 0; i < mesh.edgeCount(); i++) {
            cf.edgeIdToActive.put(mesh.edgeIdAt(i), i);
        }
        Platforms.log("[cross-field timing] active-id maps %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        sectionStart = System.nanoTime();
        /*
         * Local face frames. Convention: x_f = first half-edge of f, projected onto the
         * tangent plane. y_f = n_f × x_f. Right-handed.
         */

        for (int faceIndex = 0; faceIndex < mesh.faceCount(); faceIndex++) {
            int faceId = mesh.faceIdAt(faceIndex);
            int halfEdge = mesh.faceHalfEdge(faceId);
            int v0 = mesh.halfEdgeVertex(halfEdge);
            int v1 = mesh.halfEdgeEndVertex(halfEdge);

            Vector3f position1 = mesh.vertexPosition(v0);
            Vector3f position2 = mesh.vertexPosition(v1);
            Vector3f xAxis = new Vector3f(position2).sub(position1);

            Vector3f normal = mesh.faceNormal(faceId);

            float xDotN = xAxis.dot(normal);
            xAxis.x -= xDotN * normal.x;
            xAxis.y -= xDotN * normal.y;
            xAxis.z -= xDotN * normal.z;
            float xLen = xAxis.length();
            if (xLen < CrossField.EPSILON) {
                CrossField.arbitraryTangent(normal, xAxis);
            } else {
                xAxis.div(xLen);
            }
            Vector3f yAxis = new Vector3f();
            normal.cross(xAxis, yAxis).normalize();

            cf.faceX[faceIndex] = xAxis;
            cf.faceY[faceIndex] = yAxis;
        }
        Platforms.log("[cross-field timing] local face frames %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        sectionStart = System.nanoTime();

        /*
         * Edge transport angles κ_ij. Rotate face-i's x-axis about the shared edge by
         * the dihedral angle so it lies in face-j's tangent plane. Express the rotated
         * vector in face-j's frame (cf.faceX[j], cf.faceY[j]): κ_ij = atan2(y-component,
         * x-component).
         */

        for (int i = 0; i < mesh.edgeCount(); i++) {
            EdgeFaceIds edgeFaceIds = mesh.edgeFaceIds(i);
            if (mesh.isBoundaryEdge(edgeFaceIds.edgeId)) {
                cf.kappa[i] = 0f;
                continue;
            }
            Vector3f position1 = mesh.vertexPosition(edgeFaceIds.edgeStartVertex);
            Vector3f position2 = mesh.vertexPosition(edgeFaceIds.edgeEndVertex);
            Vector3f edgeDir = new Vector3f(position2).sub(position1);
            float edgeLen = edgeDir.length();
            if (edgeLen < CrossField.EPSILON) {
                cf.kappa[i] = 0f;
                continue;
            }
            edgeDir.div(edgeLen);

            Vector3f faceNormalU = mesh.faceNormal(edgeFaceIds.faceA);
            Vector3f faceNormalV = mesh.faceNormal(edgeFaceIds.faceB);

            Vector3f cross = new Vector3f(faceNormalU).cross(faceNormalV);
            float dihedral = (float) Math.atan2(cross.dot(edgeDir),
                    Math.max(-1f, Math.min(1f, faceNormalU.dot(faceNormalV))));
            float dihedralCos = (float) Math.cos(dihedral);
            float dihedralSin = (float) Math.sin(dihedral);
            Vector3f xiTransported = new Vector3f(cf.faceX[edgeFaceIds.faceA]);
            Vector3f kCrossV = new Vector3f(edgeDir).cross(xiTransported);
            float kDotV = edgeDir.dot(xiTransported);
            float oneMinusC = 1f - dihedralCos;
            xiTransported.x = xiTransported.x * dihedralCos + kCrossV.x * dihedralSin + edgeDir.x * kDotV * oneMinusC;
            xiTransported.y = xiTransported.y * dihedralCos + kCrossV.y * dihedralSin + edgeDir.y * kDotV * oneMinusC;
            xiTransported.z = xiTransported.z * dihedralCos + kCrossV.z * dihedralSin + edgeDir.z * kDotV * oneMinusC;
            float crossDirX = xiTransported.dot(cf.faceX[edgeFaceIds.faceB]);
            float crossDirY = xiTransported.dot(cf.faceY[edgeFaceIds.faceB]);
            cf.kappa[i] = (float) Math.atan2(crossDirY, crossDirX);
        }
        Platforms.log("[cross-field timing] edge transport angles kappa %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        sectionStart = System.nanoTime();

        detectAlignmentEdges();
    }

    private void detectAlignmentEdges() {
        for (int activeEdge = 0; activeEdge < cf.edgeCount; activeEdge++) {
            EdgeFaceIds edgeFaceIds = mesh.edgeFaceIds(activeEdge);
            if (mesh.isBoundaryEdge(edgeFaceIds.edgeId)) {
                cf.alignmentEdges.data()[activeEdge] = true;
                continue;
            }
            Vector3f faceANormal = mesh.faceNormal(edgeFaceIds.faceA);
            Vector3f faceBNormal = mesh.faceNormal(edgeFaceIds.faceB);
            if (faceANormal.dot(faceBNormal) < cf.featureDihedralCos) {
                cf.alignmentEdges.data()[activeEdge] = true;
            }
        }
    }

    /**
     * BZK09/Ray08 per-vertex singularity index from the angle defect plus signed
     * cf.kappa- and period-walks around the 1-ring; fills the field's per-vertex
     * index4 attribute, leaving boundary and zero-index vertices at 0.
     */
    private void extractSingularities() {
        int count = mesh.vertexCount();
        Arrays.fill(cf.singularityIndex4.data(), 0);
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();

        for (int vAi = 0; vAi < count; vAi++) {
            int vId = mesh.vertexIdAt(vAi);
            if (mesh.isBoundaryVertex(vId))
                continue;
            Vector3f vPos = mesh.vertexPosition(vId);

            float angleSum = 0f;
            int faces = mesh.vertexFaceCount(vId);
            for (int i = 0; i < faces; i++) {
                int fId = mesh.vertexFaceAt(vId, i);
                angleSum += mesh.interiorAngleAtVertex(fId, vId, vPos, a, b);
            }
            float defect = (float) (2.0 * Math.PI) - angleSum;

            float signedKappaSum = 0f;
            int signedPeriodSum = 0;
            int outCount = mesh.vertexOutgoingHalfEdgeCount(vId);
            for (int i = 0; i < outCount; i++) {
                int he = mesh.vertexOutgoingHalfEdgeAt(vId, i);
                int eId = mesh.halfEdgeEdge(he);
                if (mesh.isBoundaryEdge(eId))
                    continue;
                int eAi = cf.edgeIdToActive.get(eId);
                int sign = (he == mesh.edgeHalfEdge(eId)) ? 1 : -1;
                signedKappaSum += sign * cf.kappa[eAi];
                signedPeriodSum += sign * cf.periodJump[eAi];
            }

            float iTimes4 = (float) (((defect + signedKappaSum) * 2.0) / Math.PI) + signedPeriodSum;
            int iQuarter = Math.round(iTimes4);
            if (iQuarter != 0) {
                cf.singularityIndex4.data()[vAi] = iQuarter;
            }
        }
    }

    /**
     * Runs the field solve over the assembled system: the aligned PCG path when
     * curvature or feature alignment is active, the smoothest power iteration
     * otherwise, then populates the field. Re-solving skips frames and assembly.
     */
    public void solveField() {
        long sectionStart = System.nanoTime();
        boolean hasAlignmentEdges = cf.hasAlignmentEdges();
        if (useCurvatureAlignment || hasAlignmentEdges) {
            if (useCurvatureAlignment) {
                buildLoadVector();

                Platforms.log("[cross-field timing] build load vector %.3fs%n",
                        (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
                sectionStart = System.nanoTime();
                solveForHopfField();
                Platforms.log("[cross-field timing] solve for hopf field %.3fs%n",
                        (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
                sectionStart = System.nanoTime();
            }
            if (hasAlignmentEdges) {
                buildFeatureAlignmentLoad();
                Platforms.log("[cross-field timing] build feature alignment load %.3fs%n",
                        (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
                sectionStart = System.nanoTime();
            }
            solveAligned(0.0);
            Platforms.log("[cross-field timing] solve aligned %.3fs%n",
                    (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        } else {
            solveSmoothest();
            Platforms.log("[cross-field timing] solve smoothest %.3fs%n",
                    (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        }
        sectionStart = System.nanoTime();
        populate();
        Platforms.log("[cross-field timing] populate %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
    }

    private void buildLoadVector() {
        Vector3f start = new Vector3f();
        Vector3f end = new Vector3f();
        for (int activeEdge = 0; activeEdge < cf.edgeCount; activeEdge++) {
            EdgeFaceIds edge = mesh.edgeFaceIds(activeEdge);
            int edgeId = edge.edgeId;
            int startVertexId = edge.edgeStartVertex;
            int endVertexId = edge.edgeEndVertex;
            if (mesh.isBoundaryEdge(edgeId)) {
                continue;
            }
            mesh.vertexPosition(edge.edgeStartVertex, start);
            mesh.vertexPosition(edge.edgeEndVertex, end);
            float dx = end.x - start.x;
            float dy = end.y - start.y;
            float dz = end.z - start.z;
            float edgeLength = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (edgeLength < CrossField.EPSILON) {
                continue;
            }
            Vector3f leftNormal = mesh.faceNormal(edge.faceA);
            Vector3f rightNormal = mesh.faceNormal(edge.faceB);
            float cosDihedral = Math.max(-1f, Math.min(1f, leftNormal.dot(rightNormal)));
            float crossX = leftNormal.y * rightNormal.z - leftNormal.z * rightNormal.y;
            float crossY = leftNormal.z * rightNormal.x - leftNormal.x * rightNormal.z;
            float crossZ = leftNormal.x * rightNormal.y - leftNormal.y * rightNormal.x;
            float sinDihedral = (crossX * dx + crossY * dy + crossZ * dz) / edgeLength;
            float dihedralAngle = (float) Math.atan2(sinDihedral, cosDihedral);
            double edgeWeight = (dihedralAngle * edgeLength) / 4.0;
            double phase = 2.0 * getAngle(edge.edgeStartVertex, edge.halfEdge);
            double cosPhase = Math.cos(phase);
            double sinPhase = Math.sin(phase);
            int activeStartVertex = activeOfVertexId.get(startVertexId);
            int activeEndVertex = activeOfVertexId.get(endVertexId);
            loadVector[2 * activeStartVertex] += edgeWeight * cosPhase;
            loadVector[2 * activeStartVertex + 1] += edgeWeight * sinPhase;
            phase = 2.0 * getAngle(edge.edgeEndVertex, edge.twin);
            cosPhase = Math.cos(phase);
            sinPhase = Math.sin(phase);
            loadVector[2 * activeEndVertex] += edgeWeight * cosPhase;
            loadVector[2 * activeEndVertex + 1] += edgeWeight * sinPhase;
        }
    }

    /**
     * Soft alignment load for feature and boundary edges, contributing
     * {@code featureAlignmentWeight · |e| · e^{i·n·θ}} at both endpoints of each
     * edge selected in {@link CrossField#alignmentEdges}. The result is dual-form,
     * so it adds straight onto the aligned solve's right-hand side.
     *
     * <p>
     * See also: KCP*13 Section 5
     */
    private void buildFeatureAlignmentLoad() {
        featureAlignmentLoad = new double[2 * vertexCount];
        List<Integer> sortedAlignmentEdgeIds = new ArrayList<>();
        for (int activeEdge = 0; activeEdge < cf.alignmentEdges.length(); activeEdge++) {
            if (cf.alignmentEdges.get(activeEdge)) {
                sortedAlignmentEdgeIds.add(mesh.edgeIdAt(activeEdge));
            }
        }
        Collections.sort(sortedAlignmentEdgeIds);
        Vector3f start = new Vector3f();
        Vector3f end = new Vector3f();
        for (int edgeId : sortedAlignmentEdgeIds) {
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            int twin = mesh.halfEdgeTwin(halfEdge);
            int startVertexId = mesh.halfEdgeVertex(halfEdge);
            int endVertexId = mesh.halfEdgeEndVertex(halfEdge);
            mesh.vertexPosition(startVertexId, start);
            mesh.vertexPosition(endVertexId, end);
            float dx = end.x - start.x;
            float dy = end.y - start.y;
            float dz = end.z - start.z;
            float edgeLength = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (edgeLength < CrossField.EPSILON) {
                continue;
            }
            double contribution = featureAlignmentWeight * edgeLength;
            double phase = n * angleOfEdgeAtVertex(startVertexId, halfEdge, edgeId);
            int activeStartVertex = activeOfVertexId.get(startVertexId);
            featureAlignmentLoad[2 * activeStartVertex] += contribution * Math.cos(phase);
            featureAlignmentLoad[2 * activeStartVertex + 1] += contribution * Math.sin(phase);
            phase = n * angleOfEdgeAtVertex(endVertexId, twin, edgeId);
            int activeEndVertex = activeOfVertexId.get(endVertexId);
            featureAlignmentLoad[2 * activeEndVertex] += contribution * Math.cos(phase);
            featureAlignmentLoad[2 * activeEndVertex + 1] += contribution * Math.sin(phase);
        }
        double loadNormSquared = 0.0;
        for (double value : featureAlignmentLoad) {
            loadNormSquared += value * value;
        }
        Platforms.log("[n-field] feature alignment edges=%d load norm=%.6e%n",
                sortedAlignmentEdgeIds.size(), Math.sqrt(loadNormSquared));
    }

    /**
     * Rescaled tangent-frame angle of {@code edgeId} at {@code vertexId}. Prefers
     * the angle cached for {@code preferredHalfEdge}, falling back to the vertex's
     * own outgoing half-edge along the same edge; the fallback is only valid at
     * even n, where the n-fold phase is direction-insensitive.
     *
     * @param vertexId          vertex whose tangent frame the angle is measured in
     * @param preferredHalfEdge half-edge handle expected to originate at the vertex
     * @param edgeId            edge whose direction is being measured
     * @return rescaled angle in radians, or 0 if the edge is not incident
     */
    private double angleOfEdgeAtVertex(int vertexId, int preferredHalfEdge, int edgeId) {
        Double cached = angleInFrame.get((((long) vertexId) << 32) | (preferredHalfEdge & 0xFFFFFFFFL));
        if (cached != null) {
            return cached;
        }
        int outgoingCount = mesh.vertexOutgoingHalfEdgeCount(vertexId);
        for (int i = 0; i < outgoingCount; i++) {
            int outgoingHalfEdge = mesh.vertexOutgoingHalfEdgeAt(vertexId, i);
            if (mesh.halfEdgeEdge(outgoingHalfEdge) == edgeId) {
                return getAngle(vertexId, outgoingHalfEdge);
            }
        }
        return 0.0;
    }

    private void solveForHopfField() {
        int N = 2 * vertexCount;

        double loadNormSquared = 0.0;
        for (int i = 0; i < N; i++) {
            loadNormSquared += loadVector[i] * loadVector[i];
        }
        Platforms.log("[hopf]    load norm=%.6e%n", Math.sqrt(loadNormSquared));

        // M q = loadVector. PCG takes its RHS from matrix.rightHandSide.
        System.arraycopy(loadVector, 0, massSystemMatrix.rightHandSide, 0, N);

        this.hopfField = new double[N]; // warm start 0; mutated in place into q
        AdaptiveSolver.PcgResult result = AdaptiveSolver.preconditionedConjugateGradient(
                massSystemMatrix,
                this.hopfField,
                null, // no pinned DOFs: include all in the norms
                jacobi(massSystemMatrix), // M is well-conditioned; Jacobi suffices
                10000,
                1e-8);
        System.out.println("[hopf]    PCG iters=" + result.iterations() + PCG_CONVERGED_SUFFIX + result.converged());

        for (int v = 0; v < vertexCount; v++) {
            double realCurvature = hopfField[2 * v];
            double imaginaryCurvature = hopfField[2 * v + 1];
            // (qRe + i*qIm)^2 = (qRe^2 - qIm^2) + i*(2*qRe*qIm)
            crossFieldGuidance[2 * v] = realCurvature * realCurvature - imaginaryCurvature * imaginaryCurvature;
            crossFieldGuidance[2 * v + 1] = 2.0 * realCurvature * imaginaryCurvature;
        }
    }

    /** Jacobi (diagonal) preconditioner: z = D^{-1} r, with D = diag(a). */
    private static Preconditioner jacobi(NormalMatrix a) {
        int n = a.size();
        double[] invDiag = new double[n];
        for (int i = 0; i < n; i++) {
            double d = a.diag(i);
            invDiag[i] = (d != 0.0) ? 1.0 / d : 0.0;
        }
        return (r, z) -> {
            for (int i = 0; i < n; i++) {
                z[i] = invDiag[i] * r[i];
            }
        };
    }

    /**
     * Compute the per-vertex tangent frames.
     */
    public void computeVertexFrames() {
        vertexX = new Vector3f[vertexCount];
        vertexY = new Vector3f[vertexCount];
        vertexNormal = new Vector3f[vertexCount];

        for (int v = 0; v < vertexCount; v++) {
            int vId = vertexIdOf[v];

            // Vertex normal = normalized sum of incident face normals.
            Vector3f nrm = new Vector3f();
            int fCount = mesh.vertexFaceCount(vId);
            for (int i = 0; i < fCount; i++) {
                nrm.add(mesh.faceNormal(mesh.vertexFaceAt(vId, i)));
            }
            if (nrm.length() < EPS) {
                nrm.set(0f, 0f, 1f);
            }
            nrm.normalize();

            // X = first outgoing edge, projected into the tangent plane.
            Vector3f x = new Vector3f(0f, 0f, 0f);
            if (mesh.vertexOutgoingHalfEdgeCount(vId) > 0) {
                int he = mesh.vertexOutgoingHalfEdgeAt(vId, 0);
                Vector3f p0 = mesh.vertexPosition(mesh.halfEdgeVertex(he));
                Vector3f p1 = mesh.vertexPosition(mesh.halfEdgeEndVertex(he));
                x.set(p1).sub(p0);
                float d = x.dot(nrm);
                x.x -= d * nrm.x;
                x.y -= d * nrm.y;
                x.z -= d * nrm.z;
            }
            if (x.length() < EPS) {
                CrossField.arbitraryTangent(nrm, x);
            } else {
                x.normalize();
            }
            Vector3f y = new Vector3f();
            nrm.cross(x, y).normalize();

            vertexNormal[v] = nrm;
            vertexX[v] = x;
            vertexY[v] = y;
        }
    }

    /**
     * Compute the angle rescaling and per-outgoing-edge angles.
     */
    public void computeAngleRescaling() {
        angleDefect = new double[vertexCount];

        for (int v = 0; v < vertexCount; v++) {
            int vId = vertexIdOf[v];
            boolean boundary = mesh.isBoundaryVertex(vId);
            int outCount = mesh.vertexOutgoingHalfEdgeCount(vId);
            if (outCount == 0) {
                continue;
            }
            Vector3f vp = mesh.vertexPosition(vId);

            // Spokes with their direction and angle in the vertex tangent frame.
            int[] hes = new int[outCount];
            Vector3f[] dir = new Vector3f[outCount];
            double[] raw = new double[outCount];
            for (int i = 0; i < outCount; i++) {
                int he = mesh.vertexOutgoingHalfEdgeAt(vId, i);
                Vector3f d = new Vector3f(mesh.vertexPosition(mesh.halfEdgeEndVertex(he))).sub(vp);
                if (d.length() < EPS) {
                    d.set(vertexX[v]);
                }
                d.normalize();
                hes[i] = he;
                dir[i] = d;
                raw[i] = Math.atan2(d.dot(vertexY[v]), d.dot(vertexX[v]));
            }

            // The cached list is insertion order. Sort into rotational (CCW) order.
            Integer[] order = new Integer[outCount];
            for (int i = 0; i < outCount; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (p, q) -> Double.compare(raw[p], raw[q]));

            // Corner-angle gaps between rotational neighbours (gap[k] spans sorted k ->
            // k+1).
            double[] gap = new double[outCount];
            for (int k = 0; k < outCount; k++) {
                Vector3f a = dir[order[k]];
                Vector3f b = dir[order[(k + 1) % outCount]];
                double c = Math.max(-1.0, Math.min(1.0, a.dot(b)));
                gap[k] = Math.acos(c);
            }

            // On a boundary fan the opening is not a real corner; exclude the largest gap.
            int openingIndex = -1;
            if (boundary) {
                double max = -1.0;
                for (int k = 0; k < outCount; k++) {
                    if (gap[k] > max) {
                        max = gap[k];
                        openingIndex = k;
                    }
                }
            }

            double total = 0.0;
            for (int k = 0; k < outCount; k++) {
                if (k != openingIndex) {
                    total += gap[k];
                }
            }
            angleDefect[v] = boundary ? 0.0 : (2.0 * Math.PI - total);
            double scale = (boundary || total < EPS) ? 1.0 : (2.0 * Math.PI / total);

            // Walk the fan (boundary: start just after the opening) assigning cumulative
            // rescaled angle by sorted position.
            int startK = boundary ? (openingIndex + 1) % outCount : 0;
            double[] absPhi = new double[outCount];
            double phi = 0.0;
            for (int step = 0; step < outCount; step++) {
                int k = (startK + step) % outCount;
                absPhi[k] = phi;
                phi += scale * gap[k];
            }

            // Re-zero so the vertexX-defining spoke (outgoing index 0) reads 0.
            int refHe = mesh.vertexOutgoingHalfEdgeAt(vId, 0);
            double refPhi = 0.0;
            for (int k = 0; k < outCount; k++) {
                if (hes[order[k]] == refHe) {
                    refPhi = absPhi[k];
                    break;
                }
            }
            for (int k = 0; k < outCount; k++) {
                putAngle(vId, hes[order[k]], absPhi[k] - refPhi);
            }
        }
    }

    /**
     * Assemble the complex Hermitian connection Laplacian (upper triangle).
     */
    private void assemble() {
        double[] diag = new double[vertexCount];
        Map<Long, Double> upRe = new HashMap<>();
        Map<Long, Double> upIm = new HashMap<>();

        double[] diagMass = new double[vertexCount];
        Map<Long, Double> massUpRe = new HashMap<>();
        Map<Long, Double> massUpIm = new HashMap<>();

        for (int f = 0; f < mesh.faceCount(); f++) {
            int fId = mesh.faceIdAt(f);
            int[] halfEdge = orderedFaceHalfEdges(fId); // CCW: v0->v1->v2->v0
            int[] vertexId = new int[3];
            int[] active = new int[3];
            Vector3f[] position = new Vector3f[3];
            for (int corner = 0; corner < 3; corner++) {
                vertexId[corner] = mesh.halfEdgeVertex(halfEdge[corner]);
                active[corner] = activeOfVertexId.get(vertexId[corner]);
                position[corner] = mesh.vertexPosition(vertexId[corner]);
            }

            // Transport angle on each CCW directed edge, and the triangle holonomy.
            double[] transportAngle = new double[3];
            for (int corner = 0; corner < 3; corner++) {
                int twin = mesh.halfEdgeTwin(halfEdge[corner]);
                transportAngle[corner] = n * (getAngle(vertexId[(corner + 1) % 3], twin)
                        - getAngle(vertexId[corner], halfEdge[corner]));
            }
            double holonomy = principal(transportAngle[0] + transportAngle[1] + transportAngle[2]);

            Vector3f edge0 = new Vector3f(position[1]).sub(position[0]);
            double area = 0.5 * new Vector3f(edge0)
                    .cross(new Vector3f(position[2]).sub(position[0])).length();
            if (area < EPS) {
                continue;
            }

            // Diagonal: full stiffness (with the holonomy correction) minus the curvature
            // term.
            for (int corner = 0; corner < 3; corner++) {
                Vector3f toNext = new Vector3f(position[(corner + 1) % 3]).sub(position[corner]);
                Vector3f toPrev = new Vector3f(position[(corner + 2) % 3]).sub(position[corner]);
                double diagStiff = SectionIntegrals.stiffnessDiagonal(holonomy,
                        toNext.lengthSquared(), toNext.dot(toPrev), toPrev.lengthSquared());
                diag[active[corner]] += diagStiff / area - curvatureBias * holonomy / 6.0;
                diagMass[active[corner]] += area / 6.0;
            }

            // Off-diagonal: one per edge, from the two edges at the opposite vertex.
            for (int corner = 0; corner < 3; corner++) {
                int from = active[corner];
                int to = active[(corner + 1) % 3];
                int opposite = (corner + 2) % 3;
                Vector3f oppToFrom = new Vector3f(position[corner]).sub(position[opposite]);
                Vector3f oppToTo = new Vector3f(position[(corner + 1) % 3]).sub(position[opposite]);

                double[] stiff = SectionIntegrals.stiffnessOffDiagonal(holonomy,
                        oppToFrom.lengthSquared(), oppToFrom.dot(oppToTo), oppToTo.lengthSquared());
                double[] massOff = SectionIntegrals.massOffDiagonal(holonomy);

                // entry before transport = stiffness/area - curvatureBias*(holonomy*mass - i/2)
                double entryRe = stiff[0] / area - curvatureBias * holonomy * massOff[0];
                double entryIm = stiff[1] / area - curvatureBias * (holonomy * massOff[1] - 0.5);

                // multiply by the conjugate transport, e^(-i * transportAngle)
                double cosRho = Math.cos(transportAngle[corner]);
                double sinRho = Math.sin(transportAngle[corner]);
                double re = entryRe * cosRho + entryIm * sinRho;
                double im = entryIm * cosRho - entryRe * sinRho;

                int low = Math.min(from, to);
                int high = Math.max(from, to);
                if (low == from) {
                    accum(upRe, upIm, low, high, re, im);
                } else {
                    accum(upRe, upIm, low, high, re, -im);
                }

                double massRe0 = area * massOff[0];
                double massIm0 = area * massOff[1];
                double massRe = massRe0 * cosRho + massIm0 * sinRho; // * conjugate transport
                double massIm = massIm0 * cosRho - massRe0 * sinRho;
                if (low == from) {
                    accum(massUpRe, massUpIm, low, high, massRe, massIm);
                } else {
                    accum(massUpRe, massUpIm, low, high, massRe, -massIm);
                }
            }
        }

        for (int v = 0; v < vertexCount; v++) {
            diag[v] += DEFAULT_SHIFT * diagMass[v];
        }
        this.energyMatrix = realify(diag, upRe, upIm);
        this.massSystemMatrix = realify(diagMass, massUpRe, massUpIm);
    }

    private static void accum(Map<Long, Double> re, Map<Long, Double> im, int i, int j, double a, double b) {
        long k = (((long) i) << 32) | (j & 0xFFFFFFFFL);
        re.merge(k, a, Double::sum);
        im.merge(k, b, Double::sum);
    }

    private NormalMatrix realify(double[] complexDiag, Map<Long, Double> upRe,
            Map<Long, Double> upIm) {
        int V = vertexCount;
        int N = 2 * V;
        double[] diag2 = new double[N];
        for (int v = 0; v < V; v++) {
            diag2[2 * v] = complexDiag[v]; // real DOF
            diag2[2 * v + 1] = complexDiag[v]; // imaginary DOF
        }
        Map<Long, Double> upper = new HashMap<>();
        for (Map.Entry<Long, Double> en : upRe.entrySet()) {
            long k = en.getKey();
            int i = (int) (k >>> 32);
            int j = (int) (k & 0xFFFFFFFFL); // i < j
            double a = en.getValue();
            double b = upIm.getOrDefault(k, 0.0);

            int ri = 2 * i, ii = 2 * i + 1; // real / imag DOFs of vertex i
            int rj = 2 * j, ij = 2 * j + 1;

            put(upper, ri, rj, a); // Re block
            put(upper, ii, ij, a); // Re block (imag diagonal block)
            put(upper, ri, ij, -b); // (real_i, imag_j) = -b
            put(upper, ii, rj, b); // (imag_i, real_j) = b — note ii<rj since i<j
        }
        return new NormalMatrix(diag2, upper, new double[N]);
    }

    /**
     * Solve for the globally smoothest direction field via inverse power iteration
     * on the generalized eigenproblem {@code A u = lambda M u} (KCP13 Algorithm 2).
     */
    public void solveSmoothest() {
        PowerIteration iteration = new PowerIteration(system, energyMatrix, massSystemMatrix,
                DEFAULT_POWER_ITERATIONS);
        iteration.run();
        uReal = new double[vertexCount];
        uImaginary = new double[vertexCount];
        if (!iteration.factored) {
            return;
        }
        double[] u = system.solution;
        for (int v = 0; v < vertexCount; v++) {
            uReal[v] = u[v];
            uImaginary[v] = u[vertexCount + v];
        }
        fieldAngle = new double[vertexCount];
        for (int v = 0; v < vertexCount; v++) {
            fieldAngle[v] = Math.atan2(uImaginary[v], uReal[v]) / n;
        }
    }

    private void solveAligned(double t) {
        int N = 2 * vertexCount;

        // (A - tM). With t = 0 this is just A.
        NormalMatrix shifted = (t == 0.0)
                ? energyMatrix
                : energyMatrix.subtract(massSystemMatrix.scale(t));

        // RHS = M g (+ dual-form feature load), written into shifted.rightHandSide
        // for PCG to read.
        for (int i = 0; i < N; i++) {
            shifted.rightHandSide[i] = massSystemMatrix.rowDot(i, crossFieldGuidance);
        }
        if (featureAlignmentLoad != null) {
            for (int i = 0; i < N; i++) {
                shifted.rightHandSide[i] += featureAlignmentLoad[i];
            }
        }

        double[] x = system.solution; // warm start 0; mutated in place into u
        Arrays.fill(x, 0.0);
        AdaptiveSolver.PcgResult result = AdaptiveSolver.preconditionedConjugateGradient(
                shifted,
                x,
                null,
                jacobi(shifted), // Laplacian: try Jacobi first, upgrade to IC(0) if slow
                5000,
                1e-8);
        System.out.println("[aligned] PCG iters=" + result.iterations() + PCG_CONVERGED_SUFFIX + result.converged());

        massNormalize(x, massSystemMatrix);

        uReal = new double[vertexCount];
        uImaginary = new double[vertexCount];
        for (int v = 0; v < vertexCount; v++) {
            uReal[v] = x[2 * v];
            uImaginary[v] = x[2 * v + 1];
        }
        fieldAngle = new double[vertexCount];
        for (int v = 0; v < vertexCount; v++) {
            fieldAngle[v] = Math.atan2(uImaginary[v], uReal[v]) / n;
        }
    }

    private static void massNormalize(double[] v, NormalMatrix mass) {
        double quadratic = 0.0;
        for (int i = 0; i < v.length; i++) {
            quadratic += v[i] * mass.rowDot(i, v); // v^T M v
        }
        double scale = Math.sqrt(Math.max(quadratic, EPS));
        for (int i = 0; i < v.length; i++) {
            v[i] /= scale;
        }
    }

    /**
     * Compute the per-triangle singularity index.
     *
     * @return the index in {-1,0,1} for each face (in {@code faceIdAt} order).
     */
    public int[] computeTriangleIndices() {
        int count = mesh.faceCount();
        int[] index = new int[count];
        for (int f = 0; f < count; f++) {
            int fId = mesh.faceIdAt(f);
            int[] hes = orderedFaceHalfEdges(fId);
            double sumOmega = 0.0; // sum of edge rotation angles
            double holonomy = 0.0; // Omega_ijk = arg(prod r)
            double rhoProd = 0.0;
            for (int c = 0; c < hes.length; c++) {
                int he = hes[c];
                int twin = mesh.halfEdgeTwin(he);
                int aId = mesh.halfEdgeVertex(he);
                int bId = mesh.halfEdgeEndVertex(he);
                int a = activeOfVertexId.get(aId);
                int b = activeOfVertexId.get(bId);
                double rho = n * (getAngle(bId, twin) - getAngle(aId, he));
                rhoProd += rho;
                // omega_ab : u_b = e^{i omega} r_ab u_a -> omega = arg(u_b / (r_ab u_a))
                double raRe = Math.cos(rho), raIm = Math.sin(rho);
                double tRe = raRe * uReal[a] - raIm * uImaginary[a]; // r_ab * u_a
                double tIm = raRe * uImaginary[a] + raIm * uReal[a];
                double omega = Math.atan2(uImaginary[b], uReal[b]) - Math.atan2(tIm, tRe);
                sumOmega += principal(omega);
            }
            holonomy = principal(rhoProd);
            index[f] = (int) Math.round((sumOmega + holonomy) / (2.0 * Math.PI));
        }
        return index;
    }

    /**
     * Compute the cotangent of the opposite angle of a face.
     *
     * @param faceId the id of the face
     * @param pId    the id of the first vertex
     * @param qId    the id of the second vertex
     * @return the cotangent of the opposite angle of the face
     */
    public double coTangentOpposite(int faceId, int pId, int qId) {
        int[] faceVertices = faceVertices(faceId);
        int oppId = -1;
        for (int id : faceVertices) {
            if (id != pId && id != qId) {
                oppId = id;
            }
        }
        Vector3f o = mesh.vertexPosition(oppId);
        Vector3f a = new Vector3f(mesh.vertexPosition(pId)).sub(o);
        Vector3f b = new Vector3f(mesh.vertexPosition(qId)).sub(o);
        double dot = a.dot(b);
        double cross = new Vector3f(a).cross(b).length();
        return dot / Math.max(cross, EPS);
    }

    private int[] faceVertices(int fId) {
        int[] hes = orderedFaceHalfEdges(fId);
        return new int[] {
                mesh.halfEdgeVertex(hes[0]),
                mesh.halfEdgeVertex(hes[1]),
                mesh.halfEdgeVertex(hes[2])
        };
    }

    private int[] orderedFaceHalfEdges(int fId) {
        int count = mesh.faceHalfEdgeCount(fId);
        int[] hes = new int[count];
        for (int i = 0; i < count; i++) {
            hes[i] = mesh.faceHalfEdgeAt(fId, i);
        }
        return hes;
    }

    private static double principal(double a) {
        double r = a % (2.0 * Math.PI);
        if (r <= -Math.PI)
            r += 2.0 * Math.PI;
        if (r > Math.PI)
            r -= 2.0 * Math.PI;
        return r;
    }

    private static void put(Map<Long, Double> m, int i, int j, double v) {
        m.merge((((long) i) << 32) | (j & 0xFFFFFFFFL), v, Double::sum);
    }

    private void putAngle(int vId, int he, double angle) {
        angleInFrame.put((((long) vId) << 32) | (he & 0xFFFFFFFFL), angle);
    }

    private double getAngle(int vId, int he) {
        return angleInFrame.getOrDefault((((long) vId) << 32) | (he & 0xFFFFFFFFL), 0.0);
    }

    /**
     * Fill {@code cf.theta}, {@code cf.periodJump}, and
     * {@code cf.singularityIndex4} from {@code field}. The field's degree
     * {@code n} should match the cross field's symmetry (4 for a cross field).
     */
    public void populate() {
        // vertex id -> NDirectionField active vertex index. NDirectionField numbers
        // vertices by mesh.vertexIdAt order, so this inverse matches its indexing.
        Map<Integer, Integer> vIdToActive = new HashMap<>(mesh.vertexCount() * 2);
        for (int v = 0; v < mesh.vertexCount(); v++) {
            vIdToActive.put(mesh.vertexIdAt(v), v);
        }

        // Per-face angle as the complex mean of the corners' n-power
        // directions in the face frame (KCP13's u interpolated to the face).
        // The mean is reliable wherever the field has no zero; inside a
        // singular triangle it cancels and its atan2 is arbitrary, which used
        // to let the rounded period jumps split that face's ±1 winding into a
        // phantom dipole among the face's own vertices. Singular faces (by the
        // raw triangle index, KCP13 §6.1.3) therefore get their cf.theta re-set
        // below by exact transport from their most reliable neighbor.
        double[] faceMeanMagnitude = new double[cf.faceCount];
        for (int fAi = 0; fAi < cf.faceCount; fAi++) {
            int fId = mesh.faceIdAt(fAi);
            Vector3f fx = cf.faceX[fAi];
            Vector3f fy = cf.faceY[fAi];

            double accRe = 0.0;
            double accIm = 0.0;
            int corners = mesh.faceHalfEdgeCount(fId);
            for (int c = 0; c < corners; c++) {
                int he = mesh.faceHalfEdgeAt(fId, c);
                Integer va = vIdToActive.get(mesh.halfEdgeVertex(he));
                if (va == null) {
                    continue;
                }

                double a = fieldAngle[va];
                Vector3f d = new Vector3f(vertexX[va]).mul((float) Math.cos(a));
                d = d.add(new Vector3f(vertexY[va]).mul((float) Math.sin(a)));
                d = d.normalize();
                double alpha = Math.atan2(d.dot(fy), d.dot(fx)); // its angle in face frame
                accRe += Math.cos(n * alpha); // collapse n-fold symmetry
                accIm += Math.sin(n * alpha);
            }
            cf.theta[fAi] = (float) (Math.atan2(accIm, accRe) / n);
            faceMeanMagnitude[fAi] = Math.hypot(accRe, accIm);
        }
        concentrateSingularFaceWindings(faceMeanMagnitude);
        for (int eAi = 0; eAi < cf.edgeCount; eAi++) {
            int eId = mesh.edgeIdAt(eAi);
            if (mesh.isBoundaryEdge(eId)) {
                cf.periodJump[eAi] = 0;
                continue;
            }
            int he = mesh.edgeHalfEdge(eId);
            int twin = mesh.halfEdgeTwin(he);
            int i = cf.faceIdToActive.get(mesh.halfEdgeFace(he));
            int j = cf.faceIdToActive.get(mesh.halfEdgeFace(twin));

            double resid = cf.theta[j] - cf.theta[i] - cf.kappa[eAi];
            cf.periodJump[eAi] = Math.round((float) (resid / HALF_PI));
        }
        extractSingularities();

        int indexSum4 = 0;
        for (int v = 0; v < cf.singularityIndex4.length(); v++) {
            indexSum4 += cf.singularityIndex4.get(v);
        }
        int singularityCount = cf.singularityCount();
        int eulerCharacteristic = vertexCount - cf.edgeCount + cf.faceCount;
        // Discrete Poincaré–Hopf (KCP13 App. B): on a closed mesh the quarter
        // indices must sum to 4χ; boundary vertices are excluded from the walk,
        // so meshes with boundary will legitimately differ.
        Platforms.log("[n-field] singularities=%d indexSum4=%d fourChi=%d%n",
                singularityCount, indexSum4, 4 * eulerCharacteristic);

        // KCP13 §6.1.3 ground truth: the raw field's per-triangle indices. The
        // converted cf.theta/cf.periodJump representation must reproduce them; a
        // count mismatch means the conversion minted or lost cf.singularities.
        int[] triangleIndex = computeTriangleIndices();
        int triangleNonzero = 0;
        int triangleSum = 0;
        for (int face = 0; face < triangleIndex.length; face++) {
            if (triangleIndex[face] != 0) {
                triangleNonzero++;
                triangleSum += triangleIndex[face];
            }
        }
        Platforms.log("[n-field] raw triangle indices: nonzero=%d sum=%d%n",
                triangleNonzero, triangleSum);
        if (triangleNonzero != singularityCount) {
            Platforms.log("[n-field] WARNING conversion mismatch: raw field has %d singular"
                    + " triangles but extraction found %d vertex singularities%n",
                    triangleNonzero, singularityCount);
            Set<Integer> rawSingularCornerVertices = new HashSet<>();
            for (int face = 0; face < triangleIndex.length; face++) {
                if (triangleIndex[face] == 0) {
                    continue;
                }
                int faceId = mesh.faceIdAt(face);
                for (int corner = 0; corner < mesh.faceHalfEdgeCount(faceId); corner++) {
                    rawSingularCornerVertices.add(mesh.faceVertexAt(faceId, corner));
                }
            }
            for (int v = 0; v < cf.singularityIndex4.length(); v++) {
                int iQuarter = cf.singularityIndex4.get(v);
                if (iQuarter != 0 && !rawSingularCornerVertices.contains(mesh.vertexIdAt(v))) {
                    Platforms.log("[n-field]   phantom singularity vertex=%d index4=%d"
                            + " (no raw singular triangle touches it)%n",
                            mesh.vertexIdAt(v), iQuarter);
                }
            }
        }
    }

    /**
     * Re-sets the cf.theta of every face with non-zero triangle index by exact
     * transport from its most reliable non-singular neighbor, zeroing the period
     * jump across that shared edge and concentrating the face's winding at the
     * vertex opposite it.
     *
     * <p>
     * See also: KCP*13 Section 6.1.3
     *
     * @param faceMeanMagnitude per-face magnitude of the corner-direction complex
     *                          mean (reliability of that face's cf.theta)
     */
    private void concentrateSingularFaceWindings(double[] faceMeanMagnitude) {
        int[] triangleIndex = computeTriangleIndices();
        for (int fAi = 0; fAi < cf.faceCount; fAi++) {
            if (triangleIndex[fAi] == 0) {
                continue;
            }
            int fId = mesh.faceIdAt(fAi);
            int bestNeighbor = -1;
            double bestMagnitude = -1.0;
            int bestEdgeActive = -1;
            boolean bestCanonicalIntoFace = false;
            for (int corner = 0; corner < mesh.faceEdgeCount(fId); corner++) {
                int eId = mesh.faceEdgeAt(fId, corner);
                if (mesh.isBoundaryEdge(eId)) {
                    continue;
                }
                int halfEdge = mesh.edgeHalfEdge(eId);
                int canonicalFaceId = mesh.halfEdgeFace(halfEdge);
                int twinFaceId = mesh.halfEdgeFace(mesh.halfEdgeTwin(halfEdge));
                int otherFaceId = canonicalFaceId == fId ? twinFaceId : canonicalFaceId;
                if (otherFaceId < 0) {
                    continue;
                }
                int otherActive = cf.faceIdToActive.get(otherFaceId);
                if (triangleIndex[otherActive] != 0
                        || faceMeanMagnitude[otherActive] <= bestMagnitude) {
                    continue;
                }
                bestMagnitude = faceMeanMagnitude[otherActive];
                bestNeighbor = otherActive;
                bestEdgeActive = cf.edgeIdToActive.get(eId);
                bestCanonicalIntoFace = canonicalFaceId != fId;
            }
            if (bestNeighbor < 0) {
                continue;
            }
            // cf.periodJump rounds cf.theta[j] − cf.theta[i] − cf.kappa with i the
            // canonical half-edge's face; zero-jump transport solves for this
            // face's cf.theta accordingly.
            cf.theta[fAi] = (float) (bestCanonicalIntoFace
                    ? cf.theta[bestNeighbor] + cf.kappa[bestEdgeActive]
                    : cf.theta[bestNeighbor] - cf.kappa[bestEdgeActive]);
        }
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(FIELD, SINGULARITY_COUNT, SINGULARITIES, FEATURE_EDGES, DOFS);
    }

    @Override
    public String description() {
        return "Builds the quad-layout cross field (curvature-aligned n-direction field) over a"
                + " triangle mesh and extracts its singularities.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Triangle mesh to build the field on; manifold, possibly with boundary.",
                FIELD.name, "The cross field with per-face frames and singularities.",
                SINGULARITY_COUNT.name, "Number of extracted singularities.",
                SINGULARITIES.name, "Per-vertex singularity index4 attribute (0 = not singular),"
                        + " for tracing stages.",
                FEATURE_EDGES.name, "Per-edge selection of sharp feature and boundary edges.",
                DOFS.name, "The smoothing solve's system, for solver-composing graphs."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = ctx.getInput(GEOMETRY.name, GeometryBundle.class);
        CrossField field = new NDirectionField()
                .build(HalfEdgeMeshEngine.fromMeshTopology(bundle.mesh()));
        ctx.setOutput(FIELD.name, field);
        ctx.setOutput(SINGULARITY_COUNT.name, field.singularityCount());
        ctx.setOutput(SINGULARITIES.name, field.singularityIndex4);
        ctx.setOutput(FEATURE_EDGES.name, field.alignmentEdges);
        ctx.setOutput(DOFS.name, field.system);
    }
}
