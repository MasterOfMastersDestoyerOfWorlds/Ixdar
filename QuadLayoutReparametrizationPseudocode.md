# Quad-Layout Re-Parametrization — Paper-Exact Pseudo-Code

Spec for the pipeline that maps every layout patch onto its quantized rectangle and
relaxes the union into a low-distortion integer grid map (IGM). Every pseudo-code line
cites the paper/section/equation it implements. Everything already implemented names its
Java class and method. **Every weight, bias, or tolerance that no paper calls out is
flagged `NOT IN ANY PAPER` — each such flag means we are missing something.**

Papers in ~/QuadLayoutsPapersCompleted and ~/QuadLayoutsPapersToRead

Papers (short keys used throughout):

| Key | Paper |
|---|---|
| BZK09 | Bommes, Zimmer, Kobbelt 2009 — Mixed-Integer Quadrangulation |
| BCE13 | Bommes, Campen, Ebke, Alliez, Kobbelt 2013 — Integer-Grid Maps for Reliable Quad Meshing |
| MPZ14 | Myles, Pietroni, Zorin 2014 — Robust Field-Aligned Global Parametrization |
| CBK15 | Campen, Bommes, Kobbelt 2015 — Quantized Global Parametrization |
| SS15 | Smith, Schaefer 2015 — Bijective Parameterization with Free Boundaries |
| RPP17 | Rabinovich, Poranne, Panozzo, Sorkine-Hornung 2017 — Scalable Locally Injective Mappings |
| SPH17 | Shtengel, Poranne, Sorkine-Hornung, Kovalsky, Lipman 2017 — Geometric Optimization via Composite Majorization |
| MC19 | Mandad, Campen 2019 — Exact Constraint Satisfaction for Truly Seamless Parametrization |
| LCKB19 | Lyon, Campen, Bommes, Kobbelt 2019 — Parametrization Quantization with Free Boundaries |
| LCK21a | Lyon, Campen, Kobbelt 2021 — Quad Layouts via Constrained T-Mesh Quantization |

