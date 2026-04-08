# Neck Armor Collar (Gorget)
# Vertex-for-vertex reproduction of Blender reference using exact parameters from
# Blender-Procedural-Human/procedural_human/geo_node_groups/armor/neck/

# ── Rail A (lower, larger) ────────────────────────────────────────────
# Blender: circle r=0.13 in XY → rotZ=π/2 → translate(0,0,0.37) rotX=0.156 scale(1.26,1,1)
# → set_position Z offset from float_curve on Y
# Ixdar circle is in XZ, so equivalent rotX = π/2 - 0.156 = 1.4148
rail_a_circle = circle_curve(radius=0.13, resolution=73)
rail_a_xf = transform_geometry(geometry=rail_a_circle.curve, translation=<0.0, 0.0, 0.37>, rotation=<1.4148, 0.0, 0.0>, scale=<1.26, 1.0, 1.0>)
rail_a_fc = float_curve(points="0,0,0.645,0.058,1,0")
rail_a = curve_deform(curve=rail_a_xf.geometry, closure=rail_a_fc.closure, source_axis=Y, target_axis=Z, from_min=-0.14, from_max=0.14)

# ── Rail B (upper, smaller) ──────────────────────────────────────────
# Blender: circle r=0.08 in XY → rotZ=π/2 → translate(0,-0.02,0.48) rotX=0.407
# Equivalent rotX = π/2 - 0.407 = 1.1638
rail_b_circle = circle_curve(radius=0.08, resolution=73)
rail_b = transform_geometry(geometry=rail_b_circle.curve, translation=<0.0, -0.02, 0.48>, rotation=<1.1638, 0.0, 0.0>)

# ── Centre profile ───────────────────────────────────────────────────
# Two quadratic Béziers joined at shared point (0, -0.09, 0.42), then rotate Y=π/2
# Bézier A: (0,-0.14,0.35) → mid(0,-0.10,0.395) → (0,-0.09,0.42)
# Bézier B: (0,-0.09,0.42) → mid(0,-0.08,0.44) → (0,-0.09,0.45)
cp_a = curve_bezier(resolution=40, start=<0.0, -0.14, 0.35>, handle_start=<0.0, -0.10, 0.395>, end=<0.0, -0.09, 0.42>, mode=QUADRATIC)
cp_b = curve_bezier(resolution=40, start=<0.0, -0.09, 0.42>, handle_start=<0.0, -0.08, 0.44>, end=<0.0, -0.09, 0.45>, mode=QUADRATIC)
cp_joined = join_curves(curve_a=cp_a.curve, curve_b=cp_b.curve)
centre_profile = transform_geometry(geometry=cp_joined.curve, rotation=<0.0, 1.5708, 0.0>)

# ── Side profile ─────────────────────────────────────────────────────
# Two quadratic Béziers joined at shared point (-0.085, 0, 0.47), then rotate X=1.5446
# Bézier A: (-0.15,0,0.43) → mid(-0.10,0,0.45) → (-0.085,0,0.47)
# Bézier B: (-0.085,0,0.47) → mid(-0.065,0,0.49) → (-0.075,0,0.50)
sp_a = curve_bezier(resolution=40, start=<-0.15, 0.0, 0.43>, handle_start=<-0.10, 0.0, 0.45>, end=<-0.085, 0.0, 0.47>, mode=QUADRATIC)
sp_b = curve_bezier(resolution=40, start=<-0.085, 0.0, 0.47>, handle_start=<-0.065, 0.0, 0.49>, end=<-0.075, 0.0, 0.50>, mode=QUADRATIC)
sp_joined = join_curves(curve_a=sp_a.curve, curve_b=sp_b.curve)
side_profile = transform_geometry(geometry=sp_joined.curve, rotation=<1.5446, 0.0, 0.0>)

# ── PingPong blend closure (centre→side→centre→side around collar) ───
blend_fc = float_curve(points="0,0,0.25,1,0.5,0,0.75,1,1,0")

# ── Bi-rail loft with per-station closure blending ───────────────────
# Blender: X Resolution=42 (profile cross-section), Y Resolution=148 (rail stations)
# Ixdar: x_resolution = stations along rail, y_resolution = cross-section points
# So swap: x_resolution=148 (stations), y_resolution=42 (profile)
# depth_scale=0.50 compensates for Ixdar's profile normalization vs Blender's
# inter-profile-based normalization (see BiRailLoftNode javadoc)
collar_surface = bi_rail_loft(rail_a=rail_a.geometry, rail_b=rail_b.geometry, profile=centre_profile.geometry, profile_b=side_profile.geometry, blend_closure=blend_fc.closure, x_resolution=148, y_resolution=42, depth_scale=0.50, iso_curve_t=0.88)

# ── Pipes (2 rings: bottom boundary + inner at 88%) ─────────────────
# Blender reference analysis: 2 pipe features only (no top/rail B pipe)
# Cluster 0: Z≈0.37 = bottom pipe along rail A boundary (on actual surface)
# Cluster 1: Z≈0.42 = inner pipe ring at UV X≈0.88
# boundary_a/boundary_b are extracted from the loft surface vertices (yi=0 / yi=yRes-1)
# so they follow the actual surface contour including profile blending + depth_scale
pipe_bottom = curve_to_mesh(curve=collar_surface.boundary_a, radius=0.002, resolution=10)
pipe_inner = curve_to_mesh(curve=collar_surface.iso_curve, radius=0.002, resolution=10)

# ── Rivets (icospheres along both surface boundary curves) ───────────
# Use boundary_a and boundary_b from the loft surface (not raw rail curves)
# so rivets sit exactly on the surface edges
rivet_pts_a = resample_curve(curve=collar_surface.boundary_a, length=0.035)
rivet_pts_b = resample_curve(curve=collar_surface.boundary_b, length=0.035)
rivet_ball = icosphere(radius=0.001, subdivisions=1)
rivets_a = instance_on_points(points=rivet_pts_a.curve, instance=rivet_ball.mesh)
rivets_b = instance_on_points(points=rivet_pts_b.curve, instance=rivet_ball.mesh)

# ── Assembly (collar + 2 pipes + 2 rivet rows) ──────────────────────
j1 = join_geometry(a=collar_surface.geometry, b=pipe_bottom.geometry)
j2 = join_geometry(a=j1.geometry, b=pipe_inner.geometry)
j3 = join_geometry(a=j2.geometry, b=rivets_a.geometry)
j4 = join_geometry(a=j3.geometry, b=rivets_b.geometry)

# ── Trim to left half (X ≤ 0) to match Blender output ───────────────
pos = input_position()
pos_xyz = separate_xyz(vector=pos.vector)
x_positive = compare(a=pos_xyz.x, b=0.0, mode=GREATER)
collar_trimmed = delete_geometry(geometry=j4.geometry, selection=x_positive.result, domain=POINT)
