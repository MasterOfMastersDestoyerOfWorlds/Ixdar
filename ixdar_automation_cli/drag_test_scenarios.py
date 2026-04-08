"""Drag-based viewport interaction test scenarios for orbit and pan behaviors."""

from __future__ import annotations

import time
from typing import Callable

from ixdar_automation_cli.automation_client import AutomationClient


def orbit_drag_test(
    client: AutomationClient,
    window_width: int = 800,
    window_height: int = 600,
    drag_distance_px: float = 100.0,
    iterations: int = 3,
) -> dict:
    """Test orbit behavior via drag gestures.
    
    Performs a horizontal drag to orbit the camera and validates that
    the orbit state changes as expected.
    
    Args:
        client: AutomationClient instance.
        window_width: Window width in pixels.
        window_height: Window height in pixels.
        drag_distance_px: Distance to drag in pixels.
        iterations: Number of drag iterations.
        
    Returns:
        Dictionary with test results including orbit state changes.
    """
    results = {
        "test": "orbit-drag",
        "iterations": iterations,
        "drag_distance_px": drag_distance_px,
        "samples": [],
    }
    
    # Convert to normalized coordinates (center of screen)
    start_x_norm = 0.5
    start_y_norm = 0.5
    drag_x_norm = drag_distance_px / window_width
    
    for i in range(iterations):
        # Capture initial orbit state
        ui_state = client.ui_state()
        camera_state = ui_state.get("cameraState", {})
        initial_azimuth = camera_state.get("azimuthRadians", 0)
        initial_elevation = camera_state.get("elevationRadians", 0)
        initial_distance = camera_state.get("distance", 0)
        
        # Execute drag
        drag_result = client.drag(
            start_x_norm,
            start_y_norm,
            start_x_norm + drag_x_norm,
            start_y_norm,
            normalized=True,
        )
        
        # Capture final state
        time.sleep(0.1)  # Allow time for camera update
        final_ui_state = client.ui_state()
        final_camera_state = final_ui_state.get("cameraState", {})
        final_azimuth = final_camera_state.get("azimuthRadians", 0)
        final_elevation = final_camera_state.get("elevationRadians", 0)
        final_distance = final_camera_state.get("distance", 0)
        
        # Record sample
        sample = {
            "iteration": i + 1,
            "initial": {
                "azimuth": initial_azimuth,
                "elevation": initial_elevation,
                "distance": initial_distance,
            },
            "final": {
                "azimuth": final_azimuth,
                "elevation": final_elevation,
                "distance": final_distance,
            },
            "delta": {
                "azimuth": final_azimuth - initial_azimuth,
                "elevation": final_elevation - initial_elevation,
                "distance": final_distance - initial_distance,
            },
            "drag_executed": drag_result.get("ok", False),
        }
        results["samples"].append(sample)
    
    # Aggregate results
    total_azimuth_delta = sum(s["delta"]["azimuth"] for s in results["samples"])
    total_elevation_delta = sum(s["delta"]["elevation"] for s in results["samples"])
    all_drag_succeeded = all(s["drag_executed"] for s in results["samples"])
    
    results["summary"] = {
        "total_azimuth_delta": total_azimuth_delta,
        "total_elevation_delta": total_elevation_delta,
        "all_drag_succeeded": all_drag_succeeded,
        "orbit_responded": abs(total_azimuth_delta) > 0.001,
    }
    
    return results


