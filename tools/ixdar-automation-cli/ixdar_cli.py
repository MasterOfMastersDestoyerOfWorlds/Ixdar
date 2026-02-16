#!/usr/bin/env python3
import argparse
import json
import sys
import urllib.error
from automation_client import (
    DEFAULT_BASE_URL,
    AutomationClient,
    collect_tooltip_lines,
    collect_trade_tooltip_lines,
    toolbar_button_center,
)
from scene_scaffolding import scaffold_new_scene
from trade_route_ops_validation import run_validation
from trade_scenarios import click_until_scene_transition, start_new_game


def probe(base_url: str, screenshot_out: str) -> dict:
    client = AutomationClient(base_url)
    health = client.health()
    state = client.ui_state()
    screenshot = client.screenshot(screenshot_out, inline=False)
    return {
        "ok": True,
        "health": health,
        "uiStateSummary": {
            "scene": state.get("scene"),
            "menuVisible": state.get("menuVisible"),
            "windowWidth": state.get("windowWidth"),
            "windowHeight": state.get("windowHeight"),
        },
        "screenshot": {
            "path": screenshot.get("path"),
            "sha256": screenshot.get("sha256"),
            "width": screenshot.get("width"),
            "height": screenshot.get("height"),
        },
    }


def scan_trade_toolbar_tooltip(
    base_url: str,
    expected_text: str,
    toolbar_x: float | None,
    toolbar_y: float | None,
    toolbar_button: str,
    button: int,
) -> dict:
    client = AutomationClient(base_url)
    state = client.ui_state()
    window_height = float(state.get("windowHeight", 0))
    window_width = float(state.get("windowWidth", 0))
    if toolbar_x is None:
        toolbar_x, _ = toolbar_button_center(window_width, window_height, toolbar_button)
    if toolbar_y is None:
        _, toolbar_y = toolbar_button_center(window_width, window_height, toolbar_button)
    trade = state.get("trade", {})
    city_clicks: list[tuple[float, float]] = []
    if isinstance(trade, dict):
        for city in trade.get("cities", []):
            x = city.get("xPx")
            y = city.get("yPx")
            if x is None or y is None:
                continue
            click_y = (window_height - float(y)) if window_height else float(y)
            city_clicks.append((float(x), click_y))

    if not city_clicks:
        city_clicks = [(x, y) for x in range(70, 681, 60) for y in range(90, 651, 60)]

    for x, y in city_clicks:
        client.click(x, y, normalized=False, button=button)
        client.hover(toolbar_x, toolbar_y, normalized=False, persistent=True)
        state = client.ui_state()
        tips = collect_trade_tooltip_lines(state)
        active_tool = state.get("trade", {}).get("activeTool", "")
        if any(expected_text in tip for tip in tips):
            return {
                "ok": True,
                "hq_click": {"x": x, "y": y},
                "activeTool": active_tool,
                "trade_tooltips": tips,
            }

    state = client.ui_state()
    return {
        "ok": False,
        "activeTool": state.get("trade", {}).get("activeTool", ""),
        "textElements": state.get("textElements", []),
        "error": f"Could not find trade tooltip containing: {expected_text}",
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(prog="ixdar")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("health")
    subparsers.add_parser("ui-state")
    subparsers.add_parser("audio-state")
    audio_log = subparsers.add_parser("audio-log")
    audio_log.add_argument("--tail", type=int, default=20)
    probe_cmd = subparsers.add_parser("probe")
    probe_cmd.add_argument("--out", default="")
    tooltip = subparsers.add_parser("assert-tooltip")
    tooltip.add_argument("--contains", action="append", required=True)
    tooltip.add_argument("--include-trade", action="store_true")

    screenshot = subparsers.add_parser("screenshot")
    screenshot.add_argument("--out", default="")
    screenshot.add_argument("--inline", action="store_true")

    click = subparsers.add_parser("click")
    click.add_argument("--x", type=float, required=True)
    click.add_argument("--y", type=float, required=True)
    click.add_argument("--normalized", action="store_true")
    click.add_argument("--button", type=int, default=0)

    hover = subparsers.add_parser("hover")
    hover.add_argument("--x", type=float, required=True)
    hover.add_argument("--y", type=float, required=True)
    hover.add_argument("--normalized", action="store_true")
    hover.add_argument("--persistent", action="store_true")

    subparsers.add_parser("hover-clear")

    click_scan = subparsers.add_parser("click-scan")
    click_scan.add_argument("--x-values", default="250,300,350,375,400,450,500")
    click_scan.add_argument("--y-start", type=int, default=120)
    click_scan.add_argument("--y-end", type=int, default=620)
    click_scan.add_argument("--y-step", type=int, default=20)
    click_scan.add_argument("--button", type=int, default=0)

    start_game = subparsers.add_parser("start-new-game")
    start_game.add_argument("--button", type=int, default=0)
    start_game.add_argument("--no-fallback-scan", action="store_true")

    trade_hover_scan = subparsers.add_parser("trade-hover-scan")
    trade_hover_scan.add_argument("--contains", default="Pipe (P)")
    trade_hover_scan.add_argument("--toolbar-x", type=float, default=-1)
    trade_hover_scan.add_argument("--toolbar-y", type=float, default=-1)
    trade_hover_scan.add_argument(
        "--toolbar-button",
        choices=["pipe", "grow", "collapse", "undo", "confirm"],
        default="pipe",
    )
    trade_hover_scan.add_argument("--button", type=int, default=0)

    scroll = subparsers.add_parser("scroll")
    scroll.add_argument("--delta", type=float, required=True)

    key = subparsers.add_parser("key")
    key.add_argument("--key", type=int, required=True)
    key.add_argument("--action", type=int, default=1)
    key.add_argument("--mods", type=int, default=0)
    key.add_argument("--scancode", type=int, default=0)

    type_cmd = subparsers.add_parser("type")
    type_cmd.add_argument("--text", required=True)

    record = subparsers.add_parser("record")
    record_sub = record.add_subparsers(dest="record_command", required=True)
    record_sub.add_parser("start")
    record_status = record_sub.add_parser("status")
    record_stop = record_sub.add_parser("stop")
    record_stop.add_argument("--path", default="")

    replay = subparsers.add_parser("replay")
    replay_sub = replay.add_subparsers(dest="replay_command", required=True)
    replay_sub.add_parser("status")
    replay_start = replay_sub.add_parser("start")
    replay_start.add_argument("--file", required=True)
    replay_start.add_argument("--mode", choices=["abstract", "raw"], default="abstract")
    replay_sub.add_parser("pause")
    replay_sub.add_parser("resume")
    replay_sub.add_parser("cancel")

    subparsers.add_parser("shutdown")
    validate = subparsers.add_parser("validate")
    validate_sub = validate.add_subparsers(dest="validate_command", required=True)
    validate_sub.add_parser("route-ops")
    new_scene = subparsers.add_parser("new-scene")
    new_scene.add_argument("--name", required=True)
    new_scene.add_argument("--id", required=True)
    new_scene.add_argument("--subfolder", required=True)
    new_scene.add_argument("--display-name", required=True)
    new_scene.add_argument("--base", choices=["Scene", "Canvas3D"], default="Scene")
    new_scene.add_argument("--camera", choices=["2d", "3d"], default="2d")
    new_scene.add_argument("--maven-profile", default="")
    new_scene.add_argument("--dry-run", action="store_true")

    args = parser.parse_args(argv)
    base = args.base_url
    client = AutomationClient(base)

    try:
        if args.command == "health":
            result = client.health()
        elif args.command == "ui-state":
            result = client.ui_state()
        elif args.command == "audio-state":
            ui_state = client.ui_state()
            result = {
                "ok": True,
                "audio": ui_state.get("audio", {}),
            }
        elif args.command == "audio-log":
            ui_state = client.ui_state()
            audio = ui_state.get("audio", {})
            events = audio.get("eventLog", [])
            if args.tail >= 0:
                events = events[-args.tail:]
            result = {
                "ok": True,
                "count": len(events),
                "events": events,
            }
        elif args.command == "probe":
            result = probe(base, args.out)
        elif args.command == "assert-tooltip":
            ui_state = client.ui_state()
            lines = collect_tooltip_lines(ui_state, args.include_trade)
            joined = "\n".join(lines)
            missing = [needle for needle in args.contains if needle not in joined]
            result = {
                "ok": len(missing) == 0,
                "tooltips": lines,
                "missing": missing,
            }
            if missing:
                print(json.dumps(result, indent=2))
                return 4
        elif args.command == "screenshot":
            result = client.screenshot(args.out, inline=args.inline)
        elif args.command == "click":
            result = client.click(args.x, args.y, normalized=args.normalized, button=args.button)
        elif args.command == "hover":
            result = client.hover(args.x, args.y, normalized=args.normalized, persistent=args.persistent)
        elif args.command == "hover-clear":
            result = client.clear_hover()
        elif args.command == "click-scan":
            x_values = [float(v) for v in args.x_values.split(",") if v.strip()]
            result = click_until_scene_transition(
                client=client,
                x_values=x_values,
                y_start=args.y_start,
                y_end=args.y_end,
                y_step=args.y_step,
                button=args.button,
            )
        elif args.command == "start-new-game":
            result = start_new_game(client, args.button, not args.no_fallback_scan)
        elif args.command == "trade-hover-scan":
            x_arg = None if args.toolbar_x < 0 else args.toolbar_x
            y_arg = None if args.toolbar_y < 0 else args.toolbar_y
            result = scan_trade_toolbar_tooltip(
                base_url=base,
                expected_text=args.contains,
                toolbar_x=x_arg,
                toolbar_y=y_arg,
                toolbar_button=args.toolbar_button,
                button=args.button,
            )
            if not result.get("ok", False):
                print(json.dumps(result, indent=2))
                return 5
        elif args.command == "scroll":
            result = client.request_json("/input/scroll", {"delta": args.delta})
        elif args.command == "key":
            result = client.key(args.key, action=args.action, mods=args.mods, scancode=args.scancode)
        elif args.command == "type":
            result = client.request_json("/input/type", {"text": args.text})
        elif args.command == "record":
            if args.record_command == "start":
                result = client.request_json("/record/start", {})
            elif args.record_command == "status":
                result = client.request_json("/record/status")
            else:
                result = client.request_json("/record/stop", {"path": args.path})
        elif args.command == "replay":
            if args.replay_command == "status":
                result = client.request_json("/replay/status")
            elif args.replay_command == "start":
                result = client.request_json("/replay/start", {"file": args.file, "mode": args.mode})
            elif args.replay_command == "pause":
                result = client.request_json("/replay/pause", {})
            elif args.replay_command == "resume":
                result = client.request_json("/replay/resume", {})
            else:
                result = client.request_json("/replay/cancel", {})
        elif args.command == "shutdown":
            result = client.shutdown()
        elif args.command == "validate":
            if args.validate_command == "route-ops":
                exit_code, result = run_validation(base)
                print(json.dumps(result, indent=2))
                return exit_code
            parser.print_help()
            return 1
        elif args.command == "new-scene":
            result = scaffold_new_scene(
                name=args.name,
                scene_id=args.id,
                subfolder=args.subfolder,
                display_name=args.display_name,
                base=args.base,
                camera=args.camera,
                maven_profile=args.maven_profile,
                dry_run=args.dry_run,
            )
        else:
            parser.print_help()
            return 1
    except ValueError as exc:
        print(json.dumps({"ok": False, "error": str(exc)}))
        return 6
    except urllib.error.HTTPError as exc:
        print(json.dumps({"ok": False, "error": f"HTTP {exc.code}"}))
        return 2
    except urllib.error.URLError as exc:
        print(json.dumps({"ok": False, "error": str(exc.reason)}))
        return 3

    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
