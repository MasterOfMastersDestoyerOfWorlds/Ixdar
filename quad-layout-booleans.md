# QuadMixer — Boolean Blending of Quad Meshes: Pseudo-code

Reconstruction of the pipeline in Nuvoli et al. 2019, *QuadMixer: Layout Preserving Blending of
Quadrilateral Meshes* (TOG 38(6), Art. 180), written against the assumption that the following are
already available as black boxes:

| Available | Used for |
|---|---|
| cross-field generation (poly-vector / N-RoSy, with alignment constraints) | §4 field on the blending region |
| seamless parametrization | input to LCK21a, optional §5.4 |
| motorcycle graph tracing | §1 patch layout extraction |
| quad layouts via constrained T-mesh quantization (LCK21a) | optional replacement for §4–§6 (see §8) |
| layout embedding of motorcycle graphs | §1, §8 |

**Still needed (not in the list above):**
- exact/robust boolean on triangle meshes with provenance tracking — Zhou et al. 2016 mesh
  arrangements (`libigl::copyleft::cgal::mesh_boolean`);
- pattern-based quadrangulation of an n-sided patch with prescribed boundary subdivision —
  Takayama et al. 2014;
- an ILP/IQP solver (Gurobi in the paper);
- geodesic distance on a triangle mesh, LSCM, polychord refinement.

Notation follows the paper: `Q^A`, `Q^B` are the input pure-quad meshes, `T^*` triangle meshes,
`Q^0` the preserved quad part, `T^0` the region to be re-quadrangulated, `Q^1` its quadrangulation.

---

## 0. Top level

```
function QUADMIXER(Q_A, Q_B, op ∈ {∪, ∩, \}, params):
    L_A ← EXTRACT_PATCH_LAYOUT(Q_A)              # §1
    L_B ← EXTRACT_PATCH_LAYOUT(Q_B)

    (T_bool, prov) ← TRIANGULATE_AND_BOOLEAN(Q_A, Q_B, op)   # §2

    (Q_0, T_0) ← PATCH_RETRACTION(T_bool, prov, L_A, L_B, params.δ_r, params.prune)  # §3

    T_0 ← SMOOTH_BLEND_REGION(T_0, Q_0, params.smooth)       # §3.4

    (Q_0, T_0) ← ENFORCE_EVEN_BOUNDARY_PARITY(Q_0, T_0)      # §6 — do before §4/§5

    P ← PATCH_DECOMPOSITION(T_0, Q_0)                        # §4
    e ← SOLVE_SUBDIVISION(P, Q_0, params.α)                  # §5
    Q_1 ← QUADRANGULATE_PATCHES(P, e)                        # §7

    Q_out ← STITCH(Q_0, Q_1)                                 # boundaries match by construction
    Q_out ← POST_SMOOTH(Q_out, params.smooth)                # §7.3
    return Q_out
```

Typical parameters: `δ_r` ≈ 1–3 × average edge length of the retracted patches, prune threshold a
fraction of the average current patch area, `α = 0.5`, smoothing band = 5% of the bbox diagonal.

---

## 1. Patch layout extraction

Two interchangeable options; the paper makes no assumption on separatrix structure, so either is
fine. The motorcycle-graph package covers both if traces can be told to stop on first collision.

```
function EXTRACT_PATCH_LAYOUT(Q):
    S ← irregular vertices of Q                  # valence ≠ 4 (interior), ≠ 2 (boundary corner)
    if mode == SEPARATRICES:
        for each v ∈ S, for each of the 4 edge directions at v:
            trace edge-by-edge, straight (opposite edge at each regular vertex),
            stop at the next irregular vertex
    else if mode == MOTORCYCLE_GRAPH:
        propagate all traces simultaneously; each trace stops on first collision
        # fewer patches, but introduces T-junctions — acceptable here

    return connected components of Q ∖ traces    # each is a quad patch, possibly T-junctioned
```

Do **not** run layout regularization/optimization here (e.g. Bommes 2011, Tarini 2011): it would
alter the artist-authored edge flow, which is the whole point of preserving.

---

## 2. Triangulation + boolean

```
function TRIANGULATE_AND_BOOLEAN(Q_A, Q_B, op):
    for each quad q ∈ Q_A ∪ Q_B:
        split along the shorter diagonal → triangles (t1, t2)
        prov[t1] = prov[t2] = (source_mesh, quad_id(q), patch_id(q))

    T_bool ← MESH_BOOLEAN(T_A, T_B, op)          # Zhou et al. 2016

    for each triangle t ∈ T_bool:
        if t is an untouched copy of an input triangle:
            prov[t] ← inherited
        else:
            prov[t] ← NEW                        # intersection-region triangle
    return (T_bool, prov)
```

