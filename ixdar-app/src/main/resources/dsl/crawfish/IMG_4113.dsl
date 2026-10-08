# Segmentation of crawfish scan IMG_4113, edited in the ring tool (Ctrl+S saves rings here).
# Repair settings match fixtures/crawfish_rings.dsl (IMG_4109).
scan = load_mesh(path="/home/acw/crawfish/IMG_4113.glb")
repaired = repair_mesh(geometry=scan.geometry, weld_epsilon=0.0, max_hole_edges=4096, min_shell_faces=100, strict=false)
