# Simple extrude test: create a cube, extrude all faces

base = cube(size=1.0)
extruded = extrude_mesh(geometry=base.mesh, offset=0.2)
