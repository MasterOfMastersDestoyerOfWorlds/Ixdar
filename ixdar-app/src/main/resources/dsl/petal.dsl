# Cherry blossom petal — elliptical planform, float_curve margin, strong midrib, outward-flaring tip
# Uses mesh_grid base, subdivide_mesh for detail, displacement for cup/notch/lobes
# Planform: elliptical silhouette (no degenerate clipping edges)
# Margin: float_curve-driven with zeros at side-edge junctions for authorable lobes
# Midrib: pronounced centerline ridge
# Tip: outward-flaring with subtle notch

# === User Parameters ===
petal_width = input_float(name="petal_width", default=0.38, min=0.1, max=1.0)
petal_length = input_float(name="petal_length", default=0.54, min=0.2, max=2.0)
cup_amount = input_float(name="cup_amount", default=0.08, min=0.0, max=0.5)
notch_depth = input_float(name="notch_depth", default=0.025, min=0.0, max=0.1)
midrib_height = input_float(name="midrib_height", default=0.035, min=0.0, max=0.2)
margin_lobes = input_float(name="margin_lobes", default=0.12, min=0.0, max=0.5)
subdivisions = input_int(name="subdivisions", default=6, min=1, max=8)
petal_thickness = input_float(name="petal_thickness", default=0.012, min=0.0, max=0.08)

# === Planform: elliptical silhouette (no degenerate edges) ===
u_step = float_math(operation=DIVIDE, a=petal_width.result, b=24.0)
v_step = float_math(operation=DIVIDE, a=petal_length.result, b=24.0)

base_grid = mesh_grid(u_tiles=24, v_tiles=24, u_tile_size=u_step.result, v_tile_size=v_step.result)
subdivided = subdivide_mesh(mesh=base_grid.mesh, levels=subdivisions.result)

# === Position and normalization ===
pos = input_position()
separated = separate_xyz(vector=pos.vector)

half_width = float_math(operation=DIVIDE, a=petal_width.result, b=2.0)
half_len = float_math(operation=DIVIDE, a=petal_length.result, b=2.0)

# Normalize to [-1, 1] range
x_norm = float_math(operation=DIVIDE, a=separated.x, b=half_width.result)
z_norm = float_math(operation=DIVIDE, a=separated.z, b=half_len.result)

# === Elliptical planform mask ===
# Ellipse: (x/a)^2 + (z/b)^2 <= 1
x_sq = float_math(operation=MULTIPLY, a=x_norm.result, b=x_norm.result)
z_sq = float_math(operation=MULTIPLY, a=z_norm.result, b=z_norm.result)
ellipse_sum = float_math(operation=ADD, a=x_sq.result, b=z_sq.result)
ellipse_mask_raw = float_math(operation=SUBTRACT, a=1.0, b=ellipse_sum.result)
ellipse_mask = float_math(operation=MAXIMUM, a=ellipse_mask_raw.result, b=0.0)

# Strong smooth falloff at boundary
edge_falloff = float_math(operation=POWER, a=ellipse_mask.result, b=3.0)

# === Margin curve: float_curve for lobed effect ===
# Parameter t runs along the top edge (z ~ half_len), zeros at x = +/- half_width
# Points: (-1, 0), (-0.3, 0.15), (0, 0.25), (0.3, 0.15), (1, 0)
margin_curve = float_curve(points="-1,0,-0.3,0.15,0,0.25,0.3,0.15,1,0")
margin_factor_raw = evaluate_closure(closure=margin_curve.closure, value=x_norm.result)
margin_factor = float_math(operation=MULTIPLY, a=margin_factor_raw.result, b=margin_lobes.result)

# === Cup displacement: sinusoidal along Z, strongest at tip ===
# Cup: push Y upward based on distance from stem (z), sin profile
cup_phase = float_math(operation=MULTIPLY, a=z_norm.result, b=1.5707963)
cup_sin = float_math(operation=SINE, a=cup_phase.result, b=0.0)
cup_mid_weight_f = float_math(operation=MULTIPLY, a=cup_sin.result, b=0.5)
cup_mid_weight_g = float_math(operation=ADD, a=cup_mid_weight_f.result, b=0.5)
cup_strength = float_math(operation=MULTIPLY, a=cup_amount.result, b=cup_mid_weight_g.result)
cup_y = float_math(operation=MULTIPLY, a=cup_strength.result, b=edge_falloff.result)

