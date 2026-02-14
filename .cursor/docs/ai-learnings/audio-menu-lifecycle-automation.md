## Context
Added initial desktop audio playback for menu loop music and one-shot menu SFX, plus automation-visible audio state for regression checks.

## Decision
Use LWJGL OpenAL for desktop runtime and keep API calls no-op-safe when audio is unavailable. Menu lifecycle hooks live in scene/menu transition points (`Canvas3D.activate`, `TradeScene.startNewGame`, `TradeScene.returnToMenu`) to avoid scattered state changes.
Resource packaging contract: ingest audio from `IXDAR_ASSET_REPO_ROOT` during Maven build into jar classpath (`res/audio/...`), then load classpath-first at runtime.

## Evidence
- `mvn -pl ixdar-app -am -DskipTests test-compile` passes after adding `lwjgl-openal`.
- `python tools/ixdar-automation-cli/test_cli.py` passes with new `audio-state` command.
- `/ui/state` now includes:
  - `audio.available`
  - `audio.menuMusicPlaying`
  - `audio.menuMusicSourceCount`
  - `audio.lastSfxPlayed`
  - `audio.sfxPlayCountById`
- Closing the app window can leave port `47832` bound unless automation server shutdown is explicit.
- Fixed by calling `AutomationRuntime.stop()` in `Canvas3D.shutdown()` and adding `AutomationApiServer.stop()` to close server + executor.

## Reuse trigger
When adding any new scene music/SFX behavior, expose minimal telemetry in `AutomationRuntime.uiState()` first so CLI automation can assert behavior without human listening.
Also use explicit shutdown hooks for background services (HTTP servers, executors) so window close exits process cleanly.

## Anti-pattern
Do not trigger menu music directly from render-loop draw paths each frame; use activation/scene-transition hooks and idempotent playback guards to prevent overlapping loops.
Do not start automation/background servers without a matching shutdown path.
