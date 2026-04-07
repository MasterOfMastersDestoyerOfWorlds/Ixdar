# Quilting group — procedural quilt displacement pattern
# Inputs (defaults match create_quilting_group); user-editable via input_* nodes
scale_x = input_float(name="scale_x", default=0.3, min=0.001, max=10.0)
scale_y = input_float(name="scale_y", default=0.3, min=0.001, max=10.0)
depth = input_float(name="depth", default=0.05, min=0.0, max=1.0)
rotation = input_float(name="rotation", default=1.3089969, min=0.0, max=6.2832)
subdivisions_f = input_float(name="subdivisions", default=6.0, min=0.0, max=12.0)
subdivisions = float_to_int(value=subdivisions_f.result, mode=ROUND)
stitch_size = input_float(name="stitch_size", default=0.003, min=0.0001, max=0.1)
stitches = input_boolean(name="stitches", default=false)
depth_profile = float_curve(points="0,0,1,0.775,0.423,0.537")
edge_profile = float_curve(points="0.005,1,1,1,0.505,0.512")

# --- Base mesh: size=2 spans -1..1 per axis ---
base_cube = cube(size=2.0)
subdivided = subdivide_mesh(mesh=base_cube.mesh, levels=subdivisions.result)

# --- Tangent frame ---
norm = input_normal()
pos = input_position()
z_axis = combine_xyz(x=0.0, y=0.0, z=1.0)
x_axis = combine_xyz(x=1.0, y=0.0, z=0.0)
n_dot_z = vector_math(operation=DOT_PRODUCT, a=norm.vector, b=z_axis.vector)
abs_ndz = float_math(operation=ABSOLUTE, a=n_dot_z.value, b=0.0)
is_z_aligned = compare(a=abs_ndz.result, b=0.99, mode=GREATER_THAN)
reference = switch_vector(switch=is_z_aligned.result, false=z_axis.vector, true=x_axis.vector)
t1_raw = vector_math(operation=CROSS_PRODUCT, a=norm.vector, b=reference.result)
t1 = vector_math(operation=NORMALIZE, a=t1_raw.vector)
t2 = vector_math(operation=CROSS_PRODUCT, a=norm.vector, b=t1.vector)
local_u = vector_math(operation=DOT_PRODUCT, a=pos.vector, b=t1.vector)
local_v = vector_math(operation=DOT_PRODUCT, a=pos.vector, b=t2.vector)

# --- Rotation in tangent plane ---
cos_a = float_math(operation=COSINE, a=rotation.result, b=0.0)
sin_a = float_math(operation=SINE, a=rotation.result, b=0.0)
u_cos = float_math(operation=MULTIPLY, a=local_u.value, b=cos_a.result)
v_sin = float_math(operation=MULTIPLY, a=local_v.value, b=sin_a.result)
rotated_u = float_math(operation=SUBTRACT, a=u_cos.result, b=v_sin.result)
u_sin = float_math(operation=MULTIPLY, a=local_u.value, b=sin_a.result)
v_cos = float_math(operation=MULTIPLY, a=local_v.value, b=cos_a.result)
rotated_v = float_math(operation=ADD, a=u_sin.result, b=v_cos.result)

# --- Cell distance (axis_cell_dist for x) ---
divided_x = float_math(operation=DIVIDE, a=rotated_u.result, b=scale_x.result)
shifted_x = float_math(operation=ADD, a=divided_x.result, b=0.5)
cell_x = float_math(operation=FRACT, a=shifted_x.result, b=0.0)
mirror_x = float_math(operation=SUBTRACT, a=1.0, b=cell_x.result)
half_x = float_math(operation=MINIMUM, a=cell_x.result, b=mirror_x.result)
dist_x = float_math(operation=MULTIPLY, a=half_x.result, b=2.0)

# --- Cell distance (axis_cell_dist for y) ---
divided_y = float_math(operation=DIVIDE, a=rotated_v.result, b=scale_y.result)
shifted_y = float_math(operation=ADD, a=divided_y.result, b=0.5)
cell_y = float_math(operation=FRACT, a=shifted_y.result, b=0.0)
mirror_y = float_math(operation=SUBTRACT, a=1.0, b=cell_y.result)
half_y = float_math(operation=MINIMUM, a=cell_y.result, b=mirror_y.result)
dist_y = float_math(operation=MULTIPLY, a=half_y.result, b=2.0)

# --- Combined cell distance ---
dist = float_math(operation=MINIMUM, a=dist_x.result, b=dist_y.result)

# --- Position along nearest edge ---
seam_dist_x = float_math(operation=MINIMUM, a=cell_x.result, b=mirror_x.result)
seam_dist_y = float_math(operation=MINIMUM, a=cell_y.result, b=mirror_y.result)
nearest_vert = compare(a=seam_dist_x.result, b=seam_dist_y.result, mode=LESS_THAN)
pos_along = switch_float(switch=nearest_vert.result, false=cell_x.result, true=cell_y.result)

# --- Capture dist and pos_along ---
capture_dist = capture_attribute(geometry=subdivided.geometry, name=Distance, value=dist.result)
capture_pos = capture_attribute(geometry=capture_dist.geometry, name=PosAlong, value=pos_along.result)

