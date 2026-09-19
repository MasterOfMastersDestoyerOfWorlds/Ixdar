carrier = load_mesh(path="test/resources/quadlayout/figure_8/fertility_in_tri.off")
rings = ring_candidates(geometry=carrier.geometry, resolution=128, min_neckness=0.6)
base_neck = select_ring(rings=rings.geometry, point=<-62.31, -22.70, 1.70>)
limb_cut = ring_at_branch(geometry=base_neck.geometry, branch=<58.83, 14.35, -0.61>, t=0.8, resolution=128)
