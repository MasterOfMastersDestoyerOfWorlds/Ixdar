# Hand v2 — Connected control cage via grid + region extrude + CC subdivision
# Strategy: Start with a flat grid, region-extrude into a palm slab,
#   then selectively region-extrude finger columns from the top face.
#   Produces a SINGLE connected topology — no seams between palm and fingers.

# ══════════════════════════════════════════════════════════════════════
# PALM — 5x3 grid (5 columns: thumb gap + 4 fingers, 3 rows deep)
# Grid is in XZ plane at Y=0
# ══════════════════════════════════════════════════════════════════════

palm_grid = mesh_grid(u_tiles=5, v_tiles=3, u_total_size=2.2, v_total_size=1.6)
# Grid: X=[-1.1, 1.1], Z=[-0.8, 0.8], each tile = 0.44 x 0.533

# Region-extrude ALL faces to create the palm slab
# Negative offset = extrude in opposite direction (upward for XZ grid)
palm_slab = extrude_mesh(geometry=palm_grid.mesh, offset=-0.35, region=true)

# ══════════════════════════════════════════════════════════════════════
# FINGER SELECTION — top row faces 0-3 (4 finger columns)
# mesh_grid face order: row-major, row 0 = top row (most negative Z)
# Face 0 = leftmost col of top row, Face 3 = 4th col
# Face 4 = 5th col (thumb side — skip for now)
# ══════════════════════════════════════════════════════════════════════

fidx = input_face_index()
sel_f0 = compare(a=fidx.index, b=0.0, mode=EQUAL)
sel_f1 = compare(a=fidx.index, b=1.0, mode=EQUAL)
sel_f2 = compare(a=fidx.index, b=2.0, mode=EQUAL)
sel_f3 = compare(a=fidx.index, b=3.0, mode=EQUAL)
or_01 = boolean_math(a=sel_f0.result, b=sel_f1.result, mode=OR)
or_012 = boolean_math(a=or_01.result, b=sel_f2.result, mode=OR)
finger_sel = boolean_math(a=or_012.result, b=sel_f3.result, mode=OR)

# ══════════════════════════════════════════════════════════════════════
# FINGER EXTRUSION — 3 segments (proximal, middle, distal)
# Use INDIVIDUAL mode (region=false) so each face extrudes separately,
# creating 4 distinct finger columns instead of one merged block.
# ══════════════════════════════════════════════════════════════════════

ext_prox = extrude_mesh(geometry=palm_slab.geometry, offset=-0.5, selection=finger_sel.result)
ext_mid = extrude_mesh(geometry=ext_prox.geometry, offset=-0.4, selection=finger_sel.result)
ext_dist = extrude_mesh(geometry=ext_mid.geometry, offset=-0.3, selection=finger_sel.result)

# ══════════════════════════════════════════════════════════════════════
# THUMB — select face 4 (5th column, thumb side) and extrude
# ══════════════════════════════════════════════════════════════════════

sel_thumb = compare(a=fidx.index, b=4.0, mode=EQUAL)
ext_thumb1 = extrude_mesh(geometry=ext_dist.geometry, offset=-0.4, selection=sel_thumb.result)
ext_thumb2 = extrude_mesh(geometry=ext_thumb1.geometry, offset=-0.35, selection=sel_thumb.result)

# ══════════════════════════════════════════════════════════════════════
# CATMULL-CLARK SUBDIVISION — smooth the cage into organic form
# ══════════════════════════════════════════════════════════════════════

hand_tagged = subdivision_surface(geometry=ext_thumb2.geometry, levels=2)
