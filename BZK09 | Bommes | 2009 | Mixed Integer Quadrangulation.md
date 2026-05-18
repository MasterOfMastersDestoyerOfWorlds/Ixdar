Mixed-Integer Quadrangulation
David Bommes

Henrik Zimmer

Leif Kobbelt

RWTH Aachen University

(a)

(b)

(c)

(d)

Figure 1: Quadrangulation example: (a) A sparse set of conservatively estimated orientation and/or alignment constraints is selected on
the input mesh by some simple heuristic or by the user. (b) In a global optimization procedure a cross field is generated on the mesh which
interpolates the given constraints and is as smooth as possible elsewhere. The optimization includes the automatic generation and placement
of singularities. (c) A globally smooth parametrization is computed on the surface whose iso-parameter lines follow the cross field directions
and singularities lie at integer locations. (d) Finally, a consistent, feature aligned quadmesh can be extracted.

Abstract

1

We present a novel method for quadrangulating a given triangle
mesh. After constructing an as smooth as possible symmetric cross
field satisfying a sparse set of directional constraints (to capture the
geometric structure of the surface), the mesh is cut open in order to
enable a low distortion unfolding. Then a seamless globally smooth
parametrization is computed whose iso-parameter lines follow the
cross field directions. In contrast to previous methods, sparsely distributed directional constraints are sufficient to automatically determine the appropriate number, type and position of singularities
in the quadrangulation. Both steps of the algorithm (cross field
and parametrization) can be formulated as a mixed-integer problem
which we solve very efficiently by an adaptive greedy solver. We
show several complex examples where high quality quad meshes
are generated in a fully automatic manner.

The problem of generating high quality quad meshes from unstructured triangle meshes has received a lot of attention recently. The
reason for this interest is that quad meshing converts raw geometric
data into a higher representation which effectively supports sophisticated operations like texturing and shape modification. The difficulties in quad meshing arise from the fact that the quality criteria
are diverse and their optimization often requires the consideration
of global dependencies. The most common quality aspects are:

CR Categories: I.3.5 [Computational Geometry and Object Modeling]: Hierarchy and geometric transformations
Keywords: remeshing, quadrangulation, parametrization, direction field, singularities, mixed-integer

Introduction

1. Individual Element Quality: Each quad should be close to
a rectangle or square, i.e. the four corner points should be
coplanar, opposite edges should have equal length and the
four interior angles should be 90 degrees.
2. Orientation: Away from flat or umbilic points on the surface,
mesh edges should be orthogonal to the principal curvature
directions such that the dihedral angle across edges captures
these curvatures in a natural way.
3. Alignment: Sharp features of the surface should be explicitly
represented by a sequence of mesh edges in order to minimize
the Hausdorff-distance between triangulation and quadrangulation and to prevent normal noise.
4. Global Structure: Singularities, i.e. vertices with valence
6= 4, are necessary to compensate for the Gaussian curvature. Their number and position must be chosen carefully to
capture the global geometric structure since otherwise the element quality and orientation is heavily affected.
5. Semantics: In some applications additional requirements
emerge from the intended usage of the 3D model and cannot
be derived from the geometry alone. In finite element simulation of deformation processes, e.g., the optimal mesh depends

on the rest geometry as well as on the external forces and constraints.
A quadrangulation algorithm should optimize the output simultaneously with respect to all of these criteria. However, while element quality, orientation and alignment are rather simple, the global
structure is much more difficult to handle. Consequently, we focus
on automatically finding good singularity positions which optimize
the global structure of the quadrangulation. Although the overall
method is designed to run fully automatic, the user can still manually override some decisions, e.g. by manually shifting a singularity
wherever it is necessary to take semantical side-conditions into account (see criterion 5).
Many recent methods use smoothed (discrete) principal curvature
directions to guide the quad meshing. The problem with these approaches is that the final singularity positions are effectively determined by the local smoothing operator applied to the initial curvature estimates. Especially in flat or umbilic regions where the initial
directions have a random orientation, clusters of singularities may
occur. Another problem is oversmoothing which may destroy the
original orientation information in feature regions.
To overcome these problems we propose to select only the most relevant and dominant directions as depicted in Figure 1 (a), which can
be detected, e.g., by conservative thresholding of some anisotropy
measure or by manual selection. Starting with these sparsely distributed direction constraints we then search for the smoothest interpolating cross field. The singularities in this interpolating cross
field are mostly due to the surface metric deviating from a planar
configuration and not caused by incompatible constraints.
In the second phase of our algorithm the smooth cross field is used
as input for a global parametrization method. We cut the mesh open
such that we create a surface patch with a disk-like topology where
all cross field singularities lie at the boundary. Subsequently, we
can compute two piecewise linear scalar fields u and v whose gradients follow the given cross field. Finally, a consistent quadrangulation can be extracted since by construction the parametrization
is compatible at the cuts and all singularities are mapped to integer
positions along the boundary of the parameter domain.
In both steps of the algorithm the task can be formulated in terms of
a mixed-integer problem. These are linear problems where a subset
of the variables is continuous (∈ R) and the others are discrete
(∈ Z). In Section 2 we therefore present a greedy solver for this
class of problem.

