"""Run many models through one DSL graph in one quad-layout scene and tabulate how far each got.

Every crawfish agent used to write its own sweep: a JVM per model, a guessed timeout, a grep of the
log for the failure line and a stage table built by hand. This command does it once: it writes one
``.dsl`` per model from a template, starts one headless ``quad-layout`` scene, switches it to each
model with the ``model`` route, and reads the run back from the ``mesh-dsl-stages`` route (counts
before and after repair, the repair report, cones and genus, the furthest stage, quad and patch
counts, per-node times). A model that stalls in quantization's branch and bound, runs past the
timeout or kills the JVM is recorded and the scene is restarted for the next one.

Usage:
    uv run ixdar-cli model-sweep --models suite --out tmp/sweep-suite
    uv run ixdar-cli model-sweep --models crawfish --stall 120 --out tmp/sweep-crawfish
    uv run ixdar-cli model-sweep --models block,bolt --models "tmp/scans/*.glb" --graph my.dsl --out tmp/s
"""

import glob
import json
import os
import re
import sys
import threading
import time

from ..automation_client import AutomationClient, process_alive
from ..build_state import REPO_DIR
from ..cli_registry import CliCommandResult, cli_command
from ..mesh_catalog import resolve_mesh
from ..scene_host import DATA_ROOT_VARIABLE, local_data_root
from .run_scene import run
from .shutdown_scene import shutdown

SCENE = "quad-layout"

SUITE = "suite"

CRAWFISH = "crawfish"

SUITE_MODELS = ("block", "bolt", "botijo", "buddha", "elk", "fandisk", "feline", "fertility", "hand",
                "rockerarm", "sphere_base")

CRAWFISH_SCANS = tuple(f"IMG_{number}" for number in range(4109, 4119))

CRAWFISH_DIRECTORY = "crawfish"

CRAWFISH_EXTENSION = ".glb"

MODEL_PLACEHOLDER = "{{model}}"

NAME_PLACEHOLDER = "{{name}}"

# The graph the quad-layout scene runs over a named model, written as a DSL.
LAYOUT_GRAPH = "layout"

# The crawfish scans' graph: a repair pass in front of the layout.
REPAIR_LAYOUT_GRAPH = "repair-layout"

BUILT_IN_GRAPHS = {
    LAYOUT_GRAPH: (
        f'model = load_mesh(path="{MODEL_PLACEHOLDER}")\n'
        "layout = quad_layout(geometry=model.geometry)\n"),
    REPAIR_LAYOUT_GRAPH: (
        f'scan = load_mesh(path="{MODEL_PLACEHOLDER}")\n'
        "repaired = repair_mesh(geometry=scan.geometry, weld_epsilon=0.0, max_hole_edges=4096,"
        " min_shell_faces=100, strict=false)\n"
        "layout = quad_layout(geometry=repaired.geometry, alpha_degrees=15.0,"
        " target_edge_length=1.0)\n"),
}

LOCK_NAME = ".model-sweep.lock"

FINISHED = "finished"
FAILED = "failed"
STALLED = "stalled"
TIMED_OUT = "timeout"
CRASHED = "crashed"

WATCH_SECONDS = 2.0

SCENE_READY_SECONDS = 300.0

KILL_TIMEOUT_SECONDS = 5.0

# The branch and bound counts as stalled when its bound has moved less than this share of the
# incumbent gap over the stall window, or, with no incumbent yet, less than BOUND_RELATIVE_FLOOR of
# itself: the watchdog rule the Blends crawfish check used.
GAP_SHARE = 0.01

BOUND_RELATIVE_FLOOR = 1e-4

PROGRESS = re.compile(
    r"\[branch-bound\] nodes=(\d+) open=(\d+) incumbent=(\S+) bound=(\S+) (\d+)s")

RESTART = re.compile(r"\[branch-bound\] (root|done)")

CAUSE_SEPARATOR = "; caused by "

QUALIFIED_THROWABLE = re.compile(r"\b(?:[a-z_][\w$]*\.)+([A-Z][\w$]*(?:Exception|Error))")

CRASH_LINE = re.compile(r"(OutOfMemoryError|Exception in thread|fatal error|EXCEPTION_ACCESS_VIOLATION)")

