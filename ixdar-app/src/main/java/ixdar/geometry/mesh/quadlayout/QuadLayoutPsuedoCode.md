# Lyon 2021 — Quad Layouts via Constrained T‑Mesh Quantization
## Complete Pseudocode (no library black-boxes)

This document gives the full pipeline of Lyon, Campen, Kobbelt 2021, with every
step that Lyon refers to but does not specify written out from the cited
primary sources. Where Lyon says "use [X]" we expand X.

The pipeline is:

1. **Cross field** on the input triangle mesh (BZK09, optionally with CIE16 directional constraints, or Diamanti DVP14 as drop-in alternative).
2. **Singularities** — read off the cross field's index per vertex.
3. **Seamless parametrization** with these singularities (BZK09), optionally post-processed for *exact* seamlessness (MC19).
4. **Modified motorcycle graph T-mesh** — Lyon's contribution: traces don't stop at the first crash; they continue until a stopping criterion based on user bound α is met.
5. **ILP for quantization** — Lyon's contribution: variables = arc lengths, constraints = consistency + validity + layout (deviation ≤ α).
6. **Make the layout explicit** — embed it as edge paths on the surface and (optionally) generate a quad mesh inside each patch via harmonic Coons-style mapping.

Throughout, F = faces, E = edges, V = vertices; for an oriented half-edge h we
write `next(h)`, `prev(h)`, `twin(h)`, `face(h)`, `from(h)`, `to(h)`.

```
========================================================================
TOP-LEVEL DRIVER
========================================================================

INPUT:
    M = (V, E, F)            triangle mesh, manifold, possibly w/ boundary
    α  ∈ (0, π/2)             max separatrix deviation (e.g. 5°…45°)
    feature_edges ⊂ E        optional sharp creases the user wants preserved

OUTPUT:
    L = quad layout (graph of nodes + arcs embedded on M)
    Q = (optional) quad mesh refining L

ALGORITHM Lyon2021(M, α, feature_edges):
    # --- 1. Cross field ----------------------------------------------------
    ξ ← BuildCrossField(M, feature_edges)       # § A below
    # --- 2. Singularities --------------------------------------------------
    S ← ExtractSingularities(M, ξ)              # § B
    # --- 3. Seamless parametrization --------------------------------------
    (uv, transitions, cuts) ← BuildSeamlessParam(M, ξ, S, feature_edges)  # § C
    (uv, transitions)       ← MakeExactlySeamless(uv, transitions, cuts)  # § D (MC19)
    # --- 4. Modified motorcycle graph (T-mesh) ----------------------------
    T  ← BuildModifiedMotorcycleGraph(M, uv, transitions, S, α,
                                       feature_edges)                     # § E
    # T = (N nodes, A arcs, P patches), each arc has parametric length and
    # axis (u or v). Each trace is recorded with its origin singularity and
    # the ordered list of arcs along it.

    # --- 5. ILP for quantization ------------------------------------------
    q ← SolveQuantizationILP(T, α)               # § F  (one int per arc)

    # --- 6. Layout extraction ---------------------------------------------
    L ← ExtractLayout(M, uv, transitions, T, q)  # § G
    # L is the explicit conforming quad layout (nodes = singularities,
    # arcs = embedded paths on M).

    Q ← (optional) FillPatchesWithQuads(M, uv, L, q)   # § H

    return (L, Q)
```

──────────────────────────────────────────────────────────────────────
§ A.  CROSS FIELD GENERATION  (Bommes-Zimmer-Kobbelt 2009 — BZK09)
──────────────────────────────────────────────────────────────────────

A cross field is one rotation in [0, π/2) per face of M, identifying directions
modulo π/2. Following BZK09 §4 it is parametrized as:
    θ : F → ℝ        per-face angle w.r.t. a local edge frame
    p : E → ℤ        per-edge "period jump" (which of 4 vectors matches across e)
We minimise

    E_smooth = Σ_{e_ij ∈ E}  ( θ_i  +  κ_ij  +  (π/2)·p_ij  −  θ_j )²

where κ_ij is the angle between the local frames of i and j, and where
constrained faces (curvature features, sharp creases, boundary triangles)
have θ_i fixed to θ̂_i.

```
ALGORITHM BuildCrossField(M, feature_edges):
    # ---- A1. directional constraints ---------------------------------
    F_c ← {}                                       # constrained faces
    θ̂  ← {}
    # principal curvature constraints (BZK09 §3, "salient curvature directions")
    for each vertex v ∈ V:
        for r in [r_0 .. r_1] geometrically (e.g. h/4 .. 4h):
            S_r ← ShapeOperator(geodesicDisk(v, r))
            τ   ← (|κ_max| − |κ_min|) / |κ_max|       # anisotropy
            if τ > τ_min  and  meanCurvature(v, r) > K:
                mark S_r valid for v
        if v has a valid S_r with smallest direction-jitter:
            for each face f incident to v:
                F_c ← F_c ∪ {f}
                θ̂_f ← angle of κ_max in f's local frame

    # alignment with feature edges (CIE16-style for arbitrary curves on
    # a curved surface, or BZK09 §5.2 for sharp creases). Both agree on:
    # "align cross to the edge in both adjacent faces".
    for each edge e = (a,b) ∈ feature_edges:
        for each f ∈ {face(e), twin(face(e))}:
            F_c ← F_c ∪ {f}
            θ̂_f ← angle of edge_dir(e) in f's local frame
    # likewise for boundary edges of M.

    # ---- A2. fix one period-jump per free face (BZK09 §4.2) ---------
    # Construct a forest of dual Dijkstra trees rooted at each f ∈ F_c,
    # whose leaves cover all free faces. For every dual edge in this forest
    # FIX p_e := 0. (Voronoi-style assignment minimises distance from each
    # face to its nearest constrained face, so the greedy MIP rounding is
    # accurate.)

    fixed_edges ← BuildVoronoiSpanningForest(M_dual, F_c)
    # Edges between two constrained faces with directions θ̂_i, θ̂_j fix
    #   p_ij := round( (2/π)·(θ̂_j − θ̂_i − κ_ij) )

    # ---- A3. Mixed-Integer optimisation (BZK09 §2 greedy rounding) ---
    # variables: real θ_f ∀ f ∉ F_c   ;   integer p_e ∀ e ∉ fixed_edges
    # objective: E_smooth, quadratic in (θ, p).
    A x = b   ←  ∂E_smooth/∂(θ,p) = 0   (assemble a sparse linear system)

    REPEAT until all p variables are integer:
        x_real ← solve continuous L2 system on remaining free vars
        choose the integer variable x_k whose round() is smallest in
            |x_k − round(x_k)| / σ_k   (least round-off)
        fix x_k := round(x_k)
        re-solve with the new constraint    (Cholesky update)

    θ ← real solution
    p ← integer solution
    return ξ = (θ, p)
```

(Optional alternative: Diamanti et al. 2014 "Designing N-PolyVector Fields with
Complex Polynomials" replaces the angle-and-period-jump with a complex
polynomial of degree 4 per face and avoids integer optimisation entirely.
For a cross field (N=4, all four vectors symmetric → reduces to N-RoSy),
the polynomial on face f is P_f(z) = z⁴ − (u_f)⁴, and LC-parallel transport
across edge e between faces f, g is:
    (u_g · ē_g)⁴  =  (u_f · ē_f)⁴
The smooth field minimises Σ_e | (u_g·ē_g)⁴ − (u_f·ē_f)⁴ |² , a sparse complex
linear system. After solve, pick u_f as a 4th root of (u_f)⁴.)

──────────────────────────────────────────────────────────────────────
§ B.  SINGULARITIES FROM THE CROSS FIELD
──────────────────────────────────────────────────────────────────────

```
ALGORITHM ExtractSingularities(M, ξ=(θ,p)):
    S ← []
    for each vertex v ∈ V_interior:
        # base index: discrete Gaussian curvature contribution (BZK09 eq. for I_0)
        I_0 ← (1/(2π)) · ( angleDefect(v) + Σ_{e ∈ N(v)} κ_e )
        # period-jump contribution
        I_p ← Σ_{e ∈ N(v)} p_e / 4               # signed walk around v
        I(v) ← I_0 + I_p                          # ∈ {…, -2/4, -1/4, 0, 1/4, 2/4, …}
        if I(v) ≠ 0:
            S.append( (v, I(v)) )                 # +1/4 → valence 3, -1/4 → valence 5
    return S
```

