# Hand v9 — Per-finger lengths + tapering + bone weights
# Strategy: cube → loop_cut(Z,3) → inset → per-finger extrude+taper → thumb → forearm → crease → CC → bones
# All numeric values are input_float with min/max for optimizer

# ══════════════════════════════════════════════════════════════════════
# TUNABLE PARAMETERS
# ══════════════════════════════════════════════════════════════════════

palm_x = input_float(name="palm_x", default=0.903, min=0.8, max=3.0)
palm_y = input_float(name="palm_y", default=0.2735, min=0.2, max=1.2)
palm_z = input_float(name="palm_z", default=2.5876, min=2.0, max=6.0)
inset_amt = input_float(name="inset_amt", default=0.1257, min=0.05, max=0.45)

# Per-finger segment lengths (3 phalanges each) — optimizer-tuned for 84.9% similarity
pinky_1 = input_float(name="pinky_1", default=0.8481, min=0.1, max=1.0)
pinky_2 = input_float(name="pinky_2", default=0.6422, min=0.1, max=0.8)
pinky_3 = input_float(name="pinky_3", default=0.2218, min=0.05, max=0.5)

ring_1 = input_float(name="ring_1", default=1.0433, min=0.1, max=1.2)
ring_2 = input_float(name="ring_2", default=0.8948, min=0.1, max=1.0)
ring_3 = input_float(name="ring_3", default=0.7485, min=0.1, max=0.8)

middle_1 = input_float(name="middle_1", default=1.4929, min=0.1, max=1.5)
middle_2 = input_float(name="middle_2", default=1.0274, min=0.1, max=1.2)
middle_3 = input_float(name="middle_3", default=0.5286, min=0.1, max=0.8)

index_1 = input_float(name="index_1", default=0.6451, min=0.1, max=1.2)
index_2 = input_float(name="index_2", default=0.8085, min=0.1, max=1.0)
index_3 = input_float(name="index_3", default=0.7397, min=0.1, max=0.8)

finger_taper = input_float(name="finger_taper", default=0.1267, min=0.0, max=0.35)

thumb_1 = input_float(name="thumb_1", default=0.2135, min=0.2, max=1.5)
thumb_2 = input_float(name="thumb_2", default=0.4824, min=0.1, max=1.0)
thumb_3 = input_float(name="thumb_3", default=0.2733, min=0.1, max=0.8)

forearm_1 = input_float(name="forearm_1", default=0.701, min=0.3, max=2.5)
forearm_2 = input_float(name="forearm_2", default=0.519, min=0.3, max=2.5)
forearm_3 = input_float(name="forearm_3", default=0.8131, min=0.3, max=2.5)
forearm_4 = input_float(name="forearm_4", default=0.58, min=0.3, max=2.5)

crease_knuckle = input_float(name="crease_knuckle", default=0.8707, min=0.5, max=6.0)
crease_wrist = input_float(name="crease_wrist", default=1.7576, min=0.5, max=5.0)
crease_thumb = input_float(name="crease_thumb", default=4.671, min=0.5, max=5.0)

# ══════════════════════════════════════════════════════════════════════
# PALM — cube with loop_cut for finger row topology
# ══════════════════════════════════════════════════════════════════════
# cube → loop_cut(Z,3) → 18 faces
# Face map: 0,1=front/back(unchanged), 2-5=bottom, 6-9=top, 10-13=right, 14-17=left

palm_cube = cube(size=1.0)
palm_cut = loop_cut(mesh=palm_cube.mesh, axis=Z, cuts=3)
palm_scale = combine_xyz(x=palm_x.result, y=palm_y.result, z=palm_z.result)
palm = transform_geometry(geometry=palm_cut.geometry, scale=palm_scale.vector)

# ══════════════════════════════════════════════════════════════════════
# FACE SELECTIONS
# ══════════════════════════════════════════════════════════════════════

fidx = input_face_index()

