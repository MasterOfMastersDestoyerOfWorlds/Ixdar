# Charles - Humanoid Bust
# Procedural approximation of the Charles figure with head, beard, hair, and torso.
# Reference: /Users/acw28/Blends/exports/Charles.obj
# Original: 377,964 verts, 755,924 faces
# Goal: Approximate silhouette and proportions using displacement-based shaping

# ── Parameters ────────────────────────────────────────────────────────
head_size = input_float(name="head_size", default=1.2, min=0.5, max=2.0)
torso_height = input_float(name="torso_height", default=2.5, min=1.0, max=4.0)
torso_width = input_float(name="torso_width", default=1.8, min=1.0, max=3.0)
beard_size = input_float(name="beard_size", default=0.35, min=0.1, max=0.8)
hair_length = input_float(name="hair_length", default=0.6, min=0.2, max=1.5)
subdivisions = input_int(name="subdivisions", default=6, min=3, max=8)
displacement_scale = input_float(name="displacement_scale", default=0.15, min=0.05, max=0.5)

# ── Head base: subdivided sphere ─────────────────────────────────────
head_radius_val = float_math(operation=MULTIPLY, a=head_size.result, b=0.5)
head_sphere = uv_sphere(radius=head_radius_val.result, segments=subdivisions.result, rings=subdivisions.result)
head_subdivided = subdivide_mesh(mesh=head_sphere.mesh, levels=subdivisions.result)

# ── Torso base: elongated cylinder ───────────────────────────────────
torso_radius_val = float_math(operation=MULTIPLY, a=torso_width.result, b=0.25)
torso_base = cylinder(radius=torso_radius_val.result, height=torso_height.result, segments=32)
torso_subdivided = subdivide_mesh(mesh=torso_base.mesh, levels=subdivisions.result)

# ── Position head above torso ────────────────────────────────────────
head_y_offset_a = float_math(operation=MULTIPLY, a=torso_height.result, b=0.5)
head_y_offset_b = float_math(operation=MULTIPLY, a=head_size.result, b=0.4)
head_y_offset = float_math(operation=ADD, a=head_y_offset_a.result, b=head_y_offset_b.result)
head_offset = combine_xyz(x=0.0, y=head_y_offset.result, z=0.0)
head_positioned = set_position(geometry=head_subdivided.geometry, offset=head_offset.vector)

# ── Combine head and torso ───────────────────────────────────────────
combined = join_geometry(a=head_positioned.geometry, b=torso_subdivided.geometry)

# ── Position input for displacement ──────────────────────────────────
pos = input_position()
separated = separate_xyz(vector=pos.vector)

# ── Normal approximation for displacement direction ──────────────────
# Use normalized position as proxy for normal (works for sphere-like shapes)
x_sq = float_math(operation=MULTIPLY, a=separated.x, b=separated.x)
y_sq = float_math(operation=MULTIPLY, a=separated.y, b=separated.y)
z_sq = float_math(operation=MULTIPLY, a=separated.z, b=separated.z)
pos_mag_sq = float_math(operation=ADD, a=x_sq.result, b=y_sq.result)
pos_mag_sq2 = float_math(operation=ADD, a=pos_mag_sq.result, b=z_sq.result)
pos_mag = float_math(operation=SQRT, a=pos_mag_sq2.result)

pos_normalized_x = float_math(operation=DIVIDE, a=separated.x, b=pos_mag.result)
pos_normalized_y = float_math(operation=DIVIDE, a=separated.y, b=pos_mag.result)
pos_normalized_z = float_math(operation=DIVIDE, a=separated.z, b=pos_mag.result)

# ── Head region mask (y > 0) ─────────────────────────────────────────
head_y_threshold = float_math(operation=SUBTRACT, a=separated.y, b=0.0)
head_mask_raw = float_math(operation=SUBTRACT, a=1.0, b=head_y_threshold.result)
head_mask = float_math(operation=MAXIMUM, a=head_mask_raw.result, b=0.0)

# ── Torso region mask (y <= 0) ──────────────────────────────────────
torso_mask_raw = float_math(operation=SUBTRACT, a=1.0, b=head_mask.result)
torso_mask = float_math(operation=MAXIMUM, a=torso_mask_raw.result, b=0.0)