──────────────────────────────────────────────────────────────────────
§ C.  SEAMLESS PARAMETRIZATION  (BZK09 §5)
──────────────────────────────────────────────────────────────────────

We compute uv : V_corners → ℝ² (one (u,v) per face-corner) such that

    ∇u_T ≈ u_T(ξ) ,    ∇v_T ≈ v_T(ξ)            inside each triangle T

with `u_T(ξ), v_T(ξ)` the two orthogonal cross-field vectors of face T.
Across a cut edge e between charts τ_f, τ_g the parametrization must satisfy

    (u', v') = R_{90}^{r_e} · (u, v) + (s_e, t_e)        seamless transition

with rotation r_e ∈ {0,1,2,3} and translation (s_e, t_e). For a *seamless* map
we leave (s_e, t_e) ∈ ℝ². For an *integer-grid* (= quantized) map we additionally
round (s_e, t_e) ∈ ℤ² and force singularities to integer (u,v).
The two are linked: Lyon needs only a *seamless* one to start; quantization
happens later via the ILP.

```
ALGORITHM BuildSeamlessParam(M, ξ, S, feature_edges):
    # ---- C1. cut to disk topology, every singularity on the cut ----
    cuts ← {}                              # set of edges
    DST  ← DualSpanningTree(M)
    cuts ← E \ DST                         # primal of non-DST = a cut graph
    cuts ← Trim(cuts)                      # remove dangling open paths
    for each singularity s in S:
        if s ∉ vertices(cuts):
            cuts ← cuts ∪ DijkstraPath(s, cuts)   # connect it
    M_open ← cut M along `cuts`            # produces disk-topology mesh

    # ---- C2. propagate cross orientation through the cut faces -----
    # Pick any face f0; orient its cross arbitrarily as (u_0, v_0).
    # BFS to all neighbours: across a non-cut edge the orientation is identical
    # (interior of M_open is regular — no singularities inside).
    # Across a cut edge, compare the local cross orientations: the rotational
    # part r_e ∈ {0,1,2,3} of the transition is the # of π/2 turns needed.
    for each cut edge e: r_e ← 1 + ((θ_g − θ_f)/(π/2)) rounded mod 4

    # ---- C3. orientation energy (BZK09 §5) ------------------------
    # Variables:
    #   per (face, corner) → (u, v) ∈ ℝ²
    #   per cut edge e → translation (s_e, t_e) ∈ ℝ²    (real for "seamless")
    # Energy:
    E_orient = Σ_T  area(T) · ( ‖ h·∇u_T − u_T(ξ) ‖² + ‖ h·∇v_T − v_T(ξ) ‖² )
    # h is a global scale (target edge length); we don't actually need it
    # tight – the ILP later handles real lengths.
    # Subject to seamless boundary conditions across each cut edge e:
    #   (u_p', v_p') = R_{90}^{r_e}(u_p, v_p) + (s_e, t_e)         endpoint p
    #   (u_q', v_q') = R_{90}^{r_e}(u_q, v_q) + (s_e, t_e)         endpoint q
    # which eliminate 4 corner DOFs and add 2 translation DOFs.
    # For singularities-on-cut: pin one chosen incident corner per singularity
    # to its computed angle defect to keep the system well-posed.
    # Optional anisotropic norm or local stiffening for fold-over avoidance.

    # ---- C4. solve linear least squares ---------------------------
    solve sparse symmetric system  ∇E_orient = 0
    if any T has det(J_T) ≤ 0:
        apply local stiffening (BZK09 §5.4): w(T) ← w(T)/σ_2(T)
        re-solve until all dets > 0.

    return (uv, transitions={r_e, s_e, t_e}, cuts)
```

──────────────────────────────────────────────────────────────────────
§ D.  EXACT SEAMLESSNESS  (Mandad-Campen 2019 — MC19)
──────────────────────────────────────────────────────────────────────

The previous step gives a parametrization that is seamless *up to floating-
point error*. Lyon's tracing in §E uses exact predicates, so we must drive
that error to zero. MC19 takes the approximate seamless solution x̄ and
finds a nearby x ∈ ℝ^n that *exactly* satisfies all linear seamless
constraints C·x = b, while remaining representable in floating-point.

```
ALGORITHM MakeExactlySeamless(uv, transitions, cuts):
    # Assemble the linear system:
    #   for each cut edge e, two endpoints p, q, 4 equations:
    #     (u_p', v_p') − R_{90}^{r_e}·(u_p, v_p) − (s_e, t_e) = 0
    # → matrix C with entries in {-1, 0, 1} and integer-rotation-induced sign
    #   permutations only; right-hand side b = 0.
    #
    # MC19 step 1: reduce C to a row-echelon form using exact rational
    #              elimination ("safe floating-point GE": pivot rows so
    #              every non-zero entry stays representable exactly).
    # MC19 step 2: project x̄ onto null(C) using two passes:
    #              (a) compute residual r = C·x̄  in extended precision,
    #              (b) solve a least-norm correction Δx exactly so that
    #                  C·(x̄ + Δx) = 0 exactly in IEEE-754 doubles.
    # MC19 step 3: round-trip verify: re-evaluate C·x in double precision —
    #              every entry must equal exactly 0 (otherwise add another
    #              correction round).
    return (uv', transitions')
```

──────────────────────────────────────────────────────────────────────
§ E.  MODIFIED MOTORCYCLE GRAPH T-MESH  (Lyon §3, building on Eppstein et al.
                                        2008 + CBK15 §5.1 + QEx tracing)
──────────────────────────────────────────────────────────────────────

This is the single biggest place where Lyon differs from prior work, so
expanded carefully.

Definitions (Lyon §3):
    • A *trace* t_i is an iso-line in the seamless parametrization, starting at
      singularity i in one of its 4 (or v-adjusted) outgoing parametric directions.
    • Trace t_i moves along a parametric u or v direction — exactly straight lines
      in the parameter domain — so on the surface it is a piecewise-linear path
      of segments on triangles, computed with QEx-style robust line tracing.
    • Two traces t_i, t_j may intersect at a point n_ij ∈ N (T-mesh node).
      Let l_ij = signed parametric arc length from start of t_i to n_ij; α_ij =
      signed angle at start of t_i in the right triangle whose legs are
      (l_ij along t_i) and (l_ji along t_j).
    • S_ij = the set of arcs of trace t_i strictly between i and n_ij.
    • n_i* = the FIRST intersection along t_i where the colliding trace
      starts inside the π/2-sector around t_i (i.e. l_i* > l_*i). In
      classical Eppstein 2008, the trace would stop here. **Lyon does NOT
      stop here.**

Lyon's stopping criterion (Lyon §3, paraphrased):
    A trace t_i continues until it has hit two other traces t_k and t_l such that

        α_ik ∈ [0, α]    and    α_il ∈ [-α, 0]                          (*)

    i.e. it has met one trace on each side whose triangle to t_i lies inside
    the half-angle-α cone around t_i. At that moment the trace stops.

Why this works (Lyon §4.3): with constraint (*) plus the ILP layout
constraints, every separatrix in the resulting layout is provably within α
of its parametric direction.

