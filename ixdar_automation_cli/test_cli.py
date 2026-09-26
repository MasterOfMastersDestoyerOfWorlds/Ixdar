import io
import json
import os
import subprocess
import struct
import tempfile
import unittest
import zlib
from unittest.mock import patch

from ixdar_automation_cli import automation_client
from ixdar_automation_cli import collection_manifest
from ixdar_automation_cli import ixdar_cli
from ixdar_automation_cli import mesh_catalog
from ixdar_automation_cli import quilt_mesh_fingerprint
from ixdar_automation_cli.cli_commands import gen_docs
from ixdar_automation_cli.cli_commands import launch_entry
from ixdar_automation_cli import png_image
from ixdar_automation_cli import quilt_mesh_fingerprint
from ixdar_automation_cli import run_logs
from ixdar_automation_cli.cli_commands import image_commands
from ixdar_automation_cli.cli_commands import new_scene
from ixdar_automation_cli.cli_commands import run_scene
from ixdar_automation_cli.cli_commands import shutdown_scene
from ixdar_automation_cli.cli_registry import cli_command, get_registry


def write_test_png(path, width, height, pixel, filter_type=0):
    """Write an 8-bit RGB PNG so image tests do not need a rendered screenshot or Pillow.

    :param path: file to write
    :param width: image width in pixels
    :param height: image height in pixels
    :param pixel: callable taking x and y and returning an (r, g, b) tuple
    :param filter_type: PNG row filter to encode with; 0 is none, 2 is up, 4 is Paeth
    """
    def chunk(tag, body):
        return struct.pack(">I", len(body)) + tag + body + struct.pack(">I", zlib.crc32(tag + body))

    raw = b""
    previous = bytes(width * 3)
    for y in range(height):
        row = b"".join(bytes(pixel(x, y)) for x in range(width))
        if filter_type == 0:
            encoded = row
        elif filter_type == 2:
            encoded = bytes((row[i] - previous[i]) & 0xFF for i in range(len(row)))
        else:
            raise ValueError(f"test writer does not encode filter {filter_type}")
        raw += bytes([filter_type]) + encoded
        previous = row
    with open(path, "wb") as handle:
        handle.write(
            b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw))
            + chunk(b"IEND", b"")
        )


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
                patch.object(run_scene, "point_latest"), \
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
                patch.object(run_scene, "point_latest"), \
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

    def test_run_scene_keep_alive_names_the_model_switch_command(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(run_scene, "_ensure_build", return_value=[]), \
                patch.object(run_scene, "_java_command", return_value=["java", "-version"]), \
                patch.object(run_scene, "free_port", return_value=47907), \
                patch.object(run_scene, "_mesh_summary", return_value={}), \
                patch.object(subprocess, "Popen", return_value=FakeProcess()), \
                patch.object(run_scene, "_await_scene", return_value={
                    "ready": True, "exited": False, "matched": [], "crash": [], "waited": 1.0}):
            result = run_scene.run(scene="quad-layout", keep_alive=True,
                                   log=os.path.join(directory, "scene.log"))
        self.assertTrue(result["ok"])
        self.assertIn("ixdar-cli model <name>", result["next"])

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
            handle.write('{\n  // profiler\n  "java.profiler.args":'
                         ' "-agentpath:${workspaceFolder}/.profiler/lib.so",\n}')
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

    def test_launch_command_keeps_the_entry_s_vm_args_and_adds_the_port_headless(self):
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
            # ${workspaceFolder} is substituted inside the ${config:…} value, where the profiler
            # agent path lives; an unsubstituted token would be a fatal -agentpath.
            self.assertIn(f"-agentpath:{root}/.profiler/lib.so", command)
        self.assertIn("-Xmx4g", command)
        self.assertIn("-Dixdar.automation.port=47909", command)
        # The entry runs exactly as F5 runs it, with the off-screen platform as the one addition,
        # placed after the entry's own vmArgs so it wins.
        self.assertIn("-Dixdar.headless=true", command)
        self.assertLess(command.index("-Xmx4g"), command.index("-Dixdar.headless=true"))
        self.assertEqual("mesh-viewer", command[-1])
        self.assertEqual("ixdar.canvas.IxdarWindow", command[-2])

    def test_launch_takes_its_entry_as_a_positional_argument(self):
        parsed = ixdar_cli._build_parser().parse_args(["launch", "Mesh Node Viewer"])
        self.assertEqual("Mesh Node Viewer", parsed.entry)


def make_checkout(directory: str) -> str:
    """Lay out the two markers checkout_root looks for, so a temp directory counts as a checkout.

    :param directory: Directory to turn into a checkout.
    :return: The directory's real path.
    """
    root = os.path.realpath(directory)
    os.makedirs(os.path.join(root, "ixdar-app"), exist_ok=True)
    with open(os.path.join(root, "pom.xml"), "w", encoding="utf-8") as handle:
        handle.write("<project/>")
    return root


class RunLogsTest(unittest.TestCase):
    def setUp(self):
        self.previous_directory = os.getcwd()

    def tearDown(self):
        os.chdir(self.previous_directory)

    def test_numbering_takes_the_next_free_number_per_scene_and_mesh(self):
        with tempfile.TemporaryDirectory() as directory:
            root = make_checkout(directory)
            logs = os.path.join(root, "tmp", "logs")
            first = run_logs.next_log_path("quad-layout", "fertility", root)
            second = run_logs.next_log_path("quad-layout", "fertility", root)
            self.assertEqual(os.path.join(logs, "quad-layout-fertility-1.log"), first)
            self.assertEqual(os.path.join(logs, "quad-layout-fertility-2.log"), second)
            # A gap is not refilled: the next number follows the highest one on disk.
            open(os.path.join(logs, "quad-layout-fertility-7.log"), "w").close()
            self.assertEqual(os.path.join(logs, "quad-layout-fertility-8.log"),
                             run_logs.next_log_path("quad-layout", "fertility", root))
            # Another mesh, or no mesh at all, counts on its own.
            self.assertEqual(os.path.join(logs, "quad-layout-rockerarm-1.log"),
                             run_logs.next_log_path("quad-layout", "rockerarm", root))
            self.assertEqual(os.path.join(logs, "quad-layout-1.log"),
                             run_logs.next_log_path("quad-layout", "", root))
            self.assertEqual(os.path.join(logs, "quad-layout-2.log"),
                             run_logs.next_log_path("quad-layout", "Quad Layout", root))

    def test_latest_link_follows_the_newest_run(self):
        with tempfile.TemporaryDirectory() as directory:
            root = make_checkout(directory)
            first = run_logs.next_log_path("mesh-viewer", "", root)
            link = run_logs.point_latest("mesh-viewer", first, root)
            self.assertEqual(os.path.join(root, "tmp", "logs", "latest-mesh-viewer.log"), link)
            self.assertEqual(first, os.path.realpath(link))
            second = run_logs.next_log_path("mesh-viewer", "", root)
            run_logs.point_latest("mesh-viewer", second, root)
            self.assertEqual(second, os.path.realpath(link))
            # Relative, so the link survives the checkout moving.
            self.assertEqual("mesh-viewer-2.log", os.readlink(link))
            self.assertEqual(["latest-mesh-viewer.log", "mesh-viewer-1.log", "mesh-viewer-2.log"],
                             sorted(os.listdir(os.path.dirname(link))))

    def test_two_checkouts_write_different_files(self):
        with tempfile.TemporaryDirectory() as first_directory, \
                tempfile.TemporaryDirectory() as second_directory:
            first_root = make_checkout(first_directory)
            second_root = make_checkout(second_directory)
            os.chdir(first_root)
            first = run_logs.next_log_path("quad-layout")
            os.chdir(os.path.join(second_root, "ixdar-app"))
            second = run_logs.next_log_path("quad-layout")
            self.assertNotEqual(first, second)
            self.assertTrue(first.startswith(os.path.join(first_root, "tmp", "logs") + os.sep))
            self.assertTrue(second.startswith(os.path.join(second_root, "tmp", "logs") + os.sep))

    def test_mesh_label_prefers_the_mesh_option_then_the_model_property(self):
        self.assertEqual("fertility", run_logs.mesh_label(
            "fertility", ["ixdar.model=rockerarm"], mesh_catalog.MODEL_PROPERTY))
        self.assertEqual("fertility-in-tri", run_logs.mesh_label(
            "", ["quadLayout.debug=true", "ixdar.model=meshes/fertility_in_tri.off"],
            mesh_catalog.MODEL_PROPERTY))
        self.assertEqual("graph-skull", run_logs.mesh_label(
            "", ["ixdar.model=graph:Skull"], mesh_catalog.MODEL_PROPERTY))
        self.assertEqual("", run_logs.mesh_label("", ["quadLayout.debug=true"],
                                                 mesh_catalog.MODEL_PROPERTY))

    def test_run_scene_defaults_its_log_under_the_checkout_and_reports_it(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(run_scene, "_ensure_build", return_value=[]), \
                patch.object(run_scene, "_java_command", return_value=["java", "-version"]), \
                patch.object(run_scene, "free_port", return_value=47914), \
                patch.object(run_scene, "_terminate"), \
                patch.object(subprocess, "Popen", return_value=FakeProcess()), \
                patch.object(run_scene, "_await_scene", return_value={
                    "ready": False, "exited": True, "matched": [], "crash": [], "waited": 1.0}), \
                patch("sys.stderr", new_callable=io.StringIO) as stderr:
            root = make_checkout(directory)
            os.chdir(root)
            result = run_scene.run(scene="cross-field-exam", mesh="fertility")
            expected = os.path.join(root, "tmp", "logs", "cross-field-exam-fertility-1.log")
            self.assertEqual(expected, result["log"])
            self.assertIn(f"log={expected}", result["summary"])
            self.assertEqual(f"log: {expected}", stderr.getvalue().splitlines()[0])
            self.assertEqual(expected, os.path.realpath(
                os.path.join(root, "tmp", "logs", "latest-cross-field-exam.log")))
            explicit = os.path.join(root, "chosen.log")
            self.assertEqual(explicit, run_scene.run(scene="cross-field-exam", log=explicit)["log"])

    def test_launch_defaults_its_log_under_the_checkout(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(launch_entry, "_ensure_build", return_value=[]), \
                patch.object(launch_entry, "launch_command", return_value=["java", "-version"]), \
                patch.object(launch_entry, "free_port", return_value=47915), \
                patch.object(launch_entry, "_terminate"), \
                patch.object(subprocess, "Popen", return_value=FakeProcess()), \
                patch.object(launch_entry, "_await_scene", return_value={
                    "ready": False, "exited": True, "matched": [], "crash": [], "waited": 1.0}), \
                patch("sys.stderr", new_callable=io.StringIO):
            root = make_checkout(directory)
            LaunchEntryTest()._checkout(root)
            os.chdir(root)
            payload = launch_entry.launch(entry="Mesh Node Viewer").payload
            expected = os.path.join(root, "tmp", "logs", "mesh-viewer-mesh-node-viewer-1.log")
            self.assertEqual(expected, payload["log"])
            self.assertEqual(expected, os.path.realpath(
                os.path.join(root, "tmp", "logs", "latest-mesh-viewer.log")))


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
        self.assertIn("rings-list", server_commands)
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
    def test_rings_list_posts_the_mesh_path(self, urlopen):
        urlopen.return_value = FakeResponse({"ok": True, "ring_count": 5, "rings": []})
        exit_code = ixdar_cli.main(["rings-list", "--path", "fertility_in_tri.off",
                                    "--min-neckness", "0.6"])
        self.assertEqual(0, exit_code)
        request = urlopen.call_args.args[0]
        self.assertEqual("POST", request.method)
        self.assertTrue(request.full_url.endswith("/mesh/rings/list"))
        body = json.loads(request.data.decode("utf-8"))
        self.assertEqual("fertility_in_tri.off", body["path"])
        self.assertAlmostEqual(0.6, body["min_neckness"])

    @patch("urllib.request.urlopen")
    def test_rings_add_command_posts_points_under_a_group(self, urlopen):
        # The route declares the grouped command name "rings add", so the CLI parses two words.
        urlopen.return_value = FakeResponse({"ok": True, "edgeCount": 96})
        exit_code = ixdar_cli.main(
            ["rings", "add", "--points", "1.35,0,0; 0.45,0.3,0.69; 0.51,-0.3,-0.65"]
        )
        self.assertEqual(0, exit_code)
        request = urlopen.call_args.args[0]
        self.assertEqual("POST", request.method)
        self.assertTrue(request.full_url.endswith("/mesh/rings/add"))
        body = json.loads(request.data.decode("utf-8"))
        self.assertEqual("1.35,0,0; 0.45,0.3,0.69; 0.51,-0.3,-0.65", body["points"])
        self.assertTrue(body["tighten"])

    def test_rings_add_route_is_registered_under_its_group(self):
        ixdar_cli._build_parser()
        self.assertIn("rings add", ixdar_cli._server_commands())

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

    @patch("urllib.request.urlopen")
    def test_key_command_sends_a_name_and_reports_consumed(self, urlopen):
        urlopen.return_value = FakeResponse({"ok": True, "consumed": True})
        self.assertEqual(0, ixdar_cli.main(["key", "--key", "SHIFT+P"]))
        body = json.loads(urlopen.call_args[0][0].data.decode("utf-8"))
        self.assertEqual({"key": "SHIFT+P", "action": "tap", "settle": 2}, body)

    @patch("urllib.request.urlopen")
    def test_terminal_command_takes_the_line_positionally(self, urlopen):
        urlopen.return_value = FakeResponse({"ok": True, "response": ["ring 0: 12 edges"]})
        self.assertEqual(0, ixdar_cli.main(["terminal", "rings list"]))
        request = urlopen.call_args[0][0]
        self.assertTrue(request.full_url.endswith("/input/terminal"))
        self.assertEqual({"line": "rings list", "settle": 2},
                         json.loads(request.data.decode("utf-8")))

    @patch("urllib.request.urlopen")
    def test_model_command_takes_the_name_positionally_and_waits_as_long_as_the_route(self, urlopen):
        urlopen.return_value = FakeResponse(
            {"ok": True, "outcome": "loaded", "model": "bolt", "seconds": 4.2})
        self.assertEqual(0, ixdar_cli.main(["model", "bolt"]))
        request = urlopen.call_args[0][0]
        self.assertTrue(request.full_url.endswith("/scene/model"))
        self.assertEqual({"name": "bolt"}, json.loads(request.data.decode("utf-8")))
        # The route holds the request open for the whole recompute, so the client must outwait it.
        route = ixdar_cli._server_commands()["model"]
        self.assertGreater(urlopen.call_args.kwargs["timeout"], route["waitSeconds"])

    @patch("urllib.request.urlopen")
    def test_a_failed_server_command_exits_non_zero(self, urlopen):
        urlopen.return_value = FakeResponse(
            {"ok": False, "outcome": "failed", "error": "java.io.IOException: unreadable"})
        self.assertEqual(1, ixdar_cli.main(["model", "does-not-exist"]))

    def test_gen_docs_shows_a_positional_parameter_as_an_argument(self):
        route = ixdar_cli._server_commands()["model"]
        self.assertEqual(" <name>", gen_docs._flag_summary(route))

    @patch("urllib.request.urlopen")
    def test_click_and_hover_settle_by_default(self, urlopen):
        for command, path in (("click", "/input/click"), ("hover", "/input/hover")):
            with self.subTest(command=command):
                urlopen.return_value = FakeResponse({"ok": True, "settled": True})
                self.assertEqual(0, ixdar_cli.main([command, "--x", "10", "--y", "20"]))
                request = urlopen.call_args[0][0]
                self.assertTrue(request.full_url.endswith(path))
                self.assertEqual(2, json.loads(request.data.decode("utf-8"))["settle"])

    def test_png_decoder_reads_unfiltered_and_filtered_rows(self):
        def gradient(x, y):
            return (x * 3 % 256, y * 7 % 256, 40)

        with tempfile.TemporaryDirectory() as directory:
            plain = os.path.join(directory, "plain.png")
            filtered = os.path.join(directory, "filtered.png")
            write_test_png(plain, 40, 24, gradient, filter_type=0)
            write_test_png(filtered, 40, 24, gradient, filter_type=2)
            # The same pixels through two different row filters must decode to the same bytes,
            # which is what proves the unfilter step and not just the inflate step is right.
            self.assertEqual(png_image.read_png(plain).rgb, png_image.read_png(filtered).rgb)
            self.assertEqual((40, 24), (png_image.read_png(plain).width, png_image.read_png(plain).height))

    def test_image_diff_reports_zero_for_identical_and_the_changed_pixels_otherwise(self):
        with tempfile.TemporaryDirectory() as directory:
            first = os.path.join(directory, "first.png")
            second = os.path.join(directory, "second.png")
            write_test_png(first, 30, 20, lambda x, y: (10, 20, 30))
            # A ring of 12 pixels: everything else is identical.
            ring = {(x, 5) for x in range(9, 15)} | {(x, 9) for x in range(9, 15)}
            write_test_png(second, 30, 20, lambda x, y: (200, 20, 30) if (x, y) in ring else (10, 20, 30))

            self.assertEqual(0, ixdar_cli.main(["image-diff", first, first]))
            identical = png_image.compare_images(png_image.read_png(first), png_image.read_png(first), 8)
            self.assertEqual(0, identical["differingPixels"])
            self.assertEqual(0.0, identical["rmse"])
            self.assertTrue(identical["identical"])

            changed = png_image.compare_images(png_image.read_png(first), png_image.read_png(second), 8)
            self.assertEqual(12, changed["differingPixels"])
            self.assertEqual(190, changed["maxChannelDelta"])
            self.assertGreater(changed["rmse"], 0.0)
            self.assertFalse(changed["identical"])

    def test_image_diff_fails_when_more_pixels_differ_than_allowed(self):
        with tempfile.TemporaryDirectory() as directory:
            first = os.path.join(directory, "first.png")
            second = os.path.join(directory, "second.png")
            write_test_png(first, 16, 16, lambda x, y: (0, 0, 0))
            write_test_png(second, 16, 16, lambda x, y: (255, 255, 255))
            self.assertEqual(6, ixdar_cli.main(["image-diff", first, second, "--max-differing", "0"]))
            self.assertEqual(0, ixdar_cli.main(["image-diff", first, second, "--max-differing", "256"]))

    def test_image_diff_rejects_images_of_different_sizes(self):
        with tempfile.TemporaryDirectory() as directory:
            small = os.path.join(directory, "small.png")
            large = os.path.join(directory, "large.png")
            write_test_png(small, 8, 8, lambda x, y: (0, 0, 0))
            write_test_png(large, 9, 8, lambda x, y: (0, 0, 0))
            self.assertEqual(6, ixdar_cli.main(["image-diff", small, large]))

    def test_image_stats_fails_on_a_black_frame(self):
        # MESH-53: an all-black multiview composite must fail loudly rather than read as a render.
        with tempfile.TemporaryDirectory() as directory:
            black = os.path.join(directory, "multiview-black.png")
            drawn = os.path.join(directory, "multiview-drawn.png")
            write_test_png(black, 32, 16, lambda x, y: (0, 0, 0))
            write_test_png(drawn, 32, 16, lambda x, y: (0, 0, 0) if y < 8 else (90, 110, 130))

            self.assertEqual(6, ixdar_cli.main(["image-stats", black]))
            self.assertEqual(0, ixdar_cli.main(["image-stats", drawn]))
            self.assertTrue(png_image.image_statistics(png_image.read_png(black))["blank"])
            self.assertFalse(png_image.image_statistics(png_image.read_png(drawn))["blank"])
            # A dim-but-not-uniform frame still fails an explicit brightness floor.
            self.assertEqual(6, ixdar_cli.main(["image-stats", drawn, "--min-mean", "200"]))

    def test_positional_command_arguments_are_parsed(self):
        parser = ixdar_cli._build_parser()
        parsed = parser.parse_args(["image-diff", "a.png", "b.png"])
        self.assertEqual("a.png", parsed.first)
        self.assertEqual("b.png", parsed.second)
        self.assertEqual(image_commands.DEFAULT_FUZZ, parsed.fuzz)

    def test_screenshot_and_frame_routes_expose_their_new_flags(self):
        server_commands = ixdar_cli._server_commands()
        screenshot_flags = {param["cliName"] for param in server_commands["screenshot"]["params"]}
        self.assertIn("crop", screenshot_flags)
        self.assertIn("scale", screenshot_flags)
        self.assertIn("target", {param["cliName"] for param in server_commands["orbit-set"]["params"]})
        self.assertIn("frame", server_commands)
        frame_flags = {param["cliName"] for param in server_commands["frame"]["params"]}
        self.assertEqual({"selection", "bounds", "padding", "azimuth", "elevation"}, frame_flags)


if __name__ == "__main__":
    unittest.main()
