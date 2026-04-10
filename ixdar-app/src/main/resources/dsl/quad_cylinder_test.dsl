# Quad cylinder test: all-quad cylinder → subdivision
# Tests: quad_cylinder, subdivision_surface

cyl = quad_cylinder(radius=0.5, height=2.0, segments=8, rings=2, cap_rings=2)
smooth = subdivision_surface(mesh=cyl.mesh, levels=2)
