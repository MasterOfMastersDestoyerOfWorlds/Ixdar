# Solaris - Low-poly organic form with textured base and vertical spikes
# Replicates the long horizontal form with central protrusions

# === Parameters ===
base_length = input_float(name="base_length", default=55.0, min=10.0, max=100.0)
base_width = input_float(name="base_width", default=12.0, min=5.0, max=30.0)
base_thickness = input_float(name="base_thickness", default=2.0, min=0.5, max=10.0)
spike_height = input_float(name="spike_height", default=8.0, min=1.0, max=20.0)
spike_count = input_int(name="spike_count", default=6, min=3, max=12)
texture_scale = input_float(name="texture_scale", default=3.0, min=0.5, max=10.0)
subdivisions = input_int(name="subdivisions", default=4, min=2, max=8)

# === Base mesh: elongated grid ===
base_grid = mesh_grid(
    u_tiles=32,
    v_tiles=12,
    u_total_size=base_length.result,
    v_total_size=base_width.result
)

# === Subdivide for more detail ===
subdivided = subdivide_mesh(mesh=base_grid.mesh, levels=subdivisions.result)

# === Position and normal inputs for displacement ===
pos = input_position()
norm = input_normal()

separated = separate_xyz(vector=pos.vector)

# === Create wrinkled texture on base ===
# Use sine waves and noise-like patterns for organic texture
x_normalized = float_math(
    operation=DIVIDE,
    a=separated.x.value,
    b=float_math(operation=DIVIDE, a=base_length.result, b=2.0).result
)

y_normalized = float_math(
    operation=DIVIDE,
    a=separated.y.value,
    b=float_math(operation=DIVIDE, a=base_width.result, b=2.0).result
)

# Texture pattern: multi-frequency sine waves
freq1 = float_math(operation=DIVIDE, a=6.2831853, b=texture_scale.result)
freq2 = float_math(operation=DIVIDE, a=6.2831853, b=float_math(operation=MULTIPLY, a=texture_scale.result, b=1.5).result)
freq3 = float_math(operation=DIVIDE, a=6.2831853, b=float_math(operation=MULTIPLY, a=texture_scale.result, b=2.3).result)

phase1 = float_math(operation=MULTIPLY, a=x_normalized.result, b=float_math(operation=MULTIPLY, a=3.1415926, b=2.0).result)
phase2 = float_math(operation=MULTIPLY, a=y_normalized.result, b=float_math(operation=MULTIPLY, a=3.1415926, b=3.0).result)

wave1 = float_math(operation=SINE, a=float_math(operation=ADD, a=phase1.result, b=0.0).result, b=0.0)
wave2 = float_math(operation=SINE, a=float_math(operation=ADD, a=phase2.result, b=1.5).result, b=0.0)
wave3 = float_math(operation=SINE, a=float_math(operation=ADD, a=phase1.result, b=2.3).result, b=0.0)

# Combine waves for complex texture
texture_raw = float_math(operation=ADD, a=wave1.result, b=wave2.result)
texture_combined = float_math(operation=ADD, a=texture_raw.result, b=wave3.result)

# Scale texture amplitude
texture_scaled = float_math(operation=MULTIPLY, a=texture_combined.result, b=0.3)

# === Vertical spikes in the center ===
# Create spike mask based on position
center_mask_raw = float_math(operation=SUBTRACT, a=1.0, b=float_math(operation=ABSOLUTE, a=x_normalized.result, b=0.0).result)
center_mask = float_math(operation=MAXIMUM, a=center_mask_raw.result, b=0.0)

# Spike pattern: alternating peaks
spike_phase = float_math(operation=MULTIPLY, a=x_normalized.result, b=float_math(operation=MULTIPLY, a=3.1415926, b=float_math(operation=DIVIDE, a=6.0, b=spike_count.result).result).result)
spike_pattern = float_math(operation=ABSOLUTE, a=float_math(operation=SINE, a=spike_phase.result, b=0.0).result, b=0.0)

# Combine center mask with spike pattern
spike_mask = float_math(operation=MULTIPLY, a=center_mask.result, b=spike_pattern.result)

# Normalize spike mask for consistent height
spike_mask_normalized = float_math(operation=DIVIDE, a=spike_mask.result, b=1.0)

# === Displacement: combine texture and spikes ===
# Displace along normal (Y axis for flat base)
texture_displacement = float_math(operation=MULTIPLY, a=texture_scaled.result, b=0.5)
spike_displacement = float_math(operation=MULTIPLY, a=spike_mask_normalized.result, b=spike_height.result)

# Combine displacements
total_displacement_y = float_math(operation=ADD, a=texture_displacement.result, b=spike_displacement.result)

# === Apply displacement ===
displacement_vector = combine_xyz(
    x=0.0,
    y=total_displacement_y.result,
    z=0.0
)

textured_base = set_position(geometry=subdivided.geometry, offset=displacement_vector.vector)

# === Solidify to add thickness ===
solidified = solidify_mesh(geometry=textured_base.geometry, thickness=base_thickness.result)

# === Output ===
output = solidified
