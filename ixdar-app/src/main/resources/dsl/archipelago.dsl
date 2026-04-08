# Archipelago — multiple cylindrical towers on a base
# Replicates the archipelago mesh silhouette with 6 towers of varying heights

# ── Parameters ────────────────────────────────────────────────────────────────
tower_radius = input_float(name="tower_radius", default=1.2, min=0.5, max=3.0)
base_thickness = input_float(name="base_thickness", default=0.3, min=0.1, max=1.0)
towers_separation = input_float(name="towers_separation", default=3.0, min=1.0, max=6.0)

# ── Base platform — large rounded plane ────────────────────────────────────────
base_radius = float_math(operation=MULTIPLY, a=towers_separation.result, b=1.5)
base_width = float_math(operation=MULTIPLY, a=base_radius.result, b=2.0)
base_plane = mesh_grid(u_tiles=24, v_tiles=24, u_total_size=base_width.result, v_total_size=base_width.result)
base_solid = solidify_mesh(geometry=base_plane.mesh, thickness=base_thickness.result)

# ── Tower positions (radial arrangement around center) ─────────────────────────
# Tower 1: front-right
t1_x = float_math(operation=MULTIPLY, a=towers_separation.result, b=0.707)
t1_y = float_math(operation=MULTIPLY, a=towers_separation.result, b=0.707)
t1_z = float_math(operation=MULTIPLY, a=0.15, b=1.0)
t1_pos = combine_xyz(x=t1_x.result, y=t1_y.result, z=t1_z.result)

# Tower 2: front-left
t2_x = float_math(operation=MULTIPLY, a=towers_separation.result, b=-0.707)
t2_y = float_math(operation=MULTIPLY, a=towers_separation.result, b=0.707)
t2_z = float_math(operation=MULTIPLY, a=0.15, b=1.0)
t2_pos = combine_xyz(x=t2_x.result, y=t2_y.result, z=t2_z.result)

# Tower 3: back-left
t3_x = float_math(operation=MULTIPLY, a=towers_separation.result, b=-0.707)
t3_y = float_math(operation=MULTIPLY, a=towers_separation.result, b=-0.707)
t3_z = float_math(operation=MULTIPLY, a=0.15, b=1.0)
t3_pos = combine_xyz(x=t3_x.result, y=t3_y.result, z=t3_z.result)

# Tower 4: back-right
t4_x = float_math(operation=MULTIPLY, a=towers_separation.result, b=0.707)
t4_y = float_math(operation=MULTIPLY, a=towers_separation.result, b=-0.707)
t4_z = float_math(operation=MULTIPLY, a=0.15, b=1.0)
t4_pos = combine_xyz(x=t4_x.result, y=t4_y.result, z=t4_z.result)

# Tower 5: right
t5_x = float_math(operation=MULTIPLY, a=towers_separation.result, b=1.2)
t5_y = float_math(operation=MULTIPLY, a=0.0, b=1.0)
t5_z = float_math(operation=MULTIPLY, a=0.15, b=1.0)
t5_pos = combine_xyz(x=t5_x.result, y=t5_y.result, z=t5_z.result)

# Tower 6: left
t6_x = float_math(operation=MULTIPLY, a=towers_separation.result, b=-1.2)
t6_y = float_math(operation=MULTIPLY, a=0.0, b=1.0)
t6_z = float_math(operation=MULTIPLY, a=0.15, b=1.0)
t6_pos = combine_xyz(x=t6_x.result, y=t6_y.result, z=t6_z.result)

# ── Base cylinder (height=5.0) for towers ─────────────────────────────────────
base_line_src = mesh_grid(u_tiles=1, v_tiles=10, u_tile_size=0.01, v_tile_size=0.5)
base_line_curve = mesh_to_curve(geometry=base_line_src.mesh, source=BOUNDARY)
base_line_resampled = resample_curve(curve=base_line_curve.curve, length=0.5)
base_cyl = curve_to_mesh(curve=base_line_resampled.curve, radius=1.2, resolution=12, fill_caps=true)
base_sphere = uv_sphere(radius=1.2, segments=6, rings=4)

