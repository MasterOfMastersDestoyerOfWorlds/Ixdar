# Test adaptive_bridge_loops

palm_cube = cube(size=1.0)
fidx = input_face_index()
sel_top = compare(a=fidx.result, b=3.0, mode=EQUAL)
palm_inset = inset_faces(geometry=palm_cube.mesh, inset=0.3, selection=sel_top.value)
palm_with_hole = separate_geometry(geometry=palm_inset.geometry, selection=sel_top.value)

tube = dual_radial_segment(
    start_rx=0.2, start_ry=0.2, start_tx=0.0, start_ty=0.0,
    end_rx=0.15, end_ry=0.15, end_tx=0.0, end_ty=0.0,
    length=1.0, rings=4, segments=12)
tube_cap = segment_cap(geometry=tube.geometry, segments=12)
tube_pos = transform_geometry(geometry=tube_cap.geometry, translation=<0.0, 0.5, 0.0>)
tube_tagged = tag_geometry(geometry=tube_pos.geometry, tags="tube_base")

joined = join_geometry(a=palm_with_hole.inverted, b=tube_tagged.geometry)

# Bridge
bridged = adaptive_bridge_loops(geometry=joined.geometry, loop_a_tag="tube_base", segments=1)

out = transform_geometry(geometry=bridged.geometry, scale=<1.0, 1.0, 1.0>)
