# Hand v11 — Bridged Dual Radial fingers connected to palm
# Strategy: palm (box model) + inset top faces → holes + adaptive bridge to finger bases
# Each finger: 3 chained dual_radial_segment + cap, tagged, bridged to palm hole
# CC subdivision for smooth result

# ══════════════════════════════════════════════════════════════════════
# TUNABLE PARAMETERS
# ══════════════════════════════════════════════════════════════════════

palm_x = input_float(name="palm_x", default=0.903, min=0.8, max=3.0)
palm_y = input_float(name="palm_y", default=0.2735, min=0.2, max=1.2)
palm_z = input_float(name="palm_z", default=2.5876, min=2.0, max=6.0)

pinky_1 = input_float(name="pinky_1", default=0.85, min=0.1, max=1.0)
pinky_2 = input_float(name="pinky_2", default=0.64, min=0.1, max=0.8)
pinky_3 = input_float(name="pinky_3", default=0.22, min=0.05, max=0.5)

ring_1 = input_float(name="ring_1", default=1.04, min=0.1, max=1.2)
ring_2 = input_float(name="ring_2", default=0.89, min=0.1, max=1.0)
ring_3 = input_float(name="ring_3", default=0.75, min=0.1, max=0.8)

middle_1 = input_float(name="middle_1", default=1.49, min=0.1, max=1.5)
middle_2 = input_float(name="middle_2", default=1.03, min=0.1, max=1.2)
middle_3 = input_float(name="middle_3", default=0.53, min=0.1, max=0.8)

index_1 = input_float(name="index_1", default=0.65, min=0.1, max=1.2)
index_2 = input_float(name="index_2", default=0.81, min=0.1, max=1.0)
index_3 = input_float(name="index_3", default=0.74, min=0.1, max=0.8)

thumb_1 = input_float(name="thumb_1", default=0.21, min=0.1, max=1.5)
thumb_2 = input_float(name="thumb_2", default=0.48, min=0.1, max=1.0)
thumb_3 = input_float(name="thumb_3", default=0.27, min=0.1, max=0.8)

finger_rx = input_float(name="finger_rx", default=0.28, min=0.1, max=0.5)
finger_ry = input_float(name="finger_ry", default=0.22, min=0.1, max=0.5)
finger_taper = input_float(name="finger_taper", default=0.85, min=0.5, max=1.0)
finger_tip_taper = input_float(name="finger_tip_taper", default=0.6, min=0.3, max=0.9)
finger_tangent = input_float(name="finger_tangent", default=-0.04, min=-0.3, max=0.0)

seg_count = input_int(name="seg_count", default=12, min=6, max=24)
ring_count = input_int(name="ring_count", default=6, min=3, max=16)

forearm_1 = input_float(name="forearm_1", default=0.70, min=0.3, max=2.5)
forearm_2 = input_float(name="forearm_2", default=0.52, min=0.3, max=2.5)
forearm_3 = input_float(name="forearm_3", default=0.81, min=0.3, max=2.5)
forearm_4 = input_float(name="forearm_4", default=0.58, min=0.3, max=2.5)

crease_wrist = input_float(name="crease_wrist", default=1.76, min=0.5, max=5.0)

# ══════════════════════════════════════════════════════════════════════
# DERIVED VALUES
# ══════════════════════════════════════════════════════════════════════

mid_rx = float_math(operation=MULTIPLY, a=finger_rx.result, b=finger_taper.result)
mid_ry = float_math(operation=MULTIPLY, a=finger_ry.result, b=finger_taper.result)
tip_rx = float_math(operation=MULTIPLY, a=mid_rx.result, b=finger_tip_taper.result)
tip_ry = float_math(operation=MULTIPLY, a=mid_ry.result, b=finger_tip_taper.result)

half_palm_y = float_math(operation=MULTIPLY, a=palm_y.result, b=0.5)
col_width = float_math(operation=MULTIPLY, a=palm_z.result, b=0.25)

neg_three_eighths = float_math(operation=MULTIPLY, a=palm_z.result, b=-0.375)
neg_one_eighth = float_math(operation=MULTIPLY, a=palm_z.result, b=-0.125)
pos_one_eighth = float_math(operation=MULTIPLY, a=palm_z.result, b=0.125)
pos_three_eighths = float_math(operation=MULTIPLY, a=palm_z.result, b=0.375)

# ══════════════════════════════════════════════════════════════════════
# PINKY — 3 Hermite-chained segments + cap + tag
# ══════════════════════════════════════════════════════════════════════

pk_prox = dual_radial_segment(
    start_rx=finger_rx.result, start_ry=finger_ry.result,
    start_tx=0.0, start_ty=0.0,
    end_rx=mid_rx.result, end_ry=mid_ry.result,
    end_tx=finger_tangent.result, end_ty=finger_tangent.result,
    length=pinky_1.result, rings=ring_count.result, segments=seg_count.result)

