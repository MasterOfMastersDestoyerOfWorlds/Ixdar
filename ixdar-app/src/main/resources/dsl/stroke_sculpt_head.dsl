# MESH-70: a sphere sculpted into a simple head by three chained stroke_sculpt passes. Each pass
# reads its own SVG, drawn on a template `ixdar-cli multiview --svg` wrote from the previous node's
# output, and lifts it through the camera recorded on the SVG root onto its input mesh, so editing
# an earlier pass carries the later ones along. symmetry="x" mirrors every stroke and symmetrises
# the pass. Pass 1: head outline (silhouettes). Pass 2: eye sockets, brow, nose. Pass 3: six
# teeth, finer than the cage, which the node refines around before carving them.
ball = sphere(size=2.0, resolution=64)
welded = merge_by_distance(geometry=ball.mesh, distance=0.0001)
pass1_strength = input_float(name="pass1_strength", default=1.0, min=0.5, max=1.5)
pass2_strength = input_float(name="pass2_strength", default=1.0, min=0.5, max=1.5)
pass3_strength = input_float(name="pass3_strength", default=1.0, min=0.5, max=1.5)
pass1 = stroke_sculpt(geometry=welded.geometry, svg="dsl/stroke_sculpt_head/pass1.svg", symmetry="x", strength=pass1_strength.result)
pass2 = stroke_sculpt(geometry=pass1.geometry, svg="dsl/stroke_sculpt_head/pass2.svg", symmetry="x", strength=pass2_strength.result)
pass3 = stroke_sculpt(geometry=pass2.geometry, svg="dsl/stroke_sculpt_head/pass3.svg", symmetry="x", strength=pass3_strength.result)
smooth = subdivision_surface(geometry=pass3.geometry, levels=1)
