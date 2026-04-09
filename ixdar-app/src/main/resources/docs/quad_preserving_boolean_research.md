# Quad-Preserving Boolean Operations Research

## Problem Statement

The current `MeshBooleanNode` performs CSG boolean operations (union, difference, intersect) using a face-classification approach that:
1. Extracts all faces as **triangulated** triangle soups (n-gons are fan-triangulated)
2. Classifies each triangle's centroid as inside/outside the other mesh
3. Keeps or discards triangles based on the boolean operation

**Issue**: This fundamentally triangulates all output, breaking downstream nodes that expect quad topology (e.g., subdivision surfaces, quad-based shaders, retopology workflows).

---

## Approach 1: Re-quadrangulation Post-Boolean

### Description
Perform the existing boolean operation, then apply a quad-reconstruction algorithm to the triangulated result.

### Algorithm Options

#### 1a. Instant Quads (Greedy Quad Extraction)
- Walk the triangulated mesh
- Pair adjacent triangles into quads where possible
- Keep remaining triangles as-is (or further process)
- **Pros**: Fast, preserves most of the original geometry
- **Cons**: May leave stray triangles, doesn't guarantee perfect quads

#### 1b. Quad-Dominant Remeshing (e.g., QuadRemesher-style)
- Use edge-collapse/split operations to create quad-dominant topology
- Preserve feature edges and sharp boundaries
- **Pros**: High-quality quad topology, good for subdivision
- **Cons**: Slower, may alter geometry slightly

#### 1c. Catmull-Clark Compatible Remeshing
- Generate a quad mesh suitable for Catmull-Clark subdivision
- Ensure all interior faces are quads, handle boundaries with triangles/n-gons
- **Pros**: Optimized for subdivision surfaces
- **Cons**: May not preserve all features perfectly

### Implementation Complexity
- **Low-Medium**: Can leverage existing HalfEdgeMesh structure
- Need to add quad-extraction or remeshing algorithm
- Java libraries: JOML for math, custom algorithm

### Feasibility: ⭐⭐⭐⭐⭐ (High)
- Minimal changes to existing boolean code
- Can be implemented as a separate node or post-processing step
- Good balance of quality vs. complexity

---

## Approach 2: SDF-Based Boolean + Dual Contouring

### Description
Convert both meshes to Signed Distance Fields (SDFs), perform boolean operations in SDF space, then extract the result using dual contouring.

### Algorithm Steps
1. **Voxelization**: Convert both meshes to voxel grids (SDF representation)
2. **SDF Boolean**: 
   - Union: `min(distA, distB)`
   - Difference: `max(distA, -distB)`
   - Intersect: `max(distA, distB)`
3. **Dual Contouring**: Extract mesh from SDF with quad preservation
   - Place vertices at SDF zero-crossings
   - Create quads between neighboring zero-crossings
   - Optionally preserve feature edges via normal constraints

### Pros
- **Perfect topology control**: Can generate all-quad output
- **Robust**: No self-intersection issues
- **Clean results**: Smooth, manifold meshes
- **Extensible**: Can add smoothing, feature preservation

### Cons
- **Computationally expensive**: Voxelization and dual contouring are heavy
- **Memory intensive**: Voxel grids scale with O(n³)
- **Resolution dependent**: Quality depends on voxel grid resolution
- **Complex implementation**: Requires SDF math, contouring algorithms

### Implementation Complexity
- **High**: Requires SDF infrastructure, voxelization, dual contouring
- May need native libraries or heavy Java implementation
- Best suited for larger refactor

### Feasibility: ⭐⭐⭐ (Medium)
- Technically feasible but high effort
- Best as a future enhancement
- Could be optimized with GPU compute (OpenCL/Vulkan)

---

## Approach 3: Topology-Aware Face Classification

### Description
Modify the existing face-classification boolean to preserve original face topology (quads stay quads, n-gons stay n-gons) where possible.

