from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Annotated

from ..checkout import checkout_root
from ..cli_registry import CliOption, cli_command


LAUNCH_VM_ARGS = "-enableassertions -Dsun.awt.noerasebackground=true -Dorg.lwjgl.util.DebugLoader=true -XX:ErrorFile=target/hs_err_pid%p.log"


@dataclass(frozen=True)
class SceneSpec:
    name: str
    scene_id: str
    subfolder: str
    display_name: str
    camera: str
    dry_run: bool


def scaffold_new_scene(
    *,
    name: str,
    scene_id: str,
    subfolder: str,
    display_name: str,
    camera: str = "2d",
    dry_run: bool = False,
) -> dict:
    spec = _validate_spec(
        SceneSpec(
            name=name.strip(),
            scene_id=scene_id.strip(),
            subfolder=subfolder.strip(),
            display_name=display_name.strip(),
            camera=camera.strip().lower(),
            dry_run=dry_run,
        )
    )
    root = Path(checkout_root())
    java_file = root / "ixdar-app" / "src" / "main" / "java" / "ixdar" / "scenes" / spec.subfolder / f"{spec.name}.java"
    launch_file = root / ".vscode" / "launch.json"

    java_text = _scene_template(spec)
    launch_name = display_name
    launch_changed, launch_reason = _upsert_launch_json(launch_file, launch_name, spec.scene_id, dry_run=spec.dry_run)

    file_reason = "already_exists"
    if not java_file.exists():
        file_reason = "created"
        if not spec.dry_run:
            java_file.parent.mkdir(parents=True, exist_ok=True)
            java_file.write_text(java_text, encoding="utf-8")

    return {
        "ok": True,
        "dryRun": spec.dry_run,
        "scene": {
            "path": str(java_file.relative_to(root)),
            "status": file_reason,
        },
        "launch": {
            "path": str(launch_file.relative_to(root)),
            "status": launch_reason,
            "changed": launch_changed,
        },
    }


def _validate_spec(spec: SceneSpec) -> SceneSpec:
    if not re.fullmatch(r"[A-Z][A-Za-z0-9]*", spec.name):
        raise ValueError("--name must be PascalCase, e.g. IcosphereSavePointScene")
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", spec.scene_id):
        raise ValueError("--id must be kebab-case, e.g. icosphere-save-point-canvas")
    if not re.fullmatch(r"[A-Za-z0-9_/]+", spec.subfolder) or ".." in spec.subfolder:
        raise ValueError("--subfolder must be a safe relative folder, e.g. ui or anatomy/experimental")
    if spec.camera not in {"2d", "3d"}:
        raise ValueError("--camera must be one of: 2d, 3d")
    if not spec.display_name:
        raise ValueError("--display-name cannot be empty")
    return spec


def _scene_template(spec: SceneSpec) -> str:
    package_name = spec.subfolder.replace("/", ".")
    controls_block = _camera_controls_template(spec.camera)
    return (
        f"package ixdar.scenes.{package_name};\n\n"
        f"import ixdar.annotations.scene.SceneAnnotation;\n"
        f"import ixdar.scenes.Scene;\n"
        f"{controls_block}\n"
        f"@SceneAnnotation(id = \"{spec.scene_id}\")\n"
        f"public class {spec.name} extends Scene {{\n\n"
        "    @Override\n"
        "    public void initGL() {\n"
        "        super.initGL();\n"
        "        initCameraControls();\n"
        "    }\n\n"
        "    @Override\n"
        "    public void drawScene() {\n"
        "        super.drawScene();\n"
        "        updateCameraControls();\n"
        "    }\n\n"
        f"{_camera_methods_template(spec.camera)}"
        "}\n"
    )


def _camera_controls_template(camera: str) -> str:
    return "import ixdar.gui.ui.menu.MenuBox;\n"


def _camera_methods_template(camera: str) -> str:
    if camera == "2d":
        return (
            "    private void initCameraControls() {\n"
            "        MenuBox.menuVisible = false;\n"
            "        // The 2D camera pans with a drag and zooms with the wheel and the zoom keys.\n"
            "        mouse.camera = camera2D;\n"
            "        mouse.controller = camera2D;\n"
            "    }\n\n"
            "    private void updateCameraControls() {\n"
            "        camera2D.updateView(DEFAULT_VIEW);\n"
            "        camera2D.setZIndex(camera);\n"
            "        camera2D.calculateCameraTransform(camera2D.ps);\n"
            "    }\n"
        )
    return (
        "    private void initCameraControls() {\n"
        "        MenuBox.menuVisible = false;\n"
        "        // The 3D camera orbits: drag turns, shift-drag pans, the wheel zooms.\n"
        "        mouse.controller = camera;\n"
        "        camera.setOrbit(camera.azimuth, camera.elevation, camera.distance);\n"
        "    }\n\n"
        "    private void updateCameraControls() {\n"
        "        camera.updateViewFirstPerson();\n"
        "    }\n"
    )


def _upsert_launch_json(launch_file: Path, launch_name: str, scene_id: str, *, dry_run: bool) -> tuple[bool, str]:
    launch_text = launch_file.read_text(encoding="utf-8")
    if f"\"args\": \"{scene_id}\"" in launch_text:
        return False, "already_exists"

    insertion_idx = launch_text.rfind("\n  ]")
    if insertion_idx < 0:
        raise ValueError("Could not find configurations array close in launch.json")

    new_config = {
        "type": "java",
        "name": launch_name,
        "request": "launch",
        "mainClass": "ixdar.canvas.IxdarWindow",
        "args": scene_id,
        "vmArgs": LAUNCH_VM_ARGS,
        "cwd": "${workspaceFolder}/ixdar-app",
    }
    config_json = json.dumps(new_config, indent=2)
    config_json = "\n".join(f"    {line}" for line in config_json.splitlines())

    before = launch_text[:insertion_idx]
    after = launch_text[insertion_idx:]
    if before.rstrip().endswith("["):
        updated = before + "\n" + config_json + "\n" + after
    else:
        updated = before + ",\n" + config_json + "\n" + after

    if not dry_run:
        launch_file.write_text(updated, encoding="utf-8")
    return True, "created"


@cli_command(name="new-scene")
def new_scene(
    name: str,
    id: str,
    subfolder: str,
    display_name: str,
    camera: Annotated[str, CliOption(choices=("2d", "3d"))] = "2d",
    dry_run: bool = False,
) -> dict:
    """Scaffold a new Scene class and its launch.json entry.

    :param name: Scene class name.
    :param id: Scene id used by IxdarWindow and the launch.json entry.
    :param subfolder: Package subfolder under scenes for the new class.
    :param display_name: Human-readable scene name.
    :param camera: Camera mode for the scaffold.
    :param dry_run: Print planned changes without writing files.
    """
    return scaffold_new_scene(
        name=name,
        scene_id=id,
        subfolder=subfolder,
        display_name=display_name,
        camera=camera,
        dry_run=dry_run,
    )
