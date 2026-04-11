# Hand v12 — Posed hand with per-joint FK bone curling
# Based on v11_bridged: palm box + 5 finger tubes + adaptive bridge + CC subdivision
# New: per-phalanx bone weights + apply_bone FK chain for pose matching

# ══════════════════════════════════════════════════════════════════════
# SHAPE PARAMETERS (same as v11)
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
# CURL PARAMETERS — 15 joints (5 fingers × 3 joints)
# Negative values curl forward (close hand), positive extend
# ══════════════════════════════════════════════════════════════════════

pk_mcp_curl = input_float(name="pk_mcp_curl", default=-0.5, min=-2.0, max=0.5)
pk_pip_curl = input_float(name="pk_pip_curl", default=-0.8, min=-2.0, max=0.5)
pk_dip_curl = input_float(name="pk_dip_curl", default=-0.4, min=-2.0, max=0.5)

rg_mcp_curl = input_float(name="rg_mcp_curl", default=-0.5, min=-2.0, max=0.5)
rg_pip_curl = input_float(name="rg_pip_curl", default=-0.8, min=-2.0, max=0.5)
rg_dip_curl = input_float(name="rg_dip_curl", default=-0.4, min=-2.0, max=0.5)

md_mcp_curl = input_float(name="md_mcp_curl", default=-0.5, min=-2.0, max=0.5)
md_pip_curl = input_float(name="md_pip_curl", default=-0.8, min=-2.0, max=0.5)
md_dip_curl = input_float(name="md_dip_curl", default=-0.4, min=-2.0, max=0.5)

ix_mcp_curl = input_float(name="ix_mcp_curl", default=-0.5, min=-2.0, max=0.5)
ix_pip_curl = input_float(name="ix_pip_curl", default=-0.8, min=-2.0, max=0.5)
ix_dip_curl = input_float(name="ix_dip_curl", default=-0.4, min=-2.0, max=0.5)

th_mcp_curl = input_float(name="th_mcp_curl", default=-0.3, min=-2.0, max=2.0)
th_pip_curl = input_float(name="th_pip_curl", default=-0.5, min=-2.0, max=2.0)
th_dip_curl = input_float(name="th_dip_curl", default=-0.3, min=-2.0, max=2.0)

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

# Face-index thresholds for per-segment bone weight painting
# Each segment has seg_count * ring_count faces
faces_per_seg_f = float_math(operation=MULTIPLY, a=seg_count.result, b=ring_count.result)
mid_face_thresh = float_math(operation=SUBTRACT, a=faces_per_seg_f.result, b=0.5)
dist_face_start = float_math(operation=MULTIPLY, a=faces_per_seg_f.result, b=2.0)
dist_face_thresh = float_math(operation=SUBTRACT, a=dist_face_start.result, b=0.5)

# ══════════════════════════════════════════════════════════════════════
# PINKY — 3 segments + cap + per-phalanx bone weights
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

# Face index field for pinky (evaluated with pk_cap mesh context)
pk_fidx = input_face_index()
pk_mid_sel = compare(a=pk_fidx.index, b=mid_face_thresh.result, mode=GREATER)
pk_dist_sel = compare(a=pk_fidx.index, b=dist_face_thresh.result, mode=GREATER)

# Paint per-phalanx weights: prox=all, mid=mid+dist+cap, dist=dist+cap
pk_w1 = set_bone_weight(geometry=pk_cap.geometry, bone_name="pk_prox", weight=1.0)
pk_w2 = set_bone_weight(geometry=pk_w1.geometry, bone_name="pk_mid", weight=1.0, selection=pk_mid_sel.result)
pk_w3 = set_bone_weight(geometry=pk_w2.geometry, bone_name="pk_dist", weight=1.0, selection=pk_dist_sel.result)

pk_pos = combine_xyz(x=0.0, y=half_palm_y.result, z=neg_three_eighths.result)
pk_finger = transform_geometry(geometry=pk_w3.geometry, translation=pk_pos.vector)
pk_tagged = tag_geometry(geometry=pk_finger.geometry, tags="pk_base")

