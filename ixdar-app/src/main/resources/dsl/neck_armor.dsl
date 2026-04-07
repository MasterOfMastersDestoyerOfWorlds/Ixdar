# Neck Armor Collar (Gorget)
# Procedurally generates a neck collar using bi-rail loft over two circular rails.
#
# Geometry:
#   Rail A: circle r=0.13 at (0,0,0.37), Rail B: r=0.08 at (0,-0.02,0.48)
#   Centre profile: Béziers (0,-0.14,0.35)→(0,-0.09,0.45)
#   Bi-rail loft 42×24, trim to left half (X<=0), add pipes + rivets

# ── Parameters ────────────────────────────────────────────────────────
collar_thickness = input_float(name="collar_thickness", default=0.008, min=0.002, max=0.05)
pipe_radius = input_float(name="pipe_radius", default=0.003, min=0.001, max=0.01)

# ── Rails ─────────────────────────────────────────────────────────────
rail_a = circle_curve(radius=0.13, resolution=73, center=<0.0, 0.0, 0.37>)
rail_b = circle_curve(radius=0.08, resolution=73, center=<0.0, -0.02, 0.48>)

# ── Profile (collar cross-section) ───────────────────────────────────
profile = curve_bezier(resolution=24, start=<0.0, -0.05, 0.0>, handle_start=<0.035, -0.03, 0.0>, handle_end=<0.025, 0.03, 0.0>, end=<0.0, 0.05, 0.0>, mode=CUBIC)

# ── Bi-rail loft → solidify ──────────────────────────────────────────
collar_surface = bi_rail_loft(rail_a=rail_a.curve, rail_b=rail_b.curve, profile=profile.curve, x_resolution=42, y_resolution=24)
collar_solid = solidify_mesh(geometry=collar_surface.geometry, thickness=collar_thickness.result)

# ── Trim collar: delete X > 0 (keep left half) ───────────────────────
pos = input_position()
pos_xyz = separate_xyz(vector=pos.vector)
trim_sel = compare(a=pos_xyz.x, b=-0.001, mode=GREATER)
collar_trimmed = delete_geometry(geometry=collar_solid.geometry, selection=trim_sel.result, domain=POINT)

# ── Pipes along trimmed collar boundary ───────────────────────────────
boundary = mesh_to_curve(geometry=collar_trimmed.geometry, source=BOUNDARY)
pipe_smooth = resample_curve(curve=boundary.curve, length=0.006)
pipes = curve_to_mesh(curve=pipe_smooth.curve, radius=pipe_radius.result, resolution=6, fill_caps=true)

# ── Rivet accents ─────────────────────────────────────────────────────
rivet_ball = uv_sphere(radius=0.004, segments=6, rings=4)
rivet_curve = resample_curve(curve=boundary.curve, length=0.04)
rivet_scaffold = curve_to_mesh(curve=rivet_curve.curve, radius=0.001, resolution=3, fill_caps=false)
rivets_inst = instance_on_points(points=rivet_scaffold.geometry, instance=rivet_ball.mesh)
rivets = realize_instances(geometry=rivets_inst.geometry)

# ── Final assembly ────────────────────────────────────────────────────
collar_pipes = join_geometry(a=collar_trimmed.geometry, b=pipes.geometry)
neck_armor = join_geometry(a=collar_pipes.geometry, b=rivets.mesh)