def pan_drag_test(
    client: AutomationClient,
    window_width: int = 800,
    window_height: int = 600,
    drag_distance_px: float = 100.0,
    iterations: int = 3,
) -> dict:
    """Test pan behavior via drag gestures.
    
    Performs drag gestures and validates that camera pan motion
    is recorded (mouse position changes indicate drag activity).
    
    Args:
        client: AutomationClient instance.
        window_width: Window width in pixels.
        window_height: Window height in pixels.
        drag_distance_px: Distance to drag in pixels.
        iterations: Number of drag iterations.
        
    Returns:
        Dictionary with test results including pan motion validation.
    """
    results = {
        "test": "pan-drag",
        "iterations": iterations,
        "drag_distance_px": drag_distance_px,
        "samples": [],
    }
    
    # Convert to normalized coordinates (center of screen)
    start_x_norm = 0.5
    start_y_norm = 0.5
    drag_x_norm = drag_distance_px / window_width
    
    for i in range(iterations):
        # Capture initial mouse position
        ui_state = client.ui_state()
        camera_state = ui_state.get("cameraState", {})
        initial_mouse_x = camera_state.get("mouseX", 0)
        initial_mouse_y = camera_state.get("mouseY", 0)
        
        # Execute drag
        drag_result = client.drag(
            start_x_norm,
            start_y_norm,
            start_x_norm + drag_x_norm,
            start_y_norm,
            normalized=True,
        )
        
        # Capture final mouse position
        time.sleep(0.1)
        final_ui_state = client.ui_state()
        final_camera_state = final_ui_state.get("cameraState", {})
        final_mouse_x = final_camera_state.get("mouseX", 0)
        final_mouse_y = final_camera_state.get("mouseY", 0)
        
        # Record sample
        sample = {
            "iteration": i + 1,
            "initial_mouse": {"x": initial_mouse_x, "y": initial_mouse_y},
            "final_mouse": {"x": final_mouse_x, "y": final_mouse_y},
            "mouse_delta": {"x": final_mouse_x - initial_mouse_x, "y": final_mouse_y - initial_mouse_y},
            "drag_executed": drag_result.get("ok", False),
        }
        results["samples"].append(sample)
    
    # Aggregate results
    all_drag_succeeded = all(s["drag_executed"] for s in results["samples"])
    mouse_moved = any(abs(s["mouse_delta"]["x"]) > 1 or abs(s["mouse_delta"]["y"]) > 1 for s in results["samples"])
    
    results["summary"] = {
        "all_drag_succeeded": all_drag_succeeded,
        "mouse_moved": mouse_moved,
        "pan_responded": mouse_moved,
    }
    
    return results


def orbit_drag_with_validation(
    client: AutomationClient,
    window_width: int = 800,
    window_height: int = 600,
    drag_distance_px: float = 100.0,
    min_azimuth_delta: float = 0.01,
    iterations: int = 1,
) -> dict:
    """Execute drag with explicit orbit validation.
    
    This is the primary validation approach for orbit behavior:
    1. Capture initial orbit state (azimuth, elevation, distance)
    2. Execute drag gesture
    3. Capture final orbit state
    4. Assert that azimuth changed by at least min_azimuth_delta
    
    Args:
        client: AutomationClient instance.
        window_width: Window width in pixels.
        window_height: Window height in pixels.
        drag_distance_px: Distance to drag in pixels.
        min_azimuth_delta: Minimum expected azimuth change in radians.
        iterations: Number of drag iterations.
        
    Returns:
        Dictionary with validation results.
    """
    results = {
        "test": "orbit-drag-with-validation",
        "drag_distance_px": drag_distance_px,
        "min_azimuth_delta": min_azimuth_delta,
        "samples": [],
        "validations": {},
    }
    
    start_x_norm = 0.5
    start_y_norm = 0.5
    drag_x_norm = drag_distance_px / window_width
    
    for i in range(iterations):
        ui_state = client.ui_state()
        camera_state = ui_state.get("cameraState", {})
        
        if camera_state.get("type") != "orbit":
            results["validations"]["orbit_mode_active"] = False
            results["validations"]["camera_state_valid"] = False
            return results
        
        initial_azimuth = camera_state.get("azimuthRadians", 0)
        
        drag_result = client.drag(
            start_x_norm,
            start_y_norm,
            start_x_norm + drag_x_norm,
            start_y_norm,
            normalized=True,
        )
        
        time.sleep(0.1)
        final_ui_state = client.ui_state()
        final_camera_state = final_ui_state.get("cameraState", {})
        final_azimuth = final_camera_state.get("azimuthRadians", 0)
        
        azimuth_delta = final_azimuth - initial_azimuth
        
        sample = {
            "iteration": i + 1,
            "initial_azimuth": initial_azimuth,
            "final_azimuth": final_azimuth,
            "azimuth_delta": azimuth_delta,
            "drag_executed": drag_result.get("ok", False),
            "delta_sufficient": abs(azimuth_delta) >= min_azimuth_delta,
        }
        results["samples"].append(sample)
    
    # Validation checks
    all_drag_succeeded = all(s["drag_executed"] for s in results["samples"])
    all_deltas_sufficient = all(s["delta_sufficient"] for s in results["samples"])
    total_delta = sum(s["azimuth_delta"] for s in results["samples"])
    
    results["validations"] = {
        "orbit_mode_active": True,
        "all_drag_succeeded": all_drag_succeeded,
        "all_deltas_sufficient": all_deltas_sufficient,
        "total_azimuth_delta": total_delta,
        "orbit_responded": abs(total_delta) >= min_azimuth_delta,
    }
    
    results["ok"] = all(results["validations"].values())
    return results


