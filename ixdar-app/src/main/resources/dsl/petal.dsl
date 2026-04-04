# Petal — elliptical XZ silhouette (no rectangular rim), stem rounding, tip crown, cup, solidify.
# x_shape = clip grid x to petal half-width profile along z; offsets = x_shape - original x after base rounding.

petal_width = input_float(name="petal_width", default=0.38, min=0.1, max=1.0)
petal_length = input_float(name="petal_length", default=0.54, min=0.2, max=2.0)
cup_amount = input_float(name="cup_amount", default=0.095, min=0.0, max=0.5)
notch_depth = input_float(name="notch_depth", default=0.028, min=0.0, max=0.1)
subdivisions = input_int(name="subdivisions", default=4, min=1, max=8)
half_pi = float_math(operation=MULTIPLY, a=3.14159265, b=0.5)

u_step = float_math(operation=DIVIDE, a=petal_width.result, b=24.0)
v_step = float_math(operation=DIVIDE, a=petal_length.result, b=24.0)

base_grid = mesh_grid(u_tiles=24, v_tiles=24, u_tile_size=u_step.result, v_tile_size=v_step.result)
subdivided = subdivide_mesh(mesh=base_grid.mesh, levels=subdivisions.result)

pos = input_position()
separated = separate_xyz(vector=pos.vector)

half_width = float_math(operation=DIVIDE, a=petal_width.result, b=2.0)
width_sq = float_math(operation=MULTIPLY, a=half_width.result, b=half_width.result)

half_len = float_math(operation=DIVIDE, a=petal_length.result, b=2.0)
z_shifted = float_math(operation=ADD, a=separated.z, b=half_len.result)
z_normalized = float_math(operation=DIVIDE, a=z_shifted.result, b=petal_length.result)

sil_z = float_math(operation=SUBTRACT, a=z_normalized.result, b=0.31)
sil_zn = float_math(operation=DIVIDE, a=sil_z.result, b=0.78)
sil_z_sq = float_math(operation=MULTIPLY, a=sil_zn.result, b=sil_zn.result)
sil_one = float_math(operation=SUBTRACT, a=1.0, b=sil_z_sq.result)
sil_radicand = float_math(operation=MAXIMUM, a=sil_one.result, b=0.0)
sil_sqrt = float_math(operation=SQRT, a=sil_radicand.result, b=0.0)
sil_half_w = float_math(operation=MULTIPLY, a=half_width.result, b=sil_sqrt.result)
sil_floor = float_math(operation=MULTIPLY, a=petal_width.result, b=0.128)
width_cap = float_math(operation=MAXIMUM, a=sil_half_w.result, b=sil_floor.result)

neg_x0 = float_math(operation=MULTIPLY, a=separated.x, b=-1.0)
abs_x0 = float_math(operation=MAXIMUM, a=separated.x, b=neg_x0.result)
abs_safe = float_math(operation=MAXIMUM, a=abs_x0.result, b=0.0001)
cap_ratio_raw = float_math(operation=DIVIDE, a=width_cap.result, b=abs_safe.result)
cap_ratio = float_math(operation=MINIMUM, a=cap_ratio_raw.result, b=1.0)
x_shape = float_math(operation=MULTIPLY, a=separated.x, b=cap_ratio.result)

x_squared = float_math(operation=MULTIPLY, a=x_shape.result, b=x_shape.result)
x_normalized = float_math(operation=DIVIDE, a=x_squared.result, b=width_sq.result)
x_norm_clamped = float_math(operation=MINIMUM, a=x_normalized.result, b=1.0)
side_variation_raw = float_math(operation=SUBTRACT, a=1.0, b=x_normalized.result)
side_variation = float_math(operation=MAXIMUM, a=side_variation_raw.result, b=0.0)

stem_open = float_math(operation=SUBTRACT, a=1.0, b=z_normalized.result)
stem_open_sq = float_math(operation=MULTIPLY, a=stem_open.result, b=stem_open.result)
base_corner_mask = float_math(operation=MULTIPLY, a=stem_open_sq.result, b=x_norm_clamped.result)
base_x_shrink = float_math(operation=MULTIPLY, a=base_corner_mask.result, b=0.14)
one_minus_base_x = float_math(operation=SUBTRACT, a=1.0, b=base_x_shrink.result)
x_plan = float_math(operation=MULTIPLY, a=x_shape.result, b=one_minus_base_x.result)
x_taper_pull = float_math(operation=SUBTRACT, a=x_plan.result, b=separated.x)

z_sq = float_math(operation=MULTIPLY, a=z_normalized.result, b=z_normalized.result)

base_z_pull_raw = float_math(operation=MULTIPLY, a=stem_open_sq.result, b=x_norm_clamped.result)
base_z_pull = float_math(operation=MULTIPLY, a=base_z_pull_raw.result, b=petal_length.result)
base_z_pull_scaled = float_math(operation=MULTIPLY, a=base_z_pull.result, b=0.036)
z_stem_round = float_math(operation=NEGATE, a=base_z_pull_scaled.result)