Authority chain: LCK21a §6 adopts the re-parametrization of LCKB19 §6 verbatim ("we
opted to employ the recent re-parametrization approach of [LCBK19, §6]"). LCKB19 §6.2
builds the initial map with Tutte 1963 and optimizes it with the machinery of RPP17 /
SPH17; LCKB19 §7 names the objective and states the input parametrization was computed
with the BZK09 objective **plus BCE13 local-injectivity constraints**. SS15 supplies the
line-search filter both RPP17 and SPH17 use. CBK15 §7 is the predecessor route
(constrained re-optimization) that LCKB19 replaced. MC19 governs exact seam
satisfaction between the seamless stage and the motorcycle tracer.

Notation: `F` = input seamless parametrization (per-face charts). `f` = output integer
grid map (per-patch charts on the common grid). `J_t` = Jacobian of the map
`F-chart → grid-chart` on triangle `t`. `A_t` = area of `t` measured in `F`.

---

## Stage 0 — Input seamless parametrization F

The optimizer measures every triangle against its shape in `F`; the papers require `F`
to be locally injective **before** anything downstream runs. This is the stage our
pipeline deviates from most.

```text
input:  triangle mesh M, cross field (R_t per face), sizing H_t
E(f) = 1/2 Σ_t A_t ‖∇f_t − R_t H_t‖²_F                          [BCE13 (E1); BZK09 §5]
       generalized E_α^k; defaults α = 0.1 (quad layout), k = 2  [BCE13 (E2); §4 "defaults"]
subject to:
  seamless transitions g_{i→j}(a) = R90^{r_ij}·a + t_ij          [BCE13 (1); BZK09 §5.2]
  consistent orientation (3), convexified to (4):                 [BCE13 §3.1]
    per triangle: 6 linear inequalities from a trisector          [BCE13 §3.1, Fig. 4 left]
      anchored at the first Fermat point m of a reference triangle
      δ_i^0(( u_i − m)·s_i^⊥   /‖s_i^⊥‖   − ε) ≥ 0
      δ_i^1((−(u_i − m))·s_{i+1}^⊥/‖s_{i+1}^⊥‖ − ε) ≥ 0          [BCE13 (4)]
    ε = 1 % of the smallest reference edge; solver tol < 2ε       [BCE13 §3.1 "Limited Precision"]
    virtually split any triangle with an angle > 100°             [BCE13 §3.1, Fig. 4 right]
    reference triangles minimize ‖R_t^T ∇f_t − H_t‖²_α           [BCE13 §3.4 "Optimization of (P2)"]
  lazy activation: start with zero (4)-constraints; add violated
    plus all with normalized value < 0.5; re-solve until valid    [BCE13 §3.4 "Lazy Constraints"]
solver: IPOPT (continuous part)                                   [BCE13 §3.4 "Numerical Solver"]
then:  project onto EXACT seam constraints                        [MC19 §4 Alg. 1–5, §5.3]
       (fraction-free IREF/IRREF over Z, safe dot product)
  the ε-margin in (4) absorbs the projection's ~1e-7 Δu           [MC19 §6 Table 2; §7]
  rare residual flip → move vertex into its 1-ring kernel         [MC19 §5.4]
```

Implemented:

- Objective, transitions, greedy rounding, per-branch stiffening —
  `SeamlessParameterization#build`
  (`ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessParameterization.java`).
  Stiffening constants `stiffeningC = 1`, `stiffeningD = 5` are BZK09 §5.4's paper values.
- Exact seams — `SeamlessProjector#project`, run when `exactSeams = true`, after the
  stiffening loop and before the T-mesh stage. This is the MC19 projection; MC19 §6.2 is
  the reason it must run (isocurve/motorcycle tracing fails on inexact seams regardless
  of how small the violation is).

Deviations & missing:

- **IMPLEMENTED 2026-08-02 — BCE13 (4) injectivity constraints** replace the BZK09 §5.4
  stiffening loop. `InjectivityConstraints` builds the paper-exact constraint set
  (target-shape reference triangles, first Fermat point by the equilateral-apex
  construction, bisector trisector rays, ε = 1 % smallest reference edge, δ
  normalization to one at the reference, virtual altitude split past 100°);
  `SeamlessParameterization#runInjectivityConstraintLoop` runs BCE13 §3.4's lazy
  activation (violated + normalized < 0.5). **Solver substitution, flagged**: instead of
  IPOPT, active constraints are enforced by escalating quadratic penalties pulled to the
  activation threshold (CBK15 §7.1's λ-penalty precedent, SPH17 §5.3's ×10 schedule),
  re-solved by direct Cholesky per round; validity is still checked exactly each round.
  On fertility: 17 violated at round 0, zero after one penalty round, `flipped=0`,
  metrics `flippedTriangleCount=0` after the MC19 projection. The optimizer's 189-bad-
  reference audit has not yet been re-reached: the now-different layout contracts into a
  patch of 62 649 triangles on a 1×1 rectangle whose harmonic interior collapses below
  double precision (slivers at 1e-13), and the Tutte stage fails loudly there — the
  contraction-drag-locality problem, the next open front.
- **Compatibility (verified against MC19, not folklore):** adding BCE13's inequalities
  does not disturb the motorcycle phase. The inequalities constrain only the `(u,v)`
  values; every transition's rotation `r_ij` is fixed a priori (MC19 §5.1, BCE13 (1)),
  so the transition structure the tracer walks is untouched. MC19 does not repair
  inequality violations (§5.4), but it processed BCE13's full 92-model dataset with
  **zero flips introduced** precisely because BCE13 imposes (4) with the ε-margin
  (MC19 §6, Table 2); MC19 §7 prescribes that margin for exactly this purpose. Pipeline
  order is unchanged: constrained seamless solve → `SeamlessProjector` → motorcycle.
  Cost: (4) needs an inequality-capable solver (BCE13: IPOPT; CBK15 §7.1 shows the
  convexified/lazy variant), where the stiffening loop is plain linear solves.

---

## Stage 1 — T-mesh and quantization (context)

Not this document's focus; listed so the chain of custody for "every patch has positive
quantized area" is visible.

```text
trace motorcycle graph T from F's singularities                   [LCK21a §3; CBK15 §5.1]
solve ILP: consistency (3), layout constraints (4), objective (5) [LCK21a §4–§5]
```

Implemented: `MotorcycleGraph#build`, `QuantizedMeshGrid#build`
(validated against LCK21a Table 1 on ROCKERARM).

---

## Stage 2 — Re-embedding and zero-collapse

```text
re-embed T-mesh as subcomplex of a mesh copy:
  nodes → vertices, arcs → edge paths (carve walk)                [LCKB19 §6.1]
apply collapse operators until fixed point:
  (1) collapse zero-quantized arcs (shortest-path re-route)       [LCKB19 §6.1 operator 1]
  (2) split non-simple zero patches                               [LCKB19 §6.1 operator 2]
  (3) collapse simple zero patches                                [LCKB19 §6.1 operator 3]
extend surviving T-junctions across their patches                 [LCK21a §6]
POSTCONDITION: every surviving patch has quantized width > 0 and
  height > 0 — no degenerate patch may reach Stage 3              [LCKB19 §6.1→§6.2]
```

Implemented: `LayoutEmbedding#build`, `EmbeddedTMesh#contract`, `EmbeddedTMesh#conform`,
`ArcStraightening#build`.

This postcondition is what makes the Stage-5 drop-gates illegitimate: if the optimizer
ever needs to skip a zero-area triangle, the bug is here or in Stage 0, not something to
tolerate at gather time.

---

## Stage 3 — Per-patch Tutte map onto the quantized rectangle

```text
for each live patch P with quantized sides w × h:                 [LCKB19 §6.2]
  subdivide chords until the region is 3-connected                [Tutte 1963 precondition]
  place boundary onto the rectangle:
    corners at (0,0), (w,0), (w,h), (0,h)                         [LCKB19 §6.2 "fixing one of the
                                                                   patch's nodes to the origin"]
    arc endpoints at their quantized integer offsets              [LCKB19 §6.2 → all nodes integer]
    intra-arc boundary vertices: any monotone distribution        [unspecified by the papers]
  REQUIRE the boundary loop simple (no repeated vertex): a pinch
    means the region is not a disk and no bijective map exists    [Tutte 1963 precondition]
  REQUIRE opposite sides equal and positive                       [LCKB19 Def 3.1]
  solve interior by convex-combination (Tutte) map                [Tutte 1963; LCKB19 §6.2]
    weights: cotangent                                            [RPP17 §6]
    if the system is indefinite or any triangle flipped →
      re-solve with uniform weights                               [RPP17 §6: "guaranteed to give
                                                                   us a valid starting point"]
  result is bijective and flip-free — the papers have NO repair
    past uniform weights; a surviving fold means the input violates
    Tutte's preconditions or is a configuration the paper pipeline
    never produces (fail loudly, fix upstream)                    [Tutte 1963; LCKB19 §6.2]
```

Implemented:

- `PatchRectangleMap#build` → `placeBoundary` (corners at the origin exactly as above),
  `solveInterior` (cotangent weights, sparse Cholesky), `assertFoldFree`.
- `LayoutPatchMaps#build` orchestrates per patch; `ThreeConnectivityRefinement#refine`
  secures the 3-connectivity precondition.

Deviations & missing:

- `MINIMUM_HARMONIC_WEIGHT = 1e-3` clamps negative cotangents. `NOT IN ANY PAPER`. The
  paper-backed mechanism is RPP17 §6's fallback: keep raw cotangent weights, and if the
  solve produces a flipped triangle, re-solve the patch with **uniform** weights (a true
  convex combination, valid by Tutte's theorem). `PatchRectangleMap#uniformEdgeWeights`
  already exists but is dead code.
- `UNIFORM_SPACING_SHARE = 1.0` multiplies the parametric/chord term by zero, silently
  turning `BoundarySpacing.PARAMETRIC` into uniform spacing. Boundary distribution
  inside an arc is genuinely unspecified by the papers (Tutte needs only a homeomorphic
  convex boundary), so uniform is *permitted* — but the dead `PARAMETRIC` path and its
  computed-then-ignored `boundaryStepLength` must not pretend otherwise.

---

## Stage 4 — Global integer grid map assembly and degrees of freedom

```text
frame each patch in one grid: quarter-turn + integer origin (BFS
  over patch adjacency, seeded per component)                     [LCKB19 §6.2 "union … forms an
                                                                   integer grid map"; BCE13 (1)]
per two-sided arc: transition = R90^r + integer translation,
  r found by matching spans, verified at both endpoints           [BCE13 (1)]
audit: every layout node lies on an integer position              [BCE13 (2)]
choose DOFs for the relaxation:
  pin critical (singular/feature) and border nodes at integers    [LCKB19 §7 "assigned integer values";
                                                                   BCE13 (2)]
  couple every seam vertex across its arc's grid automorphism,
    one shared coordinate pair                                    [LCKB19 §7 "seamlessness"; BCE13 (1)]
  free regular nodes through fan-composed chart transitions       [LCKB19 §6.2 "most of the vertices …
                                                                   can now freely move", Fig. 10]
```

Implemented:

- `IntegerGridMap#build`, `#computeTransitions` — the transition model is exactly
  BCE13 (1): `R90^r` (`QUARTER_TURN_COSINE/SINE`) plus integer translation.
- `GlobalGridMap#build`, `#measureNodes` — the BCE13 (2) audit
  (`INTEGER_TOLERANCE = 1e-6`, `offGridNodeCount` must be 0).
- `GridMapDofSystem#build` with `SeamCoupling.COUPLED` and `NodeFreedom.REGULAR_FREE`;
  `#writeBack` pushes solved slots back through each copy's read transform.

Note: the "hold one corner at the origin" requirement of LCKB19 §6.2 **is implemented**
— locally by `placeBoundary` (corner 0 at `(0,0)`), globally by the integer origins in
`IntegerGridMap`, and audited by `measureNodes`. Its purpose in the paper is node
integrality, which we verify.

---

## Stage 5 — Global optimization (the GridMapOptimizer)

LCKB19 §7 is the governing sentence: *"a Newton solver with the objective of minimizing
the difference between output integer grid map and input parametrization F, while
preserving local injectivity, seamlessness, and assigned integer values."* The concrete
mechanics come from the papers LCKB19 §6.2 cites:

```text
gather: for each triangle of each live patch,
  reference shape = the triangle's corners read in F              [LCKB19 §7 "difference … to F"]
  PRECONDITION: reference is positively oriented and non-degenerate
    (guaranteed by Stage 0 under BCE13 (4); never repaired here)  [BCE13 (3),(4)]
energy:
  E = Σ_t A_t · D(J_t),  D = σ1² + σ2² + σ1⁻² + σ2⁻²             [RPP17 (1),(11); SS15 (1)–(3);
      (symmetric Dirichlet w.r.t. the F reference)                 SPH17 (24)]
  D → ∞ as det J_t → 0  ⇒ flips are impossible                    [RPP17 §3; SS15 §3.1; SPH17 §5]
  ⇒ start must be flip-free (Stage 3 guarantees it)               [RPP17 §1, §6]
iterate (projected Newton):                                       [LCKB19 §7 "Newton solver"]
  per-triangle gradient + Hessian; project each element Hessian
    to PSD by eigen-decomposition, clamp negative eigenvalues     [SPH17 §5 "PN"; Teran et al. 2005]
  chain-rule element contributions through each corner's chart
    rotation; assemble on free DOFs only                          [seamlessness: BCE13 (1)]
  solve H·d = −∇E  (sparse Cholesky)                              [LCKB19 §7]
  t_max = min over triangles of the smallest positive root of
    det(U + t·V) = 0  (quadratic in t)                            [SS15 §3.3, Eq. (4)]
  t0 = min{1, 0.8 · t_max}                                        [RPP17 §3 "Line search"]
  backtracking line search from t0 (Armijo / sufficient decrease) [SPH17 §5; SS15 §3.3]
until converged                                                    [criterion unspecified by papers]
write back into the grid map                                       [→ GridMapDofSystem#writeBack]
```

**Handedness caveat (discovered 2026-08-02):** the layout's patch cycles run
*clockwise* — `PatchBoundaryBuilder`'s corner-law audit reports `ccwCorner=0`, where
LCBK19 §4's direction law turns counter-clockwise — so every patch chart is the mirror
of `F`'s. `GridMapOptimizer#sourceCorners` negates the chart's v axis, one **global**
reflection aligning the conventions; a reference still negatively oriented after it is a
genuine injectivity failure and throws. Fixing the cycle orientation upstream (in the
motorcycle boundary walk) would retire this adapter — tracked with Stage 0.

Implemented (paper-conformant pieces to keep):

- `GridMapOptimizer#maximumStep` — exactly SS15 §3.3's `t_max` (per-triangle determinant
  quadratic, smallest positive root, numerically stable form).
- `GridMapOptimizer#takeStep` — backtracking with a sufficient-decrease test, the SPH17
  §5 recipe (`ARMIJO_SLOPE = 1e-4` is the Nocedal & Wright textbook `c₁`, which SPH17
  cites for its Armijo backtracking).
- `SymmetricDirichletEnergy` (`#evaluate`, `#energyOnly`,
  `#projectToPositiveSemiDefinite`) — RPP17 Eq. (11) with the SPH17 §5 "PN" per-element
  PSD projection. **Fully implemented, currently unused.**
- Sparsity/assembly, seam-rotation chain rule, fixed-DOF projection —
  `GridMapOptimizer#buildSparsityPattern`, `#rotateElementToSlots`, `#newtonDirection`.

Deviations & missing (each `NOT IN ANY PAPER` unless noted):

1. **The active energy is invented.** `ParameterizationEnergy` minimizes
   `A·‖J − Q‖²_F` where `Q` is the rotation nearest the *initial* Jacobian snapped to a
   quarter turn (`targetByTriangle`, `GridMapOptimizer#gatherTriangles`). No paper
   defines this energy or the snap. The paper reading of "difference to F" is the
   symmetric Dirichlet above with `F` as reference — under which the chart rotation is
   not estimated from the initial map at all: the relative quarter-turn between an
   `F`-chart and a patch chart is combinatorial data already carried by the transition
   matchings (BCE13 (1)). If a quadratic fit energy is ever wanted, `Q` must come from
   that bookkeeping, not from a snap.
2. **Reference repair machinery** — reflection of negative-determinant references,
   `REFERENCE_HEIGHT_FLOOR = 1e-4` sliver lift, drop-gates
   (`gridArea < 1e-8·|det|`, `det ≤ 1e-9`), `skippedTriangleCount` and friends. All of
   it compensates for a non-injective `F`; under Stage 0's BCE13 (4) none of these
   triangles can exist. Papers: none.
3. **`foldClamp`** — per-slot rescale by `0.8 ×` that slot's own fold distance, applied
   before the global line search. Replaces SS15's single global `t_max` semantics with a
   per-vertex anisotropic shrink. Papers: none.
4. **`STEP_CAP = 0.5`** grid-units-per-slot clamp. Papers: none.
5. **Block-diagonal direction** 19 of every 20 iterations (`FULL_NEWTON_PERIOD = 20`,
   `#blockDirection`). LCKB19 §7 says Newton; SPH17's PN is full projected Newton every
   iteration. Papers: none.
6. **`DAMPING = 1e-6`, `RIDGE_FLOOR = 1e-12`** Marquardt-style diagonal scaling.
   Papers: none (SPH17's PSD projection removes the need for generic damping).
7. **`MAX_STEP_MARGIN = 0.95`** — the mechanism (start below `t_max`) is RPP17's, but
   RPP17 §3 states `0.8`.
8. **`normalizeReferenceScale`** — median area-ratio rescale of the reference plus
   total-area normalization. Papers: none. (RPP17/SS15 weight by reference area
   unnormalized; a global scale mismatch is a symptom of fitting the wrong reference,
   not something to renormalize away.)
9. **Crushed-patch pre-repair** — `#findCrushedPatches(1e-3)` plus per-patch 4 s focused
   solves in `QuadLayoutEngine#buildGlobalGridMap`. Papers: none. A patch that starts
   "crushed" means Stage 2/3 delivered a near-degenerate initial map, which the papers
   exclude by construction.
10. **Convergence knobs** — `CONVERGENCE = 1e-4`, `STALL_LIMIT = 200`,
    `maxIterations = 10_000`, wall-clock budgets. Engineering necessities the papers
    leave unspecified; keep, but they are ours, not theirs.
11. **`ParameterizationEnergy.foldGuard`** documents an escape hatch for
    "initially-degenerate triangles" that nothing ever uses — under the papers no such
    triangle exists, so the flag should not either.
12. Stale docs: `GridMapOptimizer` field/method comments still describe
    `SymmetricDirichletEnergy` as the active energy.

### The path not taken (recorded for completeness)

CBK15 §7 optimizes the original field-guided objective **subject to** linear quantization
constraints `Cz = b` (equality paths from a spanning tree of singularities plus a loop
system), with dynamic re-linearization of the BCE13 regularity constraints
(soft `λ(Cz − b)²`, `λ = 10⁶`, iterate until `‖Cz′ − b‖∞ ≤ ε = 10⁻⁴`, then a hard
solve). LCKB19 §6.2 replaced this with the Tutte-then-optimize route for guaranteed
reliability (CBK15 §7.1.1 itself names that escape: remove zero-cells, then "each cell
can individually be parameterized regularly … using Tutte's barycentric mapping").
RPP17's reweighted local/global and SPH17's composite majorization are the two
optimizers LCKB19 §6.2 names as drop-in alternatives to plain projected Newton.

---

## What must change before the optimizer matches the papers exactly

In dependency order:

1. **Stage 0:** replace stiffening with BCE13 (4) constraints (ε-margin, Fermat
   trisectors, virtual splits > 100°, lazy activation, inequality-capable solver). This
   is upstream of everything: it makes every optimizer reference positively oriented and
   lets the MC19 projection stay flip-free by the margin argument (MC19 §6/§7).
2. **Stage 3:** replace the cotangent clamp with RPP17 §6's uniform-weight fallback.
3. **Stage 5:** switch the energy to the already-implemented `SymmetricDirichletEnergy`
   with `F`-references (or derive the quadratic target `Q` combinatorially from
   transitions); run full projected Newton each iteration; set the line-search start to
   `0.8·t_max`; drop `foldClamp`, `STEP_CAP`, the block direction, and the damping pair.
4. **Delete the compensation machinery** (reference reflection, height floor, drop-gates,
   crushed-patch pre-repair, `normalizeReferenceScale`) — after 1–3 they must be dead;
   while any of them still fires, the count it reports is the regression signal that an
   upstream stage broke its postcondition.

---

## Consolidated constant audit

"AUDIT" = tolerance used only to *verify* a paper invariant, not to change behavior.
"ENGINEERING" = resource limit the papers leave open. Everything else either has a paper
value or is flagged.

| Constant | Value | Location | Status |
|---|---|---|---|
| `stiffeningC` | 1.0 | `SeamlessParameterization` | BZK09 §5.4 (paper value c = 1) — but the stiffening *mechanism* deviates from BCE13 (4) |
| `stiffeningD` | 5 | `SeamlessParameterization` | BZK09 §5.4 (paper value d = 5) — same caveat |
| `maxStiffeningIterations` | 30 | `SeamlessParameterization` | `NOT IN ANY PAPER` |
| `stiffeningPcgRelativeTolerance` | 1e-8 | `SeamlessParameterization` | ENGINEERING |
| `DEGENERATE_AREA_EPSILON` | 1e-30 | `SeamlessParameterization` | `NOT IN ANY PAPER` |
| `DEGENERATE_UV_AREA_FRACTION` | 1e-6 | `SeamlessParameterization` | `NOT IN ANY PAPER` |
| `SVD_DET_FACTOR` | 4.0 | `SeamlessParameterization` | `NOT IN ANY PAPER` |
| `NEIGHBOUR_DEPTH` | 2 | `GlobalGridMap#chartNeighbourhood` | ENGINEERING (query helper, not solver behavior) |
| ε margin in BCE13 (4) | 1 % smallest reference edge | *(missing)* | BCE13 §3.1 — **required, absent** |
| virtual-split threshold | 100° | *(missing)* | BCE13 §3.1 — **required, absent** |
| lazy-add threshold | normalized value < 0.5 | *(missing)* | BCE13 §3.4 — **required, absent** |
| `EDGE_MIDPOINT` | 0.5 | `ThreeConnectivityRefinement` | split-point choice, unspecified by papers (benign) |
| `MINIMUM_HARMONIC_WEIGHT` | — | `PatchRectangleMap` | REMOVED — raw cotangent with RPP17 §6 uniform-weight fallback implemented |
| `UNIFORM_SPACING_SHARE` | — | `PatchRectangleMap` | REMOVED — boundary spacing is plainly uniform (unspecified by the papers) |
| fold-area floor | 1e-12·w·h | `PatchRectangleMap#flippedTriangleCount` | AUDIT |
| `constraintPenaltyBaseWeight` / escalation / cap | 1e2 / ×10 / 1e12 | `SeamlessParameterization` | ENGINEERING — CBK15 §7.1 λ-penalty precedent, SPH17 §5.3 schedule |
| `INTEGER_TOLERANCE` | 1e-6 | `GlobalGridMap` | AUDIT of BCE13 (2) |
| `AGREEMENT_TOLERANCE` | 1e-9 | `GridMapDofSystem` | AUDIT |
| quarter-turn snap of `Q` | — | *(deleted)* | REMOVED — energy is `SymmetricDirichletEnergy` w.r.t. F references (RPP17 Eq. 11) |
| `MAX_STEP_MARGIN` | 0.8 | `GridMapOptimizer` | RPP17 §3 (`min(1, 0.8·alphaMax)`) |
| `ARMIJO_SLOPE` | 1e-4 | `GridMapOptimizer` | Nocedal & Wright c₁ (cited by SPH17 §5) |
| `BACKTRACK` | 0.5 | `GridMapOptimizer` | mechanism SS15 §3.3/SPH17 §5; factor value `NOT IN ANY PAPER` |
| `MAX_BACKTRACKS` | 20 | `GridMapOptimizer` | ENGINEERING |
| `CONVERGENCE` | 1e-4 | `GridMapOptimizer` | ENGINEERING |
| `STALL_LIMIT` | 200 | `GridMapOptimizer` | ENGINEERING |
| `maxIterations` | 10 000 | `GridMapOptimizer` | ENGINEERING |
| `timeBudgetMilliseconds` | 500 000 | `GridMapOptimizer` | ENGINEERING |
| `FULL_NEWTON_PERIOD` | — | `GridMapOptimizer` | REMOVED — full projected Newton every iteration (SPH17 §5) |
| `DAMPING` | — | `GridMapOptimizer` | REMOVED |
| `RIDGE_FLOOR` | 1e-12 | `GridMapOptimizer` | `NOT IN ANY PAPER` |
| `STEP_CAP` | — | `GridMapOptimizer` | REMOVED |
| `foldClamp` factor | — | *(deleted)* | REMOVED |
| `REFERENCE_HEIGHT_FLOOR` | — | `GridMapOptimizer` | REMOVED — non-injective F references now throw, naming Stage 0 |
| reference reflection | — | `GridMapOptimizer#gatherTriangles` | REMOVED — negative reference determinant throws |
| degenerate-grid gate | — | `GridMapOptimizer#gatherTriangles` | REMOVED — a folded start throws |
| degenerate-Jacobian gate | — | `GridMapOptimizer#gatherTriangles` | REMOVED |
| `normalizeReferenceScale` | — | `GridMapOptimizer` | REMOVED — F is at grid scale (1 unit = 1 quad edge) by construction |
| crushed-patch threshold | — | *(deleted)* | REMOVED — RPP17: flip-free start + barrier need no local pre-repair |
| crushed-patch budget | — | *(deleted)* | REMOVED |
| `JACOBI_SWEEPS` | 6 | `SymmetricDirichletEnergy` | numerics for SPH17 §5 / Teran 2005 projection; value ENGINEERING |
| `JACOBI_TOLERANCE` | 1e-12 | `SymmetricDirichletEnergy` | ENGINEERING |