```
ALGORITHM BuildModifiedMotorcycleGraph(M, uv, transitions, S, α, feature_edges):
    # ---- E1. spawn traces ----------------------------------------------
    traces  ← []
    # For each interior singularity, spawn one trace per outgoing parametric
    # half-direction. A valence-3 vertex has 3 outgoing directions; valence-5
    # has 5. The directions are obtained by walking around the vertex
    # in the parametrization, finding angles that are integer multiples of π/2
    # in the parameter domain (the "ports" of QEx §4 / RRP15 §3.2).
    for each singularity s in S:
        for each parametric port p at s:
            traces.append( Trace(origin=s, dir=p, alive=true) )

    # also feature-line traces (Lyon §4.4): an arc on a feature curve is
    # treated as a trace that other traces should stop against, but with α=0.
    for each feature edge chain:
        traces.append( FeatureTrace(...) )                 # immortal; α=0

    # ---- E2. event-driven simulation ----------------------------------
    # Use exact predicates (QEx Algorithm 1: sanitize uv first by truncating
    # mantissa bits inconsistent across triangle fans of singularities).
    # We march each alive trace one parametric unit at a time *or* event-by-
    # event using a priority queue keyed on parametric distance from origin.
    PQ ← priority_queue of (parametric_distance, event_type, trace, target)

    for each trace t in traces:
        next_event ← AdvanceUntilFirstEdgeCrossing(t, uv)
        PQ.push( next_event )

    while PQ not empty:
        e ← PQ.pop()
        t ← e.trace

        if t.alive == false: continue

        if e is a triangle-edge crossing  (no other trace here):
            # straight-line into next triangle, taking transition into account
            ContinueIntoNextTriangle(t, uv, transitions)
            PQ.push( AdvanceUntilFirstEdgeCrossing(t, uv) )

        elif e is a "trace t hits another trace t'" (cross-junction in uv):
            # split both traces into arcs at the intersection node n
            n ← CreateNode(at e.point)
            SplitTraceArc(t,  n)
            SplitTraceArc(t', n)
            # (unlike Eppstein 2008, NEITHER trace dies here in general)

            # — CHECK LYON STOPPING CRITERION on t —
            UpdateMet[t] ← UpdateMet[t] ∪ {(t', signed_angle(t, t'))}
            (k, l) ← look in UpdateMet[t] for a pair with α_ik ∈ [0,α], α_il ∈ [-α,0]
            if such (k, l) exists:
                t.alive ← false                          # stop t
            else:
                ContinueIntoNextTriangle(t, uv, transitions)
                PQ.push( AdvanceUntilFirstEdgeCrossing(t, uv) )
            # — same check on t' —
            UpdateMet[t'] ← UpdateMet[t'] ∪ {(t, signed_angle(t', t))}
            apply same test for t'

        elif e is a "trace t reaches a singularity":
            # rare but allowed; both traces in the same iso-direction, terminates
            t.alive ← false
            CreateNode(at e.point)

        elif e is a "trace t reaches a boundary":
            t.alive ← false
            CreateNode(at e.point)
            # if alignment-with-boundary mode, treat boundary as feature trace

    # ---- E3. assemble the T-mesh data structure -----------------------
    N ← all created nodes (singularities + intersections + endpoints)
    A ← list of arcs:  for each trace, the consecutive (node, node) segments
        between trace events. Each arc records:
            arc.parametric_length  l_a   (real, from uv)
            arc.axis               'u' or 'v'
            arc.trace_index
            arc.opposite_arcs_in_each_incident_patch
    P ← faces of the planar subdivision (in the cut chart): walk around each
        T-mesh face and record its 4 (or more, with T-junctions) bounding arcs.
        Crucially, every patch is rectangular in (u,v): its sides come in
        two opposing groups, S = u-sides, S^o = opposite u-sides; likewise v.

    # Optional Lyon §4.4: if a singularity is in some patch but not on its
    # boundary because of how traces stopped, no special handling is needed —
    # the layout constraints (§F) will force separation.

    return T = (N, A, P, traces)
```

Implementation notes for the trace-segment robustness (Lyon delegates to QEx):
    • Sanitize uv first (QEx Algo 1): for each vertex p, take all images
      f(p) in incident triangles, and *truncate* mantissa bits so that all
      images agree exactly. After this all `Orient2D(a,b,c)` predicates are
      exact in double precision.
    • Pick-Next-Edge (QEx Algo 5 line 13): given current triangle t' and
      segment (a,b], take the edge of t' (other than the incoming edge) that
      intersects (a,b]. Tie-break: the one with fewer incident vertices on
      the segment. In case of tie, either works.
    • Across a cut edge, compose the transition into the running rotation g
      and (when crossing changes orientation) flip the tracing direction.

──────────────────────────────────────────────────────────────────────
§ F.  ILP FOR QUANTIZATION  (Lyon §4–§5)
──────────────────────────────────────────────────────────────────────

**Variables**: q_a ∈ ℤ_≥0  for every arc a ∈ A.

**Constraints** (Lyon §4.1, §4.2, §4.3):

  (1) Non-negativity:
        q_a ≥ 0                                    ∀ a ∈ A

  (2) Consistency (Lyon eq. (2)):
        Σ_{a ∈ S}  q_a   −   Σ_{b ∈ S^o}  q_b  =  0
            for each pair (S, S^o) of opposite sides of every patch p ∈ P.

  (3) Validity (Lyon eq. (3), one per trace, Lyon §4.2 Lemma 1):
        Σ_{a ∈ S_{i*}}  q_a  ≥  1           ∀ trace t_i
            where S_{i*} is the arc set from start of t_i up to its first
            "from-the-π/2-sector" collision n_{i*}.

  (4) Layout constraints (Lyon eq. (4)) — for every intersection node n_ij of
      two traces t_i, t_j with l_ij ≥ l_ji forming a triangle whose corner
      angle |α_ij| > α at start of t_i:
        Σ_{a ∈ S_ji}  q_a  ≥  1

      (these prevent zero-quantization paths whose geometry would imply
       a separatrix outside the α-bound)

**Objective** (Lyon eq. (5)):

    minimise   E = Σ_{a ∈ A}  l_a^⊥  ·  q_a

  where l_a^⊥ is half the parametric distance between the two arcs opposite
  to a in the patches it bounds (or half the distance to the single opposite
  arc if a is on the boundary). Geometrically Σ_a l_a^⊥·q_a is the total
  parametric length of all quad strips in the implied quad mesh — minimising
  it gives the coarsest layout.

```
ALGORITHM SolveQuantizationILP(T, α):
    (vars, cons, obj) ← BuildBasicILP(T, α)        # straightforward translation

    # ---- Lyon §5.2 size reduction --------------------------------------
    # Lyon proves that with reduction the # of variables, # of consistency
    # constraints, # of validity constraints, and # of layout constraints are
    # ALL O(n_traces).
    #
    # Reduction (a) — strip-merging:
    # Group consecutive parallel arcs running across a single quad strip
    # (a strip starts and ends at T-junctions or boundary). All arcs in
    # one strip are forced equal by consistency, so collapse them to ONE
    # variable — consistency becomes trivial except across T-junctions.
    strips ← ComputeStrips(T)
    for each strip s: replace all arcs a ∈ s by one variable q_s

    # Reduction (b) — minimal-set layout constraints:
    # For trace t_i, the validity set S_{i*} and all layout sets {S_{ij}}
    # form a chain   S_0 ⊂ S_1 ⊂ ... ⊂ S_k.
    # If q_{a_0} ∈ S_0 ≥ 1, all supersets are automatically ≥ 1.
    # → only emit the SMALLEST set's constraint per trace.
    for each trace t_i:
        smallest ← argmin_{S ∈ {S_{i*}, S_{ij}, ...}} |S|
        emit  Σ_{a ∈ smallest}  q_a ≥ 1

    # ---- Solve --------------------------------------------------------
    # The ILP is feasible: q_a := 1 ∀ a satisfies (1)…(4). (Lyon §5.1)
    # Use a branch-and-bound ILP solver (Lyon used Gurobi; SCIP / CBC also work).
    q* ← solve  min { Σ l⊥_a·q_a : Aq ≥ b, q ∈ ℤ_≥0 }

    # If a strip-merged variable q_s was solved, propagate q_s back to all
    # arcs in the strip.
    return q*
```

Note: a quantization equal to 0 on an arc means "this arc has zero parametric
length in the quantized parametrization", i.e. the two endpoints will be
identified. Three or more singularities on the same iso-line connected by
zero-length arcs ⇒ they all become layout-connected by a separatrix — which is
exactly how the layout connectivity emerges from the ILP.

──────────────────────────────────────────────────────────────────────
§ G.  LAYOUT EXTRACTION  (Lyon §6, picking the simplest of the three
                          options he names)
──────────────────────────────────────────────────────────────────────

Lyon describes three ways to materialise the layout. We give the one he
actually used (LCBK19 re-parametrization), and the fallback path-search /
RRP15 retracing variant which is fully self-contained.

Option G1 (preferred — LCBK19 §6 re-embedding into the triangle mesh):