Two properties the exact boolean gives us and that the rest of the pipeline relies on:
1. vertices of new triangles lie **on** the input surfaces;
2. the quad region and the triangle region will share *exactly* the same boundary edges.

Track, per boolean output vertex, whether it is an original mesh vertex — needed to recompose quads.

---

## 3. Optimal patch retraction

Goal: the largest possible `Q^0` of untouched original quads, with a clean rectangular boundary
against the triangulated blending region `T^0`.

```
function PATCH_RETRACTION(T_bool, prov, L_A, L_B, δ_r, prune_frac):
    # 3.1 recompose quads
    surviving ← ∅
    for each original quad q:
        if both triangles of q are present in T_bool and unmodified:
            surviving.add(q)                     # merge the two triangles back into a quad

    # 3.2 retract by geodesic distance from the intersection curve
    C ← intersection curve(s) of T_bool          # edges between NEW and inherited triangles
    d ← GEODESIC_DISTANCE(T_bool, source = C)
    δ ← δ_r · average_edge_length(surviving)
    surviving ← { q ∈ surviving : min_{v ∈ q} d(v) > δ }

    # 3.3 rebuild the layout over what is left
    kept_patches ← { P ∈ L_A ∪ L_B : every quad of P ∈ surviving }
    loose        ← surviving ∖ quads(kept_patches)

    new_patches ← ∅
    while loose ≠ ∅:
        R ← LARGEST_INSCRIBED_RECTANGLE(loose)   # largest-rectangle-in-histogram, per partial patch
        if R = ∅: break
        new_patches.add(R); loose ← loose ∖ R

    # 3.4 prune slivers back into the blending region
    a_avg ← average quad-count of (kept_patches ∪ new_patches)
    for P ∈ new_patches:
        if |P| < prune_frac · a_avg:
            new_patches.remove(P); loose ← loose ∪ P

    Q_0 ← quads(kept_patches ∪ new_patches)
    T_0 ← T_bool ∖ triangles(Q_0)                # includes the triangles of every discarded quad
    assert boundary(Q_0) == boundary(T_0)
    return (Q_0, T_0)
```

`LARGEST_INSCRIBED_RECTANGLE` operates in the (i,j) grid coordinates of a partially preserved
patch: build, per column, the run-length of consecutive available quads, then apply the classic
largest-rectangle-in-a-histogram scan. Repeat until nothing of useful size is left.

### 3.4 Smoothing the blend region

```
function SMOOTH_BLEND_REGION(T_0, Q_0, s):
    d ← GEODESIC_DISTANCE(T_0, source = intersection curve C)
    d_max ← 0.05 · bbox_diagonal
    V ← { v ∈ T_0 : d(v) < d_max }
    repeat s.iterations:
        for v ∈ V:
            w(v) ← 1 − d(v)/d_max                # nearer the seam ⇒ moves more
            v ← (1 − w) · v + w · LAPLACIAN(v)
    hold fixed: all vertices on boundary(T_0)    # so Q_0 is untouched
    return T_0
```

---

## 4. Field-aligned patch decomposition of `T^0`

Target: every patch of `T^0` is disk-like, has only convex corners, and 3 ≤ #sides ≤ 6 (the
requirement of the pattern-based quadrangulator).

```
function PATCH_DECOMPOSITION(T_0, Q_0):
    # 4.1 pre-process
    T_0 ← ISOTROPIC_REMESH(T_0, freeze = triangles with an edge on boundary(T_0))

    # 4.2 field  [available package]
    X ← CROSS_FIELD(T_0, hard constraints = boundary edge directions, smooth interior)
    X ← interpolate per-face field to vertices, mod π/2

    # 4.3 corner classification on ∂T_0
    for each boundary vertex v:
        θ ← interior angle at v
        label(v) ← CONVEX  if θ < π − π/8
                   CONCAVE if θ > π + π/8
                   FLAT    otherwise
    # tracing degrees of freedom: concave → 2 directions, flat → 1, convex → 0

    # 4.4 split all concave corners
    G ← M4_TRACING_GRAPH(T_0, X)                 # 4 nodes/vertex, one per field direction
    while ∃ patch with a concave corner:
        candidates ← traces from unsplit concave corners, boundary → boundary, following X
        reject any candidate whose crossing with an accepted trace is TANGENTIAL
              (classify via the field index in M2; keep only ORTHOGONAL crossings)
        pick the shortest surviving candidate, prefer corners not yet split
        insert it; re-label the touched corners:
            single trace  : concave → convex + flat
            two traces    : concave → three convex
            trace on flat : flat → two convex
            two orthogonal traces crossing → four convex
            (a trace never splits a convex corner)
        recurse into the two resulting sub-patches

    # 4.5 reduce side count / fix topology
    for each patch p with #sides > 6 or not disk-like:
        dense ← all traces from flat boundary vertices
        iteratively drop the traces involved in tangential crossings, shortest-first
        insert a subset of `dense` splitting p until 3 ≤ #sides ≤ 6 and p is a disk
        # each split reduces the side count of both parts by ≥ 1

    return P = { patches of T_0 }
```

