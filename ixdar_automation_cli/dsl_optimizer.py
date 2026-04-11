"""Batch parameter optimizer for mesh DSL files.

Discovers tunable parameters (input_float/input_int nodes with min/max),
generates random samples, evaluates them via the Java BatchDslEvaluator
(single JVM with inline KD-tree mesh comparison), and ranks results.

Usage:
    uv run dsl-optimize --dsl hand.dsl --ref ~/Blends/Hand/Hand.obj --samples 100
    uv run dsl-optimize --dsl hand.dsl --ref Hand.obj --samples 200 --rounds 3
"""

import argparse
import json
import os
import random
import shutil
import subprocess
import sys
import tempfile
import time

IXDAR_ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
MVN = shutil.which("mvn") or "/opt/homebrew/bin/mvn"


def _run_maven_batch(dsl_path: str, arg1: str, arg2: str = "unused", arg3: str = "unused") -> dict:
    """Run BatchDslEvaluator via Maven. Returns parsed JSON output.

    Discover mode: arg1="--discover", arg2/arg3 ignored.
    Batch mode: arg1=output_dir, arg2=params_json_path, arg3=ref_obj_path.
    """
    cmd = [
        MVN, "compile", "exec:exec", "-P", "batch-dsl",
        f"-Ddsl.file={dsl_path}",
        f"-Dbatch.arg1={arg1}",
        f"-Dbatch.arg2={arg2}",
        f"-Dbatch.arg3={arg3}",
        "-pl", "ixdar-app", "-q",
    ]
    result = subprocess.run(cmd, cwd=IXDAR_ROOT, capture_output=True, text=True, timeout=600)
    stdout = result.stdout.strip()
    if not stdout:
        raise RuntimeError(f"BatchDslEvaluator produced no output. stderr: {result.stderr[-500:]}")
    # Maven may emit compilation messages before JSON — find the first '{'
    idx = stdout.find("{")
    if idx < 0:
        raise RuntimeError(f"No JSON in output: {stdout[:200]}")
    return json.loads(stdout[idx:])


def _discover(dsl_path: str) -> list[dict]:
    """Get parameter space from DSL file."""
    result = _run_maven_batch(dsl_path, "--discover")
    return result.get("parameters", [])


def _generate_samples(params: list[dict], n: int, seed: int = 42) -> list[dict]:
    """Generate n random parameter sets within bounds."""
    rng = random.Random(seed)
    samples = []
    for _ in range(n):
        sample = {}
        for p in params:
            pid = p["id"]
            lo = p.get("min")
            hi = p.get("max")
            default = p.get("default", 0)
            if lo is None:
                lo = default * 0.5 if default > 0 else default - 1.0
            if hi is None:
                hi = default * 2.0 if default > 0 else default + 1.0
            if p["type"] == "input_int":
                sample[pid] = rng.randint(int(lo), int(hi))
            else:
                sample[pid] = round(rng.uniform(float(lo), float(hi)), 4)
        samples.append(sample)
    return samples


