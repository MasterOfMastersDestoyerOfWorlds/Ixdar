"""Decide how much a checkout must build before a scene launches, and do exactly that much.

Each launch compares the build's inputs (the poms, the annotations module's sources, the app's java
sources and resources) with the ones the last successful build recorded in a stamp under
``ixdar-app/target``, and then runs no Maven, copies resources, compiles, or clean-compiles. Paths
are relative to a checkout root, so the decision reads any checkout's tree, and each worktree has
its own ``target/`` and so its own stamp.
"""

import json
import os
import shutil
import sys
import time

from .checkout import MODULE_DIRECTORY, POM_FILE, checkout_root, run_maven

REPO_DIR = checkout_root()
IXDAR_APP_DIR = os.path.join(REPO_DIR, MODULE_DIRECTORY)
POM_PATH = os.path.join(IXDAR_APP_DIR, POM_FILE)
ANNOTATIONS_CLASSES = os.path.join(REPO_DIR, "annotations", "target", "classes")
CLASSPATH_FILE = os.path.join(IXDAR_APP_DIR, "target", "CP")
CLASSES_DIR = os.path.join(IXDAR_APP_DIR, "target", "classes")
SOURCES_DIR = os.path.join(IXDAR_APP_DIR, "src", "main", "java")
RESOURCES_DIR = os.path.join(IXDAR_APP_DIR, "src", "main", "resources")
POM_INPUTS = (POM_FILE, os.path.join("annotations", POM_FILE), os.path.join(MODULE_DIRECTORY, POM_FILE))
ANNOTATIONS_INPUT = os.path.join("annotations", "src")
SOURCES_INPUT = os.path.relpath(SOURCES_DIR, REPO_DIR)
RESOURCES_INPUT = os.path.relpath(RESOURCES_DIR, REPO_DIR)
BUILD_OUTPUTS = (os.path.relpath(CLASSES_DIR, REPO_DIR), os.path.relpath(ANNOTATIONS_CLASSES, REPO_DIR))
# Under ixdar-app/target so any ``mvn clean`` removes it with the classes it vouches for.
BUILD_STAMP = os.path.join(MODULE_DIRECTORY, "target", "ixdar-cli-build.json")
BUILD_CURRENT = "current"
BUILD_RESOURCES = "resources only"
BUILD_INCREMENTAL = "incremental compile"
BUILD_CLEAN = "clean compile"
BUILD_REASON_NAMES = 3


def sync_resources() -> list[str]:
    """Copy every resource newer on disk than its copy in ``target/classes``.

    A scene loads DSL fixtures and other resources from the classpath, so an edit under
    ``src/main/resources`` is invisible to a ``--skip-build`` run until it is copied across —
    the reason agents kept hand-running ``cp`` before every scene.

    :return: The module-relative paths that were refreshed.
    """
    copied: list[str] = []
    for directory, _subdirectories, files in os.walk(RESOURCES_DIR):
        for filename in files:
            source = os.path.join(directory, filename)
            relative = os.path.relpath(source, RESOURCES_DIR)
            target = os.path.join(CLASSES_DIR, relative)
            if os.path.exists(target) and os.path.getmtime(target) >= os.path.getmtime(source):
                continue
            os.makedirs(os.path.dirname(target), exist_ok=True)
            shutil.copy2(source, target)
            copied.append(relative)
    return copied


def build_inputs(root: str) -> dict[str, int]:
    """List every file a build reads, with its modification time.

    :param root: Checkout root.
    :return: Root-relative path to ``st_mtime_ns`` for the poms, the annotations module's sources,
        and the app's java sources and resources.
    """
    inputs: dict[str, int] = {}
    for pom in POM_INPUTS:
        path = os.path.join(root, pom)
        if os.path.exists(path):
            inputs[pom] = os.stat(path).st_mtime_ns
    for tree in (ANNOTATIONS_INPUT, SOURCES_INPUT, RESOURCES_INPUT):
        for directory, _subdirectories, files in os.walk(os.path.join(root, tree)):
            for filename in files:
                path = os.path.join(directory, filename)
                inputs[os.path.relpath(path, root)] = os.stat(path).st_mtime_ns
    return inputs


def _input_names(paths: list[str]) -> str:
    """Name a few changed inputs for a one-line build reason.

    :param paths: Root-relative input paths.
    :return: Up to :data:`BUILD_REASON_NAMES` file names, then how many more there are.
    """
    names = ", ".join(os.path.basename(path) for path in paths[:BUILD_REASON_NAMES])
    extra = len(paths) - BUILD_REASON_NAMES
    return names + (f" +{extra} more" if extra > 0 else "")