STAGE_TIME_SECONDS = 1000.0


def expand_models(specs: list[str]) -> list[dict]:
    """Turn ``--models`` values into the models to sweep, in order, each once.

    A value is a comma-separated list of the set names ``suite`` and ``crawfish``, globs, mesh
    files and catalogue names (``list-meshes``). A crawfish scan is written into its DSL below
    ``${IXDAR_DATA_ROOT}``, so the graph resolves it on whichever machine runs the scene.

    :param specs: The ``--models`` values.
    :return: ``[{"name", "path", "set"}]``; ``path`` is what the DSL's ``load_mesh`` reads.
    :raises ValueError: When a value matches no model.
    """
    models: list[dict] = []
    for spec in specs:
        for item in (part.strip() for part in spec.split(",")):
            if not item:
                continue
            if item == SUITE:
                models.extend({"name": name, "path": resolve_mesh(name), "set": SUITE}
                              for name in SUITE_MODELS)
            elif item == CRAWFISH:
                for name in CRAWFISH_SCANS:
                    local = os.path.join(local_data_root(), CRAWFISH_DIRECTORY, name + CRAWFISH_EXTENSION)
                    if not os.path.exists(local):
                        raise ValueError(f"crawfish scan {name} is not at {local}; set {DATA_ROOT_VARIABLE}")
                    models.append({"name": name, "set": CRAWFISH,
                                   "path": "${" + DATA_ROOT_VARIABLE + "}/" + CRAWFISH_DIRECTORY
                                           + "/" + name + CRAWFISH_EXTENSION})
            elif glob.has_magic(item):
                pattern = item if os.path.isabs(item) else os.path.join(REPO_DIR, item)
                matches = sorted(glob.glob(pattern))
                if not matches:
                    raise ValueError(f"no file matches {item}")
                models.extend({"name": _stem(path), "path": os.path.abspath(path), "set": ""}
                              for path in matches)
            else:
                path = resolve_mesh(item)
                name = item if not os.path.exists(item) else _stem(path)
                models.append({"name": name, "path": path, "set": SUITE if name in SUITE_MODELS else ""})
    unique: list[dict] = []
    seen: set[str] = set()
    for model in models:
        if model["path"] in seen:
            continue
        seen.add(model["path"])
        while model["name"] in {kept["name"] for kept in unique}:
            model["name"] += "_"
        unique.append(model)
    return unique


def _stem(path: str) -> str:
    return os.path.splitext(os.path.basename(path))[0]


def graph_template(graph: str, models: list[dict]) -> tuple[str, str]:
    """Pick the DSL template: a built-in by name, a file, or the default for these models.

    With no ``--graph``, a sweep of suite models only runs ``layout`` (the quad-layout scene's own
    graph) and any other sweep runs ``repair-layout``.

    :param graph: The ``--graph`` value: empty, a built-in name or a ``.dsl`` file path.
    :param models: The models being swept.
    :return: ``(label, template text)``.
    :raises ValueError: When a template file has no ``{{model}}`` placeholder.
    """
    if not graph:
        graph = LAYOUT_GRAPH if all(model["set"] == SUITE for model in models) else REPAIR_LAYOUT_GRAPH
    if graph in BUILT_IN_GRAPHS:
        return graph, BUILT_IN_GRAPHS[graph]
    path = graph if os.path.isabs(graph) else os.path.join(REPO_DIR, graph)
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    if MODEL_PLACEHOLDER not in text:
        raise ValueError(f"{graph} has no {MODEL_PLACEHOLDER} placeholder for the model's path")
    return os.path.abspath(path), text


def acquire_lock(out: str) -> tuple[str, int]:
    """Take the output directory's lock, reclaiming one whose writer has exited.

    :param out: Output directory, created when missing.
    :return: ``(lock path, 0)`` when taken, or ``("", pid)`` naming the live writer that holds it.
    """
    os.makedirs(out, exist_ok=True)
    lock = os.path.join(out, LOCK_NAME)
    while True:
        try:
            handle = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        except FileExistsError:
            try:
                with open(lock, encoding="utf-8") as existing:
                    owner = int(existing.read().strip() or 0)
            except (OSError, ValueError):
                owner = 0
            if owner and owner != os.getpid() and process_alive(owner):
                return "", owner
            try:
                os.unlink(lock)
            except OSError:
                pass
            continue
        os.write(handle, str(os.getpid()).encode())
        os.close(handle)
        return lock, 0