The tracing graph is the Kälberer/Campen construction: four copies of every vertex (one per field
direction), edges to 1-ring-plus-visibility-cone neighbours, propagation picks the most
field-aligned successor at each step.

No theoretical guarantee exists that all concave corners can be removed; the authors report never
hitting a failure. Keep a fallback (see §9).

---

## 5. Subdivision optimization (the integer program)

Variables live on **sub-sides**: each side of a patch is cut at every T-junction of the adjacent
patches, and one integer variable is assigned per resulting sub-side.

```
function SOLVE_SUBDIVISION(P, Q_0, α):
    E ← sub-sides of all patches in P
    B ← { e ∈ E : e lies on boundary(T_0) }

    # ideal sizes
    for each patch p touching the boundary:
        ê(p) ← average edge length of the Q_0 quads adjacent to p
    propagate ê to interior patches (BFS), then smooth ê across adjacent patches
    for e ∈ E: ê_e ← length(e) / ê(patch(e))

    minimize   (1−α) · Σ_{e ∈ E} (e − ê_e)²                             # isometry        (1)
             +   α   · Σ_{quad patches p} [ (Σ_{S0} e − Σ_{S2} e)²
                                          + (Σ_{S1} e − Σ_{S3} e)² ]    # regularity      (4)
    subject to
        e ≥ 1                            ∀ e ∈ E                                        (1)
        e = q_e                          ∀ e ∈ B   # q_e = #edges of Q_0 on that sub-side (2)
        Σ_{e ∈ sides(p)} e = 2·n_p ,  n_p ≥ 1 integer   ∀ p ∈ P   # even boundary sum    (3)
        e ∈ ℤ

    return solution e
```

Notes:
- (2) and (3) are hard: (2) makes `Q^1` stitch to `Q^0` without any gap, (3) is the necessary
  condition for a patch to admit a quadrangulation at all.
- `α = 0.5` is the reported sweet spot. `α → 0` gives uniform edge lengths and scattered
  singularities; `α → 1` gives clean grids and non-uniform edges.
- For large layouts, replace both squared terms with absolute differences (L1) and solve as an ILP —
  roughly 10× faster at ~150 variables, at the cost of concentrating error on individual variables.

---

## 6. Guaranteeing feasibility (the parity fix)

Constraint (3) is unsatisfiable if a connected component of `T^0` has a boundary loop with an odd
number of edges. This cannot happen when both inputs are genus 0, but can with higher genus.

```
function ENFORCE_EVEN_BOUNDARY_PARITY(Q_0, T_0):
    for each connected component K of T_0:
        odd ← { boundary loops ℓ of K : |edges(ℓ)| is odd }
        # |odd| is always even, since the total edge count of ∂K is even by construction
        while odd ≠ ∅:
            (ℓ1, ℓ2) ← the pair in `odd` joined by the shortest polychord in Q_0
            REFINE_POLYCHORD(Q_0, that polychord)   # Daniels et al. 2008: splits the strip,
                                                    # +1 edge on each of ℓ1 and ℓ2
            odd.remove(ℓ1); odd.remove(ℓ2)
    return (Q_0, T_0)
```

Such a polychord always exists: if no polychord ran from `ℓ1` to `ℓ2`, every polychord leaving `ℓ1`
would return to `ℓ1`, making `|edges(ℓ1)|` even — contradiction. Even loops can be treated as
virtually capped, reducing the general case to disjoint pairs.

Run this **before** §5 (it changes the `q_e` values feeding constraint (2)).

---

## 7. Final quadrangulation and stitching

```
function QUADRANGULATE_PATCHES(P, e):
    Q_1 ← ∅
    for each patch p ∈ P:
        map ∂p onto the boundary of a regular k-gon (k = #sides(p)), arc-length per side
        u ← LSCM(p, boundary constraints)             # Lévy et al. 2002
        M ← PATTERN_QUADRANGULATE(k-gon, subdivisions = e|_p)   # Takayama et al. 2014
        for each vertex of M: lift 3D position by barycentric interpolation in u
        Q_1 ← Q_1 ∪ M
    return Q_1

function POST_SMOOTH(Q, s):
    TANGENT_SPACE_SMOOTH(Q, region = Q_1 ∪ 1-ring)    # redistributes distortion, keeps the surface
    LAPLACIAN_SMOOTH(Q, region = band around C, weight ∝ 1 − d/d_max)
    return Q
```

