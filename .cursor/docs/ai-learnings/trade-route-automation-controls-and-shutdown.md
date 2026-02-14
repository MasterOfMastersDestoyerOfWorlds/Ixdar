## Context
TRADE-10 needed reliable automation for route-planning HUD behavior (toolbar hover/click) plus deterministic app lifecycle shutdown to avoid stale windows/ports between runs.

## Decision
Use `mvn -P game` as the canonical desktop launch path for automation runs, expose trade HUD state in `/ui/state`, and add explicit automation endpoints for hover and shutdown:
- `POST /input/hover`
- `POST /input/hover/clear`
- `POST /shutdown`

Keep hover durable via a lock in `TradeMouseTrap` that remains until cleared by automation or replaced by explicit user mouse movement.
Refactor automation scripts to share one client layer (`automation_client.py`) and one scenario layer (`trade_scenarios.py`) so CLI commands and validators do not duplicate request/input logic.
Use `python tools/ixdar-automation-cli/ixdar_cli.py validate route-ops` as the canonical route-ops validation entrypoint.

## Evidence
- `python tools/ixdar-automation-cli/test_cli.py` passes with hover/shutdown/trade-hover-scan coverage.
- `python tools/ixdar-automation-cli/ixdar_cli.py validate route-ops` passes against a live app run.
- `python tools/ixdar-automation-cli/ixdar_cli.py shutdown` now drops `/health` with connection-refused and process exits.

## Reuse trigger
Use this pattern whenever a scene adds HUD controls that need automated validation:
1. Add additive scene telemetry to `/ui/state`.
2. Add durable hover/move primitives to automation input.
3. Add an explicit shutdown endpoint and CLI command for teardown.
4. Prefer one canonical script/command path; remove superseded paths in the same refactor.

## Anti-pattern
Do not rely on transient click-based hover for tooltip assertions; render-loop updates can clear hover state between polling steps.  
Do not launch multiple app instances without teardown; stale automation listeners on `47832` cause false failures.
Do not keep compatibility wrapper files for internal automation tooling when no external consumers require them.
