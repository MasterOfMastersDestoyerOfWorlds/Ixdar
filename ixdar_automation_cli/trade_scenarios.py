from .automation_client import AutomationClient, opengl_to_click_y


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def start_new_game(client: AutomationClient, button: int, fallback_scan: bool) -> dict:
    state = client.call("ui-state")
    if state.get("mode") != "menu" or not state.get("menuVisible", True):
        return {"ok": True, "message": "Already outside menu", "state": state}

    for item in state.get("menuItems", []):
        if item.get("label", "").lower() == "start new game":
            bounds = item.get("bounds")
            if bounds:
                x = bounds.get("centerXPx")
                y = bounds.get("centerYPx")
                if x is not None and y is not None:
                    window_height = state.get("windowHeight", 0)
                    click_y = opengl_to_click_y(window_height, y) if window_height else y
                    click_result = client.call("click", x=x, y=click_y, normalized=False, button=button)
                    post = client.call("ui-state")
                    return {
                        "ok": post.get("mode") != "menu" or not post.get("menuVisible", True),
                        "strategy": "menu_bounds_center",
                        "target": {"x": x, "y": click_y},
                        "click": click_result,
                        "state": post,
                    }

    if fallback_scan:
        scan = click_until_scene_transition(
            client=client,
            x_values=[250.0, 300.0, 350.0, 375.0, 400.0, 450.0, 500.0],
            y_start=120,
            y_end=620,
            y_step=20,
            button=button,
        )
        scan["strategy"] = "fallback_click_scan"
        return scan

    return {"ok": False, "error": "Start New Game bounds not available"}


def click_until_scene_transition(
    client: AutomationClient,
    x_values: list[float],
    y_start: int,
    y_end: int,
    y_step: int,
    button: int,
) -> dict:
    initial = client.call("ui-state")
    result = {
        "ok": False,
        "initial": {
            "mode": initial.get("mode"),
            "menuVisible": initial.get("menuVisible", True),
        },
        "attempts": 0,
    }
    if initial.get("mode") != "menu" or not initial.get("menuVisible", True):
        result["ok"] = True
        result["state"] = initial
        result["message"] = "Already transitioned before scan"
        return result

    for x in x_values:
        for y in range(y_start, y_end, y_step):
            client.call("click", x=x, y=y, normalized=False, button=button)
            result["attempts"] += 1
            state = client.call("ui-state")
            if state.get("mode") != "menu" or not state.get("menuVisible", True):
                result["ok"] = True
                result["found"] = {"x": x, "y": y}
                result["state"] = state
                return result

    result["state"] = client.call("ui-state")
    result["error"] = "No scene transition detected"
    return result


def ensure_trade_scene(client: AutomationClient, button: int = 0, fallback_scan: bool = True) -> dict:
    state = client.call("ui-state")
    if state.get("mode") == "trade":
        return {"ok": True, "strategy": "already_trade", "state": state}
    return start_new_game(client, button=button, fallback_scan=fallback_scan)


def place_headquarters(client: AutomationClient, city_name: str | None = None) -> dict:
    state = client.call("ui-state")
    trade = state.get("trade", {})
    cities = [place for place in trade.get("places", []) if place.get("market")]
    require(len(cities) > 0, "No cities available for headquarters placement")

    target = cities[0]
    if city_name:
        for city in cities:
            if city.get("name") == city_name:
                target = city
                break

    click_y = opengl_to_click_y(float(state.get("windowHeight", 0)), float(target["yPx"]))
    client.call("click", x=float(target["xPx"]), y=click_y, normalized=False, button=0)
    post = client.call("ui-state")
    return {
        "ok": post.get("trade", {}).get("activeTool") == "Route Planning",
        "city": target,
        "state": post,
    }


def create_initial_pipe(client: AutomationClient, city_a: dict, city_b: dict, window_height: float) -> dict:
    click_y_a = opengl_to_click_y(window_height, float(city_a["yPx"]))
    click_y_b = opengl_to_click_y(window_height, float(city_b["yPx"]))
    client.call("click", x=float(city_a["xPx"]), y=click_y_a, normalized=False, button=0)
    client.call("click", x=float(city_b["xPx"]), y=click_y_b, normalized=False, button=0)
    client.call("key", key="ENTER")
    post = client.call("ui-state")
    return {
        "ok": bool(post.get("trade", {}).get("hasRoute", False)),
        "state": post,
    }
