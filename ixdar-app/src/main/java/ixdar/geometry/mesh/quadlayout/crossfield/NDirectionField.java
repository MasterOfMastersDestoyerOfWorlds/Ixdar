package ixdar.geometry.mesh.quadlayout.crossfield;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh.EdgeFaceIds;
import ixdar.geometry.mesh.quadlayout.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.AdaptiveSolver;
import ixdar.geometry.mesh.quadlayout.solver.DirectSolver;
import ixdar.geometry.mesh.quadlayout.solver.OrderingMethod;
import ixdar.geometry.mesh.quadlayout.solver.Preconditioner;

public class NDirectionField extends CrossField {

    /**
     * Diagonal regularizer A <- A + shift*M so the closed-surface system is SPD
     * (paper Sec. 7).
     */
    public static final double DEFAULT_SHIFT = 1e-8;
    /** Fixed power-iteration count; the paper uses 20 for all examples (Sec. 7). */
    public static final int DEFAULT_POWER_ITERATIONS = 20;

    public static final float HALF_PI = (float) (Math.PI / 2.0);
    public static final double EPS = 1e-12;

    public final HalfEdgeMesh mesh;

    public final int vertexCount;
    private final int[] vertexIdOf; // active index -> vertex id
    private final Map<Integer, Integer> activeOfVertexId = new HashMap<>();

    // Per-vertex tangent frame (world space).
    public Vector3f[] vertexX;
    public Vector3f[] vertexY;
    private Vector3f[] vertexNormal;

    private double[] angleDefect;

    // angleInFrame[ packVH(vertexId, halfEdge) ] = rescaled angle of that outgoing
    // half-edge in the vertex's flattened tangent frame (paper Eq. 11/12).
    private final Map<Long, Double> angleInFrame = new HashMap<>();

    // Solution: per-vertex n-th power coefficient u = uRe + i*uIm.
    public double[] uReal;
    public double[] uImaginary;
    /** Representative direction angle in the vertex frame: arg(u)/n. */
    public double[] fieldAngle;

    /* The degree of the direction field. */
    public final int n = 4;
    /**
     * Smoothness parameter of where to put singularities in [-1, 1]; 0 = Dirichlet.
     * -1 = holomorphic (at points of high Gaussian curvature), 1 = anti-holomorphic
     * (at points of low Gaussian curvature).
     */
    public final double curvatureBias = 0;
    public NormalMatrix energyMatrix;
    public NormalMatrix massSystemMatrix;
    public double[] loadVector;
    public double[] hopfField;
    public double[] crossFieldGuidance;

    public boolean useCurvatureAlignment = true;

    /**
     * 
     * Cross field construction.
     * 
     * @param mesh half-edge mesh providing geometry, topology, and active-id
     *             mapping
     */
    public NDirectionField(HalfEdgeMesh mesh) {
        super(mesh);
        this.mesh = mesh;
        this.vertexCount = mesh.vertexCount();
        this.vertexIdOf = new int[vertexCount];
        for (int v = 0; v < vertexCount; v++) {
            int vId = mesh.vertexIdAt(v);
            vertexIdOf[v] = vId;
            activeOfVertexId.put(vId, v);
        }
        this.loadVector = new double[2 * vertexCount];
        this.crossFieldGuidance = new double[2 * vertexCount];
    }

