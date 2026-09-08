import io
import json
import os
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from ixdar_automation_cli import automation_client
from ixdar_automation_cli import collection_manifest
from ixdar_automation_cli import ixdar_cli
from ixdar_automation_cli import mesh_catalog
from ixdar_automation_cli import quilt_mesh_fingerprint
from ixdar_automation_cli.cli_commands import launch_entry
from ixdar_automation_cli.cli_commands import new_scene
from ixdar_automation_cli.cli_commands import run_scene
from ixdar_automation_cli.cli_commands import shutdown_scene
from ixdar_automation_cli.cli_registry import cli_command, get_registry


class FakeResponse:
    def __init__(self, payload):
        self._payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False

    def read(self):
        return json.dumps(self._payload).encode("utf-8")


class FakeProcess:
    """Stands in for the launched JVM: alive until told otherwise, with a pid."""

    def __init__(self, exit_code=None):
        self.exit_code = exit_code
        self.pid = 4242

    def poll(self):
        return self.exit_code

    def wait(self, timeout=0):
        return self.exit_code or 0


class FakeHealthClient:
    """An automation client whose health flips to ready after a set number of polls."""

    def __init__(self, ready_after):
        self.ready_after = ready_after
        self.polls = 0
        self.base_url = "http://127.0.0.1:47999"

    def health(self):
        self.polls += 1
        return {"status": "ok", "sceneReady": self.polls > self.ready_after}