pk_mid = dual_radial_segment(
    geometry=pk_prox.geometry,
    start_rx=pk_prox.end_rx, start_ry=pk_prox.end_ry,
    start_tx=pk_prox.end_tx, start_ty=pk_prox.end_ty,
    end_rx=tip_rx.result, end_ry=tip_ry.result,
    end_tx=finger_tangent.result, end_ty=finger_tangent.result,
    length=pinky_2.result, rings=ring_count.result, segments=seg_count.result)

pk_dist = dual_radial_segment(
    geometry=pk_mid.geometry,
    start_rx=pk_mid.end_rx, start_ry=pk_mid.end_ry,
    start_tx=pk_mid.end_tx, start_ty=pk_mid.end_ty,
    end_rx=0.04, end_ry=0.04,
    end_tx=-0.08, end_ty=-0.08,
    length=pinky_3.result, rings=ring_count.result, segments=seg_count.result)

pk_cap = segment_cap(geometry=pk_dist.geometry, segments=seg_count.result)
pk_pos = combine_xyz(x=0.0, y=half_palm_y.result, z=neg_three_eighths.result)
pk_finger = transform_geometry(geometry=pk_cap.geometry, translation=pk_pos.vector)
pk_tagged = tag_geometry(geometry=pk_finger.geometry, tags="pk_base")

# ══════════════════════════════════════════════════════════════════════
# RING FINGER — 3 segments + cap + tag
# ══════════════════════════════════════════════════════════════════════

rg_prox = dual_radial_segment(
    start_rx=finger_rx.result, start_ry=finger_ry.result,
    start_tx=0.0, start_ty=0.0,
    end_rx=mid_rx.result, end_ry=mid_ry.result,
    end_tx=finger_tangent.result, end_ty=finger_tangent.result,
    length=ring_1.result, rings=ring_count.result, segments=seg_count.result)

rg_mid = dual_radial_segment(
    geometry=rg_prox.geometry,
    start_rx=rg_prox.end_rx, start_ry=rg_prox.end_ry,
    start_tx=rg_prox.end_tx, start_ty=rg_prox.end_ty,
    end_rx=tip_rx.result, end_ry=tip_ry.result,
    end_tx=finger_tangent.result, end_ty=finger_tangent.result,
    length=ring_2.result, rings=ring_count.result, segments=seg_count.result)

rg_dist = dual_radial_segment(
    geometry=rg_mid.geometry,
    start_rx=rg_mid.end_rx, start_ry=rg_mid.end_ry,
    start_tx=rg_mid.end_tx, start_ty=rg_mid.end_ty,
    end_rx=0.04, end_ry=0.04,
    end_tx=-0.08, end_ty=-0.08,
    length=ring_3.result, rings=ring_count.result, segments=seg_count.result)

rg_cap = segment_cap(geometry=rg_dist.geometry, segments=seg_count.result)
rg_pos = combine_xyz(x=0.0, y=half_palm_y.result, z=neg_one_eighth.result)
rg_finger = transform_geometry(geometry=rg_cap.geometry, translation=rg_pos.vector)
rg_tagged = tag_geometry(geometry=rg_finger.geometry, tags="rg_base")

# ══════════════════════════════════════════════════════════════════════
# MIDDLE FINGER — 3 segments + cap + tag
# ══════════════════════════════════════════════════════════════════════

md_prox = dual_radial_segment(
    start_rx=finger_rx.result, start_ry=finger_ry.result,
    start_tx=0.0, start_ty=0.0,
    end_rx=mid_rx.result, end_ry=mid_ry.result,
    end_tx=finger_tangent.result, end_ty=finger_tangent.result,
    length=middle_1.result, rings=ring_count.result, segments=seg_count.result)

md_mid = dual_radial_segment(
    geometry=md_prox.geometry,
    start_rx=md_prox.end_rx, start_ry=md_prox.end_ry,
    start_tx=md_prox.end_tx, start_ty=md_prox.end_ty,
    end_rx=tip_rx.result, end_ry=tip_ry.result,
    end_tx=finger_tangent.result, end_ty=finger_tangent.result,
    length=middle_2.result, rings=ring_count.result, segments=seg_count.result)

md_dist = dual_radial_segment(
    geometry=md_mid.geometry,
    start_rx=md_mid.end_rx, start_ry=md_mid.end_ry,
    start_tx=md_mid.end_tx, start_ty=md_mid.end_ty,
    end_rx=0.04, end_ry=0.04,
    end_tx=-0.08, end_ty=-0.08,
    length=middle_3.result, rings=ring_count.result, segments=seg_count.result)

