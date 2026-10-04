# CRAW-29 -- repair one Trellis2 crawfish scan once and park the result on disk.
#
# repair_mesh costs about a second on a 936k-triangle scan and every downstream graph would pay it
# again, so this graph writes the repaired shell to /home/acw/crawfish/repaired/ as GLB. Later
# graphs (and the CRAW-12 collection, pointed at that directory) load the file in about a tenth of
# a second and get a closed manifold with its per-corner UVs intact. export_mesh passes the bundle
# through unchanged, so the viewer still shows what was written.
scan = load_mesh(path="/home/acw/crawfish/IMG_4109.glb")
repaired = repair_mesh(geometry=scan.geometry, weld_epsilon=0.0, max_hole_edges=4096, min_shell_faces=100, strict=false)
exported = export_mesh(geometry=repaired.geometry, path="/home/acw/crawfish/repaired/IMG_4109.glb", format="glb")
