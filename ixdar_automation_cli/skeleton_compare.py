"""Skeleton comparison: thin wrapper around /mesh/skeleton/compare endpoint.

Exports a DSL mesh to OBJ via Maven validate-dsl, then calls the Java endpoint
which extracts TEASAR skeletons from both meshes, matches branches, and returns
per-branch errors with DSL parameter recommendations.

Usage:
    uv run python ixdar_automation_cli/skeleton_compare.py hand_v12_posed.dsl
    uv run python ixdar_automation_cli/skeleton_compare.py hand_v12_posed.dsl --ref ~/Blends/Hand/Hand.obj
    uv run python ixdar_automation_cli/skeleton_compare.py --generated /tmp/export.obj --ref ~/Blends/Hand/Hand.obj
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

IXDAR_ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
DSL_DIR = os.path.join(IXDAR_ROOT, "ixdar-app", "src", "main", "resources", "dsl")
MVN = shutil.which("mvn") or "/opt/homebrew/bin/mvn"
DEFAULT_REF = os.path.expanduser("~/Blends/Hand/Hand.obj")

sys.path.insert(0, os.path.dirname(__file__))
from automation_client import AutomationClient


def export_dsl(dsl_path: str) -> str:
    """Export a DSL file to OBJ via Maven validate-dsl. Returns the OBJ path."""
    export_path = os.path.join(tempfile.gettempdir(), f"skel_export_{int(time.time())}.obj")
    cmd = [
        MVN, "compile", "exec:exec", "-P", "validate-dsl",
        f"-Ddsl.file={dsl_path}",
        f"-Ddsl.export={export_path}",
        "-pl", "ixdar-app", "-q",
    ]
    result = subprocess.run(cmd, cwd=IXDAR_ROOT, capture_output=True, text=True, timeout=120)
    if result.returncode != 0:
        print(f"Maven export failed (exit {result.returncode}):", file=sys.stderr)
        print(result.stderr[-500:], file=sys.stderr)
        sys.exit(1)
    if not os.path.exists(export_path):
        print(f"Export file not created: {export_path}", file=sys.stderr)
        sys.exit(1)
    return export_path


def format_results(data: dict):
    """Pretty-print skeleton comparison results."""
    if not data.get("ok"):
        print(f"ERROR: {data.get('error', 'unknown')}")
        return

    gen_path = os.path.basename(data.get("generated_path", "?"))
    ref_path = os.path.basename(data.get("reference_path", "?"))
    print(f"Skeleton Comparison: {gen_path} vs {ref_path}")
    print(f"{'=' * 55}")
    print(f"Branches: {data['genBranchCount']} generated, {data['refBranchCount']} reference (matched: {data['matchedCount']})")
    print(f"Skeleton Score: {data['skeletonScore']:.1f}%")
    print()

    matches = data.get("matches", [])
    if matches:
        print("Branch Matches:")
        print(f"  {'Label':<12} {'Gen Len':>8} {'Ref Len':>8} {'Error':>8} {'Dir Err':>8} {'Joint Err':>9}")
        print(f"  {'-'*12} {'-'*8} {'-'*8} {'-'*8} {'-'*8} {'-'*9}")
        for m in matches:
            label = m["label"]
            if m["genBranchId"] < 0:
                print(f"  {label:<12} {'---':>8} {m['refLength']:>8.3f} {'MISSING':>8}")
                continue
            err_pct = (m["lengthError"] / m["refLength"] * 100) if m["refLength"] > 0.001 else 0
            print(f"  {label:<12} {m['genLength']:>8.3f} {m['refLength']:>8.3f} {err_pct:>+7.0f}% {m['directionErrorDeg']:>7.1f}\u00b0 {m['jointPositionError']:>9.4f}")
        print()

    recs = data.get("recommendations", [])
    if recs:
        print("Parameter Recommendations:")
        for r in recs:
            print(f"  {r['paramName']:<30} {r['reason']}")
        print()

    # Also dump raw JSON for programmatic use
    print("--- Raw JSON ---")
    print(json.dumps(data, indent=2))


def main():
    parser = argparse.ArgumentParser(description="Compare DSL mesh skeleton against reference")
    parser.add_argument("dsl", nargs="?", help="DSL filename (resolved relative to Ixdar DSL dir)")
    parser.add_argument("--generated", help="Pre-exported OBJ path (skips Maven export)")
    parser.add_argument("--ref", default=DEFAULT_REF, help=f"Reference OBJ path (default: {DEFAULT_REF})")
    parser.add_argument("--resolution", type=int, default=128, help="TEASAR voxel resolution (default: 128)")
    args = parser.parse_args()

    ref_path = os.path.abspath(os.path.expanduser(args.ref))
    if not os.path.exists(ref_path):
        print(f"Reference not found: {ref_path}", file=sys.stderr)
        sys.exit(1)

    export_path = None
    cleanup = False
    if args.generated:
        export_path = os.path.abspath(os.path.expanduser(args.generated))
    elif args.dsl:
        dsl_path = args.dsl
        if not os.path.isabs(dsl_path):
            dsl_path = os.path.join(DSL_DIR, dsl_path)
        if not os.path.exists(dsl_path):
            print(f"DSL file not found: {dsl_path}", file=sys.stderr)
            sys.exit(1)
        print(f"Exporting {os.path.basename(dsl_path)} to OBJ...")
        export_path = export_dsl(dsl_path)
        cleanup = True
        print(f"Exported to {export_path}")
    else:
        parser.error("Provide a DSL filename or --generated OBJ path")

    try:
        client = AutomationClient()
        # Health check
        try:
            client.health()
        except Exception:
            print("Automation server not running on localhost:47832", file=sys.stderr)
            print("Start it with: cd ~/Code/Ixdar && mvn -pl ixdar-app -P mesh-viewer exec:exec", file=sys.stderr)
            sys.exit(1)

        print(f"Comparing skeletons (resolution={args.resolution})...")
        result = client.skeleton_compare(export_path, ref_path, args.resolution)
        print()
        format_results(result)
    finally:
        if cleanup and export_path and os.path.exists(export_path):
            os.unlink(export_path)


if __name__ == "__main__":
    main()
