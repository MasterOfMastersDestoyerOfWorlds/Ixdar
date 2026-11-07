# MESH-94: a skull sculpted from seven reference pictures with no CLI step. Each picture's subject is
# traced (trace_outline) and its shading read (shading_edges); fit_references fits the outlines to one
# shared scale; silhouette_hull carves the volume they enclose into the base; reference_strokes turns
# every shading edge into a ridge or valley and every hollow into a bowl fill on that base, and
# stroke_sculpt applies them. The pictures are renders of a skull from jittered cameras (the
# stroke-sculpt skill's render_references.py, seed 77).
front_picture = load_picture(path="dsl/skull_from_pictures/ref_01_front.png")
front_right_picture = load_picture(path="dsl/skull_from_pictures/ref_02_front_right.png")
front_left_picture = load_picture(path="dsl/skull_from_pictures/ref_03_front_left.png")
right_picture = load_picture(path="dsl/skull_from_pictures/ref_04_right.png")
left_picture = load_picture(path="dsl/skull_from_pictures/ref_05_left.png")
back_picture = load_picture(path="dsl/skull_from_pictures/ref_06_back.png")
top_picture = load_picture(path="dsl/skull_from_pictures/ref_07_top.png")

front = trace_outline(image=front_picture.image, view="Front")
right = trace_outline(image=right_picture.image, view="Right")
top = trace_outline(image=top_picture.image, view="Top")
back = trace_outline(image=back_picture.image, view="Back")
left = trace_outline(image=left_picture.image, view="Left")
front_right = trace_outline(image=front_right_picture.image, view="3/4 Front-R")
front_left = trace_outline(image=front_left_picture.image, view="3/4 Front-L")
outlines = merge_curves(a=front.geometry, b=right.geometry, c=top.geometry, d=back.geometry, e=left.geometry, f=front_right.geometry, g=front_left.geometry)
fit = fit_references(outlines=outlines.geometry, height=2.0)

hull = silhouette_hull(outlines=fit.geometry, resolution=48, misses=0, rounding=8.0)
surface = volume_to_mesh(volume=hull.volume, level=0.0)
part = keep_largest_part(geometry=surface.geometry)
base = smooth_mesh(geometry=part.geometry, passes=10, lambda=0.5, mu=-0.53)

front_shading = shading_edges(image=front_picture.image, outline=front.geometry)
right_shading = shading_edges(image=right_picture.image, outline=right.geometry)
top_shading = shading_edges(image=top_picture.image, outline=top.geometry)
back_shading = shading_edges(image=back_picture.image, outline=back.geometry)
left_shading = shading_edges(image=left_picture.image, outline=left.geometry)
front_right_shading = shading_edges(image=front_right_picture.image, outline=front_right.geometry)
front_left_shading = shading_edges(image=front_left_picture.image, outline=front_left.geometry)
edges = merge_curves(a=front_shading.geometry, b=right_shading.geometry, c=top_shading.geometry, d=back_shading.geometry, e=left_shading.geometry, f=front_right_shading.geometry, g=front_left_shading.geometry)
strokes = reference_strokes(fit=fit.geometry, edges=edges.geometry, base=base.geometry)
sculpt = stroke_sculpt(geometry=base.geometry, svg=strokes.svg)