md_cap = segment_cap(geometry=md_dist.geometry, segments=seg_count.result)
md_pos = combine_xyz(x=0.0, y=half_palm_y.result, z=pos_one_eighth.result)
md_finger = transform_geometry(geometry=md_cap.geometry, translation=md_pos.vector)
md_tagged = tag_geometry(geometry=md_finger.geometry, tags="md_base")

# ══════════════════════════════════════════════════════════════════════
# INDEX FINGER — 3 segments + cap + tag
# ══════════════════════════════════════════════════════════════════════

ix_prox = dual_radial_segment(
    start_rx=finger_rx.result, start_ry=finger_ry.result,
    start_tx=0.0, start_ty=0.0,
    end_rx=mid_rx.result, end_ry=mid_ry.result,
    end_tx=finger_tangent.result, end_ty=finger_tangent.result,
    length=index_1.result, rings=ring_count.result, segments=seg_count.result)

ix_mid = dual_radial_segment(
    geometry=ix_prox.geometry,
    start_rx=ix_prox.end_rx, start_ry=ix_prox.end_ry,
    start_tx=ix_prox.end_tx, start_ty=ix_prox.end_ty,
    end_rx=tip_rx.result, end_ry=tip_ry.result,
    end_tx=finger_tangent.result, end_ty=finger_tangent.result,
    length=index_2.result, rings=ring_count.result, segments=seg_count.result)

ix_dist = dual_radial_segment(
    geometry=ix_mid.geometry,
    start_rx=ix_mid.end_rx, start_ry=ix_mid.end_ry,
    start_tx=ix_mid.end_tx, start_ty=ix_mid.end_ty,
    end_rx=0.04, end_ry=0.04,
    end_tx=-0.08, end_ty=-0.08,
    length=index_3.result, rings=ring_count.result, segments=seg_count.result)

ix_cap = segment_cap(geometry=ix_dist.geometry, segments=seg_count.result)
ix_pos = combine_xyz(x=0.0, y=half_palm_y.result, z=pos_three_eighths.result)
ix_finger = transform_geometry(geometry=ix_cap.geometry, translation=ix_pos.vector)
ix_tagged = tag_geometry(geometry=ix_finger.geometry, tags="ix_base")

# ══════════════════════════════════════════════════════════════════════
# THUMB — 3 segments + cap + tag, rotated to extend along +X
# ══════════════════════════════════════════════════════════════════════

th_prox = dual_radial_segment(
    start_rx=finger_rx.result, start_ry=finger_ry.result,
    start_tx=0.0, start_ty=0.0,
    end_rx=mid_rx.result, end_ry=mid_ry.result,
    end_tx=finger_tangent.result, end_ty=finger_tangent.result,
    length=thumb_1.result, rings=ring_count.result, segments=seg_count.result)

th_mid = dual_radial_segment(
    geometry=th_prox.geometry,
    start_rx=th_prox.end_rx, start_ry=th_prox.end_ry,
    start_tx=th_prox.end_tx, start_ty=th_prox.end_ty,
    end_rx=tip_rx.result, end_ry=tip_ry.result,
    end_tx=finger_tangent.result, end_ty=finger_tangent.result,
    length=thumb_2.result, rings=ring_count.result, segments=seg_count.result)

th_dist = dual_radial_segment(
    geometry=th_mid.geometry,
    start_rx=th_mid.end_rx, start_ry=th_mid.end_ry,
    start_tx=th_mid.end_tx, start_ty=th_mid.end_ty,
    end_rx=0.04, end_ry=0.04,
    end_tx=-0.08, end_ty=-0.08,
    length=thumb_3.result, rings=ring_count.result, segments=seg_count.result)

th_cap = segment_cap(geometry=th_dist.geometry, segments=seg_count.result)
half_palm_x = float_math(operation=MULTIPLY, a=palm_x.result, b=0.5)
thumb_z_pos = float_math(operation=MULTIPLY, a=palm_z.result, b=-0.15)
th_pos = combine_xyz(x=half_palm_x.result, y=0.0, z=thumb_z_pos.result)
th_finger = transform_geometry(geometry=th_cap.geometry, translation=th_pos.vector, rotation=<0.0, 0.0, -1.5708>)
th_tagged = tag_geometry(geometry=th_finger.geometry, tags="th_base")

# ══════════════════════════════════════════════════════════════════════
# PALM — box model (cube → loop_cut → scale)
# ══════════════════════════════════════════════════════════════════════

palm_cube = cube(size=1.0)
palm_cut = loop_cut(mesh=palm_cube.mesh, axis=Z, cuts=3)
palm_scale = combine_xyz(x=palm_x.result, y=palm_y.result, z=palm_z.result)
palm = transform_geometry(geometry=palm_cut.geometry, scale=palm_scale.vector)

