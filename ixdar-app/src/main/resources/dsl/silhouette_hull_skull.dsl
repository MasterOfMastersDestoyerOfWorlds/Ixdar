# MESH-77: a skull's starting shape carved from seven reference pictures, remeshed into quads.
# outlines.svg was written in one step by
#   ixdar-cli trace-outlines --out .../outlines.svg --reference ref_01_front.png=Front \
#       --reference ref_04_right.png=Right --reference ref_07_top.png=Top --reference ref_06_back.png=Back \
#       --reference ref_05_left.png=Left --reference "ref_02_front_right.png=3/4 Front-R" \
#       --reference "ref_03_front_left.png=3/4 Front-L"
# which fits every picture to one shared scale; the top trace's jaw, seen past the brow because that
# picture is taken at about 70 degrees, was then replaced by hand with a cubic continuing the cranium.
# silhouette_hull carves and rounds the volume the outlines enclose (strict: with traces at one shared
# scale it agrees with them best); volume_to_mesh extracts its surface, keep_largest_part drops stray
# specks, smooth_mesh evens out the slivers without folding them, and the quad layout lays a quad
# surface over it. At resolution 32 or 40 the seamless map folds and at 64 quad extraction runs out of
# heap, so 48 it is.
hull = silhouette_hull(svg="dsl/silhouette_hull_skull/outlines.svg", resolution=48, misses=0, rounding=8.0)
surface = volume_to_mesh(volume=hull.volume, level=0.0)
part = keep_largest_part(geometry=surface.geometry)
smooth = smooth_mesh(geometry=part.geometry, passes=10, lambda=0.5, mu=-0.53)
layout = quad_layout(geometry=smooth.geometry, alpha_degrees=15.0, target_edge_length=2.0)