class StallWatch:
    """Follows quantization's branch-and-bound progress lines and says when the bound stops moving."""

    def __init__(self, window: float):
        """:param window: Seconds over which the bound must move; 0 never calls a stall."""
        self.window = window
        self.history: list[tuple[float, float, float]] = []

    def feed(self, chunk: str, now: float) -> None:
        """Take newly logged text read at one moment.

        :param chunk: Log text since the last call.
        :param now: When it was read, in seconds.
        """
        if RESTART.search(chunk):
            self.history.clear()
        for match in PROGRESS.finditer(chunk):
            self.history.append((now, float(match.group(4)), float(match.group(3))))

    def stalled(self, now: float) -> str:
        """Whether the bound has stalled, as the one line a table shows.

        :param now: The current time, in the clock :meth:`feed` was given.
        :return: ``"bound a -> b over Ns, incumbent i"``, or empty while it still moves.
        """
        if not self.window or not self.history or now - self.history[0][0] < self.window:
            return ""
        older = [entry for entry in self.history if entry[0] <= now - self.window]
        if not older:
            return ""
        start, bound, incumbent = older[-1]
        _, latest_bound, latest_incumbent = self.history[-1]
        gap = incumbent - bound
        threshold = (GAP_SHARE * gap if gap != float("inf")
                     else BOUND_RELATIVE_FLOOR * max(1.0, abs(bound)))
        if abs(latest_bound - bound) >= threshold:
            return ""
        return (f"bound {bound:.3f} -> {latest_bound:.3f} over {now - start:.0f}s, "
                f"incumbent {latest_incumbent}")


def failure_line(error: str) -> str:
    """The root cause of a load failure, with exception classes unqualified.

    :param error: The ``model`` route's ``error``, ``failure; caused by root`` when wrapped.
    :return: The one line a table shows.
    """
    cause = error.rsplit(CAUSE_SEPARATOR, 1)[-1].strip()
    return QUALIFIED_THROWABLE.sub(r"\1", cause.splitlines()[0] if cause else "")


def _read_from(path: str, offset: int) -> tuple[str, int]:
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            handle.seek(offset)
            text = handle.read()
            return text, handle.tell()
    except OSError:
        return "", offset


class Scene:
    """One kept-alive quad-layout JVM and the log it writes."""

    def __init__(self, out: str, index: int, skip_build: bool):
        """Start the scene, ready to switch models.

        :param out: Sweep output directory; the JVM's log goes to ``logs/jvm-<index>.log``.
        :param index: Which start of the sweep this is, from 1.
        :param skip_build: Launch the classes on disk without the build check.
        :raises RuntimeError: When the scene does not become ready.
        """
        self.log = os.path.join(out, "logs", f"jvm-{index}.log")
        started = run(scene=SCENE, keep_alive=True, log=self.log, skip_build=skip_build,
                      timeout=SCENE_READY_SECONDS)
        if not started.get("ok"):
            raise RuntimeError(f"the {SCENE} scene did not start: {started.get('error')} ({self.log})")
        self.pid = started["pid"]
        self.base_url = started["baseUrl"]
        self.seconds = started["waitedSeconds"]
        self.client = AutomationClient(base_url=self.base_url)
        self.last_nodes: list | None = None

    def alive(self) -> bool:
        """:return: Whether the JVM is still running."""
        return process_alive(self.pid)

    def stop(self) -> None:
        """Stop the JVM, killing it when it does not exit within a few seconds."""
        shutdown(AutomationClient(base_url=self.base_url, retries=1), timeout=KILL_TIMEOUT_SECONDS)


