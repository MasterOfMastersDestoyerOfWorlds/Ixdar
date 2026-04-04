# Cherry blossom petal — single petal with cupping and tip notch
# Base surface using mesh_grid, subdivided for smooth detail

# --- Input parameters for tuning ---
petal_width = input_float(name="petal_width", default=0.3, min=0.1, max=1.0)
petal_length = input_float(name="petal_length", default=0.5, min=0.2, max=2.0)
cup_amount = input_float(name="cup_amount", default=0.15, min=0.0, max=0.5)
notch_depth = input_float(name="notch_depth", default=0.02, min=0.0, max=0.1)
subdivisions = input_int(name="subdivisions", default=3, min=1, max=8)

# --- Base mesh: mesh_grid for smooth petal surface ---
base_grid = mesh_grid(u_tiles=20, v_tiles=20, u_tile_size=petal_width.result, v_tile_size=petal_length.result)

# --- Subdivide for smooth detail ---
subdivided = subdivide_mesh(mesh=base_grid.mesh, levels=subdivisions.result)

# --- Per-vertex position ---
pos = input_position()

# --- Separate XYZ components ---
separated = separate_xyz(vector=pos.vector)

# --- Normalize Y position (0 at stem, 1 at tip) ---
y_normalized = float_math(operation=DIVIDE, a=separated.y, b=petal_length.result)

# --- Cupping: displacement upward (positive Z) based on distance from center ---
# Use SIN to create smooth cupping curve
y_for_cup = float_math(operation=MULTIPLY, a=y_normalized.result, b=3.14159265)
sin_cup = float_math(operation=SINE, a=y_for_cup.result, b=0.0)
cup_factor = float_math(operation=MULTIPLY, a=sin_cup.result, b=cup_amount.result)

# Add side-to-side variation using parabolic falloff from center
x_squared = float_math(operation=MULTIPLY, a=separated.x, b=separated.x)
half_width = float_math(operation=DIVIDE, a=petal_width.result, b=2.0)
x_normalized = float_math(operation=DIVIDE, a=x_squared.result, b=half_width.result)
side_variation_raw = float_math(operation=SUBTRACT, a=1.0, b=x_normalized.result)
side_variation = float_math(operation=MAXIMUM, a=side_variation_raw.result, b=0.0)

# Combine cupping with side variation
cup_with_sides = float_math(operation=MULTIPLY, a=cup_factor.result, b=side_variation.result)

# --- Tip notch: V-shape indentation at the far edge ---
# Notch is active near the tip (y close to petal_length)
tip_distance = float_math(operation=SUBTRACT, a=1.0, b=y_normalized.result)
tip_distance_scaled = float_math(operation=MULTIPLY, a=tip_distance.result, b=3.0)
tip_distance_clamped_hi = float_math(operation=MINIMUM, a=tip_distance_scaled.result, b=1.0)
tip_distance_clamped = float_math(operation=MAXIMUM, a=tip_distance_clamped_hi.result, b=0.0)

# V-shape using ABS on X position
neg_x = float_math(operation=MULTIPLY, a=separated.x, b=-1.0)
abs_x = float_math(operation=MAXIMUM, a=separated.x, b=neg_x.result)
notch_factor_raw = float_math(operation=SUBTRACT, a=1.0, b=abs_x.result)
notch_factor = float_math(operation=MAXIMUM, a=notch_factor_raw.result, b=0.0)

# Combine tip proximity with V-shape
notch_depth_shape = float_math(operation=MULTIPLY, a=notch_factor.result, b=tip_distance_clamped.result)
notch_depth_combined = float_math(operation=MULTIPLY, a=notch_depth_shape.result, b=notch_depth.result)

# --- Compute final displacement ---
z_displacement = float_math(operation=SUBTRACT, a=cup_with_sides.result, b=notch_depth_combined.result)

# --- Create displacement vector ---
z_axis = combine_xyz(x=0.0, y=0.0, z=1.0)
displacement = vector_math(operation=SCALE, a=z_axis.vector, scale=z_displacement.result)

# --- Apply displacement with set_position ---
cupped_petal = set_position(geometry=subdivided.geometry, offset=displacement.vector)

# --- Scale to petal proportions (final node id must match mesh viewer: petal / geometry) ---
petal_scale = combine_xyz(x=petal_width.result, y=petal_length.result, z=0.5)
petal = transform_geometry(geometry=cupped_petal.geometry, scale=petal_scale.vector)