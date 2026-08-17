cube_a = cube(size=1.0)
cube_b_base = cube(size=1.0)
cube_b = transform_geometry(geometry=cube_b_base.mesh, translation=<0.5, 0.5, 0.5>)
blended = mesh_boolean(mesh_a=cube_a.mesh, mesh_b=cube_b.geometry, operation=UNION)