> **Pipeline implementation map.**
> Top-level driver — [QuadLayoutEngine.pipeline](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/QuadLayoutEngine.java#L75-L89).
> Stage 1 — cross field — [CrossField.build](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java#L232-L424).
> Stage 2 — singularities — [CrossField.extractSingularities](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java#L1535-L1579).
> Stage 3 — seamless parameterization — [SeamlessParameterization.build](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessParameterization.java#L243-L356).
> Optional MC19 exact-seam projection — [SeamlessProjector.project](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/exact/SeamlessProjector.java#L72-L189).

1.1

Related Work

A lot of effort has been spent in the last years to compute high quality quadrangulations. Since there are several nice surveys [Alliez
et al. 2005; Hormann et al. 2007], we will discuss here only the
most related works. Generally, there are two classes of approaches,
namely explicit quadrangulations and parametrization based techniques. Examples of explicit approaches are [Alliez et al. 2003;
Marinov and Kobbelt 2004] which trace curves along the principal curvature directions or [Lai et al. 2008] which iteratively transforms a triangular mesh into a quad-dominant mesh. For all such
methods it is difficult to obtain coarse meshes consisting of quads
only. Most structure aligned parametrization techniques are guided
by vector or cross fields usually arising from estimated principal
curvature directions [Cohen-Steiner and Morvan 2003] or a manual
design process [Zhang et al. 2006; Fisher et al. 2007]. Especially
cross fields are promising since they can capture singularities of
fractional index which naturally arise in quadrangulations.

can be seen as four coupled vector fields. Consequently, smoothing algorithms must be able to handle the discretely switching vector assignments, which can be achieved by a
non-linear angle formulation like in [Hertzmann and Zorin 2000].
However, such methods often get stuck in local minima and the
result strongly depends on the initial solution.
Cross fields

Recently Ray et al. proposed a formalism to handle N-symmetry
direction fields [Ray et al. 2008b] in a linear manner, enabling the
computation of globally smooth solutions. However, all singularity
positions must be prescribed by the user. In contrast we search for
singularity positions which enable the smoothest cross field for a set
of sparse directional constraints. Besides the automatic singularity placement, another contribution of our approach is the smooth
handling of multiple directional constraints, not possible in [Ray
et al. 2008b]. Their user-given hard angle constraints already fix
the smoothness between multiple constraints, and do not exploit
that the orientation of cross fields is invariant w.r.t. rotations by
multiples of 90 degrees and that there might be rotations leading
to a smoother cross field.
In the case of highly detailed geometry a smooth cross field naturally requires lots of singularities which can be prevented by the
smoothing algorithm of Ray et al. [Ray et al. 2008a]. In contrast to
their approach, we interpret the cross field as an infinitely fine quadrangulation and compute all necessary singularities. The merging
of singularities is later controlled by the parametrization which can
perform singularity cancellations w.r.t. the given target edge length.
can be divided
into two classes, namely high-level methods where the quad orientation is controlled by a rough patch layout and low-level methods
where a desired orientation is given per triangle. Dong et al. used
the Morse-Smale complex of Laplacian eigenfunctions [Dong et al.
2006] to derive high-level patch layouts. Their method was extended in [Huang et al. 2008] to enable the control over singularity
positions, size, orientation and feature alignment. However, computing coarse high-quality results is still involved and requires an
experienced user. Tong et al. used high-level user-designed singularity graphs to enrich the space of harmonic one-forms and compute globally smooth parametrizations [Tong et al. 2006]. Once
a suitable singularity graph is provided by the user, these harmonic parametrizations produce nice quadrangulations. By allowing affine transition functions and optimizing the charts, [Bommes
et al. 2009] improved the distortion of the parametrization.
Structure aware Parametrization techniques

Ray et. al proposed a fully automatic non-linear parametrization
technique which is guided by a low-level vector field and assumes
a single chart for each triangle [Ray et al. 2006]. Kälberer et al.
developed a linear algorithm by mapping a cross field to a single
vector field on a branched covering [Kälberer et al. 2007]. As in
our parametrization, a triangle based energy is optimized and the
singularity positions are completely defined by the input cross field.
Their intermediate parametrization, i.e. the integral of the hodge decomposed vector field which is incompatible at the cuts, is exactly
the continuous solution of our mixed-integer formulation. However, ensuring compatibility at the cuts is done in a different way.
Instead of rounding the coefficients of the transition functions at
once, we apply our proposed greedy strategy which improves the
resulting quality. A more detailed comparison will be given later in
Section 6.
In the setting of conformal
parametrizations, cone singularities as introduced by Kharevych et
al. [Kharevych et al. 2006] can be placed in a greedy manner at
the local extrema of discrete conformal scaling factors [Ben-Chen
et al. 2008]. These positions can be further improved by a non-

Automatic cone singularities

linear Gauss-Seidel solver [Springborn et al. 2008]. Both methods
are designed to compute conformal parametrizations with a small
number of cone singularities and lower distortion. Unfortunately,
even when restricting to cross field cone singularities, the resulting
positions are often not sufficient for structure aligned parametrizations where singularities are additionally induced by the desired orientations, e.g. the leftmost red singularity lying on the flat part of
the fandisk in Figure 1. Furthermore, supporting orientational constraints isn’t straight forward in this formulation.

1.2

Contributions

We propose an adaptive greedy solver for mixed integer problems
which increases the computation time compared to a continuous
linear system solver only moderately. This is achieved by iterative
rounding combined with local Gauss-Seidel updates in order to reduce the local residui.
We formulate the quadrangulation problem as a two-step process,
cross field generation and global parametrization, which both reduce to a mixed-integer problem.
Our cross field generator is able to take sparsely scattered as well
as densely distributed orientation constraints into account. By
smoothly interpolating between the constraints the system can automatically place singularities at geometrically meaningful locations.
Our new globally smooth parametrization technique allows us to
generate seamless quad meshes while satisfying various constraints
like orientation, alignment, and integer singularity locations. An
optional anisotropic stretch metric allows us to trade squareness of
the quads for improved feature alignment.

2

A Greedy Mixed-Integer Solver

> **Code (§2 — Greedy MI Solver).** The cross-field stage uses
> [AdaptiveSolver.solveAfterRounding](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/solver/AdaptiveSolver.java#L75-L131)
> driven by [SmoothEnergySystem.solveGreedyMIP](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/SmoothEnergySystem.java#L163-L202).
> The seamless stage uses a different greedy loop —
> [SeamlessParameterization.runGreedyIntegerRounding](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessParameterization.java#L363-L431) —
> backed by [IncrementalCholeskySolver](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/solver/IncrementalCholeskySolver.java)
> for rank-1 LDL updates after each pin, instead of running the local-GS / CG / direct ladder.

The minimization of a quadratic energy E(x1 , . . . , xn ) is called
an integer problem if x ∈ Zn . In this paper we encounter more
general problems where some of the unknown variables x1 . . . xk
are integers and the others xk+1 . . . xn are real numbers. Integer
and mixed-integer problems are usually very hard to solve exactly,
see [Floudas 1995; Gorry et al. 1970] for more details. Hence, a
common way to find an approximate solution is to first compute the
continuous minimizer, which simply requires the solution of the linear system {∂E/∂xi = 0 | i = 1 . . . n}. Then the first k variables
of the solution vector are rounded to the nearest integer and a new
minimizer is computed by assuming these rounded values x1 . . . xk
to be constant.
While this direct rounding is a common practice, we observed that,
depending on the number of integer variables and on their mutual
dependencies, the obtained solution can deviate significantly from
the true solution. Hence, we propose an alternative approach which
we call greedy rounding. The idea is to round the integer variables
one at a time, followed by an immediate update of the continuous
part of the solution.
Let x0 be the continuous solution to the linear system and let xi ,
i ≤ k be the variable which causes the smallest absolute error if we
round it to the nearest integer. Then we can set xi to this integer
value and update the linear system by assuming xi as constant. We
solve again for the remaining variables x1 and continue to eliminate
in each step that variable which causes the least round-off error until
all variables x1 . . . xk have an integer value.
The motivation for this approach is based on the assumption that
small round-off errors will only have little impact on the final solution and that by recomputing the free variables after every rounding
step we will compensate these errors.

> **Code.** Pick-and-pin in the cross-field stage —
> [SmoothEnergySystem.buildBatchCandidates](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/SmoothEnergySystem.java#L212-L251)
> picks the period jump with the smallest |x − round(x)| (and a non-overlapping batch around it
> via [selectRoundingBatch](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/SmoothEnergySystem.java#L269-L305)).
> In the seamless stage the analogue is the inner loop of
> [runGreedyIntegerRounding](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessParameterization.java#L400-L430)
> which scans `dofIsInteger` and pins `bestDist = min |x − round(x)|`.

The obvious drawback of greedy rounding is that we have to
solve k (= number of integer variables) + 1 linear systems, which
increases the computation time prohibitively. However, as we said
above, small round-off errors only have a small impact on the solution which can be exploited to design an efficient adaptive solver.

2.1

Adaptive Mixed-Integer Solver

The first idea is to use an iterative solver like the Conjugate Gradient
or BiCG (for non-symmetric matrices) which after each rounding
reuses the previous solution as the initial value. But we can do even
better. We observed that for sparse matrices, typically arising in the
case of triangle meshes, it is often sufficient to update a small local
set of variables. In this context local means that we have a short
path in the dependency graph of all variables. Therefore, we start
with a local Gauss-Seidel iteration. That means after variable xi is
rounded we push all variables whose Gauss-Seidel update depends
on xi into a queue. These are exactly the nonzero elements of the
row Ai . Now in each iteration step we fetch the first element from
the queue, say xk , and recompute the local residuum
r k = bk −

n
X

Akj xj

j=1

If |rk | is larger than a prescribed tolerance, e.g. 10−6 , we update
the variable xk → xk − rk /Akk and push all variables which depend on xk onto the queue. The iteration terminates if the queue is
empty, i.e. all local residui are within the prescribed tolerance, or a
maximum number of iterations is reached.
> **Code (§2.1 — local Gauss-Seidel seeded by the rounded variable).**
> Implemented as [AdaptiveSolver.localGaussSeidel](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/solver/AdaptiveSolver.java#L94-L101)
> (loop body inside the file — search for `localGaussSeidel`). The seed set is collected by
> [AdaptiveSolver.collectAffectedPatch](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/solver/AdaptiveSolver.java)
> (used both for the GS frontier and for batching disjoint roundings in
> [SmoothEnergySystem.selectRoundingBatch](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/SmoothEnergySystem.java#L269-L305)).

Algorithm 1 Local Gauss-Seidel
1: xi = round(xi )
2: push nonzero(Ai ) into queue
3: iter = 0
4: while ( (not queue empty) and (iter < maxiter) ) do
5:
iter = iter + 1
6:
xk = pop( queue
P )
7:
r k = bk − n
j=1 Akj xj
8:
if (|rk | > tolerance) then
9:
xk = xk − rk /Akk
10:
push nonzero(Ak ) into queue
11:
end if
12: end while
If the local Gauss-Seidel solver does not converge (iter ≥
maxiter), we first switch to a more global Conjugate Gradient
solver and finally, if it is necessary, to a sparse Cholesky solver
[Chen et al. 2006]. Such time consuming Sparse-Cholesky computations are only necessary when a variable with large impact is
rounded. Our experiments showed that this adaptive solver is more
efficient than restricting to pure iterative solvers. More detailed
statistics about the solver are given in Section 6.

> **Code (adaptive ladder).** The three-rung escalation
> (local GS → CG → sparse Cholesky) lives in
> [AdaptiveSolver.solveAfterRounding](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/solver/AdaptiveSolver.java#L75-L131);
> the methods are enumerated in [AdaptiveSolver.Method](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/solver/AdaptiveSolver.java#L475-L481).
> The direct fallback uses CHOLMOD-style AMD reorderings via
> [DirectSolver.solve](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/solver/DirectSolver.java) and
> [SolverPermutation](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/solver/SolverPermutation.java).
> Note: this ladder is used only for the **cross-field** stage; the
> **seamless** stage skips local-GS / CG and goes straight to incremental
> sparse Cholesky updates per pin
> ([IncrementalCholeskySolver.pinDof](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/solver/IncrementalCholeskySolver.java)),
> consistent with BZK09 §6's observation that parametrization roundings
> have global impact.
In the future we plan to publish our implementation of the presented
solver which can be applied to arbitrary quadratic mixed-integer
problems. For maximum flexibility, we added the handling of linear constraints, by internally eliminating a variable for each independent constraint.

3

Salient Curvature Directions

> **Code (§3 — salient curvature directions).** Implemented as
> [CrossField.applyCurvatureConstraints](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java#L786-L982).
> Multi-radius shape-operator integration over geodesic disks —
> [CrossField.integrateCurvatureTensor](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java#L1053-L1207).
> Stability/anisotropy interval test —
> [CrossField.curvatureIntervalStatus](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java#L1264-L1288)
> and [CrossField.directionJitter](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java#L1301-L1320).
> Paper parameters (τ_min, K, r0, r1, w = h/4) —
> [CrossField fields tauMin / curvatureScaleK / radiusStartMul / radiusRatio / targetEdgeLengthFractionOfBounds](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java#L131-L196).
> Defaults deviate from the paper (τ_min = 0.9 vs 0.8; single-radius
> integration at r = h, as in libigl); the field doc strings record both
> the paper value and the empirical-tuning rationale.

In the vicinity of flat or umbilic points, the principal curvature directions are ill defined. Consequently, using the principal curvature
directions as a dense guiding field for quadrangulation leads to suboptimal results. Typical artifacts are noisy directions with badly

4

d1

d2



d4

d3

e
(a)

(b)

Figure 2: (a) The four cross field directions in a triangle are
parametrized by the angle θ w.r.t. a local reference edge e. (b)
Depicts a smooth cross field in the vicinity of a cube corner, where
the red arrows reflect the corresponding period jumps.

placed singularities or even clusters of unnecessary singularities.
Generally, these artifacts cannot be removed by cross field smoothing algorithms, since the configurations often form local minima.
Therefore, in contrast to other methods, we aim at finding the
smoothest cross field, interpolating only sparse directional constraints that can be found in a reliable manner.
The directions we want to identify are in the spirit of feature lines,
as computed in [Hildebrandt et al. 2005]. However in our case a
simple heuristic which robustly identifies parabolic regions is sufficient. Since parabolic regions are equipped with a well-defined
orientation they are the best candidates to guide a quadrangulation. Parabolic regions can be identified by measuring the relative
anisotropy of the principal curvatures
τ =

||κmax | − |κmin ||
∈ [0, 1]
|κmax |

which is defined to be zero, if κmax is zero.
Computing meaningful curvatures on discrete triangle meshes is
involved. A common technique is evaluating the shape operator
[Cohen-Steiner and Morvan 2003] of a geodesic disk near a point
p. But depending on the radius r we will get different estimates.
To achieve a more stable result we compute for each point a set of
shape operators Sr with different geodesic radii r ∈ [r0 , r1 ] and
select the most promising one with a simple heuristic. A shape
operator Sr is said to be valid if all shape operators in the interval
[r − w, r + w] have a relative anisotropy larger than a prescribed
threshold τmin and a mean curvature larger than K to exclude
almost flat regions. For all points which provide a valid shape
operator, we add a directional constraint. If there are multiple
valid candidates for a single point we choose the one with the
most stable direction, i.e. the one with the minimal angle deviation
within its interval.

Smooth cross fields

> **Code (§4 — N-RoSy formalism).** Per-face angle θ_f and per-edge
> period jump p_e are stored as
> [CrossField.theta](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java#L101) and
> [CrossField.periodJump](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java#L109).
> Per-face local frames (faceX, faceY) and per-edge transport κ_ij are
> [built up-front in CrossField.build](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java#L260-L359).
> The sign convention for κ is "angle of face A's x-axis in face B's
> frame after parallel transport across the shared edge" — chosen so the
> smoothness energy reads `(θ_A + κ_AB + (π/2)·p_AB − θ_B)²`.

In this section we will use the elegant formalism for N-Symmetry
direction fields [Ray et al. 2008b] where a cross field (N = 4)
on a triangle mesh M = (V, E, F ) is defined by an angle-field
θ : F 7→ R assigning a real number to each face and a period-jump
field p : E 7→ Z assigning an integer to each edge. The main idea
is to use the angles θ to determine a single unit length vector-field
which then extends to a symmetric cross field by applying three rotations of π2 as shown in Figure 2 (a). Because a cross consists of
four vectors between neighboring triangles it is necessary to identify which vector of the first cross is associated with which vector
of the second cross. All these topological issues are handled by the
period-jumps, as illustrated in Figure 2 (b) for a smooth cross field
near the corner of a cube. In this section we will summarize only
the discrete results about cross fields that we need in this paper. For
more details see [Ray et al. 2008b].

4.1

Measuring cross field smoothness

> **Code (§4.1 — smoothness energy E_smooth).** Assembled as the rows
> of the design matrix in
> [SmoothEnergySystem.assemble](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/SmoothEnergySystem.java#L92-L161)
> with residual `(θ_i + κ_ij + (π/2)·p_ij − θ_j)`. Hard θ̂-constraints
> are folded into the RHS via elimination (`fixedVariables` flag); only
> non-boundary edges contribute rows. The reduced normal equations are
> solved by the §2.1 adaptive ladder
> [SmoothEnergySystem.solveRelaxed](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/SmoothEnergySystem.java#L313-L363).

After fixing the topology, measuring the smoothness of a cross field
reduces to measuring the smoothness of one of the four rotation
symmetric vector-fields.
The smoothness of a unit vector-field can be measured as the integrated squared curvature of the direction field. Following [Ray et al.
2008b], on a discrete triangle mesh it turns out to be simply the sum
of all squared angle differences between neighboring triangles:
Esmooth =

X

(θi − θj )2

eij ∈E

where θi is the angle of triangle i and neighboring angles are represented in a common coordinate frame, which is always possible
by flattening both triangles along their common edge. However,
for a surface with non-zero Gaussian curvature it is not possible to
find a global coordinate frame. Therefore, a local coordinate frame
is used for each triangle, where the x axis is identical to the first
edge e of the triangle (Figure 2(a)). Thus, by incorporating the
coordinate transformations between neighbors we can express the
smoothness energy of a cross field:
Esmooth =

π
(θi + κij + pij −θj )2
{z 2 }
eij ∈E |
X

(1)

θi w.r.t. frame j

where κij ∈ (−π, π] is the angle between both local frames and
pij is the integer valued period jump across edge eij . The cross
field index of a vertex can be computed as
X

I(vi ) = I0 (vi ) +

eij ∈N (vi )

pij
4

with the constant integer valued base index
Fortunately all necessary coefficients of this heuristic have an intuitive meaning. Appropriate directions should be stable within a
range depending on the target edge length h. Following this observation we choose w = h/4. Furthermore in our experiments we
chose r0 to be the average length of all triangle edges, r1 = h,
τmin = 0.8 and K = 0.1/bs , where bs is the radius of a bounding
sphere. In general the quadrangulation result is not very sensitive
w.r.t. these parameters, since similar cross fields can be generated
with a large range of different sparse constraints, generated with
slightly different parameters.

0
1 @
I0 (vi ) =
Ad (vi ) +
2π

1
X

κij A

eij ∈N (vi )

and Ad (vi ) is the angle defect of vertex vi . Only singularities of
the cross field have a nonzero index which is always a multiple
of 14 [Ray et al. 2008b], e.g. 14 and − 14 for quadrangulation
configurations corresponding to valence 3 and 5 respectively.

> **Code (cross-field index, BZK09 §4.1 / Ray08b).**
> [CrossField.extractSingularities](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java#L1535-L1579)
> computes `4·I(v) = (2/π)·(angleDefect + Σ signed κ) + Σ signed p`,
> where the sign on each outgoing half-edge is +1 if it is the canonical
> half-edge of its edge and −1 otherwise. Boundary vertices are skipped.
> Singularity records are stored as
> [Singularity(vertexId, index4)](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/Singularity.java).

vT[ v ] u
u0
0

T



[ ]

[ ]

u2
v2

u1
v1

(a)

(b)

Figure 3: (a) The three constrained faces (red) are the roots of
dual spanning trees (green) covering the respective Voronoi cells.
Each cell contains only one constraint and along all branches of
the tree zero period jumps can be propagated without changing the
total smoothness energy. (b) With the angle θ w.r.t. the local reference direction (green) the cross field directions uT , vT can be
extracted and used for the parametrization. In the computation two
linear scalar functions (u, v) are sought whose gradients are oriented consistently with the cross field directions.

4.2

Finding a smooth, interpolating cross field

Equipped with these basic definitions we are ready to formulate
the optimization problem. Given a mesh M and a subset of faces
Fc ⊂ F with constrained directions θi = θ̂i , we search for the
smoothest interpolating cross field, i.e. we want to minimize (1).
Accordingly we have to find an integer pij per edge and a real
valued angle θi per face.
Reducing the Search Space: Up to here there is a whole space of
equivalent minimizers to the energy (1). To understand this, assume
we have already computed a minimizer which for one triangle provides the angle θ0 and the three period jumps p01 , p02 and p03 . If
we now rotate the vector by a multiple of π2 , i.e. set θ̃0 = θ0 + k · π2
and compensate this change by updating the affected period jumps
to p̃0i = p0i − k, the smoothness energy is unchanged. We can
repeat this procedure for all free triangles f ∈ F \ Fc . Consequently the solution can be made unique by fixing one period jump
per free triangle to an arbitrary value, e.g. zero, without changing
the energy of the minimizer. Care should be taken not to fix edges
whose dual path connects two constrained faces, as done in [Ray
et al. 2008b], or closes loops because in these cases the cross field
curvature along this path would be fixed to an arbitrary value and is
not the intended result of the minimizer.
A valid set of edges, whose period jumps are allowed to be set to
zero, can be found by constructing a forest of Dijkstra trees of the
dual mesh as shown in Figure 3. Each constrained face in Fc is the
root of a separate tree such that no tree connects constrained faces.
The number of fixed edges is exactly |F \ Fc | since starting from
the constrained faces each other face of the mesh is conquered by
adding a single edge. Notice that no dual loop can be closed by a
tree structure, such that we end up with a valid set of edges which
can be fixed to zero period jumps without changing the energy of
the minimizer.
Obviously there are many other valid sets of edges which could be
fixed. The reason why we use trees living in the discrete Voronoi
cells of the corresponding constrained faces is that this choice minimizes the length of a path to its corresponding constraint and so
improves the accuracy of the greedy mixed-integer solver.

> **Code (§4.2 — Voronoi-tree period-jump pinning).**
> Multi-source dual-Dijkstra rooted at every constrained face —
> [CrossField.buildVoronoiSpanningForest](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java#L1456-L1514).
> Each non-constrained face is conquered by exactly one edge; those edges
> get `periodFixed = true, periodValue = 0`. Boundary edges and edges
> between two constrained faces get their own pinning pass in
> [CrossField.build](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java#L381-L410)
> (the `pij = round(2/π · (θ̂j − θ̂i − κij))` case).
Additionally to the period jumps on tree edges each period jump
between two adjacent constrained faces fi and fj can be fixed to

Figure 4: Greedy rounding yields a smaller smoothness energy and
fewer singularities (bottom), whereas the direct rounding produces
unnecessary singularities and a higher energy (top). Note that these
are the singularities and the field as they emerge from the solver, no
singularity optimization has been carried out.
pij = round(2/π(θ̂j − θ̂i − κij )), since pij is only part of a single
quadratic term in (1), which is independent from other variables.
In summary we end up with a mixed-integer problem consisting of
|F \ Fc | ≈ 2|V | real valued variables θi and |E| − |F \ Fc | ≈ |V |
integer valued variables pij .
Mixed-Integer Formulation: To apply the greedy mixed-integer
solver from Section 2 it is sufficient to assemble the system of linear
equations by setting the gradient of the energy (1) to zero:
∂Esmooth
∂θk

=

∂Esmooth
∂pij

=

X

2(θk + κkj +

ekj ∈N (fi )

π(θi + κij +

π
!
pkj − θj ) = 0
2

π
!
pij − θj ) = 0
2

(2)
(3)

Notice that the values on edges are antisymmetric, i.e. pij = −pji
and κij = −κji , which can lead to sign changes in equations (2)
and (3). For all variables which are not fixed, we set up a row and
assemble all of them into a single matrix. After applying our greedy
mixed-integer solver, the result is a smooth cross field where the integer valued period jumps define type and position of all singularities. Figure 4 compares the result of our greedy solver with that of a
direct rounding, where red and blue spheres represent singularities
with negative and positive index respectively.
In practice we observed that some singularity positions, especially
those in flat regions, can sometimes be improved by a local search
algorithm, as described in the next section.

> **Code (§4.2 — Mixed-Integer Formulation).**
> Normal-equation assembly + greedy rounding —
> [SmoothEnergySystem.solveGreedyMIP](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/SmoothEnergySystem.java#L163-L202).
> The "chord" indexing (line 119–133) is the free-period-jump compaction.
> Antisymmetry `p_ij = −p_ji`, `κ_ij = −κ_ji` is handled implicitly by
> only assembling one row per edge, oriented along the canonical
> half-edge.

Local Search Singularity Optimization: In a postprocess we optionally check for each singularity, if the energy can be decreased
by moving it to a neighboring vertex. Moving a singularity along
an edge eij means changing the corresponding period jump pij .
Notice, that by this operation only the right-hand-side of the linear
system is changed. Consequently we can precalculate the sparse
Cholesky factorization of this matrix once and then compute solutions for different right-hand-sides efficiently [Botsch et al. 2005].

> **Code (§4.2 — local-search singularity optimization).**
> [CrossField.localSearchSingularityOptimization](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java#L441-L658).
> Implements the paper's RHS-only trick: the θ-only Laplacian is
> [factorized once](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java#L489-L499)
> and re-solved per candidate period perturbation via
> [DirectSolver.solveCompact](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/solver/DirectSolver.java)
> with the matrix held constant. Trials ±1, ±2 on every edge adjacent to
> a singularity; non-overlapping candidates run in parallel.

At the end of the two cutting steps we have a triangle mesh patch
where all the singularities are located at the boundary. If a singularity is not a leaf node of the cut graph then it appears several
times along the boundary. In order to compute a parametrization we
have to find a planar embedding of this boundary polygon as well
as all the interior vertices. The location of the mesh vertices in the
parameter domain is computed by minimizing Eorient , however,
there are a number of consistency constraints that have to be taken
into account.

p

(a)

(b)

Figure 5: (a) By placing a cut to a cone singularity p (here of index
1
) a distortion free unfolding of the patch is possible. (b) The upper
4
image shows two directions of the cross field. In the lower image
the mesh is cut into disk topology along the green edges, such that
these directions can be consistently oriented on each side of the cut.

5

Global Parametrization

> **Code (§5 entry point).** All of §5 is orchestrated by
> [SeamlessParameterization.build](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessParameterization.java#L243-L356).
> Per-corner (u, v) live in
> [uCorner / vCorner](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessParameterization.java#L84-L87)
> (length `3·faceCount`, indexed by `activeFace·3 + cornerIdx`).
> The cut graph, branch labels, chart-vertex identification, dense
> seam-edge indices, and seam rotations are all in
> [CutGraph](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/CutGraph.java).
> The DOF system (primary/secondary chart vertices, leftover-row
> Gauss-Jordan elimination, per-face assembly cache, integer-pin state,
> AMD perm) is in
> [SeamlessDofSystem](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessDofSystem.java).

We now compute a global parametrization, i.e., a map from the
given mesh M to some disk-shaped parameter domain Ω ∈ R2 .
Since the parametrization should be piecewise linear, it is sufficient
to assign a (u, v) parameter value to each vertex — more precisely
to each triangle corner — in the mesh.
The parametrization should be locally oriented according to the optimized cross field from Section 4 which implies that the gradients
of the piecewise linear scalar fields u and v defined on the mesh M
should minimize the local orientation energy
ET = kh∇u − uT k2 + kh∇v − vT k2
for each triangle T . Here h is a global scaling parameter which
controls the edge length of the resulting quad mesh. The vectors uT
and vT are two orthogonal vectors in T corresponding to the cross
field directions θ and θ + π/2. Since the cross field is defined only
up to rotations by π/2 we will have to specify which of the four
possibilities we are picking in each triangle such that the proper
compatibility conditions are satisfied across each edge in the mesh.
The global orientation energy is then defined as the integral of ET
over the entire mesh M
Z
X
Eorient =
ET dA =
ET area(T ).
(4)
M

Integer location of singularities: By allowing a singularity to be
in general position, it would cause an n-sided face instead of a
valence-n vertex. Therefore to guarantee a pure quadrangulation,
we have to snap all singularities to integer locations in the parameter domain. This means that the overall parametrization task is
now a mixed-integer problem which we solve by our mixed-integer
greedy solver from Section 2.

> **Code (integer-pinning of singularities).**
> Marking which DOFs must round to an integer (singularity chart-vertex
> (u, v), per-cut-edge translation (s, t), and §5.2 alignment iso-coords) —
> [SeamlessDofSystem.markIntegerDofs](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessDofSystem.java).
> Greedy rounding loop (BZK09 §2 closest-to-integer first, rank-1 LDL update per pin) —
> [SeamlessParameterization.runGreedyIntegerRounding](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessParameterization.java).
> IGM mode is always on; Lyon 2021 §7 used the same BZK09 IGM as its
> seamless input, and QEx-style extraction downstream of the T-mesh
> requires it.
Cross boundary compatibility: In order to avoid visible seams
across the cut paths on the surface we have to make sure that the
quad structure on both sides of a cut edge is compatible. This is
guaranteed by allowing only a grid automorphism as a transition
function. This requires that the (u, v) parameter values on both
sides of a cut edge are related by
(u0 , v 0 ) = Roti90 (u, v) + (j, k)
with integer coefficients (i, j, k).
The rotation coefficient in the transition functions can easily be
computed by propagating a globally consistent orientation in the
cross field, as illustrated in Figure 5 . Since after the cutting, all
interior vertices of the mesh are regular, we can start at a random
face and propagate its orientation in a breadth first manner to all
the neighboring faces. This will establish a zero-rotation across all
inner edges. The rotations Roti90 across the cut edges can be found
by simply comparing the orientations in neighboring faces.
After fixing the rotations, the cross boundary compatibility conditions can be incorporated into the optimization scheme as linear
constraints. Therefore for each cut edge e = p q we introduce two
integer variables je , ke to formulate the four compatibility conditions:
(u0p , vp0 )

=

e
Roti90
(up , vp ) + (je , ke )

(u0q , vq0 )

=

e
Roti90
(uq , vq ) + (je , ke )

T ∈M

The minimizer of this quadratic functional is obtained by solving
the sparse linear system which sets all the partial derivatives of
Eorient to zero.

> **Code (§5 — orientation energy E_orient).** Per-face geometry
> (faceArea, shape gradients b_i / c_i, branch-rotated targets u_T / v_T
> in the local frame) is built once in
> [SeamlessParameterization.precomputePerFaceGeometryAndTargets](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessParameterization.java#L461-L519).
> The SPD normal equations `H·x = g` are assembled by
> [SeamlessDofSystem.assemble](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessDofSystem.java#L220-L246)
> (cached per-face playback log so the §5.4 re-solves don't rebuild the
> non-zero pattern) and solved by
> [SeamlessParameterization.solveOnce](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessParameterization.java#L710-L717)
> via [DirectSolver.solveWithPerm](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/solver/DirectSolver.java).
> The global scale `h` defaults to the mean edge length —
> [SeamlessParameterization.h](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessParameterization.java#L106-L111).
Cutting the mesh: In order to be able to compute a proper
parametrization minimizing Eorient we have to cut open the mesh
M, such that we obtain a patch that is topologically equivalent to a
disk. An additional requirement is that all singular vertices must lie
on the cut, i.e. at the boundary of the parameter domain. The reason is that the angle defect of a singularity cannot be represented by
an inner vertex of the parametrization as depicted in Figure 5. We
compute an appropriate cut graph in two steps.
First we start from a random triangle and grow a topological disk
by constructing a dual spanning tree. Thus the primal of all non
spanning tree edges is already a cut graph which transforms M
into disk topology. The size of this cut graph can be significantly
reduced by iteratively removing all open paths.
In the second step paths connecting each singularity to the cut graph
are added. This can be done by successively applying Dijkstra’s
shortest path algorithm.

> **Code (§5 — cut graph in two passes).**
> [CutGraph.buildCutGraph](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/CutGraph.java#L138-L145)
> drives the full cut-graph construction.
> 1. **Min-cost dual-spanning-tree complement** —
>    [initialCutFromDualSpanningTree](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/CutGraph.java):
>    starts with every edge cut, runs a Dijkstra-style traversal of the
>    dual graph where alignment edges have cost 0 and other interior
>    edges have cost 1. Tree edges are un-cut, so feature/boundary edges
>    stay non-cut whenever surface topology allows.
> 2. **Trim open paths** —
>    [trimDanglingBranches](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/CutGraph.java):
>    iteratively remove cut-degree-1 non-singularity, non-boundary leaves.
> 3. **Route singularities to the cut** —
>    [connectDetachedSingularities](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/CutGraph.java)
>    + [shortestMeshPathToCut](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/CutGraph.java)
>    (Dijkstra over Euclidean edge lengths, with alignment edges
>    penalised by {@code ALIGNMENT_PATH_PENALTY} so they're only used
>    when no non-alignment path exists).

Hence, in total we add two integer variables and eliminate four continuous variables per cut edge.

> **Code (cross-boundary compatibility constraints).**
> The rotation r_e ∈ {0,1,2,3} is propagated from the cross-field branches —
> [CutGraph.propagateBranches](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/CutGraph.java#L422-L455)
> (BFS over non-cut edges using `g_B = g_A − p_AB` mod 4) and
> [CutGraph.buildCutRotation](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/CutGraph.java#L467-L480)
> (`r_e = (g_B − g_A + p_AB) mod 4` on cut edges, 0 elsewhere).
>
> Chart-vertex identification (union-find over corners across non-cut
> edges) —
> [CutGraph.buildChartVertices](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/CutGraph.java#L494-L531).
>
> The four equations per cut edge are folded into the system by variable
> elimination: every chart vertex is classified primary or secondary by
> [CutGraph.classifyChartVerticesForSubstitution](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/CutGraph.java#L624-L684);
> the secondary chart vertex's (u, v) is substituted out as
> `u_c = R_r · u_partner + (s_e, t_e)` (see
> [SeamlessDofSystem.rawExpansionDofs / rawExpansionCoefs](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessDofSystem.java#L518-L548)),
> giving "two ints in, four reals out" per cut edge.
>
> When more than one cut equation tries to substitute the same chart
> vertex, the surplus equations become **leftover constraints**
> ([CutGraph.tryClaimOrRecordLeftover](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/CutGraph.java#L697-L705)),
> which are reduced exactly by fraction-free Gauss-Jordan elimination —
> [SeamlessDofSystem.reduceLeftoverConstraints](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessDofSystem.java#L350-L435).
> Per-corner (u, v) is recovered by expanding the per-chart-vertex final-DOF map —
> [evaluateChartComponent](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessDofSystem.java#L309-L317).

Applying our mixed-integer greedy solver to this parametrization
task can be understood in an intuitive way. After computing an
all-continuous solution, which corresponds to the unconstrained
parametrization, we iteratively snap the singularities to integer locations.

5.1

Anisotropic Norm

> **Code — MISSING.** §5.1 is **not implemented** in the seamless module.
> [SeamlessParameterization.precomputePerFaceGeometryAndTargets](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessParameterization.java#L461-L519)
> writes isotropic targets `(u_T, v_T) = (cos θ, sin θ), (−sin θ, cos θ)`
> with no per-direction weights `(α, β) = (γ, 1)` / `(1, γ)`, and
> [SeamlessDofSystem.buildAssemblyPlan](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessDofSystem.java#L619-L765)
> sums `area · h² · (b_i b_j + c_i c_j)` for the Hessian without an
> anisotropic metric. There is no public γ field, no setter, no test.
> See the audit "Fix list" at the bottom of this file.

In practice exact orientation is often more important than exact edge
length. The reason is that changing the orientation along a highly
curved feature line, the quadrangulation quality will drop off dramatically due to normal noise. The orientation can be improved
by less penalizing stretch which is in the direction of the desired
iso-lines. This can be achieved by an anisotropic norm
k(u, v)k2(α,β) = αu2 + βv 2
which penalizes the deviation along the major directions with different weights. Notice, such a diagonal metric is sufficient since we

(a)

(b)

(c)
(a)

(b)

(c)

Figure 6: The parametrization in (a) is not aligned to the sharp
edges of the object. Using the anisotropic norm the quads are allowed to stretch in order to better align with a given input field
as shown in (b). In (c) alignment constraints have been imposed,
leading to perfectly preserved features.

Figure 7: In (a) the minimized orientation energy produces flipped
triangles, which can be removed by local stiffening (b). The chosen
weighting is shown in (c) and decreases from orange to blue.

use (uT , vT ) as the local coordinate frame in each triangle.

prescribed by linear constraints. Movements are performed if the
overall quality improves, i.e. the energy (4) decreases.

ET = kh∇u − uT k2(γ,1) + kh∇v − vT k2(1,γ)
with γ ≤ 1. Figure 6 (b) shows an example, where the orientation
of the parametrization is improved by using the anisotropic norm.

5.2

Feature Line Alignment

> **Code (cross-field + parametrization sides, both implemented).**
>
> **Cross-field side.**
> [CrossField.applyFeatureEdgeConstraints](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java)
> aligns the cross field with sharp dihedrals (CIE16-style), and
> [CrossField.applyBoundaryConstraints](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java)
> does the same for boundary edges. Both append the active edge id to
> [`CrossField.alignmentEdgeIds`](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java)
> so the seamless stage can read the union as the alignment set S ⊂ E.
>
> **Cut-graph bias.** To keep alignment edges off the seam (a feature
> edge with rotation r ≠ 0 across it cannot satisfy {@code v_p = v_q}
> on both sides), the dual-MST in
> [initialCutFromDualSpanningTree](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/CutGraph.java)
> weights alignment edges by 0 (preferred as tree edges → non-cut) and
> the singularity-to-cut Dijkstra in
> [shortestMeshPathToCut](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/CutGraph.java)
> penalises them by {@code ALIGNMENT_PATH_PENALTY × length}.
>
> **Parametrization side.**
> [SeamlessDofSystem.computeAlignmentEdgeIsoAxes](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessDofSystem.java)
> decides per edge whether u or v is the iso-coordinate by projecting
> the edge direction into face A's branch-rotated local frame; the axis
> whose target points along the edge gets the orthogonal coordinate
> pinned.
> [SeamlessDofSystem.addAlignmentEqualityRows](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessDofSystem.java)
> appends one {@code u_p − u_q = 0} (or v) row per alignment edge to
> the leftover-constraint matrix, fed through the same fraction-free
> Gauss-Jordan reduction used for seam transitions.
> [SeamlessDofSystem.markAlignmentIsoDofs](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessDofSystem.java)
> walks each endpoint's chart-vertex final-DOF expansion and marks every
> surviving DOF as integer, so the greedy rounder pins the iso-line.
>
> **Known limitation.** The current marking handles ~85% of alignment
> edges on the fandisk fixture
> ([alignmentIsoFandisk](ixdar-app/test/unit/mesh/quadlayout/SeamlessParameterizationSmokeTest.java)
> asserts ≥ 80%). The remaining ~15% are boundary chains where multiple
> alignment rows share a surviving final DOF that conflicts with another
> constraint chain; equality holds exactly but the value lands on a
> half-integer instead of snapping to round. Audit "Fix list" item 1
> tracks the chain-aware fix.

Sharp feature lines of the input mesh should be preserved in the
quadrangulation. Given a subset S ⊂ E of triangle mesh edges, the
necessary alignment conditions can be incorporated in a straightforward way. First of all, alignment requires correct orientation.
Therefore, while computing the cross field, all edges in S are used
as orientational constraints in both adjacent triangles. Additionally
to the correct orientation for alignment, a constant integer coordinate along the edge is necessary, which guarantees that this edge is
preserved in the quadrangulation. Each alignment condition for an
edge p q can be formulated independently. If the cross field direction uT is already oriented along the alignment edge, we end up
with a simple condition for the v parameter values
vp = vq ∈ Z
which ensures that p q is mapped to an integer valued iso-line. The
u = const case is handled analogously. Consequently, for each
alignment edge a single variable can be eliminated and the remaining integer variables can be handled by the greedy mixed-integer
solver. Figure 6 (c) shows an example, where all feature edges are
aligned.

The obvious drawback of this singularity relocation is its heavy
computational cost. Fortunately in all of our examples the initial
singularity positions were already sufficient. However, coarsely
quadrangulating meshes with fine details will require singularity
relocation.

5.4

Local Stiffening

> **Code (§5.4 — IRLS / per-face weighting).**
> [SeamlessParameterization.runStiffeningLoop](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessParameterization.java#L545-L703).
> Implements the paper's recipe — Hormann-Lévy-Sheffer distortion
> `λ = |τ·σ₁/h − 1| + |τ·σ₂/h − 1|`, uniform-dual-Laplacian Δλ, additive
> bump `w(T) += min(c·|Δλ|, d)`, and a few uniform smoothing passes —
> plus **two divergences from the paper**:
> 1. The paper's `c = 1, d = 5` constants are replaced by defaults
>    `c = 100, d = 10000` (see field docs at
>    [stiffeningC](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessParameterization.java#L141-L147) /
>    [stiffeningD](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessParameterization.java#L150-L153)).
> 2. An extra **multiplicative kick** of growth factor 4× is applied to
>    actually-flipped faces
>    ([lines 647-673](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessParameterization.java#L647-L673)).
>
> These divergences are justified for the seamless-without-singularity-pinning
> mode (the relaxed solve has many initial flips and `c=1` grows weights
> too slowly). Tests use `applyIgmExperimentDefaults` to reset to the
> paper-faithful values inside IGM mode.

The parametrization is the result of a quadratic energy minimization. Thus despite the global optimum, for a few triangles it might
happen that the metric distortion gets very high or even worse, that
the orientation of a mapped triangle flips. Figure 7 shows an example where such a problem occurs in the vicinity of a singularity.
The idea of local stiffening is to add an adapted triangle weighting
w(T ) into the energy formulation to penalize high local distortions,
yielding:
X
Eorient =
w(T ) ET area(T )
T ∈M

This weighting, which is initialized to one, can be updated iteratively, as described in the following, until the quality of the
parametrization is sufficient.
The metric distortion is characterized by the singular values σ1 and
σ2 of the Jacobi matrix as described in [Hormann et al. 2007]. Furthermore to penalize flips we evaluate the orientation of a triangle
»

Notice that for meshes with boundaries we can exploit the presented
alignment functionality to guarantee that the boundaries are preserved in the quadrangulation and thus prevent jagged boundary
lines (see Figure 9).

5.3

Singularity Relocation

> **Code — MISSING.** §5.3 (parametrization-aware singularity relocation
> with `h`, alignment, boundary, and symmetry constraints) is **not
> implemented**. The closest analogue is the cross-field-only
> [CrossField.localSearchSingularityOptimization](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java#L441-L658)
> (§4.2), which is unaware of `h`. The seamless module never re-runs the
> cross field or re-solves with relocated singularities. The paper notes
> "in all of our examples the initial singularity positions were already
> sufficient", but for coarse `h` or models with fine detail the
> parametrization quality will suffer without this pass.

By computing a parameterization with the given target edge length
h , new requirements have to be taken into account which cannot
be anticipated by the cross field computation, since it is independent from h. Examples are singularities which are too close to each
other, a boundary or a given alignment edge. Other aspects are symmetries which are irrelevant for a smooth cross field, but important
for a quadrangulation. Therefore, to achieve maximal quality it can
be necessary to relocate the singularties w.r.t. the requirements of
the parameterization. This can be done with a local search algorithm similar to Section 4.2. Depending on how much time is available we can restrict the search to the best local candidate, i.e. the
closest neighbor in the parametrization, or evaluate the quality of
all neighbors. In each step it is necessary to recompute the smooth
cross field w.r.t. the relocated singularity as well as the parametrization. In the cross field computation the cross field indices are now

τ = sign(det

u1 − u0 , u2 − u0
v1 − v0 , v2 − v0

–
)

where (ui , vi ) are the vertex parameter coordinates in counterclockwise ordering. We measure the local distortion of each triangle by
σ1
σ2
λ = |τ
− 1| + |τ
− 1|
h
h
which respects the edge length h. Finally, we update the weight
of a triangle by evaluating a uniform Laplacian defined on the dual
mesh
w(T ) ← w(T ) + min{c · |4λ(T )|, d}
with the proportionality constant c and a maximal allowed update of
d, which we chose as c = 1 and d = 5 in all our examples. Notice
that directly using the distortion instead of the Laplacian wouldn’t
be a good idea. The reason is that the weighting would reflect the
global stretch distribution, which is necessary for a globally consistent quadrangulation, instead of the desired local distortions. Subsequently, we increase the smoothness of the weighting field w(T )
by a few uniform smoothing steps, which in general leads to nicer
quadrangulations.

(a)

(b)

(c)

Figure 8: The presented algorithm is robust w.r.t. bad triangles (a)
and can produce meaningful singularities in the presence of noise
(b) and on smooth offset geometries (c).

Figure 9: Quadrangulation of the B EETLE model having 11
boundaries. On the right the parametrization is shown. Naturally,
due to the occurrence of − 14 singularities, parts of the flattening
are overlapping.

6

Figure 10: A comparison between the technique described in
this paper (left) and the QuadCover approach by [Kälberer et al.
2007] (right) for a sharp object (FANDISK) and a smooth object
(B OTIJO). In both comparisons the same target edge length and the
same cross field generated by our mixed-integer formulation were
used.

Comparisons and Results

The backbone of our approach is the mixed-integer solver introduced in Section 2, which is used for the computation of both the
smooth cross field and the parametrization. Although it is often
necessary to round tens of thousands of variables for the cross field
computation, the timings in Table 1 show that this can be done efficiently using the proposed solver.
The example in Figure 4 shows that the greedy rounding leads to a
significantly smoother cross field with less singularities compared
to the direct rounding approach. All our experiments confirmed this
behavior.
A comparison between our approach and QuadCover [Kälberer
et al. 2007] is carried out in Figure 10. In both examples the same
input cross field and target edge length have been used. The FAN DISK comparison clearly shows the benefit of alignment on models with sharp feature edges, while the limitations of direct rounding are especially noticeable on the B OTIJO. On complex objects
having many singularities or when remeshing with very coarse target edge length the direct rounding generates many ”twists” and
non-injectivities in the parametrization, such that the extraction of
a hole-free quad mesh is not always possible. However, the combination of greedy rounding and local stiffening allow us to automatically generate consistent, hole-free quadrangulations at almost any
resolution and with significantly less ”twists”.
The spectral approach [Huang et al. 2008] also produces oriented
and aligned parametrizations with few singularities, however the
Morse-Smale Complex sometimes fails to capture the detailed
structure of the surface. This can lead to an unfavorable stretch

Figure 11: ROCKER A RM comparison between the technique described in this paper (top) and the spectral quadrangulation approach by [Huang et al. 2008] (bottom). The upper mesh has 9413
faces and 36 singularities, the lower one has 9400 faces and 26
singularities.

F ERTILITY

Table 1: Statistics of the Greedy Mixed-Integer Solver used for
computing the cross field (Section 4) and the parametrization (Section 5). Dim refers to the initial dimension of the linear system, #Int
is the number of integer variables, #IS and #DS is the number of
calls to iterative and direct solvers respectively. Time is the total
time for the solution. Due to the global nature of the parametrization, the local and iterative search seldom lead to a gain of efficiency and therefore Time refers solely to the direct solver.

ROCKER ARM

of the quads affecting the angle as well as the edge length distribution. A comparison between [Huang et al. 2008] and our approach
can be found in Figure 11.
Quadrangulations computed by our technique typically have angle distributions with a sharp peak around 90◦ and an edge length
distribution centered around the target edge length. However, for
aligned meshes, like the FANSIDK in Figure 10, further peaks,
which reflect the unavoidable stretch may occur in the edge length
histogram.
The geometrically complex examples shown in Figure 12 underline
the ability of our method to compute coarse, oriented quadrangulations with naturally placed singularities.
All examples were computed on a 3.0GHz standard PC, the statistics are shown Table 1. Interestingly the cross field computation is
less demanding to compute than the parametrization, even though
it requires practically two orders of magnitude more roundings.
This effect is due to the locality of the cross field energy (Equation
(1)). Rounding a period jump mainly affects a local neighborhood
on the mesh and the solution can be efficiently updated by local
Gauss-Seidel iterations. Whereas, rounding a corner point in the
parametrization domain usually has global impact. Motivated by
this observation and the typically low number of integer variables
for the parametrization, we restricted the greedy solver to sparse
Cholesky updates.

L EVER

Finally Figure 8 demonstrates the robustness of the mixed-integer
quadrangulation approach w.r.t. different (degenerate) representations of a given object. The mesh in Figure 8 (a) contains almost
1000 triangles with vanishing area (the close-up shows a part of
the mesh where about 8 triangles are nearly colinear), the model in
Figure 8 (b) has been subjected to normal noise with a magnitude
of 0.3% of the bounding box diagonal and the right most model
(Figure 8 (c) ) was offset, yielding a mesh without sharp corners.
These fandisks and most of the other triangle meshes used in this
work (along with the extracted quad meshes) can be found in the
supplementary material of this paper.

7

B OTIJO
Figure 12: Results of our Mixed-Integer Quadrangulation approach

Conclusion and Future Work

We have presented a complete quadrangulation method which starts
with a pre-process that finds reliable orientation constraints. Based
on these, possibly sparsely distributed, constraints we compute a
smooth cross field on the surface. The global optimization produces
a set of singularities that are automatically placed at geometrically
meaningful locations. The cross field is used as input for a global

parametrization technique which cuts the surface open into a disklike patch and then computes a planar embedding which takes orientation, alignment, as well as boundary compatibility constraints
into account.
In the future we would like to integrate both parts of the algorithm into a single optimization scheme. Instead of making the
parametrization smooth by least squares approximation of a smooth
cross field it would be more natural to smooth the parametrization
directly. However this would most probably lead to a non-linear
optimization.

F LOUDAS , C. A. 1995. Nonlinear and Mixed-Integer Optimization
Fundamentals and Applications. Hardback.
G ORRY, G., S HAPIRO , J., AND W OLSEY, L. 1970. Relaxation methods for pure and mixed integer programming problems. Cambridge, M.I.T., Cambridge.
H ERTZMANN , A., AND Z ORIN , D. 2000. Illustrating smooth surfaces. In SIGGRAPH ’00: Proceedings of the 27th annual conference on Computer graphics and interactive techniques, ACM
Press/Addison-Wesley Publishing Co., New York, NY, USA,
517–526.

One limitation of our method is that for coarse quadrangulations of
highly complex models with many cross field singularities, the local
singularity relocation in the parametrization step is dominating the
overall computation time. Here we would like to develop a more
global search strategy.

H ILDEBRANDT, K., P OLTHIER , K., AND WARDETZKY, M. 2005.
Smooth feature lines on surface meshes. In SGP ’05: Proceedings of the third Eurographics symposium on Geometry processing, Eurographics Association, Aire-la-Ville, Switzerland,
Switzerland, 85.

Acknowledgements

H ORMANN , K., L ÉVY, B., AND S HEFFER , A. 2007. Mesh parameterization: theory and practice. In SIGGRAPH ’07: ACM
SIGGRAPH 2007 courses, 1.

This work has been supported by the UMIC Research Centre, RWTH Aachen University. We would like to thank Felix Kälberer and Matthias Nieser for their helpful support,
Tamal Dey, Muyang Zhang, AIM@SHAPE and Carlos Hernández
(www.tsi.enst.fr/3dmodels) for providing us with datasets, Jan
Möbius for the geometry processing framework OpenFlipper.org
and the reviewers for their competent and helpful comments.

References
A LLIEZ , P., C OHEN -S TEINER , D., D EVILLERS , O., L EVY, B.,
AND D ESBRUN , M. 2003. Anisotropic polygonal remeshing.
ACM Trans. Graph. 22, 3, 485–493.
A LLIEZ , P., U CELLI , G., G OTSMAN , C., AND ATTENE , M. 2005.
Recent advances in remeshing of surfaces. Research report,
AIM@SHAPE Network of Excellence.
B EN -C HEN , M IRELA , G OTSMAN , C RAIG , B UNIN , AND G UY.
2008. Conformal flattening by curvature prescription and metric
scaling. Computer Graphics Forum 27, 2 (April), 449–458.
B OMMES , D., VOSSEMER , T., AND KOBBELT, L. 2009. Quadrangular parameterization for reverse engineering. Lecture Notes in
Computer Science, to appear.
B OTSCH , M., B OMMES , D., AND KOBBELT, L. 2005. Efficient
linear system solvers for mesh processing. In IMA Conference on
the Mathematics of Surfaces, Springer, R. R. Martin, H. E. Bez,
and M. A. Sabin, Eds., vol. 3604 of Lecture Notes in Computer
Science, 62–83.
C HEN , Y., DAVIS , T. A., H AGER , W. W., AND R AJAMANICKAM ,
S. 2006. Algorithm 8xx: Cholmod, supernodal sparse cholesky
factorization and update/downdate. Technical Report TR-2006005, University of Florida.
C OHEN -S TEINER , D., AND M ORVAN , J.-M. 2003. Restricted
delaunay triangulations and normal cycle. In SCG ’03: Proceedings of the nineteenth annual symposium on Computational
geometry, 312–321.
D ONG , S., B REMER , P.-T., G ARLAND , M., PASCUCCI , V., AND
H ART, J. C. 2006. Spectral surface quadrangulation. In SIGGRAPH ’06: ACM SIGGRAPH 2006 Papers, 1057–1066.
F ISHER , M., S CHR ÖDER , P., D ESBRUN , M., AND H OPPE , H.
2007. Design of tangent vector fields. ACM TOG 26, 3, 56.

H UANG , J., Z HANG , M., M A , J., L IU , X., KOBBELT, L., AND
BAO , H. 2008. Spectral quadrangulation with orientation and
alignment control. ACM Trans. Graph. 27, 5, 1–9.
K ÄLBERER , F., N IESER , M., AND P OLTHIER , K. 2007. Quadcover - surface parameterization using branched coverings. Computer Graphics Forum 26, 3 (Sept.), 375–384.
K HAREVYCH , L., S PRINGBORN , B., AND S CHR ÖDER , P. 2006.
Discrete conformal mappings via circle patterns. ACM Trans.
Graph. 25, 2, 412–438.
L AI , Y.-K., KOBBELT, L., AND H U , S.-M. 2008. An incremental approach to feature aligned quad dominant remeshing. In
SPM ’08: Proceedings of the 2008 ACM symposium on Solid
and physical modeling, 137–145.
M ARINOV, M., AND KOBBELT, L. 2004. Direct anisotropic quaddominant remeshing. In PG ’04: Proceedings of the Computer
Graphics and Applications, 12th Pacific Conference, IEEE Computer Society, Washington, DC, USA, 207–216.
R AY, N., L I , W. C., L ÉVY, B., S HEFFER , A., AND A LLIEZ , P.
2006. Periodic global parameterization. ACM Trans. Graph. 25,
4, 1460–1485.
R AY, N., VALLET, B., A LONSO , L., AND L ÉVY, B. 2008. Geometry aware direction field design. Tech. rep., INRIA - ALICE
Project Team. Accepted pending revisions.
R AY, N., VALLET, B., L I , W. C., AND L ÉVY, B. 2008. Nsymmetry direction field design. ACM Trans. Graph. 27, 2, 1–13.
S PRINGBORN , B., S CHR ÖDER , P., AND P INKALL , U. 2008. Conformal equivalence of triangle meshes. In SIGGRAPH ’08: ACM
SIGGRAPH 2008 papers, 1–11.
T ONG , Y., A LLIEZ , P., C OHEN -S TEINER , D., AND D ESBRUN ,
M. 2006. Designing quadrangulations with discrete harmonic
forms. In Proc. SGP, Eurographics Association, 201–210.
Z HANG , E., M ISCHAIKOW, K., AND T URK , G. 2006. Vector field
design on surfaces. ACM Trans. Graph. 25, 4, 1294–1326.

---

## Audit Report — Fix list for the Seamless Parameterization module

Date: 2026-05-17. Scope: BZK09 §5 implementation under
[ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/)
and the helpers it consumes from
[crossfield/](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/),
[solver/](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/solver/),
and [seamless/exact/](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/exact/).

### What is correct

These pieces match the paper closely and need no change:

- **Cut graph topology.** Dual-spanning-tree complement + dangling-branch
  trim + singularity → cut Dijkstra connection
  ([CutGraph.buildCutGraph](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/CutGraph.java#L138-L145)).
  Every singularity ends up on the cut. Smoke tests confirm.
- **Branch propagation / seam rotation.** `g_B = g_A − p_AB` on
  non-cut, `r_e = (g_B − g_A + p_AB) mod 4` on cut edges
  ([propagateBranches](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/CutGraph.java#L422-L455),
  [buildCutRotation](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/CutGraph.java#L467-L480)).
  The
  [ParameterizationMetrics.branchConsistencyViolations](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/ParameterizationMetrics.java#L279-L297)
  assertion is zero on all fixtures.
- **Compatibility-constraint variable elimination.** `u_c = R_r·u_partner + (s_e, t_e)`
  substitution and the leftover-row fraction-free Gauss-Jordan elimination
  are correctly oriented — the transition residual asserted in
  [SeamlessParameterizationSmokeTest.TRANSITION_TOLERANCE = 1e-3](ixdar-app/test/unit/mesh/quadlayout/SeamlessParameterizationSmokeTest.java#L60)
  passes on the sphere fixture.
- **Cut-translation direction.** `(s, t) = (u_B, v_B) − R_r·(u_A, v_A)`
  agrees in all three call sites:
  [SeamlessDofSystem.reduceLeftoverConstraints](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessDofSystem.java#L360-L373),
  [SeamlessProjector.recomputeCutTranslations](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/exact/SeamlessProjector.java#L380-L402),
  [ParameterizationMetrics.computeMaxTransitionResidual](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/ParameterizationMetrics.java#L71-L107).
- **Integer-grid-map greedy rounding.** Pick-closest-to-integer + rank-1
  Cholesky update per pin
  ([runGreedyIntegerRounding](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessParameterization.java#L363-L431) +
  [IncrementalCholeskySolver](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/solver/IncrementalCholeskySolver.java))
  is faithful to §2 — and an improvement on the paper's "re-factor from
  scratch every pin" prescription.
- **MC19 exact-seam projection.** The reduced node-only constraint matrix,
  fraction-free integer RREF, F_d truncation, and back-substitution
  ([SeamlessProjector.project](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/exact/SeamlessProjector.java#L72-L189),
  [ExactArithmetic](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/exact/ExactArithmetic.java))
  are a faithful MC19 §5.3.1 implementation; the
  `exactSeamsSphereBase` test asserts literal-zero residual.

### What is wrong or missing — ordered by likely impact on output quality

> **Status note (2026-05-17).** Item §5.2 from earlier revisions of this
> list — *alignment iso-line constraint not implemented* — landed. The
> cross field now exposes
> [`alignmentEdgeIds`](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/crossfield/CrossField.java),
> the cut graph routes around those edges via min-cost dual-MST +
> penalised Dijkstra, and
> [SeamlessDofSystem.addAlignmentEqualityRows](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessDofSystem.java)
> / [markAlignmentIsoDofs](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessDofSystem.java)
> wire the equality + integer pin into the leftover-row Gauss-Jordan
> reduction. A regression test
> ([alignmentIsoFandisk](ixdar-app/test/unit/mesh/quadlayout/SeamlessParameterizationSmokeTest.java))
> guards at ≥ 80 % of alignment edges holding the BZK09 §5.2 invariant
> on the fandisk fixture (current: 194/229 = 84.7 %). The visible spiral
> + flat-region drift the audit was originally written against is gone.
>
> The `integerGridMap` toggle was removed entirely; IGM is always on
> (Lyon 2021 §7 used the BZK09 IGM as its seamless input and QEx-style
> extraction requires it).
>
> §5.4 defaults were brought back to the paper's `c=1, d=5, growth=1`
> (no multiplicative flip kick); the test fixture confirms no regression.

#### 1. §5.2 alignment iso-line chain completion (partial)
**Severity: medium — ~15 % of alignment edges still fail the invariant.**
The current
[markAlignmentIsoDofs](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessDofSystem.java)
marks every final DOF in each endpoint's chart-vertex expansion as
integer, which catches both primary and most secondary-via-cut chains.
The residual failures cluster on boundary chains where multiple
alignment rows pivot onto a shared surviving DOF that another constraint
chain then pulls to a half-integer. Equality (`u_p = u_q`) is enforced
exactly; only the integer pin slips.

*Fix.* Treat the per-chain "shared survivor" DOF as a single integer
DOF: detect the equivalence class of chart-vertex iso-coordinates joined
by alignment-equality rows and add **one** integer pin per class instead
of per chart vertex (avoids per-vertex pins fighting each other through
Gauss-Jordan pivot reshuffling). Diagnostic counter in
[alignmentIsoFandisk](ixdar-app/test/unit/mesh/quadlayout/SeamlessParameterizationSmokeTest.java)
will track the pass rate up from 84.7 % toward 100 %.

#### 2. §5.1 anisotropic norm — not implemented
**Severity: low-medium (now that §5.2 is in, this is a refinement, not the headline fix).**
There is no γ parameter, no anisotropic energy term, and no test.
With §5.2 alignment in place the iso-lines lock to feature edges; the
anisotropic norm would additionally let quads stretch along iso-lines
to better follow curved features (BZK09 Figure 6(b)).

*Fix.* Add a public field `public float anisotropyGamma = 1.0f;` on
[SeamlessParameterization](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessParameterization.java).
In
[SeamlessDofSystem.buildAssemblyPlan](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessDofSystem.java),
replace the u/v outer products' `stiffnessConstant` with two separate
constants for u and v, each scaled by the appropriate `(γ, 1)` / `(1, γ)`
weights expressed in the cross-field tangent basis. The cleanest form:
build the per-face metric `M = R · diag(γ, 1) · Rᵀ` in the face-local
frame (where R aligns to u_T) and use `M_{ij} · b_i · b_j` instead of
`b_i · b_j + c_i · c_j`. Add a fandisk test that asserts the feature-edge
length stays close to `h` once `γ < 1`.

#### 3. §5.3 parametrization-aware singularity relocation — not implemented
**Severity: low-medium (paper says "rarely needed on its examples").**
There is no relocation pass that takes `h`, boundary distance, or
alignment edges into account. The cross-field §4.2 local search runs
once at cross-field-build time and is unaware of `h`.

*Fix (deferred until a coarse-mesh fixture demands it).* The hook
already exists: the cross field can be re-built with one period jump
shifted (cross-field §4.2 has the machinery). Add a
`SeamlessParameterization.relocateSingularities()` step that, for each
singularity, evaluates `E_orient` after re-running the cross field + a
fresh seamless solve with the singularity moved one ring outward; keep
the move that reduces energy.

#### 4. Per-edge transition residual is not asserted in the non-exact path on real meshes
**Severity: medium — diagnostic gap.**
[`SeamlessParameterizationSmokeTest.realMeshBolt`](ixdar-app/test/unit/mesh/quadlayout/SeamlessParameterizationSmokeTest.java#L201-L203)
and
[`realMeshFandisk`](ixdar-app/test/unit/mesh/quadlayout/SeamlessParameterizationSmokeTest.java#L209-L212)
assert exactly-zero residual only because they enable
`exactSeams = true`. There is no real-mesh test that asserts the
plain (non-exact) BZK09 §5 residual stays below the floating-point
tolerance.

*Fix.* Add a `realMeshBoltNonExact` variant with `exactSeams = false`
that asserts `metrics.maxTransitionResidual < 1e-3`. Will catch any
regression in the leftover-row reduction's numerical stability — the
current 1e-10 pivot tolerance in
[reduceLeftoverConstraints](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessDofSystem.java#L394)
is tight for chains through singularity nodes.

#### 5. `precomputePerFaceGeometryAndTargets` silently skips degenerate triangles
**Severity: low — but lets a downstream bug hide.**
[Lines 495-499](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessParameterization.java):
when `|twoArea| < 1e-30`, `faceArea[af] = 0` and the targets are
left zero. Downstream code does `if (area <= 0) continue;` in most
spots, but
[`countFlippedTrianglesFromSolution`](ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/seamless/SeamlessParameterization.java)
also skips them — so a degenerate triangle that produces a UV-degenerate
output is invisible to the injectivity counter.

*Fix.* On detecting a degenerate input triangle either reject the mesh,
or count it as flipped so stiffening at least tries to recover, and
emit a diagnostic log line. Currently the silent zero target distorts
the smoothness solution near the degeneracy without any signal.

### Resolved since last revision

- **§5.2 alignment iso-line constraint (parametrization side)** — wired
  through the cut graph and DOF system; ≥ 80 % regression test gate in
  place. Visible spiral + flat-region drift on fandisk is gone.
- **`integerGridMap` toggle** — removed; IGM is always on, matching how
  Lyon 2021 §7 actually uses BZK09.
- **§5.4 stiffening defaults** — back to the paper's `c=1, d=5`, no
  multiplicative kick. Smoke-test override removed.

### Suggested order of attack

1. Item 1 — alignment iso-line chain completion. Push the regression
   gate from 84.7 % toward 100 % by adding one integer pin per
   alignment-equality equivalence class instead of per chart vertex.
2. Item 4 — add a non-exact real-mesh residual test. Will catch any
   numerical drift from the leftover-row Gauss-Jordan as items 1 and 2
   add more rows to that reduction.
3. Item 2 — anisotropic norm. The test rig from 4 makes this easy to
   regress-test against.
4. Items 3, 5 — quality-of-life and instrumentation.

