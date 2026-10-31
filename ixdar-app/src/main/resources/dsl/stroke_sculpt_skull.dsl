# MESH-68: a sphere sculpted toward the user's skull by weighted SVG strokes an agent drew on
# multiview screenshots, lifted with `ixdar-cli stroke-lift`. Each round sculpted on top of the
# cage the previous round stored (stroke_sculpt base/store), so the result is that stored cage:
# rounds 1-5, 8 and 9-11 of tmp/mesh68/skull/ in the MESH-68 worktree. Rounds 6 and 7 were
# rejected because the chamfer distance to the goal rose; round 8 branched from round 5; rounds
# 9-11 are line-art silhouette strokes traced from the goal's outline.
ball = sphere(size=2.0, resolution=80)
welded = merge_by_distance(geometry=ball.mesh, distance=0.0001)
sculpt = stroke_sculpt(geometry=welded.geometry, base="src/main/resources/dsl/skull/stroke_skull_cage.obj", strokes="")
smooth = subdivision_surface(geometry=sculpt.geometry, levels=2)
