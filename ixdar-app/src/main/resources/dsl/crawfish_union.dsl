# CRAW-14 -- two Trellis2 crawfish scans unioned, each keeping its own photographic texture.
#
# The scans live outside the repository (/home/acw/crawfish/IMG_4109.glb .. IMG_4118.glb), the same
# convention crawfish_repaired.dsl uses. Each is repaired first because Manifold only operates on
# closed solids, and repair carries the UV and material slots through. The second scan is nudged
# along X so the two overlap and the boolean has an intersection curve to cut, which is what makes
# the per-face material assignment visible: every output face keeps the material of the scan whose
# surface it lies on.
scan_a = load_mesh(path="/home/acw/crawfish/IMG_4109.glb")
solid_a = repair_mesh(geometry=scan_a.geometry, weld_epsilon=0.0, max_hole_edges=4096, min_shell_faces=100, strict=false)
scan_b = load_mesh(path="/home/acw/crawfish/IMG_4110.glb")
repaired_b = repair_mesh(geometry=scan_b.geometry, weld_epsilon=0.0, max_hole_edges=4096, min_shell_faces=100, strict=false)
solid_b = transform_geometry(geometry=repaired_b.geometry, translation=<0.25, 0.0, 0.0>)
merged = mesh_boolean(mesh_a=solid_a.geometry, mesh_b=solid_b.geometry, operation=UNION)
