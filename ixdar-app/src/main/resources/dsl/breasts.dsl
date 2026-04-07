# Breasts mesh - procedural approximation of bilateral breast form
# Based on reference: 374K verts, 748K faces, ~1.0x0.88x0.55 bounding box
# Two rounded forms with central cleavage, smooth surfaces

# ── Parameters ───────────────────────────────────────────────────────────
breast_radius = input_float(name="breast_radius", default=0.26, min=0.1, max=0.5)
breast_separation = input_float(name="breast_separation", default=0.15, min=0.02, max=0.2)
breast_height = input_float(name="breast_height", default=0.32, min=0.15, max=0.6)
breast_depth = input_float(name="breast_depth", default=0.14, min=0.1, max=0.5)
nipple_radius = input_float(name="nipple_radius", default=0.025, min=0.01, max=0.05)
nipple_height = input_float(name="nipple_height", default=0.035, min=0.01, max=0.1)
nipple_offset_x = input_float(name="nipple_offset_x", default=0.09, min=0.02, max=0.15)
nipple_offset_y = input_float(name="nipple_offset_y", default=0.06, min=0.01, max=0.1)
subdivisions = input_int(name="subdivisions", default=4, min=1, max=8)
thickness = input_float(name="thickness", default=0.05, min=0.01, max=0.2)

# ── Base spheres for left and right breast ──────────────────────────────
left_sphere = uv_sphere(radius=breast_radius.result, segments=16, rings=16)
right_sphere = uv_sphere(radius=breast_radius.result, segments=16, rings=16)

# ── Subdivide spheres for detail ────────────────────────────────────────
left_subdivided = subdivide_mesh(mesh=left_sphere.mesh, levels=subdivisions.result)
right_subdivided = subdivide_mesh(mesh=right_sphere.mesh, levels=subdivisions.result)

# ── Position spheres ────────────────────────────────────────────────────
neg_sep = float_math(operation=NEGATE, a=breast_separation.result)
left_pos = combine_xyz(x=neg_sep.result, y=0.0, z=0.0)
right_pos = combine_xyz(x=breast_separation.result, y=0.0, z=0.0)

left_sphere_pos = set_position(geometry=left_subdivided.mesh, offset=left_pos.vector)
right_sphere_pos = set_position(geometry=right_subdivided.mesh, offset=right_pos.vector)

# ── Shape displacement ──────────────────────────────────────────────────
pos = input_position()
pos_xyz = separate_xyz(vector=pos.vector)

# Normalize position
z_normalized = float_math(operation=DIVIDE, a=pos_xyz.z, b=breast_radius.result)
x_normalized = float_math(operation=DIVIDE, a=pos_xyz.x, b=breast_radius.result)

# Z profile: spherical top, flatter bottom
z_normalized_sq = float_math(operation=MULTIPLY, a=z_normalized.result, b=z_normalized.result)
z_profile_raw = float_math(operation=SUBTRACT, a=1.0, b=z_normalized_sq.result)
z_profile = float_math(operation=MAXIMUM, a=z_profile_raw.result, b=0.0)
z_disp = float_math(operation=MULTIPLY, a=z_profile.result, b=breast_height.result)

# Y protrusion: maximum at equator, tapering toward top/bottom
y_profile_raw = float_math(operation=SUBTRACT, a=1.0, b=z_normalized_sq.result)
y_profile = float_math(operation=SQRT, a=y_profile_raw.result, b=0.0)
y_disp = float_math(operation=MULTIPLY, a=y_profile.result, b=breast_depth.result)

# X shape: slight inward curve toward center (cleavage)
x_normalized_sq = float_math(operation=MULTIPLY, a=x_normalized.result, b=x_normalized.result)
x_profile_raw = float_math(operation=SUBTRACT, a=1.0, b=x_normalized_sq.result)
x_profile = float_math(operation=SQRT, a=x_profile_raw.result, b=0.0)
x_disp = float_math(operation=MULTIPLY, a=x_profile.result, b=breast_radius.result)

# Combine displacement using vector_math
x_disp_vec = combine_xyz(x=x_disp.result, y=0.0, z=0.0)
y_disp_vec = combine_xyz(x=0.0, y=y_disp.result, z=0.0)
z_disp_vec = combine_xyz(x=0.0, y=0.0, z=z_disp.result)

