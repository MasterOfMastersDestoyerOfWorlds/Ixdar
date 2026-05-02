# Cross-Field Directional Alignment — Paper Sources

Code in this package replicates the cross-field-stage directional-alignment pipeline of **Lyon 2021 Table 1** (ROCKERARM row, page 311), which uses **BZK09**'s mixed-integer cross-field optimization with **CIE*16**'s directional constraints. Principal-curvature estimation follows **ACDLD03** §2.1.

Every class, method, and formula in `alignment/` carries an inline citation to the section/equation it implements. Use this file as the master index when auditing the implementation against the papers.

---

## Pipeline overview

```
PrincipalCurvatureField   ←  ACDLD03 §2.1 eq.(1) + CIE*16 §3.2 ¶1
        │
        ▼
GeodesicCurvature         ←  CIE*16 §3.2 ¶3
        │
        ▼
SmoothRegions             ←  CIE*16 §3.1 (theory) + §3.2 ¶4 (significance)
        │
        ▼
DirectionalConstraints    ←  CIE*16 §4.1
        │
        ▼
FaceRosyField             ←  feeds Phase A solver (BzkSystem + BzkAdaptiveSolver + GreedyRounding)
                              which is a faithful BZK09 §4 / Campen-thesis §4.2 eq.(4.6) implementation.
```

---

## Primary references (cite these in code)

### CIE*16

> M. Campen, M. Ibing, H.-C. Ebke, D. Zorin, L. Kobbelt.
> *Scale-Invariant Directional Alignment of Surface Parametrizations.*
> Computer Graphics Forum 35:5 (2016) — Eurographics Symposium on Geometry Processing.

PDF: `~/Downloads/campen2016sid.pdf`

Sections we depend on:

- **§3.1** *Definition* — smooth region as connected component where κ^g(x) < |κ_max(x)|.
- **§3.2** *Discretization & Implementation* — concrete algorithms:
  - **¶1 "Principal Curvature"** — robust normal estimation via separate face-normal averaging, then `n = a_min × n' × a_min`.
  - **¶2** — line-field representation: one vector per face for `a_min`, `a_max`.
  - **¶3 "Geodesic Curvature"** — per-edge κ(e) via Levi-Civita transport on barycentric dual.
  - **¶4 "Significance"** + **eq.(3)** — significance angle ∠F = max over a_max-streamlines of ∫κ_max ds.
  - Page 6 col 2 *"Topology"* — cyclic regions, turning numbers, ∠F = 360° shortcut.
- **§4.1** *Cross Field Constraints* — hard {0, ∞} weight inside filtered smooth regions.

### BZK09

> D. Bommes, H. Zimmer, L. Kobbelt.
> *Mixed-Integer Quadrangulation.*
> ACM Transactions on Graphics 28:3 (2009).

PDF: `~/Downloads/bommes_zimmer_2009_siggraph_011.pdf` · text extract: `/tmp/bzk09.txt`

Sections we depend on (for cross-field stage; full set of refs in Phase A code):

- **§4.1** *Measuring cross field smoothness* — energy formulation eq.(1):
  `E = Σ_e (θ_a − θ_b + κ_e + (π/2)·m_e)²`.
- **§4.2** *Finding a smooth, interpolating cross field* — Mixed-Integer Formulation, the cycle-space basis with non-tree-edge integers.
- **§3** *Salient Curvature Directions* — for context (the *previous* directional detector that PATCH-96 half-implemented; CIE*16 supersedes it).
- **§6, Figure 11** — confirms BZK09 yields 36 singularities on ROCKER ARM.

### ACDLD03

> P. Alliez, D. Cohen-Steiner, O. Devillers, B. Lévy, M. Desbrun.
> *Anisotropic Polygonal Remeshing.*
> ACM Transactions on Graphics 22:3 (2003).

PDF: `~/Downloads/ACDLD03.pdf`

Sections we depend on:

- **§2.1** *Robust 3D Curvature Tensor Estimation* — eq.(1):
  `T(v) = (1/|B|) Σ_e β(e) |e ∩ B| ē · ē^T`.
  Default radius `r_geo = bbox/100` per §2.1 ¶3.
- **§2.3** *Tensor Field Smoothing* — Gaussian filter on the 6 unique tensor
  coefficients to denoise. ACDLD03 does this in 2D conformal parameter
  space; we approximate via repeated dual-graph Laplacian averaging. Default
  8 iterations (`PrincipalCurvatureField.DEFAULT_SMOOTH_ITERS`).