# ── Normalize Y for head sphere [0, 1] ───────────────────────────────
half_head = float_math(operation=MULTIPLY, a=head_size.result, b=0.5)
y_normalized = float_math(operation=DIVIDE, a=separated.y, b=half_head.result)

# ── Facial features: nose protrusion (front, y~0) ───────────────────
y_norm_sq = float_math(operation=MULTIPLY, a=y_normalized.result, b=y_normalized.result)
nose_y_gate_raw = float_math(operation=SUBTRACT, a=1.0, b=y_norm_sq.result)
nose_y_gate_raw2 = float_math(operation=MAXIMUM, a=nose_y_gate_raw.result, b=0.0)
nose_y_gate = float_math(operation=SQRT, a=nose_y_gate_raw2.result)
nose_y_gate2 = float_math(operation=MAXIMUM, a=nose_y_gate.result, b=0.0)

# Z-based nose width (centered at z=0)
half_nose = float_math(operation=MULTIPLY, a=head_size.result, b=0.4)
z_normalized = float_math(operation=DIVIDE, a=separated.z, b=half_nose.result)
z_norm_sq = float_math(operation=MULTIPLY, a=z_normalized.result, b=z_normalized.result)
nose_z_gate_raw = float_math(operation=SUBTRACT, a=1.0, b=z_norm_sq.result)
nose_z_gate_raw2 = float_math(operation=MAXIMUM, a=nose_z_gate_raw.result, b=0.0)
nose_z_gate = float_math(operation=SQRT, a=nose_z_gate_raw2.result)
nose_z_gate2 = float_math(operation=MAXIMUM, a=nose_z_gate.result, b=0.0)

nose_mask = float_math(operation=MULTIPLY, a=nose_y_gate2.result, b=nose_z_gate2.result)
nose_mask2 = float_math(operation=MULTIPLY, a=nose_mask.result, b=head_mask.result)
nose_strength = float_math(operation=MULTIPLY, a=nose_mask2.result, b=displacement_scale.result)
nose_strength2 = float_math(operation=MULTIPLY, a=nose_strength.result, b=0.8)

# ── Mouth region (lower face, y ~ 0.1 to 0.3) ───────────────────────
mouth_y_bottom = float_math(operation=SUBTRACT, a=0.35, b=y_normalized.result)
mouth_y_bottom2 = float_math(operation=MAXIMUM, a=mouth_y_bottom.result, b=0.0)
mouth_y_top = float_math(operation=SUBTRACT, a=y_normalized.result, b=0.1)
mouth_y_top2 = float_math(operation=MAXIMUM, a=mouth_y_top.result, b=0.0)
mouth_y_gate = float_math(operation=MINIMUM, a=mouth_y_bottom2.result, b=mouth_y_top2.result)
mouth_gate = float_math(operation=MULTIPLY, a=mouth_y_gate.result, b=nose_z_gate2.result)
mouth_gate2 = float_math(operation=MULTIPLY, a=mouth_gate.result, b=head_mask.result)

# ── Beard: protrusion at chin (negative y, centered) ────────────────
chin_y_bottom = float_math(operation=SUBTRACT, a=0.0, b=y_normalized.result)
chin_y_bottom2 = float_math(operation=MAXIMUM, a=chin_y_bottom.result, b=0.0)
chin_y_top = float_math(operation=SUBTRACT, a=y_normalized.result, b=-0.6)
chin_y_top2 = float_math(operation=MAXIMUM, a=chin_y_top.result, b=0.0)
chin_y_gate = float_math(operation=MINIMUM, a=chin_y_bottom2.result, b=chin_y_top2.result)
chin_gate = float_math(operation=MULTIPLY, a=chin_y_gate.result, b=nose_z_gate2.result)
chin_gate2 = float_math(operation=MULTIPLY, a=chin_gate.result, b=head_mask.result)
chin_strength = float_math(operation=MULTIPLY, a=chin_gate2.result, b=beard_size.result)
chin_strength2 = float_math(operation=MULTIPLY, a=chin_strength.result, b=displacement_scale.result)
chin_strength3 = float_math(operation=MULTIPLY, a=chin_strength2.result, b=1.5)

