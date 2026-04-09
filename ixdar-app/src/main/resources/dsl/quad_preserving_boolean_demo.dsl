# Quad-Preserving Boolean Operations Demo
# This DSL demonstrates the quad-reconstruction approach for preserving topology
# after boolean operations.

# ── Create base cube ──────────────────────────────────────────────────────────
mesh_cube base_cube = mesh_primitive_cube(
    size=2.0,
    center=[0, 0, 0],
    segments=[4, 4, 4]  # Quad-dominant base
)

# ── Create intersecting cylinder ──────────────────────────────────────────────
mesh_cylinder intersect_cyl = mesh_primitive_cylinder(
    radius=0.8,
    height=3.0,
    center=[0, 0, 0],
    segments=16  # Quad-dominant base
)

# ── Boolean intersection (current implementation triangulates) ────────────────
mesh_bool intersect_result = mesh_boolean(
    mesh_a=base_cube,
    mesh_b=intersect_cyl,
    operation="INTERSECT"
)
# Output: All triangles (mesh_boolean triangulates everything)

# ── Quad reconstruction to restore quad topology ──────────────────────────────
mesh_quad_reconstructed = quad_reconstruct(
    mesh=intersect_result,
    mode="GREEDY"  # Try "CATMULL_CLARK" for subdivision-ready topology
)
# Output: Quad-dominant mesh with some triangles where quads couldn't be formed

# ── Alternative: Difference with quad reconstruction ───────────────────────────
mesh_bool diff_result = mesh_boolean(
    mesh_a=base_cube,
    mesh_b=intersect_cyl,
    operation="DIFFERENCE"
)

mesh_quad_diff = quad_reconstruct(
    mesh=diff_result,
    mode="GREEDY"
)

# ── Alternative: Union with quad reconstruction ────────────────────────────────
mesh_bool union_result = mesh_boolean(
    mesh_a=base_cube,
    mesh_b=intersect_cyl,
    operation="UNION"
)

mesh_quad_union = quad_reconstruct(
    mesh=union_result,
    mode="GREEDY"
)

# ── Visualization ──────────────────────────────────────────────────────────────
# Compare the triangulated vs quad-reconstructed results
# Note: The quad_reconstruct node will output a mesh with mixed topology
# (mostly quads, some triangles where greedy pairing failed)

# Output the results for comparison
output("triangulated_intersect", intersect_result)
output("quad_intersect", mesh_quad_reconstructed)
output("triangulated_diff", diff_result)
output("quad_diff", mesh_quad_diff)
output("triangulated_union", union_result)
output("quad_union", mesh_quad_union)