- **§2.4** *Tensor Field Umbilic Points* — for context (umbilic = isotropic regions where principal directions are undefined).
- **§2.5** *Taking Care of Features* — for CAD meshes with sharp dihedral
  edges. ¶1: tensor integration BFS must be **clipped at feature edges** so
  T(f) sees only one side of a discontinuity ("a one-sided evaluation is
  therefore recommended"). ¶2: tensor smoothing must apply the **same
  clipping** ("avoid 'contamination' between separate regions"). Without
  this, ACDLD03's tensor on flat-region faces near sharp edges becomes
  rank-1 (sharp-edge dihedral dominates), producing spurious
  principal-direction estimates. Default feature threshold 30° dihedral
  angle (`PrincipalCurvatureField.DEFAULT_FEATURE_DIHEDRAL_DEG`).

### Lyon 2021

> M. Lyon, M. Campen, L. Kobbelt.
> *Quad Layouts via Constrained T-Mesh Quantization.*
> Computer Graphics Forum 40:2 (2021) — Eurographics 2021.

PDF: `~/Downloads/Computer Graphics Forum - 2021 - Lyon - Quad Layouts via Constrained T-Mesh Quantization.pdf` · text extract: `/tmp/lyon.txt`

Sections we depend on:

- **§7 ¶1** — confirms cross-field method is "BZK09 with directional constraints as proposed by [CIE*16]" for §7 + §7.1, including ROCKERARM.
- **Table 1, p. 311** — ROCKERARM at 20088 faces yields 36 singularities at α=15°.

---

## Secondary references (context only — not cited in code)

### Campen 2014 thesis

> M. Campen, *Quad Layouts — Generation and Optimization of Conforming Quadrilateral Surface Partitions*. RWTH Aachen, 2014.

PDF: `~/Downloads/quad-layouts-generation-and-optimization-of-conforming-1b7xbct85i.pdf`

Relevant sections:

- **§4.2** *Cross Fields*, eq.(4.6) — re-derivation of BZK09 §4 optimization (predates CIE*16; uses BZK09 §3 unmodified).
- **§4.2.1** *Optimization* — uses BZK09's greedy MI solver.
- **§4.2.2** *Principal Direction Alignment* — uses BZK09 §3 directly.
- **§4.4 ¶4 ("Noise")** — explicitly warns about the failure mode CIE*16 fixes:
  > "The Gaussian curvature variations on noisy surfaces can lead to an excessively large number of cross field singularities."
  This is exactly the mode PATCH-96 hit (1019 sing on rocker-arm).
- **Theorem 4.2.1** — Poincaré–Hopf relation `Σ index = χ(M)`. Used as the invariant in unit tests (`sumIdx4 = 4·χ` for cross fields).

### EGKT08

> Eppstein, Goodrich, Kim, Tamstorf 2008. *Motorcycle Graphs: Canonical Quad Mesh Partitioning*.

Relevant for the *motorcycle-graph stage* (Lyon §3), not the cross-field stage. Not cited from this `alignment/` package.

---

## Citation style in source

Top of each `.java` file:

```java
/**
 * <h2>Citations</h2>
 * <ul>
 *   <li><b>CIE*16 §3.2 ¶1</b> "Principal Curvature" — robust normal estimation.</li>
 *   <li><b>ACDLD03 §2.1 eq.(1)</b> — tensor integration formula.</li>
 *   <li><b>Lyon 2021 §7 ¶1</b> — confirms BZK09 + CIE*16 is what Lyon uses for ROCKERARM.</li>
 * </ul>
 *
 * <p>Master citation index: see {@code alignment/PAPERS.md}.
 */
```

Inline at every formula:

```java
// ACDLD03 §2.1 eq.(1):  T(v) = (1/|B|) Σ_e β(e) |e ∩ B| ē·ē^T
// CIE*16 §3.2 ¶3:      κ(e) = min‖a_min(f1) ± a_min(f2)‖ / d
// CIE*16 §3.2 ¶4:      ∠F = max_streamlines max_substring |Σ_i β_i|
private static final double DEFAULT_SIGNIFICANCE_DEG = 70.0;  // CIE*16 §3.2 ¶4 — "we used a setting of 70°"
```
