"""Skeleton sensitivity analysis: compute Jacobian of joint positions w.r.t. DSL parameters.

Can run in two modes:
  - Sensitivity: one-shot Jacobian computation, outputs parameter sensitivity matrix
  - Optimize: iterative Gauss-Newton loop using the Jacobian to minimize joint errors

Both modes can run either via the automation server (requires viewer running) or
headlessly via Maven BatchDslEvaluator (no viewer needed).

Usage:
    # Via automation server (viewer must be running):
    uv run python ixdar_automation_cli/skeleton_sensitivity.py hand_v12_posed --ref ~/Blends/Hand/Hand.obj

    # Headless via Maven:
    uv run python ixdar_automation_cli/skeleton_sensitivity.py hand_v12_posed --ref ~/Blends/Hand/Hand.obj --headless

    # Optimization mode:
    uv run python ixdar_automation_cli/skeleton_sensitivity.py hand_v12_posed --ref ~/Blends/Hand/Hand.obj --optimize --max-iters 5
"""

import argparse
import json
import os
import shutil
import subprocess
import sys

IXDAR_ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
DSL_DIR = os.path.join(IXDAR_ROOT, "ixdar-app", "src", "main", "resources", "dsl")
MVN = shutil.which("mvn") or "/opt/homebrew/bin/mvn"
DEFAULT_REF = os.path.expanduser("~/Blends/Hand/Hand.obj")

sys.path.insert(0, os.path.dirname(__file__))
from automation_client import AutomationClient


def run_headless(dsl_path: str, ref_path: str, mode: str,
                 resolution: int = 128, epsilon: float = 0,
                 max_iters: int = 10, target_score: float = 95):
    """Run sensitivity analysis or optimization headlessly via Maven BatchDslEvaluator."""
    if mode == "optimize":
        cmd_args = [
            MVN, "compile", "exec:exec", "-P", "batch-dsl",
            f"-Dbatch.args={dsl_path} --skeleton-optimize {ref_path} "
            f"--resolution {resolution} --max-iters {max_iters} --target-score {target_score}",
            "-pl", "ixdar-app", "-q",
        ]
    else:
        eps_arg = f" --epsilon {epsilon}" if epsilon > 0 else ""
        cmd_args = [
            MVN, "compile", "exec:exec", "-P", "batch-dsl",
            f"-Dbatch.args={dsl_path} --skeleton-sensitivity {ref_path} "
            f"--resolution {resolution}{eps_arg}",
            "-pl", "ixdar-app", "-q",
        ]

    print(f"Running headless {mode}...", file=sys.stderr)
    result = subprocess.run(cmd_args, cwd=IXDAR_ROOT, capture_output=True, text=True, timeout=600)
    if result.returncode != 0:
        print(f"Maven failed (exit {result.returncode}):", file=sys.stderr)
        print(result.stderr[-1000:], file=sys.stderr)
        sys.exit(1)

    # Parse JSON from stdout
    try:
        data = json.loads(result.stdout)
        return data
    except json.JSONDecodeError:
        print("Failed to parse Maven output as JSON:", file=sys.stderr)
        print(result.stdout[-500:], file=sys.stderr)
        sys.exit(1)


def run_via_server(dsl_name: str, ref_path: str, resolution: int = 128, epsilon: float = 0):
    """Run sensitivity analysis via the automation server."""
    client = AutomationClient()
    try:
        client.health()
    except Exception:
        print("Automation server not running on localhost:47832", file=sys.stderr)
        print("Start it with: cd ~/Code/Ixdar && mvn -pl ixdar-app -P mesh-viewer exec:exec", file=sys.stderr)
        print("Or use --headless for Maven-based execution.", file=sys.stderr)
        sys.exit(1)

    print(f"Computing sensitivity via automation server...", file=sys.stderr)
    return client.skeleton_sensitivity(dsl_name, ref_path, resolution, epsilon)