    @Override
    public NDirectionField build() {
        super.build();

        long sectionStart = System.nanoTime();
        computeVertexFrames();
        System.out.printf("[cross-field timing] compute vertex frames %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        sectionStart = System.nanoTime();
        computeAngleRescaling();
        System.out.printf("[cross-field timing] compute angle rescaling %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        sectionStart = System.nanoTime();
        assemble();
        System.out.printf("[cross-field timing] assemble %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        sectionStart = System.nanoTime();
        if (useCurvatureAlignment) {
            buildLoadVector();

            System.out.printf("[cross-field timing] build load vector %.3fs%n",
                    (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
            sectionStart = System.nanoTime();
            solveForHopfField();
            System.out.printf("[cross-field timing] solve for hopf field %.3fs%n",
                    (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
            sectionStart = System.nanoTime();
            solveAligned(0.0);
            System.out.printf("[cross-field timing] solve aligned %.3fs%n",
                    (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        } else {
            solveSmoothest();
            System.out.printf("[cross-field timing] solve smoothest %.3fs%n",
                    (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        }
        sectionStart = System.nanoTime();
        populate();
        System.out.printf("[cross-field timing] populate %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        return this;
    }

    private void buildLoadVector() {
        Vector3f start = new Vector3f();
        Vector3f end = new Vector3f();
        for (int activeEdge = 0; activeEdge < edgeCount; activeEdge++) {
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

    private void solveForHopfField() {
        int N = 2 * vertexCount;

        // M q = loadVector. PCG takes its RHS from matrix.rightHandSide.
        System.arraycopy(loadVector, 0, massSystemMatrix.rightHandSide, 0, N);

        this.hopfField = new double[N]; // warm start 0; mutated in place into q
        AdaptiveSolver.PcgResult result = AdaptiveSolver.preconditionedConjugateGradient(
                massSystemMatrix,
                this.hopfField,
                null,                       // no pinned DOFs: include all in the norms
                jacobi(massSystemMatrix),   // M is well-conditioned; Jacobi suffices
                10000,
                1e-8);
        System.out.println("[hopf]    PCG iters=" + result.iterations() + " converged=" + result.converged());

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
            java.util.Arrays.sort(order, (p, q) -> Double.compare(raw[p], raw[q]));

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
        ComplexUpper energyMatrixComplex = new ComplexUpper(diag, upRe, upIm);
        ComplexUpper massSystemMatrixComplex = new ComplexUpper(diagMass, massUpRe, massUpIm);

        this.energyMatrix = realify(energyMatrixComplex);
        this.massSystemMatrix = realify(massSystemMatrixComplex);
    }

    private static void accum(Map<Long, Double> re, Map<Long, Double> im, int i, int j, double a, double b) {
        long k = (((long) i) << 32) | (j & 0xFFFFFFFFL);
        re.merge(k, a, Double::sum);
        im.merge(k, b, Double::sum);
    }

    private NormalMatrix realify(ComplexUpper sys) {
        int V = vertexCount;
        int N = 2 * V;
        double[] diag2 = new double[N];
        for (int v = 0; v < V; v++) {
            diag2[2 * v] = sys.diag()[v]; // real DOF
            diag2[2 * v + 1] = sys.diag()[v]; // imaginary DOF
        }
        Map<Long, Double> upper = new HashMap<>();
        for (Map.Entry<Long, Double> en : sys.upRe().entrySet()) {
            long k = en.getKey();
            int i = (int) (k >>> 32);
            int j = (int) (k & 0xFFFFFFFFL); // i < j
            double a = en.getValue();
            double b = sys.upIm().getOrDefault(k, 0.0);

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
     * Solve the smoothest direction field.
     * 
     * @param mass
     */
    public void solveSmoothest() {
        int N = 2 * vertexCount;

        boolean[] fixed = new boolean[N];
        DirectSolver.CholeskyHandle handle = DirectSolver.factorize(energyMatrix, fixed, OrderingMethod.RCM);
        if (handle.solver() == null) {
            uReal = new double[vertexCount];
            uImaginary = new double[vertexCount];
            return;
        }

        double[] u = new double[N];
        Random rng = new Random(12345L);
        for (int i = 0; i < N; i++) {
            u[i] = rng.nextDouble() * 2.0 - 1.0;
        }
        massNormalize(u, massSystemMatrix);

        double[] rhs = new double[N];
        double[] x = new double[N];
        double[] start = new double[N];
        for (int it = 0; it < DEFAULT_POWER_ITERATIONS; it++) {
            for (int i = 0; i < N; i++) {
                rhs[i] = massSystemMatrix.rowDot(i, u); // rhs = M * u
            }
            DirectSolver.solveCompact(handle, energyMatrix, rhs, x, start, fixed);
            System.arraycopy(x, 0, u, 0, N);
            massNormalize(u, massSystemMatrix);
        }

        uReal = new double[vertexCount];
        uImaginary = new double[vertexCount];
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

        // RHS = M g, written into shifted.rightHandSide for PCG to read.
        for (int i = 0; i < N; i++) {
            shifted.rightHandSide[i] = massSystemMatrix.rowDot(i, crossFieldGuidance);
        }

        double[] x = new double[N]; // warm start 0; mutated in place into u
        AdaptiveSolver.PcgResult result = AdaptiveSolver.preconditionedConjugateGradient(
                shifted,
                x,
                null,
                jacobi(shifted),    // Laplacian: try Jacobi first, upgrade to IC(0) if slow
                5000,
                1e-8);
        System.out.println("[aligned] PCG iters=" + result.iterations() + " converged=" + result.converged());

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
        int faceCount = mesh.faceCount();
        int[] index = new int[faceCount];
        for (int f = 0; f < faceCount; f++) {
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

    private static double coTangentAt(Vector3f apex, Vector3f endA, Vector3f endB) {
        Vector3f toA = new Vector3f(endA).sub(apex);
        Vector3f toB = new Vector3f(endB).sub(apex);
        double dot = toA.dot(toB);
        double cross = new Vector3f(toA).cross(toB).length();
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

    /** Upper-triangle complex matrix in coordinate form (real diagonal). */
    private record ComplexUpper(double[] diag, Map<Long, Double> upRe, Map<Long, Double> upIm) {
    }

    /**
     * Fill {@code cf.theta}, {@code cf.periodJump}, and {@code cf.singularities}
     * from {@code field}. The field's degree {@code n} should match the cross
     * field's symmetry (4 for a cross field).
     */
    public void populate() {
        // vertex id -> NDirectionField active vertex index. NDirectionField numbers
        // vertices by mesh.vertexIdAt order, so this inverse matches its indexing.
        Map<Integer, Integer> vIdToActive = new HashMap<>(mesh.vertexCount() * 2);
        for (int v = 0; v < mesh.vertexCount(); v++) {
            vIdToActive.put(mesh.vertexIdAt(v), v);
        }

        for (int fAi = 0; fAi < faceCount; fAi++) {
            int fId = mesh.faceIdAt(fAi);
            Vector3f fx = faceX[fAi];
            Vector3f fy = faceY[fAi];

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
            theta[fAi] = (float) (Math.atan2(accIm, accRe) / n);
        }
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            int eId = mesh.edgeIdAt(eAi);
            if (mesh.isBoundaryEdge(eId)) {
                periodJump[eAi] = 0;
                continue;
            }
            int he = mesh.edgeHalfEdge(eId);
            int twin = mesh.halfEdgeTwin(he);
            int i = faceIdToActive.get(mesh.halfEdgeFace(he));
            int j = faceIdToActive.get(mesh.halfEdgeFace(twin));

            double resid = theta[j] - theta[i] - kappa[eAi];
            periodJump[eAi] = Math.round((float) (resid / HALF_PI));
        }
        extractSingularities();
    }
}
