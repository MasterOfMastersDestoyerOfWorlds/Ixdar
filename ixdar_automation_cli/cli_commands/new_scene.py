from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Annotated

from ..cli_registry import CliOption, cli_command


LAUNCH_VM_ARGS = "-enableassertions -Dsun.awt.noerasebackground=true -Dorg.lwjgl.util.DebugLoader=true -XX:ErrorFile=target/hs_err_pid%p.log"


@dataclass(frozen=True)
class SceneSpec:
    name: str
    scene_id: str
    subfolder: str
    display_name: str
    base: str
    camera: str
    maven_profile: str
    dry_run: bool


def scaffold_new_scene(
    *,
    name: str,
    scene_id: str,
    subfolder: str,
    display_name: str,
    base: str = "Scene",
    camera: str = "2d",
    maven_profile: str = "",
    dry_run: bool = False,
) -> dict:
    spec = _validate_spec(
        SceneSpec(
            name=name.strip(),
            scene_id=scene_id.strip(),
            subfolder=subfolder.strip(),
            display_name=display_name.strip(),
            base=base.strip(),
            camera=camera.strip().lower(),
            maven_profile=maven_profile.strip(),
            dry_run=dry_run,
        )
    )
    root = Path(__file__).resolve().parents[2]
    java_file = root / "ixdar-app" / "src" / "main" / "java" / "ixdar" / "scenes" / spec.subfolder / f"{spec.name}.java"
    launch_file = root / ".vscode" / "launch.json"
    pom_file = root / "ixdar-app" / "pom.xml"

    java_text = _scene_template(spec)
    launch_name = display_name
    launch_changed, launch_reason = _upsert_launch_json(launch_file, launch_name, spec.scene_id, dry_run=spec.dry_run)
    pom_changed, pom_reason = _upsert_maven_profile(pom_file, spec.maven_profile, spec.scene_id, dry_run=spec.dry_run)

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
        "mavenProfile": {
            "id": spec.maven_profile,
            "path": str(pom_file.relative_to(root)),
            "status": pom_reason,
            "changed": pom_changed,
        },
    }


def _validate_spec(spec: SceneSpec) -> SceneSpec:
    if not re.fullmatch(r"[A-Z][A-Za-z0-9]*", spec.name):
        raise ValueError("--name must be PascalCase, e.g. IcosphereSavePointScene")
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", spec.scene_id):
        raise ValueError("--id must be kebab-case, e.g. icosphere-save-point-canvas")
    if not re.fullmatch(r"[A-Za-z0-9_/]+", spec.subfolder) or ".." in spec.subfolder:
        raise ValueError("--subfolder must be a safe relative folder, e.g. ui or anatomy/experimental")
    if spec.base not in {"Scene", "Canvas3D"}:
        raise ValueError("--base must be one of: Scene, Canvas3D")
    if spec.camera not in {"2d", "3d"}:
        raise ValueError("--camera must be one of: 2d, 3d")
    if not spec.display_name:
        raise ValueError("--display-name cannot be empty")
    if spec.maven_profile and not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", spec.maven_profile):
        raise ValueError("--maven-profile must be kebab-case")
    return spec


