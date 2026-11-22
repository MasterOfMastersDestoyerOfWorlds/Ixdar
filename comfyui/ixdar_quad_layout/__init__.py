"""ComfyUI node that runs Ixdar's quad layout pipeline on a 3D model (CRAW-114).

The node only drives Ixdar from outside: it writes the input model and a three-line DSL
(load_mesh, repair_mesh, quad_layout) into the Ixdar checkout's ``tmp/``, starts the headless
``quad-layout`` scene on that graph with ``ixdar-cli run-scene``, asks the
``/mesh/quad-layout/export`` route for the layout as a GLB, and stops the scene. All geometry
happens in Ixdar; nothing here touches the mesh.

Install by linking or copying this folder into ComfyUI's ``custom_nodes``. The Ixdar checkout is
the repository this file lives in, or ``IXDAR_CHECKOUT`` when set.
"""

import json
import os
import shutil
import subprocess
import uuid
from io import BytesIO
from pathlib import Path

from comfy_api.latest import ComfyExtension, Types, io

SCENE = "quad-layout"
EXPORT_COMMAND = "mesh-quad-layout-export"
SHUTDOWN_GRACE_SECONDS = 60

# The same chain as Ixdar's fertility_quad_layout.dsl: weld and repair the mesh before laying it out.
LAYOUT_GRAPH = """tri = load_mesh(path="{path}")
repaired = repair_mesh(geometry=tri.geometry, weld_epsilon=0.0, max_hole_edges=4096, min_shell_faces=100, strict=false)
layout = quad_layout(geometry=repaired.geometry, alpha_degrees={alpha}, target_edge_length={edge})
"""


def _checkout() -> Path:
    """The Ixdar checkout the scene runs in."""
    configured = os.environ.get("IXDAR_CHECKOUT")
    if configured:
        return Path(configured)
    return Path(__file__).resolve().parents[2]


def _last_json(done: subprocess.CompletedProcess) -> dict:
    """The last top-level JSON object ixdar-cli printed; it prints log paths before it."""
    text = "\n" + done.stdout
    start = text.rfind("\n{")
    if start < 0:
        raise RuntimeError(
            f"ixdar-cli exited {done.returncode} with no JSON result:\n"
            f"{(done.stderr or '')[-2000:]}{done.stdout[-1000:]}"
        )
    return json.loads(text[start:])


def _ixdar_cli(checkout: Path, args: list[str], timeout: float) -> dict:
    """Run one ixdar-cli command in the checkout and return its JSON result."""
    try:
        done = subprocess.run(
            ["uv", "run", "ixdar-cli", *args],
            cwd=checkout,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
    except subprocess.TimeoutExpired as expired:
        raise RuntimeError(f"ixdar-cli {args[0]} ran past {timeout:.0f}s") from expired
    result = _last_json(done)
    if not result.get("ok", done.returncode == 0):
        raise RuntimeError(f"ixdar-cli {args[0]} failed: {result.get('error') or result}")
    return result


class IxdarQuadLayout(io.ComfyNode):
    @classmethod
    def define_schema(cls):
        return io.Schema(
            node_id="IxdarQuadLayout",
            display_name="Ixdar Quad Layout",
            category="3d/remesh",
            description=(
                "Run Ixdar's quad layout pipeline on a triangle mesh and return the quad mesh, or "
                "every patch's quad grid, as a GLB. The mesh is passed to Ixdar unchanged."
            ),
            inputs=[
                io.File3DAny.Input("model_3d"),
                io.Combo.Input(
                    "geometry",
                    options=["quads", "patches"],
                    default="quads",
                    tooltip="quads: the extracted quad mesh. patches: each layout patch's quad grid on the surface.",
                ),
                io.Float.Input(
                    "alpha_degrees",
                    default=15.0,
                    min=1.0,
                    max=45.0,
                    step=0.5,
                    tooltip="quad_layout's alpha: how far a separatrix may bend from the cross field, in degrees.",
                ),
                io.Float.Input(
                    "target_edge_length",
                    default=1.0,
                    min=0.01,
                    max=100.0,
                    step=0.01,
                    tooltip="quad_layout's target edge length, in the mesh's units.",
                ),
                io.Int.Input(
                    "timeout_seconds",
                    default=600,
                    min=10,
                    max=86400,
                    tooltip="Longest the layout may take before the scene is stopped and the node fails.",
                ),
            ],
            outputs=[
                io.File3DGLB.Output(display_name="model_3d"),
                io.Int.Output(display_name="quad_count"),
                io.Int.Output(display_name="patch_count"),
                io.String.Output(display_name="info"),
            ],
        )

    @classmethod
    def execute(cls, model_3d, geometry, alpha_degrees, target_edge_length, timeout_seconds):
        checkout = _checkout()
        run_dir = Path("tmp") / "comfyui" / uuid.uuid4().hex[:12]
        suffix = model_3d.format or "glb"
        source = Path(model_3d.save_to(str(checkout / run_dir / f"input.{suffix}")))
        graph = checkout / run_dir / "quad_layout.dsl"
        graph.write_text(
            LAYOUT_GRAPH.format(path=source.as_posix(), alpha=alpha_degrees, edge=target_edge_length),
            encoding="utf-8",
        )
        output = run_dir / f"{geometry}.glb"
        try:
            _ixdar_cli(
                checkout,
                ["run-scene", "--scene", SCENE, "--mesh", str(graph), "--keep-alive", "--timeout", str(timeout_seconds)],
                timeout_seconds + SHUTDOWN_GRACE_SECONDS,
            )
            result = _ixdar_cli(
                checkout,
                [EXPORT_COMMAND, "--path", output.as_posix(), "--geometry", geometry],
                SHUTDOWN_GRACE_SECONDS,
            )
        finally:
            # Always stop the scene, but never let a failed shutdown hide the layout's own error.
            try:
                _ixdar_cli(checkout, ["shutdown"], SHUTDOWN_GRACE_SECONDS)
            except RuntimeError as stuck:
                print(f"[IxdarQuadLayout] shutdown: {stuck}")
        written = checkout / output
        with open(written, "rb") as glb:
            data = glb.read()
        shutil.rmtree(checkout / run_dir, ignore_errors=True)
        info = (
            f"{result['quad_count']} quads, {result['patch_count']} patches, "
            f"{result['singularity_count']} cones; {result['triangle_count']} triangles written"
        )
        print(f"[IxdarQuadLayout] {info}")
        return io.NodeOutput(
            Types.File3D(BytesIO(data), "glb"), int(result["quad_count"]), int(result["patch_count"]), info
        )


class IxdarQuadLayoutExtension(ComfyExtension):
    async def get_node_list(self):
        return [IxdarQuadLayout]


async def comfy_entrypoint():
    return IxdarQuadLayoutExtension()
