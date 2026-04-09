# Rachel — Procedural approximation of the Rachel mesh
# Reference: /Users/acw28/Blends/exports/Rachel.obj
# Original stats: 784,794 verts, 1,298,116 faces, 2 mesh objects
# Dimensions: X=9.15, Y=3.55, Z=2.38, bounding box min=(-8.34, 0.16, -1.15), max=(0.82, 3.71, 1.23)
# Aspect ratio: ~3.85 : 1.5 : 1 (X:Y:Z)
# Strategy: Use birail_loft to create an elongated form with the correct proportions,
#           then apply displacement for surface detail approximation.

# ── Parameters ────────────────────────────────────────────────────────
u_segments = input_int(name="u_segments", default=64, min=16, max=128)
v_segments = input_int(name="v_segments", default=32, min=16, max=64)
detail_level = input_float(name="detail_level", default=0.3, min=0.0, max=1.0)

# ── Base dimensions for Rachel approximation ─────────────────────────
# Scale to match bounding box: X~9.15, Y~3.55, Z~2.38
# Using a base unit where Y=1, then X~2.58, Z~0.67
base_length = float_math(operation=MULTIPLY, a=2.58, b=1.0)
base_height = float_math(operation=MULTIPLY, a=1.0, b=1.0)
base_depth = float_math(operation=MULTIPLY, a=0.67, b=1.0)

# ── Rail curves for birail_loft ──────────────────────────────────────
# Rail A: lower curve (bottom of form)
# Rail B: upper curve (top of form)
# Both curves follow the elongated X-axis with Y variation for silhouette

# Lower rail (Rail A) - curved profile in XZ plane
rail_a_profile = curve_bezier(
    resolution=32,
    start=<-1.29, 0.0, -0.33>,
    handle_start=<-1.0, 0.0, -0.25>,
    end=<0.0, 0.0, 0.0>,
    handle_end=<1.0, 0.0, -0.25>,
    mode=QUADRATIC
)

# Upper rail (Rail B) - different curvature for top profile
rail_b_profile = curve_bezier(
    resolution=32,
    start=<-1.29, 0.8, -0.33>,
    handle_start=<-1.0, 0.7, -0.25>,
    end=<0.0, 0.5, 0.0>,
    handle_end=<1.0, 0.7, -0.25>,
    mode=QUADRATIC
)

# Transform rails to correct scale and position
# Center in X: shift by +1.29 to center at origin
# Scale Y to base_height, Z to base_depth
rail_a = transform_geometry(
    geometry=rail_a_profile.curve,
    translation=<-1.29, 0.15, 0.0>,
    scale=<7.0, 3.5, 7.0>
)

rail_b = transform_geometry(
    geometry=rail_b_profile.curve,
    translation=<-1.29, 0.85, 0.0>,
    scale=<7.0, 3.5, 7.0>
)

# ── Cross-section profile ────────────────────────────────────────────
# Elliptical cross-section for the form
cross_section = circle_curve(radius=0.33, resolution=32)
cross_section_ellipsoid = transform_geometry(
    geometry=cross_section.curve,
    scale=<1.0, 1.0, 0.5>
)

# ── Bi-rail loft ──────────────────────────────────────────────────────
# Loft between the two rails with the cross-section profile
rachel_base = bi_rail_loft(
    rail_a=rail_a.geometry,
    rail_b=rail_b.geometry,
    profile=cross_section_ellipsoid.geometry,
    profile_b=cross_section_ellipsoid.geometry,
    blend_closure=float_curve(points="0,0,1,0").closure,
    x_resolution=u_segments,
    y_resolution=v_segments,
    depth_scale=1.0,
    iso_curve_t=0.5
)

# ── Displacement for surface detail approximation ─────────────────────
# Add subtle surface variation based on position
pos = input_position()
pos_xyz = separate_xyz(vector=pos.vector)

# Normalize to [-1, 1] range
nx = float_math(operation=DIVIDE, a=pos_xyz.x, b=1.3)
ny = float_math(operation=DIVIDE, a=pos_xyz.y, b=0.9)
nz = float_math(operation=DIVIDE, a=pos_xyz.z, b=0.4)

# Simple noise-like displacement using sine waves
n1 = float_math(operation=SIN, a=float_math(operation=MULTIPLY, a=nx.result, b=6.28318))
n2 = float_math(operation=SIN, a=float_math(operation=MULTIPLY, a=ny.result, b=6.28318))
n3 = float_math(operation=SIN, a=float_math(operation=MULTIPLY, a=nz.result, b=6.28318))
noise = float_math(operation=ADD, a=float_math(operation=ADD, a=n1.result, b=n2.result), b=n3.result)
noise_scaled = float_math(operation=MULTIPLY, a=noise.result, b=detail_level.result)
noise_final = float_math(operation=MULTIPLY, a=noise_scaled.result, b=0.05)

# Apply normal-aligned displacement
nrm = input_normal()
nrm_xyz = separate_xyz(vector=nrm.vector)
disp_x = float_math(operation=MULTIPLY, a=nrm_xyz.x, b=noise_final.result)
disp_y = float_math(operation=MULTIPLY, a=nrm_xyz.y, b=noise_final.result)
disp_z = float_math(operation=MULTIPLY, a=nrm_xyz.z, b=noise_final.result)
displacement = combine_xyz(x=disp_x.result, y=disp_y.result, z=disp_z.result)

rachel_shaped = set_position(geometry=rachel_base.geometry, offset=displacement.vector)

# ── Tag for region analysis ───────────────────────────────────────────
rachel_tagged = tag_geometry(geometry=rachel_shaped.geometry, tags="rachel,main")