def _scene_template(spec: SceneSpec) -> str:
    package_name = spec.subfolder.replace("/", ".")
    base_import = "ixdar.scenes.Scene" if spec.base == "Scene" else "ixdar.canvas.Canvas3D"
    controls_block = _camera_controls_template(spec.camera)
    return (
        f"package ixdar.scenes.{package_name};\n\n"
        f"import ixdar.annotations.scene.SceneAnnotation;\n"
        f"import {base_import};\n"
        f"{controls_block}\n"
        f"@SceneAnnotation(id = \"{spec.scene_id}\")\n"
        f"public class {spec.name} extends {spec.base} {{\n\n"
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
    if camera == "2d":
        return (
            "import ixdar.gui.ui.menu.MenuBox;\n"
            "import ixdar.platform.Platforms;\n"
            "import ixdar.platform.automation.AutomationInputBinder;\n"
            "import ixdar.platform.input.KeyGuy;\n"
            "import ixdar.platform.input.MouseTrap;\n"
            "import ixdar.platform.input.Scene2DMousePanTrap;\n"
            "import ixdar.platform.input.SceneInputFrameUpdater;\n"
        )
    return (
        "import ixdar.gui.ui.menu.MenuBox;\n"
        "import ixdar.platform.Platforms;\n"
        "import ixdar.platform.automation.AutomationInputBinder;\n"
        "import ixdar.platform.input.KeyGuy;\n"
        "import ixdar.platform.input.MouseTrap;\n"
        "import ixdar.platform.input.SceneInputFrameUpdater;\n"
    )


def _camera_methods_template(camera: str) -> str:
    if camera == "2d":
        return (
            "    private void initCameraControls() {\n"
            "        MenuBox.menuVisible = false;\n"
            "        keys = new KeyGuy(camera2D, this);\n"
            "        mouse = new Scene2DMousePanTrap(camera2D, this);\n"
            "        AutomationInputBinder.bind(Platforms.get(), keys, mouse);\n"
            "        MouseTrap.subscribeScrollRegion(camera2D.getBounds(),\n"
            "                (scrollUp, deltaSeconds) -> camera2D.onScroll(scrollUp, deltaSeconds));\n"
            "    }\n\n"
            "    private void updateCameraControls() {\n"
            "        camera2D.updateView(DEFAULT_VIEW);\n"
            "        SceneInputFrameUpdater.update(keys, mouse);\n"
            "        camera2D.setZIndex(camera);\n"
            "        camera2D.calculateCameraTransform(camera2D.ps);\n"
            "    }\n"
        )
    return (
        "    private void initCameraControls() {\n"
        "        MenuBox.menuVisible = false;\n"
        "        keys = new KeyGuy(camera, this);\n"
        "        mouse = new MouseTrap(null, camera, this);\n"
        "        AutomationInputBinder.bind(Platforms.get(), keys, mouse);\n"
        "    }\n\n"
        "    private void updateCameraControls() {\n"
        "        SceneInputFrameUpdater.update(keys, mouse);\n"
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


def _upsert_maven_profile(pom_file: Path, profile_id: str, scene_id: str, *, dry_run: bool) -> tuple[bool, str]:
    if not profile_id:
        return False, "skipped"
    pom = pom_file.read_text(encoding="utf-8")
    if f"<id>{profile_id}</id>" in pom:
        return False, "already_exists"
    profile_xml = (
        "    <profile>\n"
        f"      <id>{profile_id}</id>\n"
        "      <properties>\n"
        "        <maven.test.skip>true</maven.test.skip>\n"
        "      </properties>\n"
        "      <build>\n"
        "        <defaultGoal>compile exec:exec</defaultGoal>\n"
        "        <plugins>\n"
        "          <plugin>\n"
        "            <groupId>org.codehaus.mojo</groupId>\n"
        "            <artifactId>exec-maven-plugin</artifactId>\n"
        "            <version>3.6.2</version>\n"
        "            <configuration>\n"
        "              <executable>java</executable>\n"
        "              <workingDirectory>${project.basedir}</workingDirectory>\n"
        "              <arguments>\n"
        "                <argument>-enableassertions</argument>\n"
        "                <argument>-Dsun.awt.noerasebackground=true</argument>\n"
        "                <argument>-Dorg.lwjgl.util.DebugLoader=true</argument>\n"
        "                <argument>-XX:ErrorFile=target/hs_err_pid%p.log</argument>\n"
        "                <argument>-classpath</argument>\n"
        "                <classpath />\n"
        "                <argument>ixdar.canvas.IxdarWindow</argument>\n"
        f"                <argument>{scene_id}</argument>\n"
        "              </arguments>\n"
        "            </configuration>\n"
        "          </plugin>\n"
        "        </plugins>\n"
        "      </build>\n"
        "    </profile>\n"
    )

    marker = "<id>native</id>"
    native_idx = pom.find(marker)
    if native_idx < 0:
        raise ValueError("Could not find native profile anchor in pom.xml")
    profile_start = pom.rfind("    <profile>", 0, native_idx)
    if profile_start < 0:
        raise ValueError("Could not find profile insertion point in pom.xml")
    new_pom = pom[:profile_start] + profile_xml + pom[profile_start:]
    if not dry_run:
        pom_file.write_text(new_pom, encoding="utf-8")
    return True, "created"


@cli_command(name="new-scene")
def new_scene(
    name: str,
    id: str,
    subfolder: str,
    display_name: str,
    base: Annotated[str, CliOption(choices=("Scene", "Canvas3D"))] = "Scene",
    camera: Annotated[str, CliOption(choices=("2d", "3d"))] = "2d",
    maven_profile: str = "",
    dry_run: bool = False,
) -> dict:
    """Scaffold a new Scene class, launch.json entry, and optional Maven profile.

    :param name: Scene class name.
    :param id: Scene id used by IxdarWindow and the launch.json entry.
    :param subfolder: Package subfolder under scenes for the new class.
    :param display_name: Human-readable scene name.
    :param base: Base class to extend.
    :param camera: Camera mode for the scaffold.
    :param maven_profile: Optional Maven profile id to add.
    :param dry_run: Print planned changes without writing files.
    """
    return scaffold_new_scene(
        name=name,
        scene_id=id,
        subfolder=subfolder,
        display_name=display_name,
        base=base,
        camera=camera,
        maven_profile=maven_profile,
        dry_run=dry_run,
    )