```
ALGORITHM ExtractLayout_via_Reembedding(M, uv, T, q):
    # Step 1: Integrate T into M as actual edge paths (so every arc of T
    #         is a chain of triangle-mesh edges).
    M' ← M
    for each arc a in T (with parametric length l_a):
        path ← TraceParametricLine(M, uv, a.start_uv, a.end_uv)
        M'  ← SplitTrianglesAlong(M', path)   # may insert Steiner vertices

    # Step 2: Collapse zero-quantized arcs.
    for each arc a with q_a == 0:
        EdgeCollapsePath(M', path_of(a))

    # Step 3 (Lyon's added detail §6, page 2 of the section):
    # Iteratively extend every remaining T-junction to the OPPOSITE side of
    # its patch. If the quantization on each side of the to-be-split patch
    # already matches at a node, connect the two T-junctions; otherwise
    # split the opposing arc at the matching parametric point.
    repeat:
        any_change ← false
        for each T-junction j in current T-mesh:
            (s_match, point_on_opposite) ← FindOppositeMatch(j, q)
            if s_match exists:
                ConnectAcross(j, s_match)
            else:
                ai_op ← OppositeArc(j)
                p     ← parametricPoint along ai_op matching j's quantum
                SplitArc(ai_op, p); ConnectAcross(j, p)
            any_change ← true
        if not any_change: break

    # Now every layout patch is rectangular and conforming.
    L ← (nodes_of_T_after_collapsesAndExtensions, arcs_after, patches_after)
    return L
```

Option G2 (fully self-contained — RRP15 §6.1 retracing in the original
parametrization):

```
ALGORITHM ExtractLayout_via_Retracing(M, uv, T, q):
    L_nodes ← S                  # singularities = layout nodes
    L_arcs  ← []
    for each trace t_i originating at singularity i:
        # find the closest singularity j along t_i's iso-line that is
        # NOT separated from i in either coordinate by the quantization q
        s_j ← FindFirstUnseparated(t_i, q)
        # retrace the iso-line in the parametrization from i to s_j as an
        # explicit path of triangle-edge segments (using QEx tracing — exact)
        path ← QExTrace(M, uv, transitions, i, s_j, t_i.dir)
        L_arcs.append( (i, s_j, path) )
    return (L_nodes, L_arcs)
```

──────────────────────────────────────────────────────────────────────
§ H.  FILLING PATCHES WITH A REGULAR QUAD GRID  (Lyon §6 closing paragraph,
            citing LCBK19 §6.2 — harmonic optimised parametrization)
──────────────────────────────────────────────────────────────────────

Once L is conforming, each patch p has 4 macro-sides. Decide a quad-grid size
(n_u × n_v) for p compatible with neighbours by reading off q on its sides.
Then map a regular n_u×n_v grid to p:

```
ALGORITHM FillOnePatchWithGrid(p, n_u, n_v):
    # Step 1: parametrize p over the rectangle [0, n_u] × [0, n_v]
    # by solving a harmonic Dirichlet problem on its triangulation:
    #   minimise   ∫_p  ‖∇φ_u‖² dA          subject to
    #     φ_u prescribed on the 4 macro-sides such that opposite sides
    #     are linear interpolations between corners (Coons-style boundary).
    # Use cotangent Laplacian on the patch's triangle subset:
    #     L · φ_u = 0      (interior)
    #     φ_u    = boundary values
    # Likewise for φ_v.

    # Step 2: nudge boundary if needed (Nielson 1999 'Coons cracking' fix):
    # at each macro-corner, the four sides must agree on the corner value;
    # blend with a Coons/triangular-Coons interpolant if there is a small
    # discontinuity from the ILP rounding.
    BoundaryFix_Coons(p)

    # Step 3: extract the (n_u−1)×(n_v−1) interior grid points by
    # locating preimages of integer (i, j) in p (Newton iteration, or
    # marching from already-found points).
    Q_p ← ExtractGrid(p, φ_u, φ_v, n_u, n_v)
    return Q_p
```

Final quad mesh Q is the union of all Q_p, stitched along their shared
macro-edges.