# ══════════════════════════════════════════════════════════════════════
# RING FINGER — 3 segments + cap + per-phalanx bone weights
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

rg_fidx = input_face_index()
rg_mid_sel = compare(a=rg_fidx.index, b=mid_face_thresh.result, mode=GREATER)
rg_dist_sel = compare(a=rg_fidx.index, b=dist_face_thresh.result, mode=GREATER)

rg_w1 = set_bone_weight(geometry=rg_cap.geometry, bone_name="rg_prox", weight=1.0)
rg_w2 = set_bone_weight(geometry=rg_w1.geometry, bone_name="rg_mid", weight=1.0, selection=rg_mid_sel.result)
rg_w3 = set_bone_weight(geometry=rg_w2.geometry, bone_name="rg_dist", weight=1.0, selection=rg_dist_sel.result)

rg_pos = combine_xyz(x=0.0, y=half_palm_y.result, z=neg_one_eighth.result)
rg_finger = transform_geometry(geometry=rg_w3.geometry, translation=rg_pos.vector)
rg_tagged = tag_geometry(geometry=rg_finger.geometry, tags="rg_base")

# ══════════════════════════════════════════════════════════════════════
# MIDDLE FINGER — 3 segments + cap + per-phalanx bone weights
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

md_fidx = input_face_index()
md_mid_sel = compare(a=md_fidx.index, b=mid_face_thresh.result, mode=GREATER)
md_dist_sel = compare(a=md_fidx.index, b=dist_face_thresh.result, mode=GREATER)

md_w1 = set_bone_weight(geometry=md_cap.geometry, bone_name="md_prox", weight=1.0)
md_w2 = set_bone_weight(geometry=md_w1.geometry, bone_name="md_mid", weight=1.0, selection=md_mid_sel.result)
md_w3 = set_bone_weight(geometry=md_w2.geometry, bone_name="md_dist", weight=1.0, selection=md_dist_sel.result)

md_pos = combine_xyz(x=0.0, y=half_palm_y.result, z=pos_one_eighth.result)
md_finger = transform_geometry(geometry=md_w3.geometry, translation=md_pos.vector)
md_tagged = tag_geometry(geometry=md_finger.geometry, tags="md_base")

# ══════════════════════════════════════════════════════════════════════
# INDEX FINGER — 3 segments + cap + per-phalanx bone weights
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

ix_fidx = input_face_index()
ix_mid_sel = compare(a=ix_fidx.index, b=mid_face_thresh.result, mode=GREATER)
ix_dist_sel = compare(a=ix_fidx.index, b=dist_face_thresh.result, mode=GREATER)

ix_w1 = set_bone_weight(geometry=ix_cap.geometry, bone_name="ix_prox", weight=1.0)
ix_w2 = set_bone_weight(geometry=ix_w1.geometry, bone_name="ix_mid", weight=1.0, selection=ix_mid_sel.result)
ix_w3 = set_bone_weight(geometry=ix_w2.geometry, bone_name="ix_dist", weight=1.0, selection=ix_dist_sel.result)

ix_pos = combine_xyz(x=0.0, y=half_palm_y.result, z=pos_three_eighths.result)
ix_finger = transform_geometry(geometry=ix_w3.geometry, translation=ix_pos.vector)
ix_tagged = tag_geometry(geometry=ix_finger.geometry, tags="ix_base")

# ══════════════════════════════════════════════════════════════════════
# THUMB — 3 segments + cap + bone weights, rotated to extend along +X
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

th_fidx = input_face_index()
th_mid_sel = compare(a=th_fidx.index, b=mid_face_thresh.result, mode=GREATER)
th_dist_sel = compare(a=th_fidx.index, b=dist_face_thresh.result, mode=GREATER)