class SceneLifecycleTest(unittest.TestCase):
    def test_checkout_root_is_the_working_directory_s_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = os.path.realpath(directory)
            os.makedirs(os.path.join(root, "ixdar-app", "src"))
            with open(os.path.join(root, "pom.xml"), "w", encoding="utf-8") as handle:
                handle.write("<project/>")
            previous = os.getcwd()
            try:
                os.chdir(os.path.join(root, "ixdar-app", "src"))
                self.assertEqual(root, automation_client.checkout_root())
            finally:
                os.chdir(previous)

    def test_read_port_file_accepts_json_and_a_bare_number(self):
        with tempfile.TemporaryDirectory() as directory:
            json_file = os.path.join(directory, "automation.port")
            with open(json_file, "w", encoding="utf-8") as handle:
                handle.write('{"port": 47901, "pid": 1234}\n')
            self.assertEqual({"port": 47901, "pid": 1234},
                             automation_client.read_port_file(json_file))
            with open(json_file, "w", encoding="utf-8") as handle:
                handle.write("47902\n")
            self.assertEqual({"port": 47902}, automation_client.read_port_file(json_file))
            self.assertEqual({}, automation_client.read_port_file(
                os.path.join(directory, "absent.port")))

    def test_discover_base_url_prefers_the_published_port(self):
        with patch.object(automation_client, "read_port_file", return_value={"port": 47903}):
            self.assertEqual("http://127.0.0.1:47903", automation_client.discover_base_url())
        with patch.object(automation_client, "read_port_file", return_value={}):
            self.assertEqual(automation_client.DEFAULT_BASE_URL,
                             automation_client.discover_base_url())

    def test_await_scene_keeps_waiting_until_the_scene_is_ready(self):
        # A log line matching --await-log used to end the wait on its own, handing back a scene
        # too young to screenshot; readiness is now required as well.
        client = FakeHealthClient(ready_after=2)
        with tempfile.TemporaryDirectory() as directory:
            log_path = os.path.join(directory, "scene.log")
            with open(log_path, "w", encoding="utf-8") as handle:
                handle.write("layout committed\nlayout committed again\n")
            with patch("time.sleep"):
                status = run_scene._await_scene(
                    client, FakeProcess(), log_path, "layout committed", timeout=30.0)
        self.assertTrue(status["ready"])
        self.assertEqual(2, len(status["matched"]))
        self.assertEqual(3, client.polls)

    def test_await_scene_reports_a_crash_without_waiting_out_the_timeout(self):
        client = FakeHealthClient(ready_after=1000)
        with tempfile.TemporaryDirectory() as directory:
            log_path = os.path.join(directory, "scene.log")
            with open(log_path, "w", encoding="utf-8") as handle:
                handle.write('Exception in thread "main" java.lang.IllegalStateException: no layout\n'
                             "\tat ixdar.scenes.QuadLayoutScene.initGL(QuadLayoutScene.java:88)\n")
            with patch("time.sleep"):
                status = run_scene._await_scene(client, FakeProcess(), log_path, "", timeout=30.0)
        self.assertFalse(status["ready"])
        self.assertIn("IllegalStateException", status["crash"][0])

    def test_await_scene_follows_the_port_the_scene_actually_bound(self):
        # The picked port is only a request; if the JVM had to fall back, the port file is the
        # authority and its pid is what proves the file belongs to this scene.
        client = FakeHealthClient(ready_after=1)
        client.base_url = "http://127.0.0.1:47910"
        process = FakeProcess()
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(run_scene, "read_port_file",
                             return_value={"port": 47911, "pid": process.pid}), \
                patch("time.sleep"):
            status = run_scene._await_scene(
                client, process, os.path.join(directory, "scene.log"), "", timeout=30.0)
        self.assertTrue(status["ready"])
        self.assertEqual("http://127.0.0.1:47911", client.base_url)

    def test_await_scene_ignores_a_port_file_left_by_another_scene(self):
        client = FakeHealthClient(ready_after=0)
        client.base_url = "http://127.0.0.1:47912"
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(run_scene, "read_port_file",
                             return_value={"port": 47913, "pid": 1}), \
                patch("time.sleep"):
            run_scene._await_scene(
                client, FakeProcess(), os.path.join(directory, "scene.log"), "", timeout=30.0)
        self.assertEqual("http://127.0.0.1:47912", client.base_url)

    def test_crash_headline_names_the_exception_and_its_first_frame(self):
        headline = run_scene.crash_headline([
            'Exception in thread "main" java.lang.IllegalStateException: no layout',
            "\tat ixdar.scenes.QuadLayoutScene.initGL(QuadLayoutScene.java:88)",
            "\tat ixdar.canvas.Canvas3D.run(Canvas3D.java:210)",
        ])
        self.assertEqual(
            "java.lang.IllegalStateException: no layout "
            "(at ixdar.scenes.QuadLayoutScene.initGL(QuadLayoutScene.java:88))",
            headline)
        self.assertEqual("", run_scene.crash_headline([]))

    def test_summary_line_carries_outcome_scene_mesh_port_and_screenshot(self):
        line = run_scene.summary_line({
            "ok": True,
            "scene": "quad-layout",
            "port": 47904,
            "mesh": {"name": "rockerarm", "vertices": 10044, "faces": 20088},
            "screenshot": {"path": "/tmp/shot.png"},
        })
        self.assertEqual(
            "ok scene=quad-layout mesh=rockerarm V=10044 F=20088 port=47904 "
            "screenshot=/tmp/shot.png",
            line)

    def test_sync_resources_copies_only_the_files_newer_than_their_class_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            resources = os.path.join(directory, "resources", "dsl")
            classes = os.path.join(directory, "classes", "dsl")
            os.makedirs(resources)
            os.makedirs(classes)
            for stem in ("fresh", "stale"):
                with open(os.path.join(resources, stem + ".dsl"), "w", encoding="utf-8") as handle:
                    handle.write("node loadMesh {}")
            with open(os.path.join(classes, "stale.dsl"), "w", encoding="utf-8") as handle:
                handle.write("node loadMesh {}")
            os.utime(os.path.join(classes, "stale.dsl"), (2 ** 31, 2 ** 31))
            with patch.object(run_scene, "RESOURCES_DIR", os.path.dirname(resources)), \
                    patch.object(run_scene, "CLASSES_DIR", os.path.dirname(classes)):
                copied = run_scene.sync_resources()
            self.assertEqual([os.path.join("dsl", "fresh.dsl")], copied)
            self.assertTrue(os.path.exists(os.path.join(classes, "fresh.dsl")))

    def test_run_scene_puts_the_crash_inline_and_reports_its_port(self):
        crash = ['Exception in thread "main" java.lang.IllegalStateException: no layout',
                 "\tat ixdar.scenes.QuadLayoutScene.initGL(QuadLayoutScene.java:88)"]
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(run_scene, "_ensure_build", return_value=[]), \
                patch.object(run_scene, "_java_command", return_value=["java", "-version"]), \
                patch.object(run_scene, "free_port", return_value=47905), \
                patch.object(run_scene, "_terminate"), \
                patch.object(subprocess, "Popen", return_value=FakeProcess()), \
                patch.object(run_scene, "_await_scene", return_value={
                    "ready": False, "exited": False, "matched": [], "crash": crash, "waited": 3.0}):
            result = run_scene.run(scene="quad-layout",
                                   log=os.path.join(directory, "scene.log"))
        self.assertFalse(result["ok"])
        self.assertEqual(47905, result["port"])
        self.assertEqual("http://127.0.0.1:47905", result["baseUrl"])
        self.assertIn("IllegalStateException", result["error"])
        self.assertIn("QuadLayoutScene.java:88", result["error"])
        self.assertIn("FAILED", result["summary"])

    def test_run_scene_mesh_sets_the_common_model_property_for_every_scene(self):
        captured: list[list[str]] = []

        def capture(scene, properties, profile_path, profile_event, coverage_path):
            captured.append(properties)
            return ["java", "-version"]

        with tempfile.TemporaryDirectory() as directory, \
                patch.object(run_scene, "_ensure_build", return_value=[]), \
                patch.object(run_scene, "_java_command", side_effect=capture), \
                patch.object(run_scene, "free_port", return_value=47906), \
                patch.object(run_scene, "_terminate"), \
                patch.object(subprocess, "Popen", return_value=FakeProcess()), \
                patch.object(run_scene, "_await_scene", return_value={
                    "ready": False, "exited": True, "matched": [], "crash": [], "waited": 1.0}):
            run_scene.run(scene="cross-field-exam", mesh="fertility",
                          log=os.path.join(directory, "scene.log"))
        model = [entry for entry in captured[0] if entry.startswith("ixdar.model=")]
        self.assertEqual(1, len(model))
        self.assertTrue(model[0].endswith("fertility_in_tri.off"), model[0])
        self.assertIn("ixdar.automation.port=47906", captured[0])

    def test_relative_save_property_resolves_against_the_module_resources(self):
        resolved = mesh_catalog.resolve_scene_properties(["quadLayout.save=quadlayout/probe.qlay"])
        self.assertEqual(
            ["quadLayout.save="
             + os.path.join(mesh_catalog.MODULE_RESOURCES_DIR, "quadlayout", "probe.qlay")],
            resolved)

    def test_model_property_resolves_a_mesh_name_and_leaves_other_tokens_alone(self):
        resolved = mesh_catalog.resolve_scene_properties(
            ["ixdar.model=fertility", "ixdar.model=graph:Skull"])
        self.assertTrue(resolved[0].endswith("fertility_in_tri.off"), resolved[0])
        self.assertEqual("ixdar.model=graph:Skull", resolved[1])

    def test_shutdown_returns_only_after_the_process_is_gone(self):
        alive = [True, True, False]
        with patch.object(shutdown_scene, "read_port_file",
                          return_value={"port": 47907, "pid": 9911}), \
                patch.object(shutdown_scene, "process_alive", side_effect=lambda pid: alive.pop(0)), \
                patch("time.sleep"), \
                patch("urllib.request.urlopen",
                      return_value=FakeResponse({"ok": True, "accepted": True})):
            exit_code = ixdar_cli.main(["shutdown"])
        self.assertEqual(0, exit_code)
        self.assertEqual([], alive)

    def test_shutdown_fails_when_the_process_outlives_the_timeout(self):
        with patch.object(shutdown_scene, "read_port_file",
                          return_value={"port": 47908, "pid": 9912}), \
                patch.object(shutdown_scene, "process_alive", return_value=True), \
                patch("time.sleep"), \
                patch("urllib.request.urlopen",
                      return_value=FakeResponse({"ok": True, "accepted": True})):
            exit_code = ixdar_cli.main(["shutdown", "--timeout", "0.01"])
        self.assertEqual(1, exit_code)

    def test_shutdown_replaces_the_generated_server_command(self):
        self.assertIn("shutdown", get_registry())
        self.assertNotIn("shutdown", ixdar_cli._server_commands())


