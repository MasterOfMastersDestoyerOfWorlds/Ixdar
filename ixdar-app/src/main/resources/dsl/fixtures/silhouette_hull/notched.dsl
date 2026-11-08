# A prism whose side outline has an L notch cut from one corner; its hull must keep the notch.
# SilhouetteHullNodeTest overrides hull.misses to 0 for the strict variant.
hull = silhouette_hull(svg="dsl/fixtures/silhouette_hull/notched.svg", resolution=16, misses=1)
surface = volume_to_mesh(volume=hull.volume)
part = keep_largest_part(geometry=surface.geometry)
smooth = smooth_mesh(geometry=part.geometry)