──────────────────────────────────────────────────────────────────────
ESTIMATED TIME COMPLEXITY (matches Lyon's reported numbers)
──────────────────────────────────────────────────────────────────────

  Cross field             O(|F|) sparse linear + greedy rounding ≈ O(|F| log |F|)
  Singularities           O(|V|)
  Seamless param          O(|F|) sparse linear
  Exact seamlessness      O(#cuts)
  T-mesh tracing          O(#traces · avg trace length) ≈ O(|A|)
  ILP (after reduction)   variables O(n_traces); typical < 1 s w/ Gurobi
  Layout extraction       O(|A| + |F|)
  Quad-grid fill          O(Σ_p |triangles in p|) per patch

══════════════════════════════════════════════════════════════════════
═
═                          APPENDIX OF HELPERS
═
═  Every helper used above, expanded so nothing is left to "library magic".
═
══════════════════════════════════════════════════════════════════════


──────────────────────────────────────────────────────────────────────
H.A1.  ShapeOperator(geodesicDisk(v, r))     (used in §A1)
──────────────────────────────────────────────────────────────────────

Cohen-Steiner / Morvan curvature tensor integrated over a geodesic disk
of radius r around vertex v. We use the **edge-based** form of Alliez et
al. 2003 §2.1 (which is a discretisation of Cohen-Steiner & Morvan):
each mesh edge contributes a rank-1 tensor whose magnitude is the signed
dihedral angle and whose direction is the edge.

```
FUNCTION GeodesicDisk(v, r):
    # Approximate: Dijkstra over mesh edges from v with cumulative edge-length
    # weights; collect every triangle whose all three vertices have distance ≤ r.
    visited   ← {}
    Q         ← min-heap of (distance, vertex), starting with (0, v)
    dist[v]   ← 0
    while Q non-empty:
        (d, u) ← pop(Q)
        if u in visited: continue
        visited.add(u)
        if d > r: break
        for each neighbour w of u:
            nd ← d + ‖position(w) − position(u)‖
            if nd < dist.get(w, ∞):
                dist[w] ← nd
                push(Q, (nd, w))
    B ← { triangle t ∈ F : every vertex of t has dist ≤ r }
    return B                       # set of triangles forming the disk

FUNCTION ShapeOperator(B):
    # Return a 2x2 symmetric tensor T(v) in the tangent plane at v (the
    # tangent plane is the average of incident-face normals, with two
    # arbitrary orthogonal basis vectors e1, e2 in it).
    n_v ← areaWeightedNormal(v)
    (e1, e2) ← anyOrthonormalFrame(n_v)
    T ← 0     # 2x2 zero matrix

    A ← Σ_{t ∈ B} area(t)                    # |B| in eq. (1) of Alliez03
    for each edge e in B with both endpoints inside the disk:
        # signed dihedral angle β(e):  + convex, − concave
        n_left  ← normal( leftFace(e) )
        n_right ← normal( rightFace(e) )
        cosβ ← clamp( dot(n_left, n_right), -1, 1)
        sinβ ← dot( cross(n_left, n_right), edgeDir(e) )
        β    ← atan2(sinβ, cosβ)
        # length of e clipped to disk
        Le ← min(‖e‖, r - distFromVToMidpoint(e)) ; Le ← max(Le, 0)
        # project edge direction into tangent plane
        ê    ← normalize( edgeDir(e) )
        ê2   ← (dot(ê, e1), dot(ê, e2))      # 2-vector
        T   += (β · Le) · outer(ê2, ê2)
    T ← (1 / A) · T                          # eq. (1) of Alliez03

    # Eigendecomposition of 2x2 symmetric T:
    (κ_max, κ_min, dir_max, dir_min) ← eigSym2x2(T)
    return (κ_max, κ_min, dir_max, dir_min)
```

The "validity" test in §A1 is then:
    valid(v, r)  ⇔   all r' ∈ [r-w, r+w] give consistent (within angle ε)
                     directions  AND   anisotropy τ > τ_min  AND   |H| > K
where H = (κ_max + κ_min)/2 is the mean curvature.


──────────────────────────────────────────────────────────────────────
H.A2.  BuildVoronoiSpanningForest(M_dual, F_c)         (used in §A2)
──────────────────────────────────────────────────────────────────────

Given the dual graph of M (one node per face, one dual edge per primal
edge with weight = length of the corresponding primal edge), and a set
of constrained faces F_c, build a forest F (i.e. an acyclic set of dual
edges) such that:
  (i)  every dual face f ∈ F is reachable from some root in F_c via F,
  (ii) every dual tree's faces lie within the discrete Voronoi cell of
       its root  (i.e. its root is the *closest* root in F_c).

This is just a multi-source Dijkstra:

```
FUNCTION BuildVoronoiSpanningForest(M_dual, F_c):
    dist   ← map default ∞
    parent ← map default NIL
    PQ     ← min-heap
    for each f ∈ F_c:
        dist[f] ← 0
        push(PQ, (0, f))            # 0-distance entries seed each cell

    while PQ non-empty:
        (d, f) ← pop(PQ)
        if d > dist[f]: continue      # stale entry
        for each dual edge (f, g) with weight w_e:
            if dist[f] + w_e < dist[g]:
                dist[g]   ← dist[f] + w_e
                parent[g] ← (f, e)
                push(PQ, (dist[g], g))

    fixed_edges ← { e : g ∈ F\F_c, parent[g] = (f, e) }
    return fixed_edges
```

This returns one dual edge per non-constrained face, so |fixed_edges| =
|F \ F_c|, exactly as BZK09 prescribes. Each face `g` reaches its
nearest constrained face `f` along a path of these edges, and no path
ever connects two distinct constrained faces (each cell is rooted at
exactly one), so no dual loop is formed and no path crosses a Voronoi
boundary.


──────────────────────────────────────────────────────────────────────
H.C1.  DualSpanningTree(M)                              (used in §C1)
──────────────────────────────────────────────────────────────────────

Any spanning tree of the dual graph of M is fine. The simplest is BFS:

```
FUNCTION DualSpanningTree(M):
    f0   ← any face of M
    tree ← {}                       # set of dual edges
    seen ← {f0}
    Q    ← deque([f0])
    while Q non-empty:
        f ← pop_front(Q)
        for each dual edge (f, g) (i.e. primal edge e = f ∩ g):
            if g ∉ seen:
                tree.add(e)
                seen.add(g)
                push_back(Q, g)
    return tree
```

The mesh cut along E \ tree is then a topological disk. (Standard fact:
a spanning tree of the dual + the primal complement form a CW
decomposition of M into one disk.)


──────────────────────────────────────────────────────────────────────
H.C2.  Trim(cuts)                                        (used in §C1)
──────────────────────────────────────────────────────────────────────

After cutting along E \ DST, the cut graph may contain "open ends":
edges that dangle without making the cut shorter. Trim removes them.

```
FUNCTION Trim(cuts):
    # cuts is a set of edges. We treat it as a graph G_cut on V.
    # Repeatedly delete every degree-1 vertex (and its incident edge).
    deg ← per-vertex degree in G_cut
    Q ← deque of all v with deg[v] == 1
    while Q non-empty:
        v ← pop_front(Q)
        if deg[v] != 1: continue
        e ← the single edge of G_cut incident to v
        u ← other endpoint of e
        cuts.remove(e)
        deg[v] ← 0
        deg[u] ← deg[u] - 1
        if deg[u] == 1: push_back(Q, u)
    return cuts
```

A vertex never becomes "degree 1" if it's a singularity that we will
later need to attach (we add singularity-paths AFTER Trim). All
remaining edges are part of cycles that genuinely contribute to making
the surface into a topological disk.


──────────────────────────────────────────────────────────────────────
H.C3.  DijkstraPath(s, cuts)                             (used in §C1)
──────────────────────────────────────────────────────────────────────

Shortest path from singularity s to the nearest vertex of `cuts`,
along mesh edges weighted by their Euclidean length:

```
FUNCTION DijkstraPath(s, cuts):
    targetSet ← { v : v is endpoint of some edge in cuts }
    dist[s]   ← 0
    parent[s] ← NIL
    PQ ← min-heap with (0, s)
    while PQ non-empty:
        (d, u) ← pop(PQ)
        if u ∈ targetSet:
            # reconstruct path of edges from s to u
            path ← []
            while parent[u] ≠ NIL:
                path.prepend( edge(parent[u], u) )
                u ← parent[u]
            return path
        if d > dist[u]: continue
        for each neighbour w of u:
            w_e ← ‖position(w) − position(u)‖
            if d + w_e < dist.get(w, ∞):
                dist[w] ← d + w_e
                parent[w] ← u
                push(PQ, (d + w_e, w))
    return []                 # only reachable if s is already in cuts
```


──────────────────────────────────────────────────────────────────────
H.D.  Section D — full algorithm (replaces the comment block)
──────────────────────────────────────────────────────────────────────

This is MC19 in concrete form (Algorithms 1–5 of the paper).

```
FUNCTION MakeExactlySeamless(uv, transitions, cuts):
    # ----- D.1 Assemble integer constraint system -------------------
    # Each cut edge e = (p, q) with rotation r_e ∈ {0,1,2,3} contributes
    # 4 scalar equations relating the corner-uv variables on either side
    # to the (s_e, t_e) translation variables:
    #
    #   u_p' − cos(r_e·π/2)·u_p + sin(r_e·π/2)·v_p − s_e = 0
    #   v_p' − sin(r_e·π/2)·u_p − cos(r_e·π/2)·v_p − t_e = 0
    #   (and same for endpoint q)
    #
    # All coefficients are in {-1, 0, 1}, all rhs are 0 (so b = 0).
    # Variable vector x:  all corner u's, all corner v's, all (s_e, t_e).
    (C, b) ← assembleConstraintMatrix(uv, transitions, cuts)
    x̄     ← packCurrentSolution(uv, transitions)              # ∈ ℝ^n

    # ----- D.2 Fraction-free integer row echelon (Algo 1 of MC19) ---
    # Adapted from Turner 1995 & Durvye 2012. Operates entirely in ℤ.
    Ĉ ← C                       # we'll edit in place
    b̂ ← b
    pivotRow  ← {}                # column → row index
    r ← 0                          # current "next pivot row" cursor
    for k = 0 .. n-1:
        # find a row at index ≥ r with non-zero in column k
        rowSrc ← argmin_{i ≥ r, Ĉ[i,k] ≠ 0}  (i)
        if rowSrc is NIL: continue
        swapRows(Ĉ, b̂, r, rowSrc)
        pivotRow[k] ← r
        # eliminate non-zeros in column k below row r
        for i = r+1 .. m-1:
            if Ĉ[i, k] == 0: continue
            # Bareiss-style fraction-free row reduction:
            #   Ĉ[i, :] ← (Ĉ[r, k]·Ĉ[i, :] − Ĉ[i, k]·Ĉ[r, :]) / prevPivot
            # where prevPivot = 1 the first time, then the previous pivot.
            for j = k .. n-1:
                Ĉ[i, j] ← (Ĉ[r, k]·Ĉ[i, j] − Ĉ[i, k]·Ĉ[r, j]) / prevPivot
            b̂[i]    ← (Ĉ[r, k]·b̂[i]    − Ĉ[i, k]·b̂[r])    / prevPivot
            # gcd-divide row i to keep magnitudes small
            g ← gcd(|Ĉ[i, k:]|, |b̂[i]|)
            if g > 1:
                Ĉ[i, :] /= g
                b̂[i]    /= g
        prevPivot ← Ĉ[r, k]
        r += 1

    # ----- D.3 Reduced row echelon (Algo 2 of MC19) -----------------
    # Cancel non-zeros ABOVE each pivot, again fraction-free.
    for col k that has pivot at row j = pivotRow[k]:
        for i = 0 .. j-1:
            if Ĉ[i, k] == 0: continue
            for jj = k .. n-1:
                Ĉ[i, jj] ← Ĉ[j, k]·Ĉ[i, jj] − Ĉ[i, k]·Ĉ[j, jj]
            b̂[i]      ← Ĉ[j, k]·b̂[i]      − Ĉ[i, k]·b̂[j]
            g ← gcd(|Ĉ[i, :]|, |b̂[i]|)
            if g > 1:
                Ĉ[i, :] /= g
                b̂[i]    /= g

    # ----- D.4 Choose the magnitude bound d (Algo 3 of MC19) --------
    K ← max_i ⌈ log2(|x̄_i| + 1) ⌉ + 1
    d ← 2^K              # only floats with exponent ≤ K and ≥ K - mantissaBits zero are "Fd"

    # Helper: round a float to Fd (zero out bits below 2^(K - mantissaBits))
    FUNCTION truncToFd(v):
        sv ← sign(v)
        return (v + sv·d) − sv·d        # IEEE-754 trick (Ebke 2013)

    FUNCTION makeDiv(x, D):                       # Algo 4 of MC19
        if D is empty: return truncToFd(x)
        ℓ ← lcm of integers in D
        return truncToFd(x / ℓ) · ℓ              # divisible by every element of D

    FUNCTION safeDot(pairs):                       # Algo 5: error-free Σ a_i·b_i
        # All b_i are in Fd / Ĉ_jp(j) by construction; all a_i are integers.
        # The sum cannot exceed |sum| < d because that would mean infeasible
        # input. We add in increasing magnitude:
        sort pairs by |a_i · b_i| ascending
        s ← 0.0
        for (a, b) in pairs:
            s ← s + a · b              # exact in float because of Fd-divisibility
        return s

    # ----- D.5 Solve column-by-column (Algo 3 of MC19) --------------
    # Walk columns from right to left. Free columns (no pivot) get a value
    # in Fd that is divisible by the lcm of the relevant pivots.  Pivot
    # columns get evaluated from already-set free variables.
    x ← zero-vector of length n
    for k = n-1 .. 0:
        if k is NOT a pivot column:
            # collect divisors needed: pivots of all rows i ≤ min(k, m̂) with Ĉ[i,k] ≠ 0
            D ← {}
            for i = 0 .. min(k, m̂)-1:
                if Ĉ[i, k] ≠ 0:
                    D ← D ∪ { Ĉ[i, pivotCol(i)] }
            x[k] ← makeDiv(x̄[k], D)
        else:
            j ← pivotRow[k]
            # x[k] = (b̂[j] − Σ_{i>k} Ĉ[j,i]·x[i]) / Ĉ[j,k]
            terms ← [ ( -Ĉ[j, i],  x[i] / Ĉ[j, k] )  for i in (k+1 .. n-1) if Ĉ[j, i] ≠ 0 ]
            x[k]  ← b̂[j] / Ĉ[j, k]  +  safeDot(terms)
            # Note: x[i]/Ĉ[j,k] is exact in Fd because makeDiv chose x[i]
            # to be divisible by Ĉ[j,k] (Ĉ[j,k] ∈ D for column i).

    # ----- D.6 Sanity check ----------------------------------------
    # In *exact* arithmetic, C·x = 0 now. Verify in float:
    residual ← C · x   computed in double
    assert max(|residual|) == 0       # if not, we screwed up; re-run

    (uv', transitions') ← unpackSolution(x)
    return (uv', transitions')
```


──────────────────────────────────────────────────────────────────────
H.E1.  TraceParametricLine(M, uv, p_uv, q_uv)            (used in §G1)
──────────────────────────────────────────────────────────────────────

Trace the straight parametric line from p_uv to q_uv across the
triangle mesh M (in the same chart), returning a list of (triangle,
entry-edge, exit-edge, exit-uv-position) crossings. This is QEx Algo 5
distilled.

```
FUNCTION TraceParametricLine(M, uv, p_uv, q_uv):
    # locate which triangle p_uv is in (using exact Orient2D)
    t_curr ← LocatePointInUV(uv, p_uv)
    crossings ← [(t_curr, p_uv)]
    e_prev ← NIL
    while NOT pointInTriangle(q_uv, t_curr.uv):
        # PickNextEdge: the edge of t_curr that:
        #   (a) is not e_prev,
        #   (b) the open segment (current, q_uv] crosses
        # Use Orient2D for crossing test. On a tie (segment goes through
        # a vertex), pick the edge whose far endpoint is NOT on (current, q_uv].
        e_next ← PickNextEdge(t_curr, e_prev, current_uv, q_uv)
        if e_next has only one incident triangle:
            return crossings + [(t_curr, "boundary at " + intersect)]
        x_uv  ← exact intersection of segment with e_next     # in current chart
        crossings.append( (t_curr, e_next, x_uv) )
        # cross the edge: if the chart matches (no transition), stay in same uv;
        # else apply transition function:
        t_next ← oppositeTriangle(t_curr, e_next)
        if e_next is a CUT edge:
            (r_e, s_e, t_e) ← transitions[e_next]
            # transform remainder of the line into t_next's chart
            R ← R_{90}^{r_e}
            current_uv  ← R · x_uv  + (s_e, t_e)
            q_uv        ← R · q_uv  + (s_e, t_e)
        else:
            current_uv ← x_uv
        e_prev ← e_next
        t_curr ← t_next
    crossings.append((t_curr, q_uv))
    return crossings
```


──────────────────────────────────────────────────────────────────────
H.G1.  SplitTrianglesAlong(M', path)                      (used in §G1)
──────────────────────────────────────────────────────────────────────

Given a path of crossings as returned by TraceParametricLine, modify
the mesh in place so that every segment of the path lies along a chain
of mesh edges (Steiner vertices inserted on edges and triangles as
needed).

```
FUNCTION SplitTrianglesAlong(M', path):
    # path is [ (t_0, e_0, x_0), (t_1, e_1, x_1), ..., (t_{k-1}, x_{k-1}) ]
    # where x_i is the entry/exit point of triangle t_i.
    # We need an output edge-path on M' that traces these xs in order.

    # 1) Insert a vertex at every interior x_i that lies strictly inside
    #    a triangle (a "face point"). To keep the mesh a triangulation,
    #    1-3 split the triangle (turn 1 triangle into 3 by connecting the
    #    new vertex to all three corners).
    for each crossing x_i on edge e_i:
        if x_i is interior of edge:
            # 2-2 split: turn 2 incident triangles into 4 by inserting v
            v ← new vertex at position (in 3D, recovered from uv via
                                        barycentric interpolation in t_i)
            split_edge(M', e_i, v)

        elif x_i is interior of triangle (rare — only happens at endpoints):
            v ← new vertex
            split_triangle_1to3(M', t_i, v)
        # else x_i coincides with an existing vertex — no action

    # 2) Insert intermediate points if the segment within a triangle does not
    #    coincide with an edge of M' after step 1. Walk the chain of edges
    #    on M' from x_i to x_{i+1}; if there is no such chain (because the
    #    triangle lies between the two), 1-3 split t_i at its centroid then
    #    re-route. In practice we typically need at most one Steiner per
    #    triangle since the path is straight in uv.
    for i = 0 .. k-2:
        if NOT existsEdgeChain(M', vertex_for(x_i), vertex_for(x_{i+1})):
            v_mid ← midpoint along segment in t_i, lifted to 3D
            split_triangle_1to3(M', t_i, v_mid)
            # now there ARE three new edges; one of them connects.

    return list_of_path_vertices_in_M'(path)
```

The path of new mesh edges is recorded with the arc that produced it;
this is what `path_of(arc a)` returns later in EdgeCollapsePath.


──────────────────────────────────────────────────────────────────────
H.G2.  EdgeCollapsePath(M', path)                          (used in §G1)
──────────────────────────────────────────────────────────────────────

Standard half-edge collapse, applied to every edge of the path:

```
FUNCTION EdgeCollapsePath(M', path):
    for each edge e on path (front to back):
        if collapseValid(e):           # link condition: no fold-overs,
                                        # no triangles becoming degenerate
            (u, v) ← endpoints of e
            for each triangle t incident to e:
                deleteTriangle(M', t)
            redirect every edge incident to v to be incident to u instead
            deleteVertex(M', v)
        else:
            # tiny perturbation — nudge u slightly off the path before
            # collapsing. After Lyon's modified motorcycle graph this
            # rarely happens.
            perturb_and_retry(e)
```


──────────────────────────────────────────────────────────────────────
H.G3.  OppositeArc(j),  FindOppositeMatch(j, q),  ConnectAcross,
       SplitArc                                            (used in §G1)
──────────────────────────────────────────────────────────────────────

Lyon §6 "T-junction extension". The geometry is:

      arc a₁          arc a₂
   ┌───────────┬──────────────┐                 same patch on both sides
   │           │              │                 of T-junction j (j is
   │           │ T-junction j │                 between a₁ and a₂)
   │           ▼              │
   │       ─ patch p ─        │
   │                          │
   └──────────────────────────┘
              opposite arc(s) of j inside p

The "opposite" of j is the chain of arcs of p on the side directly
across from j. Pictorially: walk around p starting from j, advance by
one quad-strip orthogonal to j's direction (using consistency, this is
unambiguous), and look at the corresponding side.

```
FUNCTION OppositeArc(j):
    # j sits on the edge between (a₁, a₂) of patch p. Walk the boundary
    # of p, recording (axis, side index) of each arc:
    #   axis ∈ {u, v},   side ∈ {0 = bottom, 1 = top, 2 = right, 3 = left}
    # The opposite of side s is sideOpposite[s] = s ^ 1 (XOR 1 for
    # 0↔1 and 2↔3 in our labelling).
    p ← patch_containing(j)
    side_of_j ← whichSide(j, p)                  # ∈ {0,1,2,3}
    opp_side  ← side_of_j XOR 1
    return arcs of p on side opp_side             # 1 or more arcs

FUNCTION FindOppositeMatch(j, q):
    # j has its own "parametric depth" along its side: it's at some
    # cumulative quantum k_j = Σ q on the arcs of side_of_j between
    # the patch-corner and j.
    p           ← patch_containing(j)
    side_of_j   ← whichSide(j, p)
    arcs_side   ← arcs of p on side_of_j ordered from corner
    k_j         ← sum of q over those arcs strictly before j
    arcs_opp    ← arcs of p on (side_of_j XOR 1), ordered from same corner
    # walk arcs_opp accumulating quanta:
    k ← 0
    for a in arcs_opp:
        k_next ← k + q[a]
        if k == k_j:
            # there is already a node here on the opposite side
            return ("node_match", node_at_start_of(a))
        if k < k_j < k_next:
            # need to split arc a at parametric offset (k_j − k)
            return ("split_match", a, k_j − k)
        k ← k_next
    return ("none", NIL, 0)

FUNCTION ConnectAcross(j, target):
    # `target` is either a node or a (split-arc, offset) pair.
    if target is a node n:
        # Trace a parametric line in uv from j across the patch to n.
        # Add the resulting edge-path to the mesh (SplitTrianglesAlong),
        # creating one new arc joining j and n in T.
        path ← TraceParametricLine(M, uv, uv(j), uv(n))
        SplitTrianglesAlong(M', path)
        AddArcToTMesh(j, n)
    else:
        (a, off) ← target
        SplitArc(a, off)         # creates a new node n_new on a
        ConnectAcross(j, n_new)  # then recurse with the case above

FUNCTION SplitArc(a, off):
    # a has parametric length l_a. The new node sits at parametric
    # offset `off` measured from a's start. In the underlying triangle
    # mesh, the arc is already an edge-path; locate the edge of that
    # path whose cumulative parametric length spans `off`, split it
    # (insert vertex via SplitTrianglesAlong primitives), and split
    # arc a at the new vertex into a' (length off) and a'' (length l_a−off).
    cumulative ← 0
    for edge e along path_of(a):
        seg ← parametric_length_of(e)
        if cumulative + seg ≥ off:
            v_new ← position along e at parametric offset (off − cumulative)
            split_edge(M', e, v_new)
            (a', a'') ← splitArcAtVertex(a, v_new)
            # Inherit quantization: q[a'] = floor(off · q[a] / l_a) etc.
            # In Lyon's case off is chosen so this is exact.
            return v_new
        cumulative += seg
```


──────────────────────────────────────────────────────────────────────
H.H1.  ExtractGrid(p, φ_u, φ_v, n_u, n_v)                  (used in §H)
──────────────────────────────────────────────────────────────────────

Given that p has been parametrised (φ_u, φ_v) : p → [0, n_u]×[0, n_v]
by Dirichlet harmonic interpolation, find the n_u·n_v grid points whose
images lie at integer (i, j) with 0 ≤ i ≤ n_u, 0 ≤ j ≤ n_v.

```
FUNCTION ExtractGrid(p, φ_u, φ_v, n_u, n_v):
    grid ← [[NIL]*(n_v+1) for _ in 0..n_u]
    # Boundary points are immediate: they sit at known parametric integers
    # along the macro-sides of p.
    populateBoundaryFromMacroSides(grid, p)
    # Interior: use a marching scheme. Start from any known boundary
    # point, hop to its unknown neighbour at integer offset (1, 0).
    # Newton iteration in the triangle subset of p:
    #   solve (φ_u(x) − target_i, φ_v(x) − target_j) = 0
    #   starting from an estimate produced by linear inversion in the
    #   triangle currently containing the previous grid point.
    queue ← all boundary cells with at least one known neighbour
    while queue non-empty:
        (i, j) ← popKnown(queue)
        for (di, dj) in [(±1,0), (0,±1)]:
            (i', j') ← (i+di, j+dj)
            if grid[i'][j'] != NIL: continue
            if (i', j') is out of [0..n_u]×[0..n_v]: continue
            # newton-solve from grid[i][j] for parametric (i', j')
            x_init ← position of grid[i][j]
            t_init ← triangleContaining(p, x_init)
            x_sol  ← NewtonInverse(φ_u, φ_v, target=(i', j'),
                                   start=x_init, hint_triangle=t_init)
            grid[i'][j'] ← x_sol
            queue.push((i', j'))
    return grid


FUNCTION NewtonInverse(φ_u, φ_v, target, start, hint_triangle):
    x ← start
    t ← hint_triangle
    for _ in 1..MAX_ITER:
        # piecewise-linear (φ_u, φ_v) on triangle t, so the Jacobian is
        # constant per triangle:
        J ← [ [∂φ_u/∂x, ∂φ_u/∂y],          # 2x2 matrix
              [∂φ_v/∂x, ∂φ_v/∂y] ]_t
        residual ← (φ_u(x), φ_v(x)) − target
        Δx       ← solve J · Δx = −residual
        x_new    ← x + Δx
        # Walk to neighbouring triangle if x_new exited t:
        while x_new ∉ t:
            edge_crossed ← edgeOfExit(t, x, x_new)
            t ← oppositeTriangle(t, edge_crossed)
            if t is NIL: break              # exited p — clamp to boundary
        x ← x_new
        if ‖residual‖ < ε: return x
    return x
```

Newton converges in a single iteration when restricted to a single
triangle (because the function is affine there); the only iteration
cost is the triangle-walking, which is O(diameter of patch).


──────────────────────────────────────────────────────────────────────
H.H2.  BoundaryFix_Coons(p)                                (used in §H)
──────────────────────────────────────────────────────────────────────

After the harmonic solve, the boundary of p was prescribed as a linear
interpolation between corners along each macro-side. If the four sides
do not meet exactly at the corners after the rounding from §G, there is
a small "crack" at each corner. Nielson 1999 supplies the fix: replace
the linear edge interpolation with a transfinite triangular Coons
interpolant that exactly matches the (possibly non-linear) corner data.

For Lyon's patches the macro-quadrilateral is split into 2 macro-
triangles along a diagonal; Nielson's NTW linear/linear patch is then
applied per macro-triangle.

```
FUNCTION BoundaryFix_Coons(p):
    # p is rectangular with macro-sides a, b, c, d and corners V0..V3
    # parametrised (locally) over (x, y) ∈ [0,1]².
    F00 ← position(V0); F10 ← position(V3)
    F01 ← position(V1); F11 ← position(V2)
    F_x0 ← linearMap_along(a)              # F(x, 0)  (curve along bottom)
    F_x1 ← linearMap_along(c)              # F(x, 1)
    F_0y ← linearMap_along(d)              # F(0, y)
    F_1y ← linearMap_along(b)              # F(1, y)

    # Update φ_u and φ_v on each boundary triangle to use the rectangular
    # Coons interpolant from Nielson eq. (1):
    #   C(x,y) = (1-x)F(0,y) + x F(1,y)
    #          + (1-y)F(x,0) + y F(x,1)
    #          - (1-x)(1-y)F(0,0) - x y F(1,1)
    #          - (1-y) x F(1,0) - (1-x) y F(0,1)
    #
    # In Lyon's setting, the "F" of each side is just the chain of
    # already-traced arcs of L, so F(0, y) is exactly the parametric
    # arc-length-fraction along side d. Replacing the linear boundary
    # with this Coons surface guarantees the four corners agree exactly
    # (verifiable by plugging in {(0,0),(1,0),(0,1),(1,1)} and
    # observing the boundary terms collapse to F(0,0) etc.).
    re-solve harmonic system on p with the new Coons boundary values
```

For triangular sub-patches Lyon doesn't generate (since all his patches
are quads), the rectangular Coons formula above is enough. The
triangular Coons of Nielson eq. (3) is only needed when the patch is
genuinely triangular, e.g. in the rare case a layout patch ends up
3-sided due to two adjacent macro-corners merging. We use it as drop-
in replacement of the rectangular form there.


──────────────────────────────────────────────────────────────────────
H.E2.  LocatePointInUV(uv, p_uv)                          (used in §H.E1)
──────────────────────────────────────────────────────────────────────

Find which triangle of M contains the parametric point p_uv. Required
at the start of TraceParametricLine and at every Newton restart. The
naive linear scan is O(|F|) per query and fine for tracing where the
start triangle is usually known (the previous segment ended in it); we
only need a fast LocatePointInUV for the very first lookup per trace.

```
FUNCTION BuildUVLocator(M, uv):
    # Pre-process: build a kd-tree (or AABB-tree) over per-triangle
    # uv-bounding-boxes in the chart of interest.
    # Each leaf stores: triangle_id, (u_min, v_min, u_max, v_max).
    boxes ← []
    for each triangle t in M (in current chart):
        (uv0, uv1, uv2) ← uv-coords of t's three corners
        box ← AABB( min(uv0,uv1,uv2), max(uv0,uv1,uv2) )
        boxes.append( (t, box) )
    return kdTree.build(boxes, axis-key = box-centroid)

FUNCTION LocatePointInUV(uv, p_uv, locator):
    # 1) Query the kd-tree for all triangle-boxes that contain p_uv.
    candidates ← locator.query_point(p_uv)        # O(log |F| + k) typically
    # 2) Among candidates, find the one whose triangle in uv actually
    #    contains p_uv (boxes overlap, but only one triangle does — modulo
    #    fold-overs which Lyon's pipeline excludes after MC19).
    for t in candidates:
        (uv0, uv1, uv2) ← uv-coords of t
        s0 ← sign( Orient2D(uv0, uv1, p_uv) )
        s1 ← sign( Orient2D(uv1, uv2, p_uv) )
        s2 ← sign( Orient2D(uv2, uv0, p_uv) )
        # all the same sign → strictly inside; one zero → on edge;
        # two zeros → on vertex; three zeros impossible for non-degen tri
        if s0 ≠ 0 and s1 ≠ 0 and s2 ≠ 0:
            if s0 == s1 == s2:
                return (t, "interior")
        elif (s0 == s1 == s2) or onlyOneZero(s0,s1,s2):
            return (t, "boundary_or_edge")
    raise NotInAnyTriangle               # only happens if p_uv outside chart

FUNCTION LocatePointInUV_Fast(uv, p_uv, hint_triangle):
    # When tracing, we usually know a triangle very close to p_uv (the
    # one we just exited). Walk the dual graph greedily until p_uv is
    # inside the current triangle or we've crossed a chart boundary.
    t ← hint_triangle
    while True:
        (uv0, uv1, uv2) ← uv-coords of t
        s0 ← Orient2D(uv0, uv1, p_uv)
        s1 ← Orient2D(uv1, uv2, p_uv)
        s2 ← Orient2D(uv2, uv0, p_uv)
        if sameSign(s0, s1, s2):                  # inside (or on boundary)
            return (t, classifyByZeros(s0,s1,s2))
        # at least one Orient2D is on the wrong side → cross that edge
        worst_edge ← argmin( {s0, s1, s2} )       # the most negative
        t_next ← oppositeTriangle(t, worst_edge)
        if t_next is NIL:                         # left the mesh / chart
            # fall back to the kd-tree global query
            return LocatePointInUV(uv, p_uv, global_locator)
        if cross_is_a_cut(worst_edge):
            # apply transition function to p_uv and continue in t_next's chart
            (r_e, s_e, t_e) ← transitions[worst_edge]
            p_uv ← R_{90}^{r_e} · p_uv + (s_e, t_e)
        t ← t_next
```

In Lyon's pipeline, `LocatePointInUV` is needed:
  • once per trace at spawn time (use the singularity's incident triangle
    as the immediate answer — no search needed);
  • once per `ConnectAcross` (use the patch's first triangle as the hint);
  • inside `NewtonInverse` (use the previous Newton iterate's triangle
    as hint — this is the fast path 99% of the time).

Build `global_locator = BuildUVLocator(M, uv)` once after MakeExactlySeamless;
all subsequent calls use the fast walk with a kd-tree fallback.

Edge / vertex cases (a parametric line endpoint sitting exactly on a
mesh edge or vertex) are returned with the appropriate classification
so the caller can decide which incident triangle to actually advance
into. Because the input uv has been sanitized by MC19, the Orient2D
predicates are exact in IEEE-754 doubles, so no robustness epsilon is
needed.


──────────────────────────────────────────────────────────────────────
H.G4.  ParametricPointAlongArc(a, off)                   (used in §G3)
──────────────────────────────────────────────────────────────────────

Compute the 3D position (and uv-position) of the point that sits at
parametric offset `off` along arc `a`. Used by `SplitArc` and by
`ConnectAcross` when the target is `(arc, offset)`. Recall that arc `a`
has been integrated into M' as a chain of triangle-mesh edges
`path_of(a) = [e_0, e_1, ..., e_{k-1}]`, each carrying an attribute
`parametric_length_of(eᵢ)` that records its parametric length in the
chart of arc `a`'s trace.

```
FUNCTION ParametricPointAlongArc(a, off):
    assert 0 ≤ off ≤ a.parametric_length

    cumulative ← 0
    for each edge e in path_of(a):                # in arc-direction order
        seg ← parametric_length_of(e)
        if cumulative + seg ≥ off:
            # point lies on edge e at fractional position s ∈ [0, 1]
            s ← (off − cumulative) / seg
            (v0, v1) ← endpoints(e), in arc-direction order

            # 3D position by linear interpolation of vertex positions
            pos3D ← (1 − s) · position(v0)  +  s · position(v1)

            # uv position in the chart of this edge
            # (each half-edge stores its corner-uv; use the half-edge that
            #  belongs to a triangle on arc a's "primary" side to avoid
            #  cut ambiguity)
            h_e ← arcSideHalfEdge(e, a)
            uv_v0 ← cornerUV(h_e, from_vertex)
            uv_v1 ← cornerUV(h_e, to_vertex)
            uv_pt ← (1 − s) · uv_v0  +  s · uv_v1

            return (pos3D, uv_pt, e, s)
        cumulative += seg

    # off equals a.parametric_length exactly → return the end node
    return ( position(a.end_node),
             cornerUV(path_of(a)[-1], to_vertex),
             path_of(a)[-1],
             1.0 )
```

Notes:
  • Linear interpolation in 3D is justified because each `e` is a single
    triangle-mesh edge (a straight segment in ℝ³), not a curved chain.
  • The arc's parametric length per edge is recorded at the moment the
    arc is integrated into M' by `SplitTrianglesAlong`; for an edge
    whose endpoints are at uv-positions `uv_a` and `uv_b` along the
    iso-line direction `d ∈ {(1,0), (0,1)}`, the parametric length is
    `|⟨uv_b − uv_a, d⟩|`. Sum of all edges' parametric lengths equals
    the arc's total parametric length by construction.
  • The `s` returned is what `split_edge(M', e, v_new)` needs to place
    the new vertex; the returned `(pos3D, uv_pt)` is what `SplitArc`
    feeds to the topological-mesh splitter.
  • For the very last node of the arc (`off == a.parametric_length`)
    we return the existing end node — no split needed.

When `SplitArc(a, off)` is called, the consumer wires it together as:

```
v_new ← new vertex at pos3D                       # from above
split_edge(M', e, s, v_new)                        # 2→4 triangles
(a', a'') ← splitArcAtVertex(a, v_new)             # T-mesh bookkeeping
# inherited quantization (Lyon §6: off chosen so this is exact integer)
q[a' ] ← round( off / a.parametric_length · q[a] )
q[a''] ← q[a] − q[a']
```