# Top faces 6-9: individual finger bases
sel_6 = compare(a=fidx.result, b=6.0, mode=EQUAL)
sel_7 = compare(a=fidx.result, b=7.0, mode=EQUAL)
sel_8 = compare(a=fidx.result, b=8.0, mode=EQUAL)
sel_9 = compare(a=fidx.result, b=9.0, mode=EQUAL)
or_67 = boolean_math(a=sel_6.value, b=sel_7.value, operation=OR)
or_678 = boolean_math(a=or_67.value, b=sel_8.value, operation=OR)
finger_sel = boolean_math(a=or_678.value, b=sel_9.value, operation=OR)

# Right face strip at palm base (face 11) for thumb
sel_thumb = compare(a=fidx.result, b=11.0, mode=EQUAL)

# All digits for inset
digit_sel = boolean_math(a=finger_sel.value, b=sel_thumb.value, operation=OR)

# Bottom faces 2-5 for forearm
sel_2 = compare(a=fidx.result, b=2.0, mode=EQUAL)
sel_3 = compare(a=fidx.result, b=3.0, mode=EQUAL)
sel_4 = compare(a=fidx.result, b=4.0, mode=EQUAL)
sel_5 = compare(a=fidx.result, b=5.0, mode=EQUAL)
or_23 = boolean_math(a=sel_2.value, b=sel_3.value, operation=OR)
or_234 = boolean_math(a=or_23.value, b=sel_4.value, operation=OR)
forearm_sel = boolean_math(a=or_234.value, b=sel_5.value, operation=OR)

# ══════════════════════════════════════════════════════════════════════
# INSET — gap between fingers/thumb before extrusion
# ══════════════════════════════════════════════════════════════════════

palm_inset = inset_faces(geometry=palm.geometry, inset=inset_amt.result, selection=digit_sel.value)

# ══════════════════════════════════════════════════════════════════════
# PER-FINGER EXTRUSION — 3 segments each with taper insets
# Face index stability: extrude replaces original face slot with tip face
# ══════════════════════════════════════════════════════════════════════

# Pinky (face 6) — shortest finger
ext_pk_1 = extrude_mesh(geometry=palm_inset.geometry, offset=pinky_1.result, selection=sel_6.value)
taper_pk_1 = inset_faces(geometry=ext_pk_1.geometry, inset=finger_taper.result, selection=sel_6.value)
ext_pk_2 = extrude_mesh(geometry=taper_pk_1.geometry, offset=pinky_2.result, selection=sel_6.value)
taper_pk_2 = inset_faces(geometry=ext_pk_2.geometry, inset=finger_taper.result, selection=sel_6.value)
ext_pk_3 = extrude_mesh(geometry=taper_pk_2.geometry, offset=pinky_3.result, selection=sel_6.value)

# Ring (face 7)
ext_rg_1 = extrude_mesh(geometry=ext_pk_3.geometry, offset=ring_1.result, selection=sel_7.value)
taper_rg_1 = inset_faces(geometry=ext_rg_1.geometry, inset=finger_taper.result, selection=sel_7.value)
ext_rg_2 = extrude_mesh(geometry=taper_rg_1.geometry, offset=ring_2.result, selection=sel_7.value)
taper_rg_2 = inset_faces(geometry=ext_rg_2.geometry, inset=finger_taper.result, selection=sel_7.value)
ext_rg_3 = extrude_mesh(geometry=taper_rg_2.geometry, offset=ring_3.result, selection=sel_7.value)

# Middle (face 8) — longest finger
ext_md_1 = extrude_mesh(geometry=ext_rg_3.geometry, offset=middle_1.result, selection=sel_8.value)
taper_md_1 = inset_faces(geometry=ext_md_1.geometry, inset=finger_taper.result, selection=sel_8.value)
ext_md_2 = extrude_mesh(geometry=taper_md_1.geometry, offset=middle_2.result, selection=sel_8.value)
taper_md_2 = inset_faces(geometry=ext_md_2.geometry, inset=finger_taper.result, selection=sel_8.value)
ext_md_3 = extrude_mesh(geometry=taper_md_2.geometry, offset=middle_3.result, selection=sel_8.value)