# ── Hair: long flowing strands (top and back of head) ───────────────
# Top of head (y > 0.5)
hair_top_bottom = float_math(operation=SUBTRACT, a=1.0, b=y_normalized.result)
hair_top_bottom2 = float_math(operation=MAXIMUM, a=hair_top_bottom.result, b=0.0)
hair_top_top = float_math(operation=SUBTRACT, a=y_normalized.result, b=0.4)
hair_top_top2 = float_math(operation=MAXIMUM, a=hair_top_top.result, b=0.0)
hair_top_gate = float_math(operation=MINIMUM, a=hair_top_bottom2.result, b=hair_top_top2.result)
hair_top_gate2 = float_math(operation=MULTIPLY, a=hair_top_gate.result, b=head_mask.result)

# Back of head (z < 0) - use abs(z) via manual computation
z_abs_neg = float_math(operation=NEGATE, a=z_normalized.result)
z_abs = float_math(operation=MAXIMUM, a=z_normalized.result, b=z_abs_neg.result)
hair_back_gate_raw = float_math(operation=SUBTRACT, a=0.5, b=z_abs.result)
hair_back_gate_raw2 = float_math(operation=MAXIMUM, a=hair_back_gate_raw.result, b=0.0)
hair_back_gate = float_math(operation=MAXIMUM, a=hair_back_gate_raw2.result, b=0.0)
hair_back_gate2 = float_math(operation=MULTIPLY, a=hair_back_gate.result, b=head_mask.result)

# Combine hair regions
hair_gate = float_math(operation=ADD, a=hair_top_gate2.result, b=hair_back_gate2.result)
hair_gate2 = float_math(operation=MINIMUM, a=hair_gate.result, b=1.0)
hair_gate3 = float_math(operation=MULTIPLY, a=hair_gate2.result, b=head_mask.result)

# Hair length falloff (longer at top/back)
hair_strength_raw = float_math(operation=ADD, a=hair_top_gate2.result, b=hair_back_gate2.result)
hair_strength_raw2 = float_math(operation=MINIMUM, a=hair_strength_raw.result, b=1.0)
hair_strength_base = float_math(operation=MULTIPLY, a=hair_gate3.result, b=hair_length.result)
hair_strength_base2 = float_math(operation=MULTIPLY, a=hair_strength_base.result, b=displacement_scale.result)
hair_strength = float_math(operation=MULTIPLY, a=hair_strength_base2.result, b=3.0)

# ── Shoulder width: expand torso at y ~ 0 ───────────────────────────
shoulder_y_bottom = float_math(operation=SUBTRACT, a=0.2, b=y_normalized.result)
shoulder_y_bottom2 = float_math(operation=MAXIMUM, a=shoulder_y_bottom.result, b=0.0)
shoulder_y_top = float_math(operation=SUBTRACT, a=y_normalized.result, b=-0.2)
shoulder_y_top2 = float_math(operation=MAXIMUM, a=shoulder_y_top.result, b=0.0)
shoulder_y_gate = float_math(operation=MINIMUM, a=shoulder_y_bottom2.result, b=shoulder_y_top2.result)
shoulder_gate = float_math(operation=MULTIPLY, a=shoulder_y_gate.result, b=torso_mask.result)

# ── Eye sockets: indentations (y ~ 0.2, z ~ +/- 0.3) ────────────────
eye_y_bottom = float_math(operation=SUBTRACT, a=0.35, b=y_normalized.result)
eye_y_bottom2 = float_math(operation=MAXIMUM, a=eye_y_bottom.result, b=0.0)
eye_y_top = float_math(operation=SUBTRACT, a=y_normalized.result, b=0.1)
eye_y_top2 = float_math(operation=MAXIMUM, a=eye_y_top.result, b=0.0)
eye_y_gate = float_math(operation=MINIMUM, a=eye_y_bottom2.result, b=eye_y_top2.result)

# Z-based eye gates (left and right)
eye_z_width = float_math(operation=MULTIPLY, a=head_size.result, b=0.35)
eye_z_normalized = float_math(operation=DIVIDE, a=separated.z, b=eye_z_width.result)
eye_z_center = float_math(operation=SUBTRACT, a=0.3, b=eye_z_normalized.result)
eye_z_center2 = float_math(operation=MAXIMUM, a=eye_z_center.result, b=0.0)
eye_z_center3 = float_math(operation=SQRT, a=eye_z_center2.result)
eye_z_gate = float_math(operation=MAXIMUM, a=eye_z_center3.result, b=0.0)

