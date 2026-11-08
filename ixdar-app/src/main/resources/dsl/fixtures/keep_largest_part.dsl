# Two disjoint shells, a unit icosphere and one of half the radius three units along x;
# keep_largest_part must keep the unit sphere's faces and only those.
big = icosphere(radius=1.0, subdivisions=2)
small = icosphere(radius=0.5, subdivisions=2)
moved = transform_geometry(geometry=small.mesh, translation=<3.0, 0.0, 0.0>)
both = join_geometry(a=big.mesh, b=moved.geometry)
kept = keep_largest_part(geometry=both.geometry)
