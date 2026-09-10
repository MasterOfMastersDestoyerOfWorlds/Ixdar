# TOOL-8 -- the graph the peak-heap column of `ixdar-cli mesh-dsl-timing` is read on.
#
# Three stages with very different memory profiles on one tracked mesh: the loader, the topology
# repair, and the quad layout pipeline, which is the stage that runs into the gigabytes. Fertility
# is genus four and closed, so repair_mesh has nothing to fill and the peak belongs to the layout.
tri = load_mesh(path="ixdar-app/test/resources/quadlayout/figure_8/fertility_in_tri.off")
repaired = repair_mesh(geometry=tri.geometry, weld_epsilon=0.0, max_hole_edges=4096, min_shell_faces=100, strict=false)
layout = quad_layout(geometry=repaired.geometry, alpha_degrees=15.0, target_edge_length=1.0)