### Algorithm Steps
1. **Face Classification (Existing)**: Classify each face's centroid as inside/outside
2. **Intersection Detection**: For faces near the intersection boundary:
   - Subdivide only intersected faces (triangulate minimally)
   - Preserve non-intersected faces in original topology
3. **Topology Reconstruction**:
   - Keep original quads intact where possible
   - Only triangulate faces that actually intersect the boundary
   - Rebuild half-edge structure preserving quad neighborhoods

### Pros
- **Preserves topology**: Quads stay quads where not intersected
- **Incremental improvement**: Works with existing boolean code
- **Predictable**: Same output topology as input where possible
- **Fast**: Minimal overhead over current approach

### Cons
- **Complex intersection logic**: Need robust face-face intersection detection
- **Partial solution**: Faces at boolean boundaries will still be triangulated
- **Edge cases**: T-junctions, coincident faces need special handling
- **Half-edge reconstruction**: Non-trivial to rebuild topology correctly

### Implementation Complexity
- **Medium-High**: Requires robust geometric intersection algorithms
- Need to extend HalfEdgeMesh with face-splitting operations
- Java libraries: JOML for math, custom intersection code

### Feasibility: ⭐⭐⭐⭐ (High-Medium)
- Directly addresses the problem
- Can be implemented incrementally
- Best balance of topology preservation vs. implementation cost

---

## Recommendation

### Primary Recommendation: **Approach 1a (Instant Quads Post-Boolean)**

**Rationale**:
1. **Fastest to implement**: Can be added as a separate node or optional boolean mode
2. **Good results**: Preserves most of the original quad topology
3. **Low risk**: Doesn't change existing boolean behavior, just adds post-processing
4. **Extensible**: Can upgrade to full remeshing later if needed

**Implementation Plan**:
1. Create `QuadReconstructionNode` that:
   - Takes a triangulated mesh as input
   - Applies greedy quad pairing algorithm
   - Outputs a quad-dominant mesh
2. Add optional `preserveTopology` flag to `MeshBooleanNode`:
   - When true, automatically applies quad reconstruction
   - When false, uses current triangulating behavior (backward compatible)
3. Test with various boolean operations and mesh types

### Secondary Option: **Approach 3 (Topology-Aware Classification)**

If Approach 1 doesn't meet quality requirements, implement Approach 3 as a more sophisticated solution that preserves topology at the source rather than fixing it afterwards.

### Future Enhancement: **Approach 2 (SDF-Based)**

Consider for future major release when performance is less critical and maximum quality is required. Could be GPU-accelerated for better performance.

---

## Testing Strategy

1. **Topology Metrics**:
   - Quad ratio (quad faces / total faces)
   - Triangle count
   - N-gon count
   - Boundary edge count

2. **Visual Comparison**:
   - Render boolean results with current vs. quad-preserving approach
   - Compare subdivision surface results
   - Check for artifacts, holes, non-manifold geometry

3. **Performance**:
   - Measure boolean operation time
   - Measure quad reconstruction time
   - Compare memory usage

4. **Edge Cases**:
   - Self-intersecting meshes
   - Non-manifold geometry
   - Very thin features
   - Disconnected components

---

## References

1. **Instant Quads Algorithm**:
   - "Greedy Quad Mesh Generation" - various implementations
   - OpenSource: QEM-based quad remeshing, Instant Meshes

2. **Dual Contouring**:
   - "Dual Contouring of Hermite Data" - Ju et al. 2002
   - OpenSource: libigl's dual_contouring, OpenVDB

3. **Topology Preservation**:
   - "Topology-Preserving Mesh Boolean Operations" - various papers
   - CGAL library's boolean operations with topology preservation

---

## Next Steps

1. ✅ Create research document (this file)
2. ⏳ Implement prototype (QuadReconstructionNode)
3. ⏳ Test with boolean operations
4. ⏳ Evaluate results and iterate
5. ⏳ Optional: Add to MeshBooleanNode as preserveTopology flag