def pan_drag_with_screenshot_validation(
    client: AutomationClient,
    window_width: int = 800,
    window_height: int = 600,
    drag_distance_px: float = 100.0,
    iterations: int = 1,
) -> dict:
    """Execute drag with screenshot-based pan validation.
    
    This approach validates pan by comparing screenshots before and after
    drag, looking for viewport changes. Useful when camera telemetry is
    not available.
    
    Args:
        client: AutomationClient instance.
        window_width: Window width in pixels.
        window_height: Window height in pixels.
        drag_distance_px: Distance to drag in pixels.
        iterations: Number of drag iterations.
        
    Returns:
        Dictionary with screenshot validation results.
    """
    results = {
        "test": "pan-drag-with-screenshot-validation",
        "drag_distance_px": drag_distance_px,
        "samples": [],
        "validations": {},
    }
    
    start_x_norm = 0.5
    start_y_norm = 0.5
    drag_x_norm = drag_distance_px / window_width
    
    # Capture initial screenshot
    initial_screenshot = client.screenshot(inline=True)
    initial_sha = initial_screenshot.get("sha256", "")
    
    for i in range(iterations):
        drag_result = client.drag(
            start_x_norm,
            start_y_norm,
            start_x_norm + drag_x_norm,
            start_y_norm,
            normalized=True,
        )
        
        time.sleep(0.1)
        
        # Capture final screenshot
        final_screenshot = client.screenshot(inline=True)
        final_sha = final_screenshot.get("sha256", "")
        
        sample = {
            "iteration": i + 1,
            "drag_executed": drag_result.get("ok", False),
            "initial_sha": initial_sha,
            "final_sha": final_sha,
            "screenshot_changed": initial_sha != final_sha,
        }
        results["samples"].append(sample)
        
        initial_sha = final_sha
    
    all_drag_succeeded = all(s["drag_executed"] for s in results["samples"])
    any_screenshot_changed = any(s["screenshot_changed"] for s in results["samples"])
    
    results["validations"] = {
        "all_drag_succeeded": all_drag_succeeded,
        "screenshot_changed": any_screenshot_changed,
        "viewport_changed": any_screenshot_changed,
    }
    
    results["ok"] = all(results["validations"].values())
    return results


# Convenience functions for CLI usage

def run_orbit_drag_test(
    base_url: str = "http://127.0.0.1:47832",
    window_width: int = 800,
    window_height: int = 600,
    drag_distance_px: float = 100.0,
    iterations: int = 3,
) -> dict:
    """Run orbit drag test from base URL."""
    client = AutomationClient(base_url)
    return orbit_drag_test(client, window_width, window_height, drag_distance_px, iterations)


def run_pan_drag_test(
    base_url: str = "http://127.0.0.1:47832",
    window_width: int = 800,
    window_height: int = 600,
    drag_distance_px: float = 100.0,
    iterations: int = 3,
) -> dict:
    """Run pan drag test from base URL."""
    client = AutomationClient(base_url)
    return pan_drag_test(client, window_width, window_height, drag_distance_px, iterations)


def run_orbit_drag_validation(
    base_url: str = "http://127.0.0.1:47832",
    window_width: int = 800,
    window_height: int = 600,
    drag_distance_px: float = 100.0,
    min_azimuth_delta: float = 0.01,
    iterations: int = 1,
) -> dict:
    """Run orbit drag test with validation from base URL."""
    client = AutomationClient(base_url)
    return orbit_drag_with_validation(client, window_width, window_height, drag_distance_px, min_azimuth_delta, iterations)