edge_weight = float_math(operation=SUBTRACT, a=1.0, b=side_variation.result)
flutter_phase = float_math(operation=MULTIPLY, a=z_normalized.result, b=3.14159265)
flutter_sin = float_math(operation=SINE, a=flutter_phase.result, b=0.0)
flutter_raw = float_math(operation=MULTIPLY, a=flutter_sin.result, b=edge_weight.result)
flutter_z = float_math(operation=MULTIPLY, a=flutter_raw.result, b=petal_length.result)
flutter_z_scaled = float_math(operation=MULTIPLY, a=flutter_z.result, b=0.007)

cup_phase = float_math(operation=MULTIPLY, a=z_normalized.result, b=half_pi.result)
sin_cup = float_math(operation=SINE, a=cup_phase.result, b=0.0)
cup_radial = float_math(operation=MULTIPLY, a=sin_cup.result, b=side_variation.result)
edge_waist = float_math(operation=POWER, a=side_variation.result, b=1.3)
cup_body = float_math(operation=MULTIPLY, a=cup_radial.result, b=edge_waist.result)
cup_factor = float_math(operation=MULTIPLY, a=cup_body.result, b=cup_amount.result)

heart_center = float_math(operation=SUBTRACT, a=1.0, b=x_normalized.result)
heart_mask = float_math(operation=MULTIPLY, a=stem_open_sq.result, b=heart_center.result)
heart_lift = float_math(operation=MULTIPLY, a=heart_mask.result, b=petal_length.result)
heart_y = float_math(operation=MULTIPLY, a=heart_lift.result, b=0.01)

neg_x = float_math(operation=MULTIPLY, a=x_shape.result, b=-1.0)
abs_x = float_math(operation=MAXIMUM, a=x_shape.result, b=neg_x.result)
notch_frac = float_math(operation=DIVIDE, a=abs_x.result, b=half_width.result)
notch_factor_raw = float_math(operation=SUBTRACT, a=1.0, b=notch_frac.result)
notch_factor = float_math(operation=MAXIMUM, a=notch_factor_raw.result, b=0.0)

tip_weight = float_math(operation=MULTIPLY, a=z_normalized.result, b=z_normalized.result)
notch_depth_shape = float_math(operation=MULTIPLY, a=notch_factor.result, b=tip_weight.result)
notch_depth_combined = float_math(operation=MULTIPLY, a=notch_depth_shape.result, b=notch_depth.result)

edge_droop_strength = float_math(operation=MULTIPLY, a=petal_length.result, b=0.05)
edge_droop_shape = float_math(operation=MULTIPLY, a=z_normalized.result, b=edge_weight.result)
edge_droop = float_math(operation=MULTIPLY, a=edge_droop_shape.result, b=edge_droop_strength.result)

y_displacement_raw = float_math(operation=SUBTRACT, a=cup_factor.result, b=notch_depth_combined.result)
y_displacement_mid = float_math(operation=SUBTRACT, a=y_displacement_raw.result, b=edge_droop.result)
y_displacement = float_math(operation=ADD, a=y_displacement_mid.result, b=heart_y.result)

arch_strength = float_math(operation=MULTIPLY, a=petal_length.result, b=0.078)
arch_shape = float_math(operation=MULTIPLY, a=z_sq.result, b=side_variation.result)
arch_delta = float_math(operation=MULTIPLY, a=arch_shape.result, b=arch_strength.result)
z_arch_pull = float_math(operation=NEGATE, a=arch_delta.result)

tip_z_gate_raw = float_math(operation=SUBTRACT, a=z_normalized.result, b=0.76)
tip_z_gate = float_math(operation=MAXIMUM, a=tip_z_gate_raw.result, b=0.0)
tip_region = float_math(operation=DIVIDE, a=tip_z_gate.result, b=0.24)
tip_region_clamped = float_math(operation=MINIMUM, a=tip_region.result, b=1.0)
tip_round_profile = float_math(operation=MULTIPLY, a=tip_region_clamped.result, b=side_variation.result)
tip_crown_z = float_math(operation=MULTIPLY, a=tip_round_profile.result, b=petal_length.result)
tip_crown_z_scaled = float_math(operation=MULTIPLY, a=tip_crown_z.result, b=0.042)

z_body = float_math(operation=ADD, a=z_arch_pull.result, b=z_stem_round.result)
z_with_flutter = float_math(operation=ADD, a=z_body.result, b=flutter_z_scaled.result)
z_longitudinal = float_math(operation=ADD, a=z_with_flutter.result, b=tip_crown_z_scaled.result)

xz_pull = combine_xyz(x=x_taper_pull.result, y=0.0, z=z_longitudinal.result)
y_only = combine_xyz(x=0.0, y=y_displacement.result, z=0.0)
displacement = vector_math(operation=ADD, a=xz_pull.vector, b=y_only.vector)

petal_surface = set_position(geometry=subdivided.geometry, offset=displacement.vector)
petal_thickness = input_float(name="petal_thickness", default=0.011, min=0.0, max=0.08)
petal = solidify_mesh(geometry=petal_surface.geometry, thickness=petal_thickness.result)
