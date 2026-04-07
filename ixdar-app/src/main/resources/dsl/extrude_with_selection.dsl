# Extrude test with explicit selection
base = cube(size=1.0)
# selection=1 means all faces selected (index 0-5, all true)
extruded = extrude_mesh(geometry=base.mesh, offset=0.2, selection=1)
