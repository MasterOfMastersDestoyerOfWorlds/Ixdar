import io
import json
import os
import tempfile
import unittest
from unittest.mock import patch

from ixdar_automation_cli import ixdar_cli
from ixdar_automation_cli import quilt_mesh_fingerprint
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


class CliTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
