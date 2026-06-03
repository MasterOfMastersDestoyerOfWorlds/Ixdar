package ixdar.geometry.mesh.quadlayout.crossfield;

import java.util.HashMap;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh.EdgeFaceIds;
import ixdar.geometry.mesh.quadlayout.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.DirectSolver;
import ixdar.geometry.mesh.quadlayout.solver.OrderingMethod;

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

    // Per-vertex lumped mass and angle defect (2*pi - sum of incident angles).
    private double[] mass;
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
    }

    @Override
    public NDirectionField build() {
        super.build();
        computeVertexFrames();
        computeAngleRescaling();
        ComplexUpper sys = assemble();
        solveSmoothest(sys);
        fieldAngle = new double[vertexCount];
        for (int v = 0; v < vertexCount; v++) {
            fieldAngle[v] = Math.atan2(uImaginary[v], uReal[v]) / n;
        }
        populate();
        return this;
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
        mass = new double[vertexCount];
        angleDefect = new double[vertexCount];

        for (int v = 0; v < vertexCount; v++) {
            int vId = vertexIdOf[v];
            boolean boundary = mesh.isBoundaryVertex(vId);
            int outCount = mesh.vertexOutgoingHalfEdgeCount(vId);
            Vector3f vp = mesh.vertexPosition(vId);

            // Directions of outgoing edges in rotational order.
            int[] hes = new int[outCount];
            Vector3f[] dir = new Vector3f[outCount];
            for (int i = 0; i < outCount; i++) {
                int he = mesh.vertexOutgoingHalfEdgeAt(vId, i);
                hes[i] = he;
                Vector3f d = new Vector3f(mesh.vertexPosition(mesh.halfEdgeEndVertex(he))).sub(vp);
                if (d.length() < EPS) {
                    d.set(vertexX[v]);
                }
                dir[i] = d.normalize();
            }

            // Gaps between consecutive outgoing edges; wrap-around only on interior.
            int gaps = boundary ? outCount - 1 : outCount;
            double[] gap = new double[Math.max(gaps, 0)];
            double total = 0.0;
            for (int i = 0; i < gaps; i++) {
                Vector3f a = dir[i];
                Vector3f b = dir[(i + 1) % outCount];
                double c = Math.max(-1.0, Math.min(1.0, a.dot(b)));
                gap[i] = Math.acos(c);
                total += gap[i];
            }
            angleDefect[v] = boundary ? 0.0 : (2.0 * Math.PI - total);

            // Eq. 11: rescale so interior angles sum to 2*pi (boundary: no rescale).
            double scale = (boundary || total < EPS) ? 1.0 : (2.0 * Math.PI / total);

            double phi = 0.0;
            putAngle(vId, hes[0], 0.0);
            for (int i = 0; i < gaps; i++) {
                phi += scale * gap[i];
                int next = (i + 1) % outCount;
                if (next != 0) {
                    putAngle(vId, hes[next], phi);
                }
            }
        }

        // Lumped (barycentric) mass: each triangle donates area/3 to its 3 vertices.
        int faceCount = mesh.faceCount();
        for (int f = 0; f < faceCount; f++) {
            int fId = mesh.faceIdAt(f);
            int[] vv = faceVertices(fId);
            Vector3f p0 = mesh.vertexPosition(vv[0]);
            Vector3f p1 = mesh.vertexPosition(vv[1]);
            Vector3f p2 = mesh.vertexPosition(vv[2]);
            Vector3f e1 = new Vector3f(p1).sub(p0);
            Vector3f e2 = new Vector3f(p2).sub(p0);
            double area = 0.5 * new Vector3f(e1).cross(e2).length();
            double third = area / 3.0;
            mass[activeOfVertexId.get(vv[0])] += third;
            mass[activeOfVertexId.get(vv[1])] += third;
            mass[activeOfVertexId.get(vv[2])] += third;
        }
    }

    /**
     * Assemble the complex Hermitian connection Laplacian (upper triangle).
     */
    private ComplexUpper assemble() {
        double[] diag = new double[vertexCount]; // real (Hermitian diagonal is real)
        Map<Long, Double> upRe = new HashMap<>();
        Map<Long, Double> upIm = new HashMap<>();

        int edgeCount = mesh.edgeCount();
        for (int e = 0; e < edgeCount; e++) {
            EdgeFaceIds ef = mesh.edgeFaceIds(e);
            if (mesh.isBoundaryEdge(ef.edgeId)) {
                continue;
            }
            int he = ef.halfEdge; // directed p -> q
            int twin = mesh.halfEdgeTwin(he);
            int pId = mesh.halfEdgeVertex(he);
            int qId = mesh.halfEdgeEndVertex(he);
            int p = activeOfVertexId.get(pId);
            int q = activeOfVertexId.get(qId);

            // Cotan weight w = 1/2 (cot(alpha) + cot(beta)) from the two opposite angles.
            double w = 0.5 * (cotOpposite(ef.faceA, pId, qId) + cotOpposite(ef.faceB, pId, qId));

            // Transport p -> q : rho_pq = n*(theta_q - theta_p) (Eqs. 4, 12).
            double thetaP = getAngle(pId, he);
            double thetaQ = getAngle(qId, twin);
            double rho = n * (thetaQ - thetaP);

            // Energy term w*|r_pq u_p - u_q|^2 contributes:
            // diag[p] += w, diag[q] += w,
            // off-diagonal coeff of (conj(u_p) u_q) = -w * conj(r_pq) = -w e^{-i rho}.
            diag[p] += w;
            diag[q] += w;
            double aRe = -w * Math.cos(rho);
            double aIm = w * Math.sin(rho); // = -w * (-sin rho)
            int lo = Math.min(p, q);
            int hi = Math.max(p, q);
            // stored entry is coeff of conj(u_lo) u_hi; conjugate if we flipped order
            if (lo == p) {
                accum(upRe, upIm, lo, hi, aRe, aIm);
            } else {
                accum(upRe, upIm, lo, hi, aRe, -aIm);
            }
        }

        // Geometry-aware term (approximate): A_ii += -s * n * defect_i.
        // Exact per-triangle form is paper Eq. 18 (see plan). Skipped for s == 0.
        if (curvatureBias != 0.0) {
            for (int v = 0; v < vertexCount; v++) {
                diag[v] += -curvatureBias * n * angleDefect[v];
            }
        }

        // Spectral shift A <- A + shift*M (does not change eigenvectors).
        for (int v = 0; v < vertexCount; v++) {
            diag[v] += DEFAULT_SHIFT * mass[v];
        }

        return new ComplexUpper(diag, upRe, upIm);
    }

    /**
     * Solve the smoothest direction field.
     */
    public void solveSmoothest(ComplexUpper sys) {
        int V = vertexCount;
        int N = 2 * V;

        // Realified diagonal and upper triangle for the [[A,-B],[B,A]] block matrix.
        double[] diag2 = new double[N];
        for (int v = 0; v < V; v++) {
            diag2[v] = sys.diag[v];
            diag2[V + v] = sys.diag[v];
        }
        Map<Long, Double> upper = new HashMap<>();
        for (Map.Entry<Long, Double> en : sys.upRe.entrySet()) {
            long k = en.getKey();
            int i = (int) (k >>> 32);
            int j = (int) (k & 0xFFFFFFFFL);
            double a = en.getValue();
            double b = sys.upIm.getOrDefault(k, 0.0);
            put(upper, i, j, a); // (A) top-left
            put(upper, V + i, V + j, a); // (A) bottom-right
            put(upper, i, V + j, -b); // (-B) top-right
            put(upper, j, V + i, b); // (-B) mirror, keeps big matrix symmetric
        }

        NormalMatrix aReal = new NormalMatrix(diag2, upper, new double[N]);
        boolean[] fixed = new boolean[N]; // all free
        DirectSolver.CholeskyHandle handle = DirectSolver.factorize(aReal, fixed, OrderingMethod.AMD);
        if (handle.solver() == null) {
            uReal = new double[V];
            uImaginary = new double[V];
            return;
        }

        double[] mass2 = new double[N];
        for (int v = 0; v < V; v++) {
            mass2[v] = mass[v];
            mass2[V + v] = mass[v];
        }

        double[] u = new double[N];
        java.util.Random rng = new java.util.Random(12345L);
        for (int i = 0; i < N; i++) {
            u[i] = rng.nextDouble() * 2.0 - 1.0;
        }
        massNormalize(u, mass2);

        double[] rhs = new double[N];
        double[] x = new double[N];
        double[] start = new double[N];
        for (int it = 0; it < DEFAULT_POWER_ITERATIONS; it++) {
            for (int i = 0; i < N; i++) {
                rhs[i] = mass2[i] * u[i];
            }
            DirectSolver.solveCompact(handle, aReal, rhs, x, start, fixed);
            System.arraycopy(x, 0, u, 0, N);
            massNormalize(u, mass2);
        }

        uReal = new double[V];
        uImaginary = new double[V];
        for (int v = 0; v < V; v++) {
            uReal[v] = u[v];
            uImaginary[v] = u[V + v];
        }
    }

    private static void massNormalize(double[] u, double[] mass2) {
        double norm = 0.0;
        for (int i = 0; i < u.length; i++) {
            norm += mass2[i] * u[i] * u[i];
        }
        norm = Math.sqrt(Math.max(norm, EPS));
        for (int i = 0; i < u.length; i++) {
            u[i] /= norm;
        }
    }

    /**
     * World-space representative direction at vertex {@code v} (one of the n arms).
     */
    public Vector3f fieldDirection(int v) {
        double a = fieldAngle[v];
        Vector3f d = new Vector3f(vertexX[v]).mul((float) Math.cos(a));
        d.add(new Vector3f(vertexY[v]).mul((float) Math.sin(a)));
        return d.normalize();
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
     * @param pId the id of the first vertex
     * @param qId the id of the second vertex
     * @return the cotangent of the opposite angle of the face
     */
    public double cotOpposite(int faceId, int pId, int qId) {
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

    private static double cotangentAt(Vector3f apex, Vector3f endA, Vector3f endB) {
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

    private static void accum(Map<Long, Double> re, Map<Long, Double> im, int i, int j, double a, double b) {
        long k = (((long) i) << 32) | (j & 0xFFFFFFFFL);
        re.merge(k, a, Double::sum);
        im.merge(k, b, Double::sum);
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
                Vector3f d = fieldDirection(va); // one world-space arm
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