def optimize(dsl_path: str, ref_path: str, samples: int = 100, rounds: int = 1, seed: int = 42):
    """Run optimization loop. Returns list of ranked results."""
    dsl_path = os.path.abspath(os.path.expanduser(dsl_path))
    ref_path = os.path.abspath(os.path.expanduser(ref_path))

    # Discover parameter space
    params = _discover(dsl_path)
    if not params:
        print("No tunable parameters found (no input_float/input_int with min/max).")
        return []

    print(f"Parameter Space ({len(params)} parameters):")
    for p in params:
        lo = p.get("min", "?")
        hi = p.get("max", "?")
        default = p.get("default", "?")
        print(f"  {p['id']:<20} [{lo}, {hi}]  default={default}")
    print()

    all_results = []
    current_seed = seed

    for rnd in range(rounds):
        if rounds > 1:
            print(f"--- Round {rnd + 1}/{rounds} ---")

        # If not first round, narrow bounds around best result
        if rnd > 0 and all_results:
            best = all_results[0]
            best_params = best["params"]
            for p in params:
                pid = p["id"]
                if pid in best_params:
                    bv = best_params[pid]
                    lo = p.get("min", bv - 1)
                    hi = p.get("max", bv + 1)
                    rng = (hi - lo) * 0.3  # Narrow to 30% of original range around best
                    p["min"] = max(lo, bv - rng)
                    p["max"] = min(hi, bv + rng)

        # Generate samples
        sample_list = _generate_samples(params, samples, seed=current_seed)
        current_seed += 1

        # Write samples JSON
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as f:
            json.dump({"samples": sample_list}, f)
            params_json_path = f.name

        # Create temp output directory
        output_dir = tempfile.mkdtemp(prefix="dsl_opt_")

        try:
            # Batch evaluate + compare (all in Java, single JVM)
            t0 = time.time()
            batch_result = _run_maven_batch(dsl_path, output_dir, params_json_path, ref_path)
            total_time = time.time() - t0

            eval_results = batch_result.get("results", [])
            ok_count = sum(1 for r in eval_results if r.get("ok"))
            compared_count = sum(1 for r in eval_results if r.get("similarity") is not None)
            print(f"Evaluated {len(eval_results)} samples ({ok_count} valid, {compared_count} compared) in {total_time:.1f}s")

            # Collect results — comparison already done in Java
            round_results = []
            for r in eval_results:
                if not r.get("ok") or r.get("similarity") is None:
                    continue

                round_results.append({
                    "params": r["params"],
                    "similarity": r["similarity"],
                    "chamfer": r.get("chamfer", 0),
                    "coverage": r.get("coverage", 0),
                    "proximity": r.get("proximity", 0),
                    "per_axis": r.get("per_axis_extents", {}),
                })

            print(f"  {len(round_results)} results with comparison data")

            # Merge with all_results and re-rank
            all_results.extend(round_results)
            all_results.sort(key=lambda x: x["similarity"], reverse=True)
            # Keep top 50
            all_results = all_results[:50]

        finally:
            os.unlink(params_json_path)
            shutil.rmtree(output_dir, ignore_errors=True)

    return all_results


def main() -> int:
    parser = argparse.ArgumentParser(prog="dsl-optimize", description="Batch parameter optimizer for mesh DSL")
    parser.add_argument("--dsl", required=True, help="Path to the DSL file")
    parser.add_argument("--ref", required=True, help="Path to reference OBJ file")
    parser.add_argument("--samples", type=int, default=100, help="Number of random samples per round (default: 100)")
    parser.add_argument("--rounds", type=int, default=1, help="Number of refinement rounds (default: 1)")
    parser.add_argument("--seed", type=int, default=42, help="Random seed (default: 42)")
    parser.add_argument("--json", default="", help="Output best params to JSON file")
    args = parser.parse_args()

    results = optimize(args.dsl, args.ref, args.samples, args.rounds, args.seed)

    if not results:
        print("\nNo valid results.")
        return 1

    # Print top 10
    print(f"\nTop {min(10, len(results))} Results:")
    print(f"  {'#':<4}{'Sim':>7}{'Cov':>7}{'Prox':>7}  Parameters")
    print(f"  {'':4}{'-'*7}{'-'*7}{'-'*7}  {'-'*40}")
    for i, r in enumerate(results[:10]):
        params_str = "  ".join(f"{k}={v}" for k, v in r["params"].items())
        print(f"  {i+1:<4}{r['similarity']:>6.1f}%{r['coverage']*100:>6.1f}%{r['proximity']*100:>6.1f}%  {params_str}")

    # Write best params to file
    best = results[0]
    if args.json:
        out_path = os.path.abspath(args.json)
    else:
        out_path = "/tmp/dsl_best_params.json"

    with open(out_path, "w") as f:
        json.dump({"best_params": best["params"], "similarity": best["similarity"],
                    "coverage": best["coverage"], "proximity": best["proximity"]}, f, indent=2)
    print(f"\nBest parameters written to {out_path}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