# ── Tower 1 (height 5.5) ──────────────────────────────────────────────────────
t1_line_src = mesh_grid(u_tiles=1, v_tiles=11, u_tile_size=0.01, v_tile_size=0.5)
t1_line_curve = mesh_to_curve(geometry=t1_line_src.mesh, source=BOUNDARY)
t1_line_resampled = resample_curve(curve=t1_line_curve.curve, length=0.5)
t1_cyl = curve_to_mesh(curve=t1_line_resampled.curve, radius=1.2, resolution=12, fill_caps=true)
t1_top_z = float_math(operation=ADD, a=t1_z.result, b=5.5)
t1_top_offset_x = float_math(operation=MULTIPLY, a=0.0, b=1.0)
t1_top_offset_y = float_math(operation=MULTIPLY, a=0.0, b=1.0)
t1_top_offset = combine_xyz(x=t1_top_offset_x.result, y=t1_top_offset_y.result, z=t1_top_z.result)
t1_top_final = set_position(geometry=base_sphere.mesh, offset=t1_top_offset.vector)
t1_combined = join_geometry(a=t1_cyl.geometry, b=t1_top_final.geometry)
t1_final = set_position(geometry=t1_combined.geometry, offset=t1_pos.vector)

# ── Tower 2 (height 6.2) ──────────────────────────────────────────────────────
t2_line_src = mesh_grid(u_tiles=1, v_tiles=13, u_tile_size=0.01, v_tile_size=0.5)
t2_line_curve = mesh_to_curve(geometry=t2_line_src.mesh, source=BOUNDARY)
t2_line_resampled = resample_curve(curve=t2_line_curve.curve, length=0.5)
t2_cyl = curve_to_mesh(curve=t2_line_resampled.curve, radius=1.2, resolution=12, fill_caps=true)
t2_top_z = float_math(operation=ADD, a=t2_z.result, b=6.2)
t2_top_offset_x = float_math(operation=MULTIPLY, a=0.0, b=1.0)
t2_top_offset_y = float_math(operation=MULTIPLY, a=0.0, b=1.0)
t2_top_offset = combine_xyz(x=t2_top_offset_x.result, y=t2_top_offset_y.result, z=t2_top_z.result)
t2_top_final = set_position(geometry=base_sphere.mesh, offset=t2_top_offset.vector)
t2_combined = join_geometry(a=t2_cyl.geometry, b=t2_top_final.geometry)
t2_final = set_position(geometry=t2_combined.geometry, offset=t2_pos.vector)

# ── Tower 3 (height 4.8) ──────────────────────────────────────────────────────
t3_line_src = mesh_grid(u_tiles=1, v_tiles=10, u_tile_size=0.01, v_tile_size=0.5)
t3_line_curve = mesh_to_curve(geometry=t3_line_src.mesh, source=BOUNDARY)
t3_line_resampled = resample_curve(curve=t3_line_curve.curve, length=0.5)
t3_cyl = curve_to_mesh(curve=t3_line_resampled.curve, radius=1.2, resolution=12, fill_caps=true)
t3_top_z = float_math(operation=ADD, a=t3_z.result, b=4.8)
t3_top_offset_x = float_math(operation=MULTIPLY, a=0.0, b=1.0)
t3_top_offset_y = float_math(operation=MULTIPLY, a=0.0, b=1.0)
t3_top_offset = combine_xyz(x=t3_top_offset_x.result, y=t3_top_offset_y.result, z=t3_top_z.result)
t3_top_final = set_position(geometry=base_sphere.mesh, offset=t3_top_offset.vector)
t3_combined = join_geometry(a=t3_cyl.geometry, b=t3_top_final.geometry)
t3_final = set_position(geometry=t3_combined.geometry, offset=t3_pos.vector)