# ══════════════════════════════════════════════════════════════════════
# FOREARM — extrude from palm bottom faces (indices 2,3,4,5)
# ══════════════════════════════════════════════════════════════════════

fidx = input_face_index()

sel_2 = compare(a=fidx.result, b=2.0, mode=EQUAL)
sel_3 = compare(a=fidx.result, b=3.0, mode=EQUAL)
sel_4 = compare(a=fidx.result, b=4.0, mode=EQUAL)
sel_5 = compare(a=fidx.result, b=5.0, mode=EQUAL)
or_23 = boolean_math(a=sel_2.value, b=sel_3.value, operation=OR)
or_234 = boolean_math(a=or_23.value, b=sel_4.value, operation=OR)
forearm_sel = boolean_math(a=or_234.value, b=sel_5.value, operation=OR)

ext_a1 = extrude_mesh(geometry=palm.geometry, offset=forearm_1.result, selection=forearm_sel.value, region=true)
ext_a2 = extrude_mesh(geometry=ext_a1.geometry, offset=forearm_2.result, selection=forearm_sel.value, region=true)
ext_a3 = extrude_mesh(geometry=ext_a2.geometry, offset=forearm_3.result, selection=forearm_sel.value, region=true)
ext_a4 = extrude_mesh(geometry=ext_a3.geometry, offset=forearm_4.result, selection=forearm_sel.value, region=true)

# ══════════════════════════════════════════════════════════════════════
# PALM HOLES — inset top 4 faces (indices 6,7,8,9), remove inner faces
# ══════════════════════════════════════════════════════════════════════

sel_6 = compare(a=fidx.result, b=6.0, mode=EQUAL)
sel_7 = compare(a=fidx.result, b=7.0, mode=EQUAL)
sel_8 = compare(a=fidx.result, b=8.0, mode=EQUAL)
sel_9 = compare(a=fidx.result, b=9.0, mode=EQUAL)
or_67 = boolean_math(a=sel_6.value, b=sel_7.value, operation=OR)
or_89 = boolean_math(a=sel_8.value, b=sel_9.value, operation=OR)
top_sel = boolean_math(a=or_67.value, b=or_89.value, operation=OR)

# Thumb hole: face 10 is +X side near -Z
sel_10 = compare(a=fidx.result, b=10.0, mode=EQUAL)
hole_sel = boolean_math(a=top_sel.value, b=sel_10.value, operation=OR)

palm_inset = inset_faces(geometry=ext_a4.geometry, inset=0.12, selection=hole_sel.value)
palm_holes = separate_geometry(geometry=palm_inset.geometry, selection=hole_sel.value)

# ══════════════════════════════════════════════════════════════════════
# JOIN — palm with holes + all 5 tagged finger tubes (including thumb)
# ══════════════════════════════════════════════════════════════════════

join_pk_rg = join_geometry(a=pk_tagged.geometry, b=rg_tagged.geometry)
join_md_ix = join_geometry(a=md_tagged.geometry, b=ix_tagged.geometry)
join_4fingers = join_geometry(a=join_pk_rg.geometry, b=join_md_ix.geometry)
join_5fingers = join_geometry(a=join_4fingers.geometry, b=th_tagged.geometry)
join_all = join_geometry(a=palm_holes.inverted, b=join_5fingers.geometry)

# ══════════════════════════════════════════════════════════════════════
# BRIDGE — connect each finger base ring to nearest palm hole
# ══════════════════════════════════════════════════════════════════════

bridge_pk = adaptive_bridge_loops(geometry=join_all.geometry, loop_a_tag="pk_base", segments=1)
bridge_rg = adaptive_bridge_loops(geometry=bridge_pk.geometry, loop_a_tag="rg_base", segments=1)
bridge_md = adaptive_bridge_loops(geometry=bridge_rg.geometry, loop_a_tag="md_base", segments=1)
bridge_ix = adaptive_bridge_loops(geometry=bridge_md.geometry, loop_a_tag="ix_base", segments=1)
bridge_th = adaptive_bridge_loops(geometry=bridge_ix.geometry, loop_a_tag="th_base", segments=1)

# ══════════════════════════════════════════════════════════════════════
# CREASE + SUBDIVISION
# ══════════════════════════════════════════════════════════════════════

crease_w = mark_edges(geometry=bridge_th.geometry, label="crease", type="FLOAT", value_float=crease_wrist.result, selection=forearm_sel.value, face_boundary=true)
smooth = subdivision_surface(geometry=crease_w.geometry, levels=2)

# ══════════════════════════════════════════════════════════════════════
# OUTPUT
# ══════════════════════════════════════════════════════════════════════

out = transform_geometry(geometry=smooth.geometry, scale=<1.0, 1.0, 1.0>)
