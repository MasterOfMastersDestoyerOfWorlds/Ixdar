"""DSL node reference generator - emits JSON schema of all registered mesh nodes."""

import json
import subprocess
import sys
from pathlib import Path

try:
    from ..cli_registry import cli_command
except ImportError:
    from cli_registry import cli_command


@cli_command
def dsl_node_reference() -> dict:
    """Generate a JSON schema of all registered DSL mesh nodes.

    Emits a JSON array of node definitions, each containing:
    - id: The DSL node identifier
    - sourceFile: Path to the Java source file
    - inputs: List of input ports with name, type, default value, and mode constraints
    - outputs: List of output ports with name and type

    Output is sorted by node id for stable diffing.
    """
    try:
        # Find the Ixdar project root
        script_dir = Path(__file__).resolve().parent
        project_root = script_dir.parent.parent

        # Run the Java tool using Maven exec
        result = subprocess.run(
            [
                "mvn",
                "-f", str(project_root / "ixdar-app" / "pom.xml"),
                "exec:java",
                "-Dexec.mainClass=ixdar.geometry.mesh.documentation.DslNodeReference",
                "-Dexec.classpathScope=compile",
                "-q",
            ],
            cwd=str(project_root),
            capture_output=True,
            text=True,
            timeout=60,
        )

        if result.returncode != 0:
            return {
                "ok": False,
                "error": f"Java tool failed: {result.stderr}",
            }

        # Parse and return the JSON output
        try:
            nodes = json.loads(result.stdout)
            return {
                "ok": True,
                "count": len(nodes),
                "nodes": nodes,
            }
        except json.JSONDecodeError as e:
            return {
                "ok": False,
                "error": f"Invalid JSON output: {e}",
                "raw_output": result.stdout,
            }

    except subprocess.TimeoutExpired:
        return {
            "ok": False,
            "error": "Java tool timed out",
        }
    except Exception as e:
        return {
            "ok": False,
            "error": f"Failed to run Java tool: {e}",
        }
