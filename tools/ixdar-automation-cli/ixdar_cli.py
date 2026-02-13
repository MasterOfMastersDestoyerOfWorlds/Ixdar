#!/usr/bin/env python3
import argparse
import json
import sys
import urllib.error
import urllib.request


DEFAULT_BASE_URL = "http://127.0.0.1:47832"


def request_json(base_url: str, path: str, body: dict | None = None) -> dict:
    payload = None
    headers = {"Content-Type": "application/json"}
    if body is not None:
        payload = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(base_url + path, data=payload, headers=headers, method="POST" if body is not None else "GET")
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


def click_until_scene_transition(
    base_url: str,
    x_values: list[float],
    y_start: int,
    y_end: int,
    y_step: int,
    button: int,
) -> dict:
    initial = request_json(base_url, "/ui/state")
    result = {
        "ok": False,
        "initial": {
            "scene": initial.get("scene"),
            "menuVisible": initial.get("menuVisible", True),
        },
        "attempts": 0,
    }
    if initial.get("scene") != "menu" or not initial.get("menuVisible", True):
        result["ok"] = True
        result["state"] = initial
        result["message"] = "Already transitioned before scan"
        return result

    for x in x_values:
        for y in range(y_start, y_end, y_step):
            request_json(base_url, "/input/click", {"x": x, "y": y, "normalized": False, "button": button})
            result["attempts"] += 1
            state = request_json(base_url, "/ui/state")
            if state.get("scene") != "menu" or not state.get("menuVisible", True):
                result["ok"] = True
                result["found"] = {"x": x, "y": y}
                result["state"] = state
                return result

    result["state"] = request_json(base_url, "/ui/state")
    result["error"] = "No scene transition detected"
    return result


def start_new_game(base_url: str, button: int, fallback_scan: bool) -> dict:
    state = request_json(base_url, "/ui/state")
    if state.get("scene") != "menu" or not state.get("menuVisible", True):
        return {"ok": True, "message": "Already outside menu", "state": state}

    for item in state.get("menuItems", []):
        if item.get("label", "").lower() == "start new game":
            bounds = item.get("bounds")
            if bounds:
                x = bounds.get("centerXPx")
                y = bounds.get("centerYPx")
                if x is not None and y is not None:
                    window_height = state.get("windowHeight", 0)
                    click_y = (window_height - y) if window_height else y
                    click_result = request_json(base_url, "/input/click", {"x": x, "y": click_y, "normalized": False, "button": button})
                    post = request_json(base_url, "/ui/state")
                    return {
                        "ok": post.get("scene") != "menu" or not post.get("menuVisible", True),
                        "strategy": "menu_bounds_center",
                        "target": {"x": x, "y": click_y},
                        "click": click_result,
                        "state": post,
                    }

    if fallback_scan:
        scan = click_until_scene_transition(
            base_url=base_url,
            x_values=[250.0, 300.0, 350.0, 375.0, 400.0, 450.0, 500.0],
            y_start=120,
            y_end=620,
            y_step=20,
            button=button,
        )
        scan["strategy"] = "fallback_click_scan"
        return scan

    return {"ok": False, "error": "Start New Game bounds not available"}


def probe(base_url: str, screenshot_out: str) -> dict:
    health = request_json(base_url, "/health")
    state = request_json(base_url, "/ui/state")
    screenshot = request_json(base_url, "/ui/screenshot", {"path": screenshot_out, "inline": False})
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


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(prog="ixdar")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("health")
    subparsers.add_parser("ui-state")
    probe_cmd = subparsers.add_parser("probe")
    probe_cmd.add_argument("--out", default="")

    screenshot = subparsers.add_parser("screenshot")
    screenshot.add_argument("--out", default="")
    screenshot.add_argument("--inline", action="store_true")

    click = subparsers.add_parser("click")
    click.add_argument("--x", type=float, required=True)
    click.add_argument("--y", type=float, required=True)
    click.add_argument("--normalized", action="store_true")
    click.add_argument("--button", type=int, default=0)

    click_scan = subparsers.add_parser("click-scan")
    click_scan.add_argument("--x-values", default="250,300,350,375,400,450,500")
    click_scan.add_argument("--y-start", type=int, default=120)
    click_scan.add_argument("--y-end", type=int, default=620)
    click_scan.add_argument("--y-step", type=int, default=20)
    click_scan.add_argument("--button", type=int, default=0)

    start_game = subparsers.add_parser("start-new-game")
    start_game.add_argument("--button", type=int, default=0)
    start_game.add_argument("--no-fallback-scan", action="store_true")

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

    args = parser.parse_args(argv)
    base = args.base_url

    try:
        if args.command == "health":
            result = request_json(base, "/health")
        elif args.command == "ui-state":
            result = request_json(base, "/ui/state")
        elif args.command == "probe":
            result = probe(base, args.out)
        elif args.command == "screenshot":
            result = request_json(base, "/ui/screenshot", {"path": args.out, "inline": args.inline})
        elif args.command == "click":
            result = request_json(
                base,
                "/input/click",
                {"x": args.x, "y": args.y, "normalized": args.normalized, "button": args.button},
            )
        elif args.command == "click-scan":
            x_values = [float(v) for v in args.x_values.split(",") if v.strip()]
            result = click_until_scene_transition(
                base_url=base,
                x_values=x_values,
                y_start=args.y_start,
                y_end=args.y_end,
                y_step=args.y_step,
                button=args.button,
            )
        elif args.command == "start-new-game":
            result = start_new_game(base, args.button, not args.no_fallback_scan)
        elif args.command == "scroll":
            result = request_json(base, "/input/scroll", {"delta": args.delta})
        elif args.command == "key":
            result = request_json(
                base,
                "/input/key",
                {"key": args.key, "action": args.action, "mods": args.mods, "scancode": args.scancode},
            )
        elif args.command == "type":
            result = request_json(base, "/input/type", {"text": args.text})
        elif args.command == "record":
            if args.record_command == "start":
                result = request_json(base, "/record/start", {})
            elif args.record_command == "status":
                result = request_json(base, "/record/status")
            else:
                result = request_json(base, "/record/stop", {"path": args.path})
        elif args.command == "replay":
            if args.replay_command == "status":
                result = request_json(base, "/replay/status")
            elif args.replay_command == "start":
                result = request_json(base, "/replay/start", {"file": args.file, "mode": args.mode})
            elif args.replay_command == "pause":
                result = request_json(base, "/replay/pause", {})
            elif args.replay_command == "resume":
                result = request_json(base, "/replay/resume", {})
            else:
                result = request_json(base, "/replay/cancel", {})
        else:
            parser.print_help()
            return 1
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
