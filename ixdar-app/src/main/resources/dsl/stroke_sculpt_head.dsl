# sculpt-migrate: strokes carry their own world-unit data-depth and data-radius (MESH-80)
# MESH-70: a sphere sculpted into a simple head by stroke_sculpt; since MESH-86 one pass on the base.
# Its SVG's <g id> groups are the layers: outline (silhouettes), face (eye socket, brow, nose) and
# teeth (finer than the cage, which the node refines around). head.strokes beside the SVG holds every
# stroke as a curve on the base sphere under a stable id; ixdar-cli sculpt-sync writes both files and
# folds SVG edits in any cell back. symmetry="x" mirrors every stroke and symmetrises the pass.
ball = sphere(size=2.0, resolution=64)
welded = merge_by_distance(geometry=ball.mesh, distance=0.0001)
head = stroke_sculpt(geometry=welded.geometry, svg="dsl/stroke_sculpt_head/head.svg", symmetry="x")
smooth = subdivision_surface(geometry=head.geometry, levels=1)