def build_decision(root: str, inputs: dict[str, int]) -> tuple[str, str]:
    """Decide how much building a checkout needs before a launch, by comparing its inputs with the
    ones its last successful build recorded.

    Inputs are compared by exact mtime and by path, so an edit, an added file, a deleted one and a
    rename with its old mtime all count. The stamp is deleted before each build and rewritten only
    once it succeeds, so a failed or interrupted build leaves none and the next decision is clean.
    A java change compiles without ``clean``: on any stale source maven-compiler-plugin 3.13
    recompiles the whole module and deletes the outputs of removed sources, so the annotation
    processors see every annotated type and the generated registries match a clean build's.
    TOOL-18 checked it: touching a plain class, an annotated route and an annotated scene, then
    adding an ``@AutomationRouteAnnotation`` class and deleting it, each compiled without
    ``clean``, left ``target/generated-sources/annotations``, ``automation_routes.json``,
    ``mesh-node-catalog.json`` and the ``target/classes`` file list byte-identical to a clean
    build's. Repeat that comparison after changing the compiler plugin or the processors.

    :param root: Checkout root whose ``target/`` holds the stamp and the classes.
    :param inputs: The checkout's current :func:`build_inputs`.
    :return: ``(mode, reason)``; the mode is one of the ``BUILD_*`` constants.
    """
    try:
        with open(os.path.join(root, BUILD_STAMP), encoding="utf-8") as handle:
            stamp = json.load(handle)
    except (OSError, ValueError):
        stamp = {}
    recorded = stamp.get("inputs")
    if not recorded:
        return BUILD_CLEAN, "no successful build recorded"
    missing = [output for output in BUILD_OUTPUTS if not os.path.isdir(os.path.join(root, output))]
    if missing:
        return BUILD_CLEAN, f"{missing[0]} missing"
    changed = sorted(path for path, mtime in inputs.items() if recorded.get(path) != mtime)
    removed = sorted(path for path in recorded if path not in inputs)
    poms = [path for path in changed + removed if path in POM_INPUTS]
    if poms:
        return BUILD_CLEAN, f"pom changed: {', '.join(poms)}"
    annotations = [path for path in changed + removed if path.startswith(ANNOTATIONS_INPUT + os.sep)]
    if annotations:
        return BUILD_CLEAN, f"annotations changed: {_input_names(annotations)}"
    removed_resources = [path for path in removed if path.startswith(RESOURCES_INPUT + os.sep)]
    if removed_resources:
        return BUILD_CLEAN, f"resource removed: {_input_names(removed_resources)}"
    sources = sorted(path for path in changed + removed if path.startswith(SOURCES_INPUT + os.sep))
    if sources:
        return BUILD_INCREMENTAL, f"{len(sources)} source(s) changed: {_input_names(sources)}"
    if changed:
        return BUILD_RESOURCES, f"{len(changed)} resource(s) changed: {_input_names(changed)}"
    built = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(stamp.get("built", 0)))
    return BUILD_CURRENT, f"no input changed since the last build at {built}"


def ensure_build(skip_build: bool) -> list[str]:
    """Bring the checkout's classes up to date as :func:`build_decision` says, then make sure the
    classpath file exists.

    Both modules are compiled because the app's annotation processors run from the reactor's
    ``annotations/target/classes``, which also precedes the classpath file's installed
    ``annotations`` jar on the scene's ``-cp``; a stale one surfaces as a ``NoSuchMethodError``.

    :param skip_build: Force a launch of whatever classes are on disk, current or not, after
        copying resources newer than their ``target/classes`` copy. The classpath file is still
        resolved when absent, since the scene cannot launch without one.
    :return: Resources copied into ``target/classes`` instead of compiling.
    :raises RuntimeError: When a Maven step fails, with the tail of its output.
    """
    synced: list[str] = []
    if skip_build:
        print("build: skipped (--skip-build)", file=sys.stderr)
        synced = sync_resources()
    else:
        built = time.time()
        inputs = build_inputs(REPO_DIR)
        mode, reason = build_decision(REPO_DIR, inputs)
        print(f"build: {mode} ({reason})", file=sys.stderr)
        stamp = os.path.join(REPO_DIR, BUILD_STAMP)
        if mode in (BUILD_INCREMENTAL, BUILD_CLEAN):
            if os.path.exists(stamp):
                os.remove(stamp)
            goals = ["clean", "compile"] if mode == BUILD_CLEAN else ["compile"]
            run_maven(["-q", *goals, "-pl", "annotations,ixdar-app"], "Building", cwd=REPO_DIR)
        elif mode == BUILD_RESOURCES:
            synced = sync_resources()
        if mode != BUILD_CURRENT:
            with open(stamp, "w", encoding="utf-8") as handle:
                json.dump({"built": built, "mode": mode, "inputs": inputs}, handle)
    if synced:
        print(f"  synced {len(synced)} resource(s) into target/classes", file=sys.stderr)
    if not os.path.exists(CLASSPATH_FILE):
        run_maven(["-q", "-f", POM_PATH, "dependency:build-classpath",
                   f"-Dmdep.outputFile={CLASSPATH_FILE}"],
                  "Refreshing classpath (CP)", cwd=REPO_DIR)
    return synced