def format_sensitivity(data: dict):
    """Pretty-print sensitivity analysis results."""
    if "error" in data and not data.get("ok", True):
        print(f"ERROR: {data['error']}")
        return

    baseline = data.get("baselineScore", 0)
    projected = data.get("projectedScore", 0)
    improvement = projected - baseline

    print(f"Skeleton Sensitivity Analysis")
    print(f"{'=' * 55}")
    print(f"Parameters: {data.get('parameterCount', '?')}")
    print(f"Joints tracked: {data.get('jointCount', '?')}")
    print(f"Baseline Score: {baseline:.1f}%")
    print(f"Projected Score: {projected:.1f}% ({improvement:+.1f}%)")
    print()

    # Parameter table
    params = data.get("parameters", [])
    suggested = data.get("suggestedDeltas", data.get("suggestedValues", {}))

    if params:
        print("Parameter Sensitivity (sorted by impact):")
        print(f"  {'Parameter':<25} {'Sensitivity':>12} {'Delta':>10} {'New Value':>12}")
        print(f"  {'-' * 62}")
        # Sort by total sensitivity descending
        sorted_params = sorted(params, key=lambda p: p.get("totalSensitivity", 0), reverse=True)
        for p in sorted_params:
            pid = p["id"]
            sens = p.get("totalSensitivity", 0)
            delta = p.get("suggestedDelta", suggested.get(pid, 0))
            default = p.get("default", 0)
            new_val = default + delta if isinstance(default, (int, float)) and isinstance(delta, (int, float)) else "?"
            if abs(delta) > 1e-6 or sens > 0.01:
                print(f"  {pid:<25} {sens:>12.4f} {delta:>+10.4f} {new_val:>12.4f}")
    elif suggested:
        print("Suggested Parameter Changes:")
        for pid, val in suggested.items():
            print(f"  {pid}: {val}")

    # Branch errors
    branch_errors = data.get("branchErrors", [])
    if branch_errors:
        print()
        print("Per-Branch Joint Errors:")
        for be in branch_errors:
            print(f"  {be['branch']:<14} avg error: {be['avgError']:.4f} ({be['joints']} joints)")

    unstable = data.get("unstableParams", [])
    if unstable:
        print(f"\nTopology-unstable parameters ({len(unstable)}):")
        for p in unstable:
            print(f"  - {p}")


def format_optimization(data: dict):
    """Pretty-print optimization results."""
    print(f"Skeleton Optimization Results")
    print(f"{'=' * 55}")
    print(f"Initial Score: {data.get('initialScore', 0):.1f}%")
    print(f"Final Score:   {data.get('finalScore', 0):.1f}%")
    print(f"Improvement:   {data.get('improvement', 0):+.1f}%")
    print(f"Iterations:    {data.get('iterations', 0)}")
    print()

    steps = data.get("steps", [])
    if steps:
        print("Optimization Trajectory:")
        for s in steps:
            imp = f" (+{s['improvement']:.2f})" if s.get("improvement", 0) > 0 else ""
            print(f"  Iter {s['iteration']:>2}: score={s['score']:.2f}%{imp}")

    final = data.get("finalParams", {})
    if final:
        print()
        print("Final Parameter Values:")
        for pid, val in final.items():
            print(f"  {pid}: {val:.6f}")


def main():
    parser = argparse.ArgumentParser(description="Skeleton sensitivity analysis for DSL parameter optimization")
    parser.add_argument("dsl", help="DSL filename (e.g. hand_v12_posed)")
    parser.add_argument("--ref", default=DEFAULT_REF, help=f"Reference OBJ path (default: {DEFAULT_REF})")
    parser.add_argument("--resolution", type=int, default=128, help="TEASAR voxel resolution (default: 128)")
    parser.add_argument("--epsilon", type=float, default=0, help="Relative perturbation size (default: auto)")
    parser.add_argument("--optimize", action="store_true", help="Run iterative optimization instead of one-shot analysis")
    parser.add_argument("--max-iters", type=int, default=10, help="Max optimization iterations (default: 10)")
    parser.add_argument("--target-score", type=float, default=95, help="Target skeleton score (default: 95)")
    parser.add_argument("--headless", action="store_true", help="Run via Maven instead of automation server")
    parser.add_argument("--json", action="store_true", help="Output raw JSON instead of formatted text")
    args = parser.parse_args()

    ref_path = os.path.abspath(os.path.expanduser(args.ref))
    if not os.path.exists(ref_path):
        print(f"Reference not found: {ref_path}", file=sys.stderr)
        sys.exit(1)

    # Resolve DSL path for headless mode
    dsl_name = args.dsl
    if not dsl_name.endswith(".dsl"):
        dsl_name += ".dsl"
    dsl_path = os.path.join(DSL_DIR, dsl_name)
    if not os.path.exists(dsl_path):
        print(f"DSL file not found: {dsl_path}", file=sys.stderr)
        sys.exit(1)

    if args.headless:
        mode = "optimize" if args.optimize else "sensitivity"
        data = run_headless(dsl_path, ref_path, mode,
                            args.resolution, args.epsilon,
                            args.max_iters, args.target_score)
    elif args.optimize:
        print("Optimization mode requires --headless (runs via Maven BatchDslEvaluator)", file=sys.stderr)
        sys.exit(1)
    else:
        data = run_via_server(args.dsl, ref_path, args.resolution, args.epsilon)

    if args.json:
        print(json.dumps(data, indent=2))
    elif args.optimize:
        format_optimization(data)
    else:
        format_sensitivity(data)


if __name__ == "__main__":
    main()
