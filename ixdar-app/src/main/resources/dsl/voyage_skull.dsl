

subdivisions = input_int(name="subdivisions", default=6, min=4, max=8)
handle_weight = input_float(name="handle_weight", default=0.333, min=0.0, max=2.0)

palm_x = input_float(name="palm_x", default=2.1152, min=1.5, max=3.0)
palm_y = input_float(name="palm_y", default=2.9476, min=2.0, max=4.0)
palm_z = input_float(name="palm_z", default=2.7647, min=2.0, max=3.5)

eye_socket_depth = input_float(name="eye_socket_depth", default=0.25, min=0.1, max=0.5)
nasal_depth = input_float(name="nasal_depth", default=0.2, min=0.1, max=0.4)
jaw_depth = input_float(name="jaw_depth", default=0.3, min=0.1, max=0.6)
brow_height = input_float(name="brow_height", default=0.15, min=0.05, max=0.3)


base_cube = cube(size=1.0)

palm_cut_z = loop_cut(mesh=base_cube.mesh, axis=Z, cuts=4)

palm_cut_x = loop_cut(mesh=palm_cut_z.mesh, axis=X, cuts=4)

palm_cut_y = loop_cut(mesh=palm_cut_x.mesh, axis=Y, cuts=3)


with_handles = assign_bezier_handles(geometry=palm_cut_y.geometry, weight=handle_weight.result)


patch = coons_patch(geometry=with_handles.geometry, subdivisions=subdivisions.result)
watertight = merge_by_distance(geometry=patch.geometry, distance=0.0001)


scale_vec = combine_xyz(x=palm_x.result, y=palm_y.result, z=palm_z.result)
scaled = transform_geometry(geometry=watertight.geometry, scale=scale_vec.vector, translation=<0.0, 0.0, 0.0>)


fidx = input_face_index()
pos = input_position()
pos_xyz = separate_xyz(vector=pos.vector)

eye_x_left = compare(a=pos_xyz.x, b=0.3, mode=LESS)
eye_x_right = compare(a=pos_xyz.x, b=0.3, mode=GREATER)
eye_x_center = boolean_math(a=eye_x_left.result, b=eye_x_right.result, mode=OR)

eye_y_min = compare(a=pos_xyz.y, b=0.0, mode=GREATER)
eye_y_max = compare(a=pos_xyz.y, b=0.5, mode=LESS)
eye_y_range = boolean_math(a=eye_y_min.result, b=eye_y_max.result, mode=AND)

eye_sel = boolean_math(a=eye_x_center.result, b=eye_y_range.result, mode=AND)

eye_inset = inset_faces(geometry=scaled.geometry, inset=eye_socket_depth.result, selection=eye_sel.result)


nasal_x_min = compare(a=pos_xyz.x, b=0.2, mode=GREATER)
nasal_x_max = compare(a=pos_xyz.x, b=0.2, mode=LESS)
nasal_x_center = boolean_math(a=nasal_x_min.result, b=nasal_x_max.result, mode=AND)
nasal_y_min = compare(a=pos_xyz.y, b=0.0, mode=GREATER)
nasal_y_max = compare(a=pos_xyz.y, b=0.5, mode=LESS)
nasal_y_range = boolean_math(a=nasal_y_min.result, b=nasal_y_max.result, mode=AND)

nasal_sel = boolean_math(a=nasal_x_center.result, b=nasal_y_range.result, mode=AND)

nasal_inset = inset_faces(geometry=eye_inset.geometry, inset=nasal_depth.result, selection=nasal_sel.result)


brow_y_min = compare(a=pos_xyz.y, b=0.3, mode=GREATER)
brow_y_max = compare(a=pos_xyz.y, b=1.0, mode=LESS)
brow_y_range = boolean_math(a=brow_y_min.result, b=brow_y_max.result, mode=AND)
brow_x_min = compare(a=pos_xyz.x, b=0.6, mode=GREATER)
brow_x_max = compare(a=pos_xyz.x, b=0.6, mode=LESS)
brow_x_range = boolean_math(a=brow_x_min.result, b=brow_x_max.result, mode=AND)

brow_sel = boolean_math(a=brow_y_range.result, b=brow_x_range.result, mode=AND)

brow_extrude = extrude_mesh(geometry=nasal_inset.geometry, offset=brow_height.result, selection=brow_sel.result)


jaw_y_max = compare(a=pos_xyz.y, b=0.5, mode=LESS)
jaw_x_min = compare(a=pos_xyz.x, b=0.8, mode=GREATER)
jaw_x_max = compare(a=pos_xyz.x, b=0.8, mode=LESS)
jaw_x_range = boolean_math(a=jaw_x_min.result, b=jaw_x_max.result, mode=AND)

jaw_sel = boolean_math(a=jaw_y_max.result, b=jaw_x_range.result, mode=AND)

neg_jaw_depth = float_math(operation=SUBTRACT, a=0.0, b=jaw_depth.result)
jaw_extrude = extrude_mesh(geometry=brow_extrude.geometry, offset=neg_jaw_depth.result, selection=jaw_sel.result)


skull_tagged = tag_geometry(geometry=jaw_extrude.geometry, tags="skull,cranium,face,jaw,eye_sockets,nasal_cavity,brow_ridge")


out = transform_geometry(geometry=skull_tagged.geometry, scale=<1.0, 1.0, 1.0>)
