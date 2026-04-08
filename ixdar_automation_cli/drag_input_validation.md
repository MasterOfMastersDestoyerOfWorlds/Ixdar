# Drag-Based Input Validation for Orbit and Pan Behaviors

## Overview

This document describes the design and implementation of drag-based input validation for viewport interactions in Ixdar. The goal is to enable intentional testing of camera orbit and pan behaviors through automation, without requiring manual mouse use.

## Implementation Summary

### API Endpoints

Added `/input/drag` endpoint to the Automation API:

- **Path**: `POST /input/drag`
- **Parameters**:
  - `startX`: Starting X coordinate (pixel or normalized)
  - `startY`: Starting Y coordinate (pixel or normalized)
  - `endX`: Ending X coordinate (pixel or normalized)
  - `endY`: Ending Y coordinate (pixel or normalized)
  - `normalized`: Whether coordinates are normalized (0..1) or pixel values

### CLI Commands

1. **`drag`**: Execute a drag gesture
   ```bash
   ixdar drag --start-x 0.5 --start-y 0.5 --end-x 0.6 --end-y 0.5 --normalized
   ```

2. **`drag-validate`**: Execute drag and validate camera motion
   ```bash
   ixdar drag-validate --start-x 0.5 --start-y 0.5 --end-x 0.6 --end-y 0.5 --expected-delta-x-min 10 --normalized
   ```

### Camera State Telemetry

Added `cameraState` to `/ui/state` response for orbit tracking:

```json
{
  "cameraState": {
    "ok": true,
    "type": "orbit",
    "azimuthRadians": 1.57,
    "elevationRadians": 0.35,
    "distance": 3.5,
    "mouseX": 400,
    "mouseY": 300
  }
}
```

## Validation Approaches

### 1. Camera Telemetry-Based Validation (Recommended)

**Approach**: Capture orbit state (azimuth, elevation, distance) before and after drag.

**Pros**:
- Direct measurement of camera parameters
- Precise delta calculations
- Works without screenshots
- Fast and reliable

**Cons**:
- Requires camera state exposure (OrbitMouseTrap getters)
- Only works for orbit-capable scenes

**Usage**:
```python
from ixdar_automation_cli.drag_test_scenarios import run_orbit_drag_validation

result = run_orbit_drag_validation(
    drag_distance_px=100.0,
    min_azimuth_delta=0.01,
    iterations=3
)

assert result["ok"], f"Orbit validation failed: {result['validations']}"
assert result["validations"]["orbit_responded"], "Camera did not respond to drag"
```

### 2. Mouse Position Tracking

**Approach**: Track mouse position changes to verify drag was processed.

**Pros**:
- Works for any scene with mouse tracking
- Simple implementation
- Good for verifying drag execution

**Cons**:
- Doesn't directly measure camera motion
- Mouse position ≠ camera state

**Usage**:
```python
from ixdar_automation_cli.drag_test_scenarios import run_pan_drag_test

result = run_pan_drag_test(
    drag_distance_px=100.0,
    iterations=3
)

assert result["ok"], f"Pan validation failed: {result['validations']}"
```

### 3. Screenshot Delta Validation

**Approach**: Compare screenshots before and after drag to detect viewport changes.

**Pros**:
- Works without camera state exposure
- Visual validation
- Scene-agnostic

**Cons**:
- Slower (requires rendering)
- Less precise
- May miss subtle changes
- Requires image comparison logic

**Usage**:
```python
from ixdar_automation_cli.drag_test_scenarios import run_pan_drag_with_screenshot_validation

result = run_pan_drag_with_screenshot_validation(
    drag_distance_px=100.0,
    iterations=1
)

assert result["ok"], f"Screenshot validation failed: {result['validations']}"
```

## Test Scenarios

### Orbit Behavior Test

Tests that dragging horizontally orbits the camera:

```python
from ixdar_automation_cli.drag_test_scenarios import run_orbit_drag_test

result = run_orbit_drag_test(
    window_width=800,
    window_height=600,
    drag_distance_px=100.0,
    iterations=3
)

# Check that orbit responded
assert result["summary"]["orbit_responded"], "Camera did not orbit"
assert result["summary"]["all_drag_succeeded"], "Some drags failed"
```

### Pan Behavior Test

Tests that dragging causes camera pan motion:

```python
from ixdar_automation_cli.drag_test_scenarios import run_pan_drag_test

result = run_pan_drag_test(
    window_width=800,
    window_height=600,
    drag_distance_px=100.0,
    iterations=3
)

# Check that pan responded
assert result["summary"]["pan_responded"], "Camera did not pan"
```

## File Structure