def sweep_one(scene: Scene, model: dict, dsl: str, out: str, stall: float, timeout: float) -> dict:
    """Run one model in the scene and record how far it got.

    :param scene: The running scene.
    :param model: ``{"name", "path"}`` from :func:`expand_models`.
    :param dsl: The model's graph file.
    :param out: Sweep output directory.
    :param stall: Stall window in seconds.
    :param timeout: Seconds the model may take.
    :return: The model's row; its ``outcome`` is stalled, timeout or crashed when the scene was stopped.
    """
    _, offset = _read_from(scene.log, 0)
    watch = StallWatch(stall)
    answer: dict = {}

    def switch() -> None:
        try:
            answer.update(scene.client.call("model", path=dsl))
        except Exception as unanswered:
            answer.setdefault("transport", str(unanswered))

    worker = threading.Thread(target=switch, daemon=True)
    started = time.monotonic()
    worker.start()
    outcome, note = "", ""
    position = offset
    while worker.is_alive():
        worker.join(WATCH_SECONDS)
        chunk, position = _read_from(scene.log, position)
        now = time.monotonic()
        watch.feed(chunk, now)
        if not worker.is_alive():
            break
        if not scene.alive():
            outcome = CRASHED
            break
        note = watch.stalled(now)
        if note:
            outcome = STALLED
            break
        if now - started > timeout:
            outcome, note = TIMED_OUT, f"still running after {timeout:.0f}s"
            break
    seconds = round(time.monotonic() - started, 1)

    stages: dict = {}
    if scene.alive():
        try:
            stages = scene.client.call("mesh-dsl-stages")
        except Exception as unanswered:
            stages = {"ok": False, "error": str(unanswered)}
    if stages.get("ok") and stages.get("nodes") == scene.last_nodes:
        # A graph that failed before it started running leaves the previous model's run in place.
        stages = {"ok": False, "error": "the graph never ran; the scene still holds the previous model's run"}
    elif stages.get("ok"):
        scene.last_nodes = stages.get("nodes")
    if outcome:
        scene.stop()
    log_text, _ = _read_from(scene.log, offset)
    model_log = os.path.join(out, "logs", model["name"] + ".log")
    with open(model_log, "w", encoding="utf-8") as handle:
        handle.write(log_text)

    if not outcome and not scene.alive():
        outcome = CRASHED
    if not outcome:
        outcome = FINISHED if answer.get("outcome") == "loaded" else FAILED
    if outcome == CRASHED:
        crash = [line.strip() for line in log_text.splitlines() if CRASH_LINE.search(line)]
        note = crash[0] if crash else answer.get("transport", "the JVM exited")
    elif outcome == FAILED:
        note = failure_line(str(answer.get("error") or answer.get("transport") or ""))

    row = _row(model, dsl, stages, outcome, note, seconds, model_log)
    layout = row.get("layout") or {}
    if outcome == FINISHED and layout.get("quad_count"):
        shot = os.path.join(out, "shots", model["name"] + ".png")
        os.makedirs(os.path.dirname(shot), exist_ok=True)
        try:
            scene.client.call("frame")
            row["screenshot"] = scene.client.call("screenshot", path=shot).get("path", shot)
        except Exception as unanswered:
            row["screenshotError"] = str(unanswered)
    return row


def _row(model: dict, dsl: str, stages: dict, outcome: str, note: str, seconds: float,
         model_log: str) -> dict:
    statements = stages.get("statements") or []
    loaded = next((entry for entry in statements if entry.get("type") == "load_mesh"), {})
    repaired = next((entry for entry in statements if entry.get("repair")), {})
    layout = next((entry["layout"] for entry in statements if entry.get("layout")), {})
    furthest = layout.get("furthest") or stages.get("furthest", "")
    return {
        "name": model["name"],
        "path": model["path"],
        "dsl": dsl,
        "outcome": outcome,
        "note": note,
        "seconds": seconds,
        "input": {"vertices": loaded.get("vertex_count"), "faces": loaded.get("face_count")},
        "repaired": ({"vertices": repaired.get("vertex_count"), "faces": repaired.get("face_count")}
                     if repaired else None),
        "repair": repaired.get("repair"),
        "layout": layout,
        "furthest": furthest,
        "furthestType": layout.get("furthest_type") or stages.get("furthest_type", ""),
        "stages": [{"node": node["node"], "type": node["type"], "ms": node["ms"]}
                   for node in stages.get("nodes", [])],
        "totalMs": stages.get("total_ms"),
        "peakHeapMib": round(stages["peak_heap_mib"]) if stages.get("peak_heap_mib") else None,
        "stagesError": "" if stages.get("ok") else stages.get("error", ""),
        "log": model_log,
    }


