# CRAW-26 -- one Trellis2 crawfish scan as repair_mesh leaves it.
#
# The scans live outside the repository (/home/acw/crawfish/IMG_4109.glb .. IMG_4118.glb); the
# glTF loader already welds bitwise-identical positions, so weld_epsilon stays at zero and this
# graph only exercises the topological stages. Trellis closes the back surface badly rather than
# leaving it open, so every boundary loop here is a scanning artefact and max_hole_edges is only a
# safety cap: the repaired scan has no boundary left, and the `open_boundary` edge-marks overlay
# the mesh viewer draws is empty.
scan = load_mesh(path="/home/acw/crawfish/IMG_4109.glb")
repaired = repair_mesh(geometry=scan.geometry, weld_epsilon=0.0, max_hole_edges=4096, min_shell_faces=100, strict=false)
