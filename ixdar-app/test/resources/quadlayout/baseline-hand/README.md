# metriko Hand baseline — regression target for PATCH-37 Java port

This directory contains the per-stage outputs of [komietty/metriko](https://github.com/komietty/metriko) running its `example_qgp` (a custom headless variant — see `/tmp/metriko/example/example_qgp_headless.cpp`) on the Hand mesh. The Java port (PATCH-37 umbrella, sub-tickets PATCH-38..45) targets these outputs as its regression goal.

## Input

`Hand-tri-30k.obj` — 30 k-triangle decimation of `/Users/acw28/Blends/Hand/Hand.obj` (which is all-quad at 66 k vertices and required triangulation; the full 133 k-tri version did NOT converge in metriko's IGM stage within 15 min wall-clock, so we decimated. Replication caveat for the Java port: tune solver tolerances to match this scale, OR plan to handle the bigger mesh later.)

Decimation script: `decimate.py` (Blender 5.x, ratio = 0.225).

## Outputs (run with target quad-size = 0.03)

| File | Stage | Description |
|---|---|---|
| `stage1_extrinsic_field.tsv` | 1 (cross field) | Per-face direction-field vectors — metriko ships KCPS-style extrinsic 3D directions, NOT the angle-based `θ` from Campen 4.6 we're porting. **Regression target = topology only** (singularity count + indices). |
| `stage1_matching.txt` | 1 | Per-edge index-of-rotation between adjacent face frames (0..3). |
| `stage1_seam.txt` | 1 | Edges on the cut graph (= seams of the combed field). |
| `stage1_singular.txt` | 1 | Singular vertex IDs + their indices. **Primary regression target for PATCH-39.** |
| `stage2_uv_corners.tsv` | 2 (IGM) | Per-corner (u, v) of the seamless parametrization. **Primary regression target for PATCH-40.** |
| `stage3_tedges.tsv` | 3 (motorcycle T-mesh) | T-mesh arc list with parametric lengths. **Primary regression target for PATCH-41.** |
| `stage3_tedge_R.tsv` | 3 | Pre-quantization arc lengths. |
| `stage4_tedge_X.tsv` | 4 (quantization) | Post-quantization integer arc lengths. **Primary regression target for PATCH-42.** |
| `hand-quad.obj` | 5 (QEx) | Final quad mesh: **3641 verts, 2339 quads.** Primary regression target for PATCH-43. |
| `hand-quad-{front,back,side,iso,multiview}.png` | — | Blender renders for visual sanity check. |
| `log.txt` | — | metriko stdout log with per-stage timing. |
| `render_quad.py`, `composite.py` | — | Scripts used to produce the renders. |

## Replication plan

Each Java sub-ticket (PATCH-39..43) should reproduce its corresponding TSV/OBJ to a documented tolerance:

- **PATCH-39** (cross field): topological match — singularity count + per-singularity index sum to χ. Geometric direction-field values can differ since we use Campen's angle-based MI vs. metriko's KCPS vector form.
- **PATCH-40** (IGM): UV match within 1e-3 at non-singularity corners (modulo seam-shift integers).
- **PATCH-41** (motorcycle T-mesh): node/arc count match within 5%.
- **PATCH-42** (quantization): exact match on quantization integers if the input T-mesh + objective match; comparable if the upstream stages' floating-point outputs differ.
- **PATCH-43** (QEx): quad count within 10%.

## metriko-side timings (regression budget reference)

```
readOBJ                0.07s
Hmesh                  1.6s
cross field+matching   1.0s
seam/cut/combed/extr   2.8s
IGM setup+integ       59.6s   ← dominant, especially with localInjectivity=true
motorcycle+T-mesh      1.7s
quantization           3.1s
mesh extract           0.17s
TOTAL                 70.3s
```

The 60-second IGM cost is the obvious bottleneck. The Java port should aim to match or beat this; SparseLU.refactor() not warm-starting (PATCH-38 gotcha) may need attention here.

## How to regenerate (if metriko changes)

```bash
cd /tmp/metriko && git pull && \
  cmake --build example/build --target metriko_example_headless && \
  ./example/build/metriko_example_headless \
    /Users/acw28/Code/Ixdar/ixdar-app/test/resources/quadlayout/baseline-hand/Hand-tri-30k.obj 0.03 \
    /Users/acw28/Code/Ixdar/ixdar-app/test/resources/quadlayout/baseline-hand/
```

The headless harness (`example_qgp_headless.cpp`) skips Polyscope's viewer and dumps the per-stage TSVs + final OBJ. metriko was built with SuiteSparse via Homebrew (`brew install cmake suite-sparse`) and `-DCMAKE_PREFIX_PATH=/opt/homebrew/opt/suite-sparse`.
