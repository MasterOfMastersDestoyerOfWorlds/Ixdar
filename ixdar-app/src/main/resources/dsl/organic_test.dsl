# Organic mesh test: quad_cylinder → inset → extrude → subdivide with creases
# Tests: quad_cylinder, inset_faces, extrude_mesh, mark_crease, subdivision_surface

# Start with a quad cylinder (all-quad, subdivision-ready)
base = quad_cylinder(radius=0.5, height=0.8, segments=8, rings=1, cap_rings=2)

# Inset all faces slightly to add edge loops
inset = inset_faces(geometry=base.geometry, inset=0.2)

# Mark the top cap boundary as creased (semi-sharp)
fidx = input_face_index()
# Select first 8 faces (barrel faces) — NOT the cap faces
barrel_sel = compare(a=fidx.index, b=8.0, mode=LESS)
creased = mark_crease(geometry=inset.geometry, selection=barrel_sel.result, face_boundary=true, weight=2.0)

# Subdivide with crease support
smooth = subdivision_surface(geometry=creased.geometry, levels=2)