th_w1 = set_bone_weight(geometry=th_cap.geometry, bone_name="th_prox", weight=1.0)
th_w2 = set_bone_weight(geometry=th_w1.geometry, bone_name="th_mid", weight=1.0, selection=th_mid_sel.result)
th_w3 = set_bone_weight(geometry=th_w2.geometry, bone_name="th_dist", weight=1.0, selection=th_dist_sel.result)

half_palm_x = float_math(operation=MULTIPLY, a=palm_x.result, b=0.5)
thumb_z_pos = float_math(operation=MULTIPLY, a=palm_z.result, b=-0.15)
th_pos = combine_xyz(x=half_palm_x.result, y=0.0, z=thumb_z_pos.result)
th_finger = transform_geometry(geometry=th_w3.geometry, translation=th_pos.vector, rotation=<0.0, 0.0, -1.5708>)
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

sel_2 = compare(a=fidx.index, b=2.0, mode=EQUAL)
sel_3 = compare(a=fidx.index, b=3.0, mode=EQUAL)
sel_4 = compare(a=fidx.index, b=4.0, mode=EQUAL)
sel_5 = compare(a=fidx.index, b=5.0, mode=EQUAL)
or_23 = boolean_math(a=sel_2.result, b=sel_3.result, mode=OR)
or_234 = boolean_math(a=or_23.result, b=sel_4.result, mode=OR)
forearm_sel = boolean_math(a=or_234.result, b=sel_5.result, mode=OR)

ext_a1 = extrude_mesh(geometry=palm.geometry, offset=forearm_1.result, selection=forearm_sel.result, region=true)
ext_a2 = extrude_mesh(geometry=ext_a1.geometry, offset=forearm_2.result, selection=forearm_sel.result, region=true)
ext_a3 = extrude_mesh(geometry=ext_a2.geometry, offset=forearm_3.result, selection=forearm_sel.result, region=true)
ext_a4 = extrude_mesh(geometry=ext_a3.geometry, offset=forearm_4.result, selection=forearm_sel.result, region=true)

# ══════════════════════════════════════════════════════════════════════
# PALM HOLES — inset top 4 faces (6,7,8,9) + thumb face (10)
# ══════════════════════════════════════════════════════════════════════

sel_6 = compare(a=fidx.index, b=6.0, mode=EQUAL)
sel_7 = compare(a=fidx.index, b=7.0, mode=EQUAL)
sel_8 = compare(a=fidx.index, b=8.0, mode=EQUAL)
sel_9 = compare(a=fidx.index, b=9.0, mode=EQUAL)
or_67 = boolean_math(a=sel_6.result, b=sel_7.result, mode=OR)
or_89 = boolean_math(a=sel_8.result, b=sel_9.result, mode=OR)
top_sel = boolean_math(a=or_67.result, b=or_89.result, mode=OR)

sel_10 = compare(a=fidx.index, b=10.0, mode=EQUAL)
hole_sel = boolean_math(a=top_sel.result, b=sel_10.result, mode=OR)

palm_inset = inset_faces(geometry=ext_a4.geometry, inset=0.12, selection=hole_sel.result)
palm_holes = separate_geometry(geometry=palm_inset.geometry, selection=hole_sel.result)

# ══════════════════════════════════════════════════════════════════════
# JOIN — palm with holes + all 5 tagged finger tubes
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
# CREASE
# ══════════════════════════════════════════════════════════════════════

crease_w = mark_crease(geometry=bridge_th.geometry, weight=crease_wrist.result, selection=forearm_sel.result, face_boundary=true)

# ══════════════════════════════════════════════════════════════════════
# POSE — FK bone chain on control cage (before subdivision)
# Apply tip-to-root: DIP → PIP → MCP for each finger
# ══════════════════════════════════════════════════════════════════════