eye_mask = float_math(operation=MULTIPLY, a=eye_y_gate.result, b=eye_z_gate.result)
eye_mask2 = float_math(operation=MULTIPLY, a=eye_mask.result, b=head_mask.result)
eye_strength = float_math(operation=MULTIPLY, a=eye_mask2.result, b=displacement_scale.result)
eye_strength2 = float_math(operation=MULTIPLY, a=eye_strength.result, b=0.25)

# ── Compute combined displacement ───────────────────────────────────
# Nose: positive Z (outward)
nose_disp_z = float_math(operation=MULTIPLY, a=nose_strength2.result, b=pos_normalized_z.result)

# Mouth: slight inward curve
mouth_disp_y = float_math(operation=MULTIPLY, a=mouth_gate2.result, b=displacement_scale.result)
mouth_disp_y2 = float_math(operation=MULTIPLY, a=mouth_disp_y.result, b=0.15)
mouth_disp_y3 = float_math(operation=NEGATE, a=mouth_disp_y2.result)

# Beard: positive Y (outward from chin)
beard_disp_y = float_math(operation=MULTIPLY, a=chin_strength3.result, b=pos_normalized_y.result)

# Hair: outward from head surface
hair_disp_x = float_math(operation=MULTIPLY, a=hair_strength.result, b=pos_normalized_x.result)
hair_disp_y = float_math(operation=MULTIPLY, a=hair_strength.result, b=pos_normalized_y.result)
hair_disp_z = float_math(operation=MULTIPLY, a=hair_strength.result, b=pos_normalized_z.result)

# Eyes: inward (negative)
eye_disp_x = float_math(operation=MULTIPLY, a=eye_strength2.result, b=pos_normalized_x.result)
eye_disp_y = float_math(operation=MULTIPLY, a=eye_strength2.result, b=pos_normalized_y.result)
eye_disp_z = float_math(operation=MULTIPLY, a=eye_strength2.result, b=pos_normalized_z.result)
eye_disp_x2 = float_math(operation=NEGATE, a=eye_disp_x.result)
eye_disp_y2 = float_math(operation=NEGATE, a=eye_disp_y.result)
eye_disp_z2 = float_math(operation=NEGATE, a=eye_disp_z.result)

# Shoulders: expand X outward
shoulder_disp_x = float_math(operation=MULTIPLY, a=shoulder_gate.result, b=displacement_scale.result)
shoulder_disp_x2 = float_math(operation=MULTIPLY, a=shoulder_disp_x.result, b=0.4)
shoulder_disp_x3 = float_math(operation=MULTIPLY, a=shoulder_disp_x2.result, b=pos_normalized_x.result)

# ── Combine all displacements ───────────────────────────────────────
# X channel: hair + eyes + shoulders
disp_x = float_math(operation=ADD, a=hair_disp_x.result, b=eye_disp_x2.result)
disp_x2 = float_math(operation=ADD, a=disp_x.result, b=shoulder_disp_x3.result)

# Y channel: mouth + beard + hair + eyes
disp_y = float_math(operation=ADD, a=mouth_disp_y3.result, b=beard_disp_y.result)
disp_y2 = float_math(operation=ADD, a=disp_y.result, b=hair_disp_y.result)
disp_y3 = float_math(operation=ADD, a=disp_y2.result, b=eye_disp_y2.result)

# Z channel: nose + hair + eyes
disp_z = float_math(operation=ADD, a=nose_disp_z.result, b=hair_disp_z.result)
disp_z2 = float_math(operation=ADD, a=disp_z.result, b=eye_disp_z2.result)

# ── Apply displacement ───────────────────────────────────────────────
displacement = combine_xyz(x=disp_x2.result, y=disp_y3.result, z=disp_z2.result)
shaped = set_position(geometry=combined.geometry, offset=displacement.vector)

# ── Solidify for thickness ──────────────────────────────────────────
thickness = input_float(name="thickness", default=0.08, min=0.02, max=0.3)
charles = solidify_mesh(geometry=shaped.geometry, thickness=thickness.result)