class LaunchEntryTest(unittest.TestCase):
    LAUNCH_JSON = """{
  // A launch entry, with a comment.
  "version": "0.2.0",
  "configurations": [
    {
      "name": "Mesh Node Viewer",
      "mainClass": "ixdar.canvas.IxdarWindow",
      "args": "mesh-viewer",
      "vmArgs": ["${config:java.profiler.args}", "-Xmx4g"],
      "cwd": "${workspaceFolder}/ixdar-app",
    },
    {
      "name": "Quad Layout",
      "mainClass": "ixdar.canvas.IxdarWindow",
      "args": "quad-layout",
      "vmArgs": "-enableassertions -Xmx4g"
    }
  ]
}"""

    def _checkout(self, directory: str) -> str:
        os.makedirs(os.path.join(directory, ".vscode"))
        with open(os.path.join(directory, ".vscode", "launch.json"), "w", encoding="utf-8") as handle:
            handle.write(self.LAUNCH_JSON)
        with open(os.path.join(directory, ".vscode", "settings.json"), "w", encoding="utf-8") as handle:
            handle.write('{\n  // profiler\n  "java.profiler.args": "-agentpath:/usr/lib/lib.so",\n}')
        return directory

    def test_strip_jsonc_removes_comments_and_trailing_commas(self):
        parsed = json.loads(launch_entry.strip_jsonc(self.LAUNCH_JSON))
        self.assertEqual(2, len(parsed["configurations"]))
        self.assertEqual("Mesh Node Viewer", parsed["configurations"][0]["name"])

    def test_a_url_inside_a_string_survives_comment_stripping(self):
        parsed = json.loads(launch_entry.strip_jsonc('{"doc": "https://example.com/a"}'))
        self.assertEqual("https://example.com/a", parsed["doc"])

    def test_find_configuration_matches_exactly_then_by_substring(self):
        with tempfile.TemporaryDirectory() as directory:
            configurations = launch_entry.read_configurations(self._checkout(directory))
        self.assertEqual("Quad Layout",
                         launch_entry.find_configuration(configurations, "Quad Layout")["name"])
        self.assertEqual("Mesh Node Viewer",
                         launch_entry.find_configuration(configurations, "node viewer")["name"])
        with self.assertRaisesRegex(ValueError, "no launch entry matches"):
            launch_entry.find_configuration(configurations, "Nonexistent")

    def test_launch_command_keeps_the_entry_s_vm_args_and_adds_the_port(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self._checkout(directory)
            classpath_file = os.path.join(directory, "CP")
            with open(classpath_file, "w", encoding="utf-8") as handle:
                handle.write("/lib/gson.jar\n")
            configurations = launch_entry.read_configurations(root)
            configuration = launch_entry.find_configuration(configurations, "Mesh Node Viewer")
            with patch.object(launch_entry, "CLASSPATH_FILE", classpath_file):
                command = launch_entry.launch_command(configuration, root, 47909)
            self.assertEqual(os.path.join(root, "ixdar-app"),
                             launch_entry.working_directory(configuration, root))
        self.assertIn("-agentpath:/usr/lib/lib.so", command)
        self.assertIn("-Xmx4g", command)
        self.assertIn("-Dixdar.automation.port=47909", command)
        self.assertNotIn("-Dixdar.headless=true", command)
        self.assertEqual("mesh-viewer", command[-1])
        self.assertEqual("ixdar.canvas.IxdarWindow", command[-2])

    def test_launch_takes_its_entry_as_a_positional_argument(self):
        parsed = ixdar_cli._build_parser().parse_args(["launch", "Mesh Node Viewer"])
        self.assertEqual("Mesh Node Viewer", parsed.entry)


class CliTest(unittest.TestCase):
    def test_async_profiler_library_prefers_environment_override(self):
        with tempfile.NamedTemporaryFile(suffix=".so") as fake_library:
            with patch.dict(os.environ, {"ASYNC_PROFILER_LIB": fake_library.name}):
                self.assertEqual(fake_library.name, run_scene.async_profiler_library())

    def test_async_profiler_library_falls_back_to_platform_install(self):
        with tempfile.NamedTemporaryFile(suffix=".so") as fake_library:
            with patch.dict(os.environ, {"ASYNC_PROFILER_LIB": ""}), \
                    patch.object(run_scene, "ASYNC_PROFILER_LIB", "/nonexistent/.profiler/lib"), \
                    patch.object(run_scene, "ASYNC_PROFILER_CANDIDATES", (fake_library.name,)):
                self.assertEqual(fake_library.name, run_scene.async_profiler_library())

    def test_async_profiler_library_missing_names_the_install_step(self):
        with patch.dict(os.environ, {"ASYNC_PROFILER_LIB": ""}), \
                patch.object(run_scene, "ASYNC_PROFILER_LIB", "/nonexistent/.profiler/lib"), \
                patch.object(run_scene, "ASYNC_PROFILER_CANDIDATES", ("/nonexistent/lib.so",)):
            with self.assertRaises(FileNotFoundError) as failure:
                run_scene.async_profiler_library()
            self.assertIn("ASYNC_PROFILER_LIB", str(failure.exception))

    def test_build_parser_registers_decorated_commands(self):
        ixdar_cli._build_parser()
        registry = get_registry()
        self.assertIn("mesh-state", registry)
        self.assertIn("assert-tooltip", registry)
        self.assertIn("trade-hover-scan", registry)
        self.assertIn("quilt-mesh-compare", registry)

    def test_registry_walks_every_command_module(self):
        # Guards the walker: these commands live in modules nothing else imports, so a broken
        # discovery walk drops them silently rather than failing loudly.
        registry = get_registry()
        for command_name in ("install-alias", "gen-docs", "dsl-optimize", "mesh-viewer",
                             "rebuild-krieg-web", "validate-route-ops", "new-scene"):
            self.assertIn(command_name, registry)

    def test_build_parser_registers_server_routes(self):
        ixdar_cli._build_parser()
        server_commands = ixdar_cli._server_commands()
        # Server commands are generated from the manifest; their names come from Java describe().
        self.assertIn("health", server_commands)
        self.assertIn("click", server_commands)
        self.assertIn("mesh-patches-decompose", server_commands)
        self.assertIn("record-start", server_commands)
        # Names owned by a registry command (e.g. the mesh-overlay scenario) are not duplicated.
        self.assertNotIn("mesh-overlay", server_commands)

    @patch("urllib.request.urlopen")
    def test_health_command(self, urlopen):
        urlopen.return_value = FakeResponse({"status": "ok"})
        exit_code = ixdar_cli.main(["health"])
        self.assertEqual(0, exit_code)

    @patch("urllib.request.urlopen")
    def test_click_command_posts_payload(self, urlopen):
        urlopen.return_value = FakeResponse({"ok": True})
        exit_code = ixdar_cli.main(["click", "--x", "10", "--y", "20"])
        self.assertEqual(0, exit_code)
        request = urlopen.call_args.args[0]
        self.assertEqual("POST", request.method)

    @patch("urllib.request.urlopen")
    def test_hover_command_posts_payload(self, urlopen):
        urlopen.return_value = FakeResponse({"ok": True})
        exit_code = ixdar_cli.main(["hover", "--x", "15", "--y", "30", "--persistent"])
        self.assertEqual(0, exit_code)
        request = urlopen.call_args.args[0]
        self.assertEqual("POST", request.method)

    @patch("urllib.request.urlopen")
    def test_audio_state_command_extracts_audio_payload(self, urlopen):
        urlopen.return_value = FakeResponse({"audio": {"menuMusicPlaying": True, "menuMusicSourceCount": 1}})
        exit_code = ixdar_cli.main(["audio-state"])
        self.assertEqual(0, exit_code)

    @patch("urllib.request.urlopen")
    def test_mesh_state_command_extracts_mesh_payload(self, urlopen):
        urlopen.return_value = FakeResponse({"mesh": {"vertexCount": 8, "edgeCount": 18, "faceCount": 12}})
        exit_code = ixdar_cli.main(["mesh-state"])
        self.assertEqual(0, exit_code)

    @patch("urllib.request.urlopen")
    def test_mesh_validate_command_returns_ok_for_closed_mesh(self, urlopen):
        urlopen.return_value = FakeResponse(
            {
                "mesh": {
                    "vertexCount": 8,
                    "edgeCount": 18,
                    "faceCount": 12,
                    "boundaryEdgeCount": 0,
                    "degenerateFaceCount": 0,
                }
            }
        )
        exit_code = ixdar_cli.main(["mesh-validate"])
        self.assertEqual(0, exit_code)

    @patch("urllib.request.urlopen")
    def test_mesh_validate_command_returns_failure_for_open_mesh(self, urlopen):
        urlopen.return_value = FakeResponse(
            {
                "mesh": {
                    "vertexCount": 4,
                    "edgeCount": 5,
                    "faceCount": 2,
                    "boundaryEdgeCount": 4,
                    "degenerateFaceCount": 0,
                }
            }
        )
        exit_code = ixdar_cli.main(["mesh-validate"])
        self.assertEqual(6, exit_code)

    @patch("urllib.request.urlopen")
    def test_audio_log_command_returns_tail_events(self, urlopen):
        urlopen.return_value = FakeResponse({"audio": {"eventLog": ["1|a|INIT_OK", "2|b|MUSIC_PLAY", "3|c|SFX_PLAY"]}})
        exit_code = ixdar_cli.main(["audio-log", "--tail", "2"])
        self.assertEqual(0, exit_code)

    @patch("urllib.request.urlopen")
    def test_click_scan_finds_transition(self, urlopen):
        responses = [
            {"mode": "menu", "menuVisible": True},
            {"ok": True},
            {"mode": "trade", "menuVisible": False},
        ]
        urlopen.side_effect = [FakeResponse(payload) for payload in responses]
        exit_code = ixdar_cli.main(["click-scan", "--x-values", "250", "--y-start", "120", "--y-end", "140", "--y-step", "20"])
        self.assertEqual(0, exit_code)

    @patch("urllib.request.urlopen")
    def test_start_new_game_uses_menu_bounds(self, urlopen):
        responses = [
            {
                "mode": "menu",
                "menuVisible": True,
                "menuItems": [
                    {"label": "Start New Game", "bounds": {"centerXPx": 250, "centerYPx": 420}},
                ],
            },
            {"ok": True},
            {"mode": "trade", "menuVisible": False},
        ]
        urlopen.side_effect = [FakeResponse(payload) for payload in responses]
        exit_code = ixdar_cli.main(["start-new-game"])
        self.assertEqual(0, exit_code)

    @patch("urllib.request.urlopen")
    def test_trade_hover_scan_finds_pipe_tooltip(self, urlopen):
        responses = [
            {
                "windowWidth": 750,
                "windowHeight": 750,
                "trade": {"cities": [{"xPx": 120, "yPx": 200}]},
            },
            {"ok": True},
            {"ok": True},
            {"textElements": [{"type": "trade_tooltip", "lines": ["Pipe (P)"]}]},
        ]
        urlopen.side_effect = [FakeResponse(payload) for payload in responses]
        exit_code = ixdar_cli.main(["trade-hover-scan", "--contains", "Pipe (P)"])
        self.assertEqual(0, exit_code)

    @patch("urllib.request.urlopen")
    def test_shutdown_command_posts_request(self, urlopen):
        urlopen.return_value = FakeResponse({"ok": True, "accepted": True})
        # No port file means no pid to wait on, so the command reports the acknowledgement alone.
        with patch.object(shutdown_scene, "read_port_file", return_value={}):
            exit_code = ixdar_cli.main(["shutdown"])
        self.assertEqual(0, exit_code)

    @patch("urllib.request.urlopen")
    def test_probe_returns_health_state_screenshot(self, urlopen):
        responses = [
            {"status": "ok", "port": 47832},
            {
                "sceneId": "ixdar",
                "sceneClass": "Canvas3D",
                "mode": "main",
                "menuVisible": False,
                "windowWidth": 800,
                "windowHeight": 600,
            },
            {"path": "out.png", "sha256": "abc123", "width": 800, "height": 600},
        ]
        urlopen.side_effect = [FakeResponse(payload) for payload in responses]
        exit_code = ixdar_cli.main(["probe", "--out", "out.png"])
        self.assertEqual(0, exit_code)

    @patch("urllib.request.urlopen")
    def test_mesh_probe_returns_health_mesh_and_screenshot(self, urlopen):
        responses = [
            {"status": "ok", "port": 47832},
            {
                "sceneId": "mesh-viewer",
                "sceneClass": "MeshNodeViewerScene",
                "mode": "main",
                "menuVisible": False,
                "windowWidth": 800,
                "windowHeight": 600,
                "mesh": {
                    "vertexCount": 8,
                    "edgeCount": 18,
                    "faceCount": 12,
                    "boundaryEdgeCount": 0,
                },
            },
            {"path": "mesh.png", "sha256": "mesh123", "width": 800, "height": 600},
        ]
        urlopen.side_effect = [FakeResponse(payload) for payload in responses]
        exit_code = ixdar_cli.main(["mesh-probe", "--out", "mesh.png"])
        self.assertEqual(0, exit_code)

    @patch("urllib.request.urlopen")
    def test_quilt_mesh_compare_matches_when_hashes_equal(self, urlopen):
        obj = "v 0 0 0\nv 1 0 0\nv 1 1 0\nf 1 2 3\n"
        with tempfile.NamedTemporaryFile(mode="w", suffix=".obj", delete=False, encoding="utf-8") as f:
            f.write(obj)
            path = f.name
        try:
            ref = quilt_mesh_fingerprint.sha256_hex_from_obj_path(path)
            urlopen.return_value = FakeResponse(
                {
                    "ok": True,
                    "algorithm": quilt_mesh_fingerprint.ALGORITHM_ID,
                    "sha256": ref,
                    "vertexCount": 3,
                    "triangleCount": 1,
                }
            )
            exit_code = ixdar_cli.main(["quilt-mesh-compare", "--reference", path])
            self.assertEqual(0, exit_code)
            request = urlopen.call_args.args[0]
            self.assertEqual("GET", request.method)
        finally:
            os.unlink(path)

    @patch("urllib.request.urlopen")
    def test_quilt_mesh_compare_fails_on_hash_mismatch(self, urlopen):
        obj = "v 0 0 0\nv 1 0 0\nv 1 1 0\nf 1 2 3\n"
        with tempfile.NamedTemporaryFile(mode="w", suffix=".obj", delete=False, encoding="utf-8") as f:
            f.write(obj)
            path = f.name
        try:
            urlopen.return_value = FakeResponse(
                {
                    "ok": True,
                    "algorithm": quilt_mesh_fingerprint.ALGORITHM_ID,
                    "sha256": "0" * 64,
                    "vertexCount": 3,
                    "triangleCount": 1,
                }
            )
            exit_code = ixdar_cli.main(["quilt-mesh-compare", "--reference", path])
            self.assertEqual(7, exit_code)
        finally:
            os.unlink(path)

    @patch("urllib.request.urlopen")
    def test_assert_tooltip_includes_trade_tooltip_when_enabled(self, urlopen):
        urlopen.return_value = FakeResponse(
            {
                "textElements": [
                    {"type": "trade_tooltip", "lines": ["Pipe (P)", "Connect two cities/knots into one loop"]},
                ]
            }
        )
        exit_code = ixdar_cli.main(["assert-tooltip", "--contains", "Pipe (P)", "--include-trade"])
        self.assertEqual(0, exit_code)

    @patch("urllib.request.urlopen")
    def test_assert_tooltip_returns_failure_when_missing(self, urlopen):
        urlopen.return_value = FakeResponse(
            {
                "textElements": [
                    {"type": "tooltip", "lines": ["Some other tip"]},
                ]
            }
        )
        exit_code = ixdar_cli.main(["assert-tooltip", "--contains", "Collapse (C)"])
        self.assertEqual(4, exit_code)

    @patch("urllib.request.urlopen")
    def test_assert_tooltip_accepts_repeated_contains_flags(self, urlopen):
        urlopen.return_value = FakeResponse(
            {
                "textElements": [
                    {"type": "tooltip", "lines": ["Pipe (P)", "Collapse (C)"]},
                ]
            }
        )
        exit_code = ixdar_cli.main(["assert-tooltip", "--contains", "Pipe (P)", "--contains", "Collapse (C)"])
        self.assertEqual(0, exit_code)

    @patch("ixdar_automation_cli.cli_commands.trade_route_ops_validation.run_validation")
    def test_validate_route_ops_command_invokes_validation(self, run_validation):
        run_validation.return_value = (0, {"ok": True, "report": {"steps": []}})
        exit_code = ixdar_cli.main(["validate-route-ops"])
        self.assertEqual(0, exit_code)

    @patch("ixdar_automation_cli.cli_commands.new_scene.scaffold_new_scene")
    def test_new_scene_command_invokes_scaffolder(self, scaffold_new_scene):
        scaffold_new_scene.return_value = {"ok": True, "dryRun": True}
        exit_code = ixdar_cli.main(
            [
                "new-scene",
                "--name",
                "TestScene",
                "--id",
                "test-scene-canvas",
                "--subfolder",
                "ui",
                "--display-name",
                "Test Scene",
                "--camera",
                "3d",
                "--dry-run",
            ]
        )
        self.assertEqual(0, exit_code)
        scaffold_new_scene.assert_called_once()

    def test_scaffolded_scene_binds_automation_reflectively(self):
        # A direct AutomationInputBinder call drags the desktop automation stack, and gson behind
        # it, into the TeaVM web build, where they fail to link and the whole output is dropped.
        for camera in ("2d", "3d"):
            for base in ("Scene", "Canvas3D"):
                spec = new_scene.SceneSpec(
                    name="ProbeScene",
                    scene_id="probe-scene",
                    subfolder="ui",
                    display_name="Probe",
                    base=base,
                    camera=camera,
                    maven_profile="",
                    dry_run=True,
                )
                source = new_scene._scene_template(spec)
                with self.subTest(camera=camera, base=base):
                    self.assertIn("bindAutomationIfAvailable(Platforms.get(), keys, mouse);", source)
                    self.assertNotIn("AutomationInputBinder.bind(", source)
                    self.assertNotIn("import ixdar.platform.automation.AutomationInputBinder;", source)

    def test_subcommand_help_is_generated_from_docstrings(self):
        captured = io.StringIO()
        with patch("sys.stdout", captured):
            with self.assertRaises(SystemExit) as raised:
                ixdar_cli.main(["assert-tooltip", "--help"])
        self.assertEqual(0, raised.exception.code)
        help_text = captured.getvalue()
        self.assertIn("Assert that the visible tooltip text contains the requested strings.", help_text)
        self.assertIn("--contains", help_text)
        normalized_help = " ".join(help_text.split())
        self.assertIn("pass the flag multiple times", normalized_help)

    def test_cli_command_requires_param_docs(self):
        with self.assertRaisesRegex(ValueError, "missing ':param count:' documentation"):
            @cli_command(name="test-missing-param-docs")
            def invalid_command(count: int) -> dict:
                """Invalid command."""
                return {"ok": True}

    def test_collection_manifest_round_trips_members_and_keep_flags(self):
        with tempfile.TemporaryDirectory() as directory:
            for stem in ("charlie", "alpha", "bravo"):
                with open(os.path.join(directory, stem + ".gltf"), "w", encoding="utf-8") as handle:
                    handle.write("{}")
            collection = collection_manifest.scan_directory(directory)
            self.assertEqual(["alpha", "bravo", "charlie"],
                             [member["name"] for member in collection["members"]])
            self.assertTrue(all(member["keep"] for member in collection["members"]))

            collection_manifest.set_keep(directory, "bravo", False)
            reloaded = collection_manifest.read_manifest(collection["manifest"])
            self.assertEqual(["alpha", "bravo", "charlie"],
                             [member["name"] for member in reloaded["members"]])
            self.assertEqual([True, False, True],
                             [member["keep"] for member in reloaded["members"]])

            rescanned = collection_manifest.scan_directory(directory)
            self.assertFalse(rescanned["members"][1]["keep"])

    def test_collection_manifest_rewrite_is_byte_stable(self):
        with tempfile.TemporaryDirectory() as directory:
            for stem in ("alpha", "bravo"):
                with open(os.path.join(directory, stem + ".gltf"), "w", encoding="utf-8") as handle:
                    handle.write("{}")
            first = collection_manifest.render(collection_manifest.scan_directory(directory))
            collection_manifest.write_manifest(collection_manifest.scan_directory(directory))
            second = collection_manifest.render(collection_manifest.scan_directory(directory))
            self.assertEqual(first, second)
            self.assertIn('keep_alpha = input_boolean(name="keep:alpha", default=true)', first)
            self.assertTrue(first.rstrip().endswith('bravo.gltf")'),
                            "the last statement loads a mesh so the graph output is geometry")

    def test_collection_commands_are_registered_and_set_keep_flags(self):
        registry = get_registry()
        for command_name in ("collection-list", "collection-keep", "collection-reject"):
            self.assertIn(command_name, registry)
        with tempfile.TemporaryDirectory() as directory:
            for stem in ("alpha", "bravo"):
                with open(os.path.join(directory, stem + ".gltf"), "w", encoding="utf-8") as handle:
                    handle.write("{}")
            self.assertEqual(0, ixdar_cli.main(
                ["collection-reject", "--directory", directory, "--member", "alpha"]))
            flags = collection_manifest.read_keep_flags(
                os.path.join(directory, collection_manifest.MANIFEST_NAME))
            self.assertEqual({"alpha": False, "bravo": True}, flags)
            self.assertEqual(0, ixdar_cli.main(
                ["collection-keep", "--directory", directory, "--member", "alpha"]))
            flags = collection_manifest.read_keep_flags(
                os.path.join(directory, collection_manifest.MANIFEST_NAME))
            self.assertEqual({"alpha": True, "bravo": True}, flags)
            self.assertEqual(0, ixdar_cli.main(["collection-list", "--directory", directory]))


if __name__ == "__main__":
    unittest.main()