def _counts(counts: dict | None) -> str:
    if not counts or counts.get("vertices") is None:
        return "-"
    return f"{counts['vertices']} / {counts['faces']}"


def _repair_cell(repair: dict | None) -> str:
    if not repair:
        return "-"
    return (f"shells {repair['shell_count']} (dropped {repair['dropped_shell_count']}), "
            f"holes {repair['filled_hole_count']}/{repair['hole_count']} filled, "
            f"open {repair['open_hole_count']}, folded {repair['folded_face_count']}"
            f"->{repair['still_folded_face_count']}")


def _stage_cell(row: dict) -> str:
    parts = []
    for stage in row["stages"]:
        if stage["type"] == "breakpoint":
            continue
        parts.append(f"{stage['node'].rsplit('/', 1)[-1]} {stage['ms'] / STAGE_TIME_SECONDS:.1f}")
    return ", ".join(parts) or "-"


def markdown(header: dict, rows: list[dict]) -> str:
    """Render the sweep as a markdown table, one row per model.

    :param header: The sweep's ``graph``, ``seconds`` and ``jvmStarts``.
    :param rows: The model rows.
    :return: Markdown text.
    """
    lines = [
        f"# model-sweep: {len(rows)} models, graph {header['graph']}",
        "",
        f"{header['seconds']:.0f} s wall, {header['jvmStarts']} JVM start(s), "
        f"{sum(row['outcome'] == FINISHED for row in rows)} finished.",
        "",
        "| model | outcome | in V / F | repaired V / F | repair | cones / chi / genus "
        "| furthest stage | failure | quads / patches | time (s) | stages (s) |",
        "|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for row in rows:
        layout = row["layout"] or {}
        topology = ("-" if layout.get("cones") is None
                    else f"{layout['cones']} / {layout['euler_characteristic']} / {layout['genus']}")
        quads = ("-" if layout.get("quad_count") is None
                 else f"{layout['quad_count']} / {layout['patch_count']}")
        furthest = f"{row['furthest']} ({row['furthestType']})" if row["furthest"] else "-"
        note = (row["note"] or "").replace("|", "\\|")
        lines.append(
            f"| {row['name']} | {row['outcome']} | {_counts(row['input'])} | {_counts(row['repaired'])} "
            f"| {_repair_cell(row['repair'])} | {topology} | {furthest} | {note or '-'} | {quads} "
            f"| {row['seconds']:.1f} | {_stage_cell(row)} |")
    return "\n".join(lines) + "\n"


def _write(out: str, header: dict, rows: list[dict]) -> tuple[str, str]:
    json_path = os.path.join(out, "sweep.json")
    markdown_path = os.path.join(out, "sweep.md")
    with open(json_path, "w", encoding="utf-8") as handle:
        json.dump({**header, "models": rows}, handle, indent=2)
    with open(markdown_path, "w", encoding="utf-8") as handle:
        handle.write(markdown(header, rows))
    return json_path, markdown_path


@cli_command(name="model-sweep")
def model_sweep(
    models: list[str] | None = None,
    graph: str = "",
    out: str = "tmp/model-sweep",
    stall: float = 120.0,
    timeout: float = 600.0,
    skip_build: bool = False,
) -> CliCommandResult:
    """Run models through one DSL graph in one quad-layout scene and tabulate how far each got.

    Each model's graph is the template with ``{{model}}`` replaced by its path (``{{name}}`` by its
    name), written to ``<out>/dsl/``; the one headless scene is switched to it with the ``model``
    route and the run read back from ``mesh-dsl-stages``. ``<out>/sweep.md`` and ``sweep.json`` hold
    one row per model, rewritten after each: input and repaired vertex and face counts, the repair
    report, cones, Euler characteristic and genus, the furthest stage, the failure line, quad and
    patch counts, per-node times and, for a finished layout, ``shots/<model>.png``. Each model's
    stretch of the scene log is copied to ``logs/<model>.log``. A model whose branch-and-bound bound
    stops moving, that runs past ``--timeout`` or that kills the JVM is recorded as stalled, timeout
    or crashed, the JVM is stopped and the next model gets a fresh one. A second sweep writing to the
    same ``--out`` is refused while the first runs.

    ``--models`` takes ``suite`` (the 11 quad-layout suite models), ``crawfish`` (IMG_4109 to
    IMG_4118 under ``${IXDAR_DATA_ROOT}/crawfish``), globs, mesh files and ``list-meshes`` names.
    ``--graph`` takes a template file or a built-in: ``layout`` (load_mesh, quad_layout) or
    ``repair-layout`` (load_mesh, repair_mesh, quad_layout at 15 degrees and edge length 1); the
    default is ``layout`` for suite models only, else ``repair-layout``.

    :param models: Repeatable, comma-separated set names, globs, mesh files and mesh names.
    :param graph: ``.dsl`` template with a ``{{model}}`` placeholder, or a built-in graph's name.
    :param out: Output directory, relative to the checkout.
    :param stall: Seconds the branch-and-bound bound may go without moving 1% of its gap; 0 is off.
    :param timeout: Seconds one model may take before it is stopped.
    :param skip_build: Launch the classes on disk even if stale; by default the build state decides.
    """
    try:
        chosen = expand_models(list(models or []))
        if not chosen:
            raise ValueError("name the models to sweep with --models (suite, crawfish, names, globs)")
        label, template = graph_template(graph, chosen)
    except (ValueError, OSError) as refused:
        return CliCommandResult(payload={"ok": False, "error": str(refused)}, exit_code=2)
    out = os.path.abspath(out if os.path.isabs(out) else os.path.join(REPO_DIR, out))
    lock, owner = acquire_lock(out)
    if not lock:
        return CliCommandResult(payload={
            "ok": False, "error": f"{out} is locked by model-sweep pid {owner}; wait for it or use another --out"},
            exit_code=1)
    for directory in ("dsl", "logs"):
        os.makedirs(os.path.join(out, directory), exist_ok=True)
    started = time.monotonic()
    header = {"graph": label, "template": template, "stall": stall, "timeout": timeout,
              "jvmStarts": 0, "jvmStartSeconds": [], "seconds": 0.0}
    rows: list[dict] = []
    scene: Scene | None = None
    try:
        for model in chosen:
            dsl = os.path.join(out, "dsl", model["name"] + ".dsl")
            with open(dsl, "w", encoding="utf-8") as handle:
                handle.write(template.replace(MODEL_PLACEHOLDER, model["path"].replace("\\", "/"))
                             .replace(NAME_PLACEHOLDER, model["name"]))
            if scene is None:
                header["jvmStarts"] += 1
                scene = Scene(out, header["jvmStarts"], skip_build or header["jvmStarts"] > 1)
                header["jvmStartSeconds"].append(scene.seconds)
            print(f"model-sweep: {model['name']} ...", file=sys.stderr, flush=True)
            row = sweep_one(scene, model, dsl, out, stall, timeout)
            print(f"model-sweep: {model['name']} {row['outcome']} {row['seconds']:.0f}s "
                  f"{row['furthest']} {row['note']}", file=sys.stderr, flush=True)
            rows.append(row)
            if row["outcome"] in (STALLED, TIMED_OUT, CRASHED):
                scene = None
            header["seconds"] = round(time.monotonic() - started, 1)
            _write(out, header, rows)
    except RuntimeError as failed_start:
        header["error"] = str(failed_start)
    finally:
        if scene is not None:
            scene.stop()
        header["seconds"] = round(time.monotonic() - started, 1)
        json_path, markdown_path = _write(out, header, rows)
        os.unlink(lock)
    counts: dict[str, int] = {}
    for row in rows:
        counts[row["outcome"]] = counts.get(row["outcome"], 0) + 1
    payload = {
        "ok": "error" not in header and len(rows) == len(chosen),
        "graph": label,
        "seconds": header["seconds"],
        "jvmStarts": header["jvmStarts"],
        "outcomes": counts,
        "markdown": markdown_path,
        "json": json_path,
        "rows": [f"{row['name']}: {row['outcome']} {row['seconds']:.0f}s {row['furthest']} {row['note']}".rstrip()
                 for row in rows],
    }
    if "error" in header:
        payload["error"] = header["error"]
    return CliCommandResult(payload=payload, exit_code=0 if payload["ok"] else 1)