# --- Pinky pivots ---
pk_pip_y = float_math(operation=ADD, a=half_palm_y.result, b=pinky_1.result)
pk_dip_y = float_math(operation=ADD, a=pk_pip_y.result, b=pinky_2.result)
pk_dip_pivot = combine_xyz(x=0.0, y=pk_dip_y.result, z=neg_three_eighths.result)
pk_pip_pivot = combine_xyz(x=0.0, y=pk_pip_y.result, z=neg_three_eighths.result)
pk_mcp_pivot = combine_xyz(x=0.0, y=half_palm_y.result, z=neg_three_eighths.result)
pk_dip_rot = combine_xyz(x=pk_dip_curl.result, y=0.0, z=0.0)
pk_pip_rot = combine_xyz(x=pk_pip_curl.result, y=0.0, z=0.0)
pk_mcp_rot = combine_xyz(x=pk_mcp_curl.result, y=0.0, z=0.0)

pose_pk_dip = apply_bone(geometry=crease_w.geometry, bone_name="pk_dist", rotation=pk_dip_rot.vector, pivot=pk_dip_pivot.vector)
pose_pk_pip = apply_bone(geometry=pose_pk_dip.geometry, bone_name="pk_mid", rotation=pk_pip_rot.vector, pivot=pk_pip_pivot.vector)
pose_pk_mcp = apply_bone(geometry=pose_pk_pip.geometry, bone_name="pk_prox", rotation=pk_mcp_rot.vector, pivot=pk_mcp_pivot.vector)

# --- Ring pivots ---
rg_pip_y = float_math(operation=ADD, a=half_palm_y.result, b=ring_1.result)
rg_dip_y = float_math(operation=ADD, a=rg_pip_y.result, b=ring_2.result)
rg_dip_pivot = combine_xyz(x=0.0, y=rg_dip_y.result, z=neg_one_eighth.result)
rg_pip_pivot = combine_xyz(x=0.0, y=rg_pip_y.result, z=neg_one_eighth.result)
rg_mcp_pivot = combine_xyz(x=0.0, y=half_palm_y.result, z=neg_one_eighth.result)
rg_dip_rot = combine_xyz(x=rg_dip_curl.result, y=0.0, z=0.0)
rg_pip_rot = combine_xyz(x=rg_pip_curl.result, y=0.0, z=0.0)
rg_mcp_rot = combine_xyz(x=rg_mcp_curl.result, y=0.0, z=0.0)

pose_rg_dip = apply_bone(geometry=pose_pk_mcp.geometry, bone_name="rg_dist", rotation=rg_dip_rot.vector, pivot=rg_dip_pivot.vector)
pose_rg_pip = apply_bone(geometry=pose_rg_dip.geometry, bone_name="rg_mid", rotation=rg_pip_rot.vector, pivot=rg_pip_pivot.vector)
pose_rg_mcp = apply_bone(geometry=pose_rg_pip.geometry, bone_name="rg_prox", rotation=rg_mcp_rot.vector, pivot=rg_mcp_pivot.vector)

# --- Middle pivots ---
md_pip_y = float_math(operation=ADD, a=half_palm_y.result, b=middle_1.result)
md_dip_y = float_math(operation=ADD, a=md_pip_y.result, b=middle_2.result)
md_dip_pivot = combine_xyz(x=0.0, y=md_dip_y.result, z=pos_one_eighth.result)
md_pip_pivot = combine_xyz(x=0.0, y=md_pip_y.result, z=pos_one_eighth.result)
md_mcp_pivot = combine_xyz(x=0.0, y=half_palm_y.result, z=pos_one_eighth.result)
md_dip_rot = combine_xyz(x=md_dip_curl.result, y=0.0, z=0.0)
md_pip_rot = combine_xyz(x=md_pip_curl.result, y=0.0, z=0.0)
md_mcp_rot = combine_xyz(x=md_mcp_curl.result, y=0.0, z=0.0)