# --- Depth and edge profiles (single curve each; edit float_curve control points directly) ---
profile = evaluate_closure(closure=depth_profile.closure, value=dist.result)
edge_factor = evaluate_closure(closure=edge_profile.closure, value=pos_along.result)

# --- Displacement: depth * (1 - (1-profile) * edge_factor) ---
inv_profile = float_math(operation=SUBTRACT, a=1.0, b=profile.result)
trench_factor = float_math(operation=MULTIPLY, a=inv_profile.result, b=edge_factor.result)
blend = float_math(operation=SUBTRACT, a=1.0, b=trench_factor.result)
displacement = float_math(operation=MULTIPLY, a=depth.result, b=blend.result)
offset_vec = vector_math(operation=SCALE, a=norm.vector, scale=displacement.result)
quilted = set_position(geometry=capture_pos.geometry, offset=offset_vec.vector)

# --- Stitching: adaptive thresholds from bounding box ---
bbox = bound_box(geometry=subdivided.geometry)
bbox_min_sep = separate_xyz(vector=bbox.min)
bbox_max_sep = separate_xyz(vector=bbox.max)
bbox_w = float_math(operation=SUBTRACT, a=bbox_max_sep.x, b=bbox_min_sep.x)
bbox_h = float_math(operation=SUBTRACT, a=bbox_max_sep.y, b=bbox_min_sep.y)
bbox_d = float_math(operation=SUBTRACT, a=bbox_max_sep.z, b=bbox_min_sep.z)
bbox_max_wh = float_math(operation=MAXIMUM, a=bbox_w.result, b=bbox_h.result)
max_dim = float_math(operation=MAXIMUM, a=bbox_max_wh.result, b=bbox_d.result)
subdiv_power = float_math(operation=POWER, a=2.0, b=subdivisions_f.result)
vert_spacing = float_math(operation=DIVIDE, a=max_dim.result, b=subdiv_power.result)
min_scale = float_math(operation=MINIMUM, a=scale_x.result, b=scale_y.result)
norm_spacing_raw = float_math(operation=DIVIDE, a=vert_spacing.result, b=min_scale.result)
norm_spacing = float_math(operation=MULTIPLY, a=norm_spacing_raw.result, b=2.0)
seam_thresh = float_math(operation=MULTIPLY, a=norm_spacing.result, b=1.2)

# --- Corner and seam detection ---
is_corner_x = compare(a=dist_x.result, b=seam_thresh.result, mode=LESS_THAN)
is_corner_y = compare(a=dist_y.result, b=seam_thresh.result, mode=LESS_THAN)
is_corner = boolean_math(a=is_corner_x.result, b=is_corner_y.result, mode=AND)
on_seam = compare(a=dist.result, b=seam_thresh.result, mode=LESS_THAN)
not_corner = boolean_math(a=is_corner.result, mode=NOT)
start_verts = boolean_math(a=on_seam.result, b=not_corner.result, mode=AND)

# --- Shortest paths and edge selection ---
edge_cost = float_math(operation=ADD, a=dist.result, b=0.001)
paths = input_shortest_edge_paths(end=is_corner.result, edge_cost=edge_cost.result)
edge_sel = edge_paths_to_selection(start=start_verts.result, next_vertex=paths.next_vertex)

# --- Filter: keep edges where both endpoints are near seams ---
edge_verts = input_mesh_edge_vertices()
dist_v1 = field_at_index(value=dist.result, index=edge_verts.vertex_a)
dist_v2 = field_at_index(value=dist.result, index=edge_verts.vertex_b)
v1_near = compare(a=dist_v1.result, b=seam_thresh.result, mode=LESS_THAN)
v2_near = compare(a=dist_v2.result, b=seam_thresh.result, mode=LESS_THAN)
both_near = boolean_math(a=v1_near.result, b=v2_near.result, mode=AND)
stitch_sel = boolean_math(a=edge_sel.selection, b=both_near.result, mode=AND)
inv_stitch = boolean_math(a=stitch_sel.result, mode=NOT)

# --- Delete non-stitch edges, merge, convert to curve ---
deleted = delete_geometry(geometry=quilted.geometry, selection=inv_stitch.result, domain=EDGE)
merge_dist = float_math(operation=MULTIPLY, a=vert_spacing.result, b=3.0)
merged = merge_by_distance(geometry=deleted.geometry, distance=merge_dist.result)
curve_geo = mesh_to_curve(geometry=merged.geometry)
stitch_spacing = float_math(operation=MULTIPLY, a=stitch_size.result, b=3.0)
resampled = resample_curve(curve=curve_geo.curve, length=stitch_spacing.result)

# --- Stitch beads ---
bead = icosphere(radius=1.0, subdivisions=2)
tangent = input_tangent()
align_rot = align_rotation_to_vector(vector=tangent.tangent)
stitch_scale = combine_xyz(x=stitch_size.result, y=stitch_size.result, z=stitch_size.result)
instanced = instance_on_points(points=resampled.curve, instance=bead.mesh, rotation=align_rot.rotation)
realized = realize_instances(geometry=instanced.geometry)
joined = join_geometry(a=realized.mesh, b=quilted.geometry)

# --- Output: switch on stitches toggle ---
quilt_out = switch_geometry(switch=stitches.result, false=quilted.geometry, true=joined.geometry)