y_combined = vector_math(operation=ADD, a=y_disp_vec.vector, b=z_disp_vec.vector)
displacement = vector_math(operation=ADD, a=x_disp_vec.vector, b=y_combined.vector)

# Apply displacement
left_displaced = set_position(geometry=left_sphere_pos.geometry, offset=displacement.vector)
right_displaced = set_position(geometry=right_sphere_pos.geometry, offset=displacement.vector)

# ── Nipple displacement on each breast (before solidify) ────────────────
nipple_left_x = float_math(operation=SUBTRACT, a=nipple_offset_x.result, b=breast_separation.result)
nipple_right_x = float_math(operation=ADD, a=nipple_offset_x.result, b=breast_separation.result)

nipple_radius_sq = float_math(operation=MULTIPLY, a=nipple_radius.result, b=nipple_radius.result)
nipple_radius_sq_check = float_math(operation=MAXIMUM, a=nipple_radius_sq.result, b=0.0001)

nipple_left_x_shifted = float_math(operation=SUBTRACT, a=pos_xyz.x, b=nipple_left_x.result)
nipple_right_x_shifted = float_math(operation=SUBTRACT, a=pos_xyz.x, b=nipple_right_x.result)
nipple_y_shifted = float_math(operation=SUBTRACT, a=pos_xyz.y, b=nipple_offset_y.result)

nipple_left_x_sq = float_math(operation=MULTIPLY, a=nipple_left_x_shifted.result, b=nipple_left_x_shifted.result)
nipple_y_sq = float_math(operation=MULTIPLY, a=nipple_y_shifted.result, b=nipple_y_shifted.result)
nipple_left_rad_sq = float_math(operation=ADD, a=nipple_left_x_sq.result, b=nipple_y_sq.result)

nipple_right_x_sq = float_math(operation=MULTIPLY, a=nipple_right_x_shifted.result, b=nipple_right_x_shifted.result)
nipple_right_rad_sq = float_math(operation=ADD, a=nipple_right_x_sq.result, b=nipple_y_sq.result)

nipple_left_div = float_math(operation=DIVIDE, a=nipple_left_rad_sq, b=nipple_radius_sq_check)
nipple_left_falloff_raw = float_math(operation=SUBTRACT, a=1.0, b=nipple_left_div.result)
nipple_left_falloff = float_math(operation=MAXIMUM, a=nipple_left_falloff_raw.result, b=0.0)

nipple_right_div = float_math(operation=DIVIDE, a=nipple_right_rad_sq, b=nipple_radius_sq_check)
nipple_right_falloff_raw = float_math(operation=SUBTRACT, a=1.0, b=nipple_right_div.result)
nipple_right_falloff = float_math(operation=MAXIMUM, a=nipple_right_falloff_raw.result, b=0.0)

nipple_left_z_disp = float_math(operation=MULTIPLY, a=nipple_left_falloff.result, b=nipple_height.result)
nipple_right_z_disp = float_math(operation=MULTIPLY, a=nipple_right_falloff.result, b=nipple_height.result)

nipple_left_total_z = float_math(operation=ADD, a=z_disp.result, b=nipple_left_z_disp.result)
nipple_right_total_z = float_math(operation=ADD, a=z_disp.result, b=nipple_right_z_disp.result)

nipple_left_disp = combine_xyz(x=x_disp.result, y=y_disp.result, z=nipple_left_total_z.result)
nipple_right_disp = combine_xyz(x=x_disp.result, y=y_disp.result, z=nipple_right_total_z.result)

left_with_nipple = set_position(geometry=left_displaced.geometry, offset=nipple_left_disp.vector)
right_with_nipple = set_position(geometry=right_displaced.geometry, offset=nipple_right_disp.vector)

# ── Solidify ────────────────────────────────────────────────────────────
left_solid = solidify_mesh(geometry=left_with_nipple.geometry, thickness=thickness.result)
right_solid = solidify_mesh(geometry=right_with_nipple.geometry, thickness=thickness.result)

# ── Join ────────────────────────────────────────────────────────────────
breasts = join_geometry(a=left_solid.geometry, b=right_solid.geometry)