`STITCH` is a merge of coincident boundary vertices: constraint (2) guarantees identical vertex
counts along every shared sub-side, so the result is a closed two-manifold pure quad mesh.

---

## 8. Where LCK21a could substitute

LCK21a solves a different problem (coarse conforming layout from a seamless parametrization on a
whole surface), so it is not a drop-in for §4–§5, but two uses are natural:

**(a) Pre-process — normalizing inputs.** If `Q^A` and `Q^B` come from automatic pipelines, running
LCK21a on each gives clean coarse layouts and a controllable base complex, which improves §3
(bigger rectangles survive retraction). Note the paper deliberately *avoids* re-layouting
artist-authored inputs.

**(b) Alternative decomposition of `T^0`.** Instead of §4's cross-field tracing:

```
u ← SEAMLESS_PARAMETRIZATION(T_0, aligned to boundary(T_0) as feature curves)
T ← MOTORCYCLE_GRAPH(u, stopping criterion of LCK21a §3, angular bound α_dev)
q ← SOLVE_ILP(T)             # LCK21a §5, with boundary arcs treated as feature lines (§4.4)
P ← LAYOUT_EMBEDDING(T, q)
```

Two caveats before doing this:
1. **The boundary is not free.** LCK21a minimizes total layout-strip length subject to validity and
   angular bounds; it has no mechanism to force a *prescribed* number of edges along each boundary
   sub-side. You would still need §5's ILP afterwards, or you would need to fold constraint (2)
   into LCK21a's ILP as additional equalities on the boundary arcs. The latter is the more
   principled merge: both are integer programs over arc lengths, and LCK21a's consistency
   constraints (its Eq. 2) are structurally the same object as §5's per-patch parity constraint.
2. **Patch shape.** LCK21a returns rectangular (4-sided) patches by construction, which is
   *stronger* than what Takayama needs — so if you go this route you can quadrangulate each patch
   with a plain regular grid and skip §7's LSCM + pattern step entirely, provided opposite sides
   received equal quantization.

The blocker is that `T^0` is a small, thin, high-curvature strip; obtaining a good seamless
parametrization there is exactly the situation §4's discussion argues against (the paper explicitly
avoids requiring a bijective parametrization of the blending patch because it is fragile on thin
boolean off-cuts). Worth trying, but keep §4 as the fallback.

---

## 9. Failure modes to handle

| Condition | Behaviour |
|---|---|
| `Q^0 = ∅` (blend band swallows both meshes) | no valid patch decomposition — fall back to full remeshing of `T_bool` |
| Concave corner cannot be split without a tangential crossing | relax the heuristic, allow the tangential trace, accept elongated patches |
| Very different input resolutions | Catmull-Clark-subdivide the coarser input before §2 |
| Sharp features (esp. for `\`) | not preserved; would require feature alignment in the cross-field, features as forced traces in §4, and pinned vertices in the smoothers |
| Small motion of one input ⇒ jumpy tessellation | randomly perturb the intersection placement, keep the best result under `0.3·Q_t + 0.7·Av_d` (quad quality, average absolute valence deficit) |

---

## 10. Complexity / timing sanity check

Runtime scales with the triangle count of the *blending band*, not the whole model. Reported on an
i7-8750H, single-threaded, Gurobi for §5: ~0.25–3 s total for typical animal/CAD merges
(boolean ≈ 100–800 ms, tracing ≈ 80–800 ms, ILP ≈ 25–1300 ms, quadrangulation ≈ 40–700 ms);
worst case in the paper 14.9 s, for a self-merge of the fertility model where the band covers most
of the surface.





----


how can we do the quad mixer algorithm and maintain quad layouts properties ( all four sided with minimal singularities)

I think the answer is at the boundary re-layout we need to have the dissolved patches ( the ones that hte paper triangulates for the mesh boolean operation), be not disolved just by those that touch the boundary but till a full ring around the mesh is found  that way on both input meshes you have a loop where each node is a tjunction on the quad layout, here there are some problems if we want to keep the combined mesh as a quad layout: 1. theloop of mesh A could have a different number of tjunctions than the loop of mesh B this could be solved by adding loop cuts  from on of the edges between two tjunctions following the grid until it reaches back a round to the other side of the loop, then we have +2 tjunctions on the loop, repeating this we could make loop A have the same number of tjunctions as loop B within  -1 tjunction. then to get odd/even parity we can introduce a 3 valence singularity as a y split in the boundary region. the question is would this always work? maybe I think depends on the topology of the two meshes.

I think the other option would be to redo the quad layout pipeline with some forces singularities on the border, e.g. at every tjunction for each loop we make a 3 valence singularity in the cross field, then resolve, I think that might work better but idk if we can force there to be no singularities otehr than the ones we desire on the oops