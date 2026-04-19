# Ruled ribbon between two parallel rail polylines (closed BOUNDARY loops from thin mesh strips).
# Rail B is rail A shifted in +Y; birail_loft linearly interpolates across.

base_strip = mesh_grid(u_tiles=10, v_tiles=1, u_total_size=2.0, v_tile_size=0.04)
base_geo = subdivide_mesh(mesh=base_strip.mesh, levels=0)
rail_a = mesh_to_curve(geometry=base_geo.geometry, source=BOUNDARY)
rail_offset = combine_xyz(x=0.0, y=0.28, z=0.0)
strip_b = set_position(geometry=base_geo.geometry, offset=rail_offset.vector)
rail_b = mesh_to_curve(geometry=strip_b.geometry, source=BOUNDARY)
ribbon = birail_loft(rail_a=rail_a.geometry, rail_b=rail_b.geometry, u_segments=32, v_segments=6)
