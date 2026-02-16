import json
import unittest
from unittest.mock import patch

import ixdar_cli


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
    def test_audio_log_command_returns_tail_events(self, urlopen):
        urlopen.return_value = FakeResponse({"audio": {"eventLog": ["1|a|INIT_OK", "2|b|MUSIC_PLAY", "3|c|SFX_PLAY"]}})
        exit_code = ixdar_cli.main(["audio-log", "--tail", "2"])
        self.assertEqual(0, exit_code)

    @patch("urllib.request.urlopen")
    def test_click_scan_finds_transition(self, urlopen):
        responses = [
            {"scene": "menu", "menuVisible": True},
            {"ok": True},
            {"scene": "trade", "menuVisible": False},
        ]
        urlopen.side_effect = [FakeResponse(payload) for payload in responses]
        exit_code = ixdar_cli.main(["click-scan", "--x-values", "250", "--y-start", "120", "--y-end", "140", "--y-step", "20"])
        self.assertEqual(0, exit_code)

    @patch("urllib.request.urlopen")
    def test_start_new_game_uses_menu_bounds(self, urlopen):
        responses = [
            {
                "scene": "menu",
                "menuVisible": True,
                "menuItems": [
                    {"label": "Start New Game", "bounds": {"centerXPx": 250, "centerYPx": 420}},
                ],
            },
            {"ok": True},
            {"scene": "trade", "menuVisible": False},
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
            {"scene": "main", "menuVisible": False, "windowWidth": 800, "windowHeight": 600},
            {"path": "out.png", "sha256": "abc123", "width": 800, "height": 600},
        ]
        urlopen.side_effect = [FakeResponse(payload) for payload in responses]
        exit_code = ixdar_cli.main(["probe", "--out", "out.png"])
        self.assertEqual(0, exit_code)

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

    @patch("ixdar_cli.run_validation")
    def test_validate_route_ops_command_invokes_validation(self, run_validation):
        run_validation.return_value = (0, {"ok": True, "report": {"steps": []}})
        exit_code = ixdar_cli.main(["validate", "route-ops"])
        self.assertEqual(0, exit_code)

    @patch("ixdar_cli.scaffold_new_scene")
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


if __name__ == "__main__":
    unittest.main()
