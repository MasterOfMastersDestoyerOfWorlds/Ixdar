carrier = load_mesh(path="test/resources/quadlayout/figure_8/fertility_in_tri.off")
rings = ring_candidates(geometry=carrier.geometry, resolution=128, min_neckness=0.6)