```
ixdar_automation_cli/
├── automation_client.py          # Added drag() method
├── cli_commands/
│   └── flat_commands.py          # Added drag, drag-validate commands
├── drag_test_scenarios.py        # New: Test scenario library
└── drag_input_validation.md      # New: This documentation
```

## Integration Points

### Java Backend

1. **AutomationApiServer.java**: Added `/input/drag` endpoint
2. **AutomationRuntime.java**: Added `injectDrag()` and `getCameraState()` methods
3. **OrbitMouseTrap.java**: Added getters for orbit state (`getAzimuth()`, `getElevation()`, `getDistance()`)

### Python Frontend

1. **automation_client.py**: Added `drag()` method
2. **flat_commands.py**: Added `drag` and `drag-validate` CLI commands
3. **drag_test_scenarios.py**: Test scenario library

## Unknowns and Trade-offs

### Unknowns

1. **Camera State Granularity**: Current implementation exposes basic orbit state. More granular state (view matrix, projection changes) could provide richer validation but requires more invasive changes.

2. **Gesture Semantics**: The current implementation treats all drags uniformly. Different gestures (swipe, flick, long-press) may need different handling and validation approaches.

3. **Multi-Pointer Support**: Future gestures may involve multiple pointers (pinch, rotate). Current implementation is single-pointer only.

4. **Timing Sensitivity**: Drag timing (duration, velocity) may affect camera response. Current implementation doesn't account for timing-based validation.

5. **Scene-Specific Behavior**: Different scenes may have different drag behaviors (orbit vs. pan vs. rotate). Validation logic needs to be scene-aware.

### Trade-offs

1. **Telemetry vs. Screenshot**: 
   - Telemetry is faster and more precise but requires camera state exposure
   - Screenshots are scene-agnostic but slower and less precise
   - **Recommendation**: Use telemetry as primary, screenshots as fallback

2. **Normalized vs. Pixel Coordinates**:
   - Normalized coordinates are portable but less intuitive
   - Pixel coordinates are intuitive but window-dependent
   - **Recommendation**: Support both with clear documentation

3. **Atomic vs. Stepwise Drag**:
   - Atomic drag (start→end in one call) is simpler
   - Stepwise drag (intermediate points) allows velocity/trajectory validation
   - **Recommendation**: Start with atomic, extend to stepwise if needed

4. **Blocking vs. Non-Blocking**:
   - Current implementation is blocking (waits for drag to complete)
   - Non-blocking would allow concurrent operations but adds complexity
   - **Recommendation**: Keep blocking for simplicity

5. **Record vs. Replay**:
   - Drags can be recorded in automation sessions
   - Replay of drags requires coordinate transformation
   - **Recommendation**: Ensure drag events are recorded and can be replayed

### Future Work

1. **Gesture Library**: Add support for common gestures (swipe, flick, pinch)
2. **Velocity Validation**: Add timing-based validation for gesture velocity
3. **Scene-Aware Validation**: Add scene-specific validation logic
4. **Multi-Pointer Gestures**: Extend to support multi-pointer interactions
5. **Trajectory Analysis**: Validate drag paths for complex gestures
6. **Performance Metrics**: Add timing and frame-rate metrics for drag operations

## Example Usage

### Basic Orbit Test

```bash
# Start Ixdar with mesh viewer scene
ixdar --scene mesh-viewer

# In another terminal, run orbit test
ixdar drag --start-x 0.5 --start-y 0.5 --end-x 0.6 --end-y 0.5 --normalized
```

### Validation Test

```bash
ixdar drag-validate \
  --start-x 0.5 \
  --start-y 0.5 \
  --end-x 0.6 \
  --end-y 0.5 \
  --expected-delta-x-min 10 \
  --normalized
```

### Python Test Script

```python
from ixdar_automation_cli.automation_client import AutomationClient
from ixdar_automation_cli.drag_test_scenarios import run_orbit_drag_validation

client = AutomationClient("http://127.0.0.1:47832")
result = run_orbit_drag_validation(client, drag_distance_px=100.0, min_azimuth_delta=0.01)

if result["ok"]:
    print("Orbit test passed!")
else:
    print(f"Orbit test failed: {result['validations']}")
```

## Related Files

- `ixdar-app/src/main/java/ixdar/platform/automation/AutomationApiServer.java`
- `ixdar-app/src/main/java/ixdar/platform/automation/AutomationRuntime.java`
- `ixdar-app/src/main/java/ixdar/platform/input/OrbitMouseTrap.java`
- `tools/ixdar-automation-cli/automation_client.py`
- `tools/ixdar-automation-cli/cli_commands/flat_commands.py`
- `tools/ixdar-automation-cli/drag_test_scenarios.py`