# Index (face 9)
ext_ix_1 = extrude_mesh(geometry=ext_md_3.geometry, offset=index_1.result, selection=sel_9.value)
taper_ix_1 = inset_faces(geometry=ext_ix_1.geometry, inset=finger_taper.result, selection=sel_9.value)
ext_ix_2 = extrude_mesh(geometry=taper_ix_1.geometry, offset=index_2.result, selection=sel_9.value)
taper_ix_2 = inset_faces(geometry=ext_ix_2.geometry, inset=finger_taper.result, selection=sel_9.value)
ext_ix_3 = extrude_mesh(geometry=taper_ix_2.geometry, offset=index_3.result, selection=sel_9.value)

# ══════════════════════════════════════════════════════════════════════
# THUMB EXTRUSION — 3 segments from right face strip (face 11, palm base)
# ══════════════════════════════════════════════════════════════════════

ext_t1 = extrude_mesh(geometry=ext_ix_3.geometry, offset=thumb_1.result, selection=sel_thumb.value)
ext_t2 = extrude_mesh(geometry=ext_t1.geometry, offset=thumb_2.result, selection=sel_thumb.value)
ext_t3 = extrude_mesh(geometry=ext_t2.geometry, offset=thumb_3.result, selection=sel_thumb.value)

# ══════════════════════════════════════════════════════════════════════
# FOREARM EXTRUSION — 4 segments (region mode)
# ══════════════════════════════════════════════════════════════════════

ext_a1 = extrude_mesh(geometry=ext_t3.geometry, offset=forearm_1.result, selection=forearm_sel.value, region=true)
ext_a2 = extrude_mesh(geometry=ext_a1.geometry, offset=forearm_2.result, selection=forearm_sel.value, region=true)
ext_a3 = extrude_mesh(geometry=ext_a2.geometry, offset=forearm_3.result, selection=forearm_sel.value, region=true)
ext_a4 = extrude_mesh(geometry=ext_a3.geometry, offset=forearm_4.result, selection=forearm_sel.value, region=true)

# ══════════════════════════════════════════════════════════════════════
# MARK CREASE — sharp folds at knuckles, wrist, thumb base
# Must be AFTER all topology changes, BEFORE subdivision
# ══════════════════════════════════════════════════════════════════════

crease_k = mark_edges(geometry=ext_a4.geometry, label="crease", type="FLOAT", value_float=crease_knuckle.result, selection=finger_sel.value, face_boundary=true)
crease_w = mark_edges(geometry=crease_k.geometry, label="crease", type="FLOAT", value_float=crease_wrist.result, selection=forearm_sel.value, face_boundary=true)
crease_t = mark_edges(geometry=crease_w.geometry, label="crease", type="FLOAT", value_float=crease_thumb.result, selection=sel_thumb.value, face_boundary=true)

# ══════════════════════════════════════════════════════════════════════
# BONE WEIGHTS — reuse existing face selections
# Applied before subdivision so weights are stored in GeometryBundle
# ══════════════════════════════════════════════════════════════════════

bone_palm = set_bone_weight(geometry=crease_t.geometry, bone_name="palm", weight=1.0, selection=finger_sel.value)
bone_thumb = set_bone_weight(geometry=bone_palm.geometry, bone_name="thumb", weight=1.0, selection=sel_thumb.value)
bone_forearm = set_bone_weight(geometry=bone_thumb.geometry, bone_name="forearm", weight=1.0, selection=forearm_sel.value)
bone_pinky = set_bone_weight(geometry=bone_forearm.geometry, bone_name="pinky", weight=1.0, selection=sel_6.value)
bone_ring = set_bone_weight(geometry=bone_pinky.geometry, bone_name="ring", weight=1.0, selection=sel_7.value)
bone_middle = set_bone_weight(geometry=bone_ring.geometry, bone_name="middle", weight=1.0, selection=sel_8.value)
bone_index = set_bone_weight(geometry=bone_middle.geometry, bone_name="index", weight=1.0, selection=sel_9.value)

# ══════════════════════════════════════════════════════════════════════
# CC SUBDIVISION
# ══════════════════════════════════════════════════════════════════════

smooth = subdivision_surface(geometry=bone_index.geometry, levels=2)

# ══════════════════════════════════════════════════════════════════════
# OUTPUT
# ══════════════════════════════════════════════════════════════════════

out = transform_geometry(geometry=smooth.geometry, scale=<1.0, 1.0, 1.0>)