pose_md_dip = apply_bone(geometry=pose_rg_mcp.geometry, bone_name="md_dist", rotation=md_dip_rot.vector, pivot=md_dip_pivot.vector)
pose_md_pip = apply_bone(geometry=pose_md_dip.geometry, bone_name="md_mid", rotation=md_pip_rot.vector, pivot=md_pip_pivot.vector)
pose_md_mcp = apply_bone(geometry=pose_md_pip.geometry, bone_name="md_prox", rotation=md_mcp_rot.vector, pivot=md_mcp_pivot.vector)

# --- Index pivots ---
ix_pip_y = float_math(operation=ADD, a=half_palm_y.result, b=index_1.result)
ix_dip_y = float_math(operation=ADD, a=ix_pip_y.result, b=index_2.result)
ix_dip_pivot = combine_xyz(x=0.0, y=ix_dip_y.result, z=pos_three_eighths.result)
ix_pip_pivot = combine_xyz(x=0.0, y=ix_pip_y.result, z=pos_three_eighths.result)
ix_mcp_pivot = combine_xyz(x=0.0, y=half_palm_y.result, z=pos_three_eighths.result)
ix_dip_rot = combine_xyz(x=ix_dip_curl.result, y=0.0, z=0.0)
ix_pip_rot = combine_xyz(x=ix_pip_curl.result, y=0.0, z=0.0)
ix_mcp_rot = combine_xyz(x=ix_mcp_curl.result, y=0.0, z=0.0)

pose_ix_dip = apply_bone(geometry=pose_md_mcp.geometry, bone_name="ix_dist", rotation=ix_dip_rot.vector, pivot=ix_dip_pivot.vector)
pose_ix_pip = apply_bone(geometry=pose_ix_dip.geometry, bone_name="ix_mid", rotation=ix_pip_rot.vector, pivot=ix_pip_pivot.vector)
pose_ix_mcp = apply_bone(geometry=pose_ix_pip.geometry, bone_name="ix_prox", rotation=ix_mcp_rot.vector, pivot=ix_mcp_pivot.vector)

# --- Thumb pivots ---
# Thumb is rotated -90deg Z, so it extends along +X from (half_palm_x, 0, thumb_z_pos)
# Curl axis for thumb is Z (perpendicular to thumb extension direction)
th_pip_x = float_math(operation=ADD, a=half_palm_x.result, b=thumb_1.result)
th_dip_x = float_math(operation=ADD, a=th_pip_x.result, b=thumb_2.result)
th_dip_pivot = combine_xyz(x=th_dip_x.result, y=0.0, z=thumb_z_pos.result)
th_pip_pivot = combine_xyz(x=th_pip_x.result, y=0.0, z=thumb_z_pos.result)
th_mcp_pivot = combine_xyz(x=half_palm_x.result, y=0.0, z=thumb_z_pos.result)
th_dip_rot = combine_xyz(x=0.0, y=0.0, z=th_dip_curl.result)
th_pip_rot = combine_xyz(x=0.0, y=0.0, z=th_pip_curl.result)
th_mcp_rot = combine_xyz(x=0.0, y=0.0, z=th_mcp_curl.result)

pose_th_dip = apply_bone(geometry=pose_ix_mcp.geometry, bone_name="th_dist", rotation=th_dip_rot.vector, pivot=th_dip_pivot.vector)
pose_th_pip = apply_bone(geometry=pose_th_dip.geometry, bone_name="th_mid", rotation=th_pip_rot.vector, pivot=th_pip_pivot.vector)
pose_th_mcp = apply_bone(geometry=pose_th_pip.geometry, bone_name="th_prox", rotation=th_mcp_rot.vector, pivot=th_mcp_pivot.vector)

# ══════════════════════════════════════════════════════════════════════
# SUBDIVISION
# ══════════════════════════════════════════════════════════════════════

smooth = subdivision_surface(geometry=pose_th_mcp.geometry, levels=2)

# ══════════════════════════════════════════════════════════════════════
# OUTPUT
# ══════════════════════════════════════════════════════════════════════

out = transform_geometry(geometry=smooth.geometry, scale=<1.0, 1.0, 1.0>)
