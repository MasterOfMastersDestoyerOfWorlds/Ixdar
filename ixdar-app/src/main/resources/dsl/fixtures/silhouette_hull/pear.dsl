# A pear revolved about y with a waist, seen from the side, the top and the front; its hull must
# keep the waist. SilhouetteHullNodeTest overrides hull.misses to 0 for the strict variant.
hull = silhouette_hull(svg="dsl/fixtures/silhouette_hull/pear.svg", resolution=16, misses=1)
surface = volume_to_mesh(volume=hull.volume)
part = keep_largest_part(geometry=surface.geometry)
smooth = smooth_mesh(geometry=part.geometry)