# === Midrib: pronounced ridge along centerline (x=0) ===
# Midrib: push Y upward near x=0, falloff with |x|
abs_x_raw = float_math(operation=SUBTRACT, a=0.0, b=x_norm.result)
abs_x = float_math(operation=MAXIMUM, a=x_norm.result, b=abs_x_raw.result)
midrib_falloff_a = float_math(operation=SUBTRACT, a=1.0, b=abs_x.result)
midrib_falloff_b = float_math(operation=MAXIMUM, a=midrib_falloff_a.result, b=0.0)
midrib_falloff_sq = float_math(operation=MULTIPLY, a=midrib_falloff_b.result, b=midrib_falloff_b.result)
midrib_y_a = float_math(operation=MULTIPLY, a=midrib_falloff_sq.result, b=midrib_height.result)
midrib_y = float_math(operation=MULTIPLY, a=midrib_y_a.result, b=edge_falloff.result)

# === Tip notch: V-indent at far edge (z ~ half_len) ===
# Notch: pull Y downward near tip and center, zero at edges
tip_dist_raw = float_math(operation=SUBTRACT, a=1.0, b=abs_x.result)
tip_dist = float_math(operation=MAXIMUM, a=tip_dist_raw.result, b=0.0)
tip_weight = float_math(operation=POWER, a=tip_dist.result, b=2.0)
notch_center_a = float_math(operation=SUBTRACT, a=1.0, b=abs_x.result)
notch_center_b = float_math(operation=MAXIMUM, a=notch_center_a.result, b=0.0)
notch_mask = float_math(operation=MULTIPLY, a=tip_weight.result, b=notch_center_b.result)
notch_y = float_math(operation=MULTIPLY, a=notch_mask.result, b=notch_depth.result)

# === Tip flare: outward curve at tip edge ===
# Flare: push Y upward at tip, stronger at edges
tip_flare_raw = float_math(operation=SUBTRACT, a=1.0, b=abs_x.result)
tip_flare = float_math(operation=MAXIMUM, a=tip_flare_raw.result, b=0.0)
tip_edge_weight_a = float_math(operation=SUBTRACT, a=1.0, b=abs_x.result)
tip_edge_weight_b = float_math(operation=MAXIMUM, a=tip_edge_weight_a.result, b=0.0)
tip_flare_weight = float_math(operation=MULTIPLY, a=tip_edge_weight_b.result, b=tip_edge_weight_b.result)
tip_flare_y_a = float_math(operation=MULTIPLY, a=tip_flare.result, b=tip_flare_weight.result)
tip_flare_strength = float_math(operation=MULTIPLY, a=petal_length.result, b=0.02)
tip_flare_y = float_math(operation=MULTIPLY, a=tip_flare_y_a.result, b=tip_flare_strength.result)

# === Combine displacements ===
# Total Y displacement: cup + midrib - notch + tip_flare
# X displacement: margin lobes push outward at tip
y_displacement_a = float_math(operation=ADD, a=cup_y.result, b=midrib_y.result)
y_displacement_b = float_math(operation=SUBTRACT, a=y_displacement_a.result, b=notch_y.result)
y_displacement = float_math(operation=ADD, a=y_displacement_b.result, b=tip_flare_y.result)

# X displacement from margin lobes (pushes edges outward at tip)
margin_x_a = float_math(operation=MULTIPLY, a=margin_factor.result, b=petal_width.result)
margin_x = float_math(operation=MULTIPLY, a=margin_x_a.result, b=tip_dist.result)

# Z displacement: slight arch for natural curvature
z_arch_a = float_math(operation=MULTIPLY, a=x_sq.result, b=petal_length.result)
z_arch = float_math(operation=MULTIPLY, a=z_arch_a.result, b=0.015)

# === Apply displacement ===
displacement = combine_xyz(x=margin_x.result, y=y_displacement.result, z=z_arch.result)
petal_surface = set_position(geometry=subdivided.geometry, offset=displacement.vector)

# === Solidify for thickness ===
petal_solid = solidify_mesh(geometry=petal_surface.geometry, thickness=petal_thickness.result)

# === Scale to final proportions ===
petal_scale = input_float(name="petal_scale", default=1.0, min=0.1, max=3.0)
scale_vec = combine_xyz(x=petal_scale.result, y=petal_scale.result, z=petal_scale.result)
petal = transform_geometry(geometry=petal_solid.geometry, scale=scale_vec)
