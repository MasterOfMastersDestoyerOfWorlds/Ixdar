"""Batch parameter optimizer for mesh DSL files.

Discovers tunable parameters (input_float/input_int nodes with min/max),
generates random samples, evaluates them via the Java BatchDslEvaluator
(single JVM with inline KD-tree mesh comparison), and ranks results.

Usage:
    uv run dsl-optimize --dsl hand.dsl --ref ~/Blends/Hand/Hand.obj --samples 100
    uv run dsl-optimize --dsl hand.dsl --ref Hand.obj --samples 200 --rounds 3
"""

import contextlib
import json
import os
import random
import shutil
import subprocess
import sys
import tempfile
import time

from ..cli_registry import CliCommandResult, cli_command

IXDAR_ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))
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
                    "hausdorff": r.get("hausdorff", 0),
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


def run(dsl: str, ref: str, samples: int = 100, rounds: int = 1, seed: int = 42, json_out: str = "") -> dict:
    """Run the optimizer and return a structured result, writing the best params to a JSON file.

    Human-readable progress from ``optimize`` is redirected to stderr so the CLI's JSON stdout
    stays valid.

    :param dsl: path to the DSL file
    :param ref: path to the reference OBJ
    :param samples: random samples per round
    :param rounds: refinement rounds
    :param seed: RNG seed
    :param json_out: path to write best params to; empty uses /tmp/dsl_best_params.json
    :return: ``{"ok", "count", "top", "best_params", "similarity", "output_path"}`` or an error dict
    """
    with contextlib.redirect_stdout(sys.stderr):
        results = optimize(dsl, ref, samples, rounds, seed)

    if not results:
        return {"ok": False, "error": "No valid results (no tunable params, or no samples compared)."}

    best = results[0]
    out_path = os.path.abspath(json_out) if json_out else "/tmp/dsl_best_params.json"
    with open(out_path, "w") as f:
        json.dump(
            {
                "best_params": best["params"],
                "similarity": best["similarity"],
                "hausdorff": best["hausdorff"],
                "chamfer": best["chamfer"],
            },
            f,
            indent=2,
        )
    return {
        "ok": True,
        "count": len(results),
        "top": results[:10],
        "best_params": best["params"],
        "similarity": best["similarity"],
        "output_path": out_path,
    }


@cli_command(name="dsl-optimize")
def dsl_optimize(dsl: str, ref: str, samples: int = 100, rounds: int = 1, seed: int = 42, json: str = "") -> CliCommandResult:
    """Batch-optimize mesh DSL parameters against a reference OBJ.

    :param dsl: Path to the DSL file.
    :param ref: Path to the reference OBJ file.
    :param samples: Random samples per round.
    :param rounds: Refinement rounds.
    :param seed: Random seed.
    :param json: Output best params to this JSON file (empty uses /tmp/dsl_best_params.json).
    """
    payload = run(dsl, ref, samples, rounds, seed, json)
    return CliCommandResult(payload=payload, exit_code=0 if payload.get("ok") else 1)