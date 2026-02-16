#!/usr/bin/env python3
import argparse
import json
import time
import urllib.error

from automation_client import DEFAULT_BASE_URL, KEY_G, AutomationClient, collect_trade_tooltip_lines, toolbar_button_center
from trade_scenarios import create_initial_pipe, ensure_trade_scene, place_headquarters, require


def run_validation(base_url: str) -> tuple[int, dict]:
    client = AutomationClient(base_url)
    report: dict = {"steps": []}
    try:
        health = client.health()
        require(health.get("status") == "ok", "Automation health check failed")
        report["steps"].append({"health": "ok"})

        enter_trade = ensure_trade_scene(client, button=0, fallback_scan=True)
        require(enter_trade.get("ok", False), "Could not enter trade scene")
        if enter_trade.get("strategy") != "already_trade":
            time.sleep(0.2)

        state = client.ui_state()
        require(state.get("mode") == "trade", "Did not enter trade scene")
        trade = state.get("trade", {})
        cities = trade.get("cities", [])
        require(len(cities) >= 3, "Need at least 3 cities for route operations validation")
        report["steps"].append({"enter_trade": "ok", "cities": len(cities)})

        # Place HQ on first city.
        hq_result = place_headquarters(client, city_name=None)
        require(hq_result.get("ok", False), "Expected Route Planning after HQ placement")
        state = hq_result.get("state", client.ui_state())
        trade = state.get("trade", {})
        hq_city = hq_result["city"]
        report["steps"].append({"headquarters": trade.get("headquartersCity")})

        # Hover pipe button and assert tooltip visible.
        window_width = float(state.get("windowWidth", 0))
        window_height = float(state.get("windowHeight", 0))
        pipe_x, pipe_y = toolbar_button_center(window_width, window_height, "pipe")
        client.hover(pipe_x, pipe_y, normalized=False, persistent=True)
        time.sleep(0.1)
        state = client.ui_state()
        tooltip_lines = collect_trade_tooltip_lines(state)
        require(any("Pipe (P)" in line for line in tooltip_lines), "Pipe toolbar tooltip missing")
        report["steps"].append({"pipe_tooltip": tooltip_lines})

        # Create initial route (HQ -> second city -> Enter confirm).
        second_city = cities[1]
        create_pipe_result = create_initial_pipe(client, hq_city, second_city, window_height)
        require(create_pipe_result.get("ok", False), "Expected route after initial pipe")
        time.sleep(0.15)
        state = client.ui_state()
        trade = state.get("trade", {})
        require(trade.get("routeSegmentCount", 0) >= 2, "Expected at least 2 route segments after initial pipe")
        report["steps"].append({"initial_pipe_segments": trade.get("routeSegmentCount", 0)})

        # Grow route with a third city (G mode -> route city -> singleton city -> Enter).
        third_city = cities[2]
        client.key(KEY_G)
        create_initial_pipe(client, hq_city, third_city, window_height)
        time.sleep(0.15)
        state = client.ui_state()
        trade = state.get("trade", {})
        require(trade.get("routeSegmentCount", 0) >= 3, "Expected grow to increase route segment count")
        require(trade.get("canUndo", False), "Expected undo availability after operations")
        report["steps"].append({"grow_segments": trade.get("routeSegmentCount", 0), "canUndo": trade.get("canUndo", False)})
        return 0, {"ok": True, "report": report}
    except (AssertionError, urllib.error.URLError, urllib.error.HTTPError, KeyError, TypeError, ValueError) as exc:
        return 2, {"ok": False, "error": str(exc), "report": report}


def main() -> int:
    parser = argparse.ArgumentParser(prog="trade-route-ops-validation")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    args = parser.parse_args()
    exit_code, payload = run_validation(args.base_url)
    print(json.dumps(payload, indent=2))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