# ── Tower 4 (height 5.9) ──────────────────────────────────────────────────────
t4_line_src = mesh_grid(u_tiles=1, v_tiles=12, u_tile_size=0.01, v_tile_size=0.5)
t4_line_curve = mesh_to_curve(geometry=t4_line_src.mesh, source=BOUNDARY)
t4_line_resampled = resample_curve(curve=t4_line_curve.curve, length=0.5)
t4_cyl = curve_to_mesh(curve=t4_line_resampled.curve, radius=1.2, resolution=12, fill_caps=true)
t4_top_z = float_math(operation=ADD, a=t4_z.result, b=5.9)
t4_top_offset_x = float_math(operation=MULTIPLY, a=0.0, b=1.0)
t4_top_offset_y = float_math(operation=MULTIPLY, a=0.0, b=1.0)
t4_top_offset = combine_xyz(x=t4_top_offset_x.result, y=t4_top_offset_y.result, z=t4_top_z.result)
t4_top_final = set_position(geometry=base_sphere.mesh, offset=t4_top_offset.vector)
t4_combined = join_geometry(a=t4_cyl.geometry, b=t4_top_final.geometry)
t4_final = set_position(geometry=t4_combined.geometry, offset=t4_pos.vector)

# ── Tower 5 (height 6.5) ──────────────────────────────────────────────────────
t5_line_src = mesh_grid(u_tiles=1, v_tiles=13, u_tile_size=0.01, v_tile_size=0.5)
t5_line_curve = mesh_to_curve(geometry=t5_line_src.mesh, source=BOUNDARY)
t5_line_resampled = resample_curve(curve=t5_line_curve.curve, length=0.5)
t5_cyl = curve_to_mesh(curve=t5_line_resampled.curve, radius=1.2, resolution=12, fill_caps=true)
t5_top_z = float_math(operation=ADD, a=t5_z.result, b=6.5)
t5_top_offset_x = float_math(operation=MULTIPLY, a=0.0, b=1.0)
t5_top_offset_y = float_math(operation=MULTIPLY, a=0.0, b=1.0)
t5_top_offset = combine_xyz(x=t5_top_offset_x.result, y=t5_top_offset_y.result, z=t5_top_z.result)
t5_top_final = set_position(geometry=base_sphere.mesh, offset=t5_top_offset.vector)
t5_combined = join_geometry(a=t5_cyl.geometry, b=t5_top_final.geometry)
t5_final = set_position(geometry=t5_combined.geometry, offset=t5_pos.vector)

# ── Tower 6 (height 5.2) ──────────────────────────────────────────────────────
t6_line_src = mesh_grid(u_tiles=1, v_tiles=11, u_tile_size=0.01, v_tile_size=0.5)
t6_line_curve = mesh_to_curve(geometry=t6_line_src.mesh, source=BOUNDARY)
t6_line_resampled = resample_curve(curve=t6_line_curve.curve, length=0.5)
t6_cyl = curve_to_mesh(curve=t6_line_resampled.curve, radius=1.2, resolution=12, fill_caps=true)
t6_top_z = float_math(operation=ADD, a=t6_z.result, b=5.2)
t6_top_offset_x = float_math(operation=MULTIPLY, a=0.0, b=1.0)
t6_top_offset_y = float_math(operation=MULTIPLY, a=0.0, b=1.0)
t6_top_offset = combine_xyz(x=t6_top_offset_x.result, y=t6_top_offset_y.result, z=t6_top_z.result)
t6_top_final = set_position(geometry=base_sphere.mesh, offset=t6_top_offset.vector)
t6_combined = join_geometry(a=t6_cyl.geometry, b=t6_top_final.geometry)
t6_final = set_position(geometry=t6_combined.geometry, offset=t6_pos.vector)

# ── Join all towers with base ──────────────────────────────────────────────────
join_1 = join_geometry(a=t1_final.geometry, b=t2_final.geometry)
join_2 = join_geometry(a=join_1.geometry, b=t3_final.geometry)
join_3 = join_geometry(a=join_2.geometry, b=t4_final.geometry)
join_4 = join_geometry(a=join_3.geometry, b=t5_final.geometry)
join_5 = join_geometry(a=join_4.geometry, b=t6_final.geometry)

archipelago = join_geometry(a=join_5.geometry, b=base_solid.geometry)
