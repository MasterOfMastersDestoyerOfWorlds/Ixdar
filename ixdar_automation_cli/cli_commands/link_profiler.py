"""Find async-profiler's agent library, link it where the launch entries load it from, and build
the agent argument every scene launch carries.

Every scene is profiled, whichever path starts it: ``run-scene``, ``mesh-viewer`` and ``launch``
all take their ``-agentpath`` from :func:`profiler_agent_argument`, and the F5 entries take the
same one from ``java.profiler.args`` in ``.vscode/settings.json``. Each run writes its own capture,
``tmp/profiles/<label>-<time>-<pid>.html``, so two scenes never overwrite each other's and the
capture of a scene is found from its pid alone (:func:`profile_capture`).

The launch entries load the agent from one fixed, extension-free path,
``.profiler/libasyncProfiler`` under the checkout, so a single setting works on every platform.
``-agentpath`` is fatal when that file is missing, so a fresh checkout runs ``link-profiler`` once;
``ixd wt new`` does it for every worktree. async-profiler does not create the capture directory,
so ``link-profiler`` and every CLI launch create it.
"""

import glob
import os
import shutil
import time

from ..checkout import checkout_root
from ..cli_registry import CliCommandResult, cli_command

ASYNC_PROFILER_LIB = os.path.join(checkout_root(), ".profiler", "libasyncProfiler")
PROFILES_RELATIVE = os.path.join("tmp", "profiles")
# async-profiler substitutes the JVM's pid for this in its file name.
PID_PATTERN = "%p"
CAPTURE_SUFFIX = ".html"
CAPTURE_TIME_FORMAT = "%Y%m%d-%H%M%S"
# Where a system install of async-profiler puts its agent library, per platform. An install under
# any other prefix (Homebrew's, say) is found through the ``asprof`` launcher on PATH instead.
ASYNC_PROFILER_LIBRARY_NAMES = ("libasyncProfiler.dylib", "libasyncProfiler.so")
ASYNC_PROFILER_CANDIDATES = (
    "/usr/local/lib/libasyncProfiler.dylib",
    "/usr/lib/libasyncProfiler.so",
    "/usr/local/lib/libasyncProfiler.so",
    "/opt/async-profiler/lib/libasyncProfiler.so",
)


def async_profiler_library(include_link: bool = True) -> str:
    """Resolve the async-profiler agent library.

    Order: the ``ASYNC_PROFILER_LIB`` environment variable, the checkout's ``.profiler`` link
    (unless ``include_link`` is false), the platform install locations in
    :data:`ASYNC_PROFILER_CANDIDATES`, then the ``lib`` directory beside the real path of the
    ``asprof`` launcher on PATH.

    :param include_link: Accept the checkout's own ``.profiler`` link; false when that link is
        what is being made.
    :return: Path to the agent library.
    :raises FileNotFoundError: When no candidate exists, with the install hint in the message.
    """
    from_env = os.environ.get("ASYNC_PROFILER_LIB", "")
    asprof = shutil.which("asprof")
    beside_asprof = ()
    if asprof:
        install_lib = os.path.join(os.path.dirname(os.path.dirname(os.path.realpath(asprof))), "lib")
        beside_asprof = tuple(os.path.join(install_lib, name) for name in ASYNC_PROFILER_LIBRARY_NAMES)
    link = (ASYNC_PROFILER_LIB,) if include_link else ()
    for candidate in (from_env, *link, *ASYNC_PROFILER_CANDIDATES, *beside_asprof):
        if candidate and os.path.exists(candidate):
            return candidate
    raise FileNotFoundError(
        "async-profiler library not found; set ASYNC_PROFILER_LIB, or install it "
        "(macOS: brew install async-profiler; Linux: place libasyncProfiler.so in /usr/lib)"
    )


def profiles_directory(root: str = "") -> str:
    """Return the checkout's capture directory, creating it, since async-profiler will not.

    :param root: Checkout root; empty uses :func:`checkout_root`.
    :return: Absolute path of ``tmp/profiles``.
    """
    directory = os.path.join(root or checkout_root(), PROFILES_RELATIVE)
    os.makedirs(directory, exist_ok=True)
    return directory


def profiler_agent_argument(label: str, event: str = "cpu", path: str = "", root: str = "") -> tuple[str, str]:
    """Build the ``-agentpath`` argument that profiles a scene from its first instruction.

    :param label: Scene id or launch entry name, the start of the capture's file name.
    :param event: async-profiler event: ``cpu`` for time, ``alloc`` for allocation sites.
    :param path: Capture path to use instead of the per-run one; may contain ``%p``.
    :param root: Checkout root; empty uses :func:`checkout_root`.
    :return: ``(argument, capture pattern)``, the pattern holding ``%p`` until
        :func:`capture_path` fills in the pid; ``("", reason)`` when async-profiler is not
        installed, so the scene still starts, unprofiled, and the caller reports why.
    """
    try:
        library = async_profiler_library()
    except FileNotFoundError as missing:
        return "", str(missing)
    if not path:
        safe_label = "".join(char if char.isalnum() or char in "-_." else "_" for char in label)
        stamp = time.strftime(CAPTURE_TIME_FORMAT)
        path = os.path.join(profiles_directory(root), f"{safe_label}-{stamp}-{PID_PATTERN}{CAPTURE_SUFFIX}")
    else:
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    return f"-agentpath:{library}=start,event={event},file={path}", path


def capture_path(pattern: str, pid: int) -> str:
    """Name the file a profiled JVM writes, its pid substituted the way async-profiler does.

    :param pattern: Capture pattern from :func:`profiler_agent_argument`.
    :param pid: The JVM's pid.
    :return: The capture's path.
    """
    return pattern.replace(PID_PATTERN, str(pid))


def profile_capture(pid: int = 0, root: str = "") -> str:
    """Find a scene's capture from its pid, or the newest capture when no pid is given.

    Captures end in ``-<pid>.html`` whichever path wrote them (the F5 setting included), so a
    scene stopped by ``shutdown`` is matched to its capture without the launcher's help.

    :param pid: The scene's pid; 0 for the newest capture of any scene.
    :param root: Checkout root; empty uses :func:`checkout_root`.
    :return: The capture's path, or ``""`` when there is none.
    """
    directory = os.path.join(root or checkout_root(), PROFILES_RELATIVE)
    pattern = f"*-{pid}{CAPTURE_SUFFIX}" if pid else f"*{CAPTURE_SUFFIX}"
    captures = glob.glob(os.path.join(directory, pattern))
    return max(captures, key=os.path.getmtime) if captures else ""


@cli_command(name="link-profiler")
def link_profiler() -> CliCommandResult:
    """Point .profiler/libasyncProfiler at this platform's async-profiler library, in one line.

    The link is replaced when it exists. Where symlinks need a privilege the process lacks (Windows
    without developer mode), the library is copied there instead. The capture directory
    ``tmp/profiles`` is created too, since the F5 entries write there and async-profiler does not
    create directories. Exits 1 with the install hint when no library is found.
    """
    profiles_directory()
    try:
        library = async_profiler_library(include_link=False)
    except FileNotFoundError as missing:
        return CliCommandResult(payload=str(missing), exit_code=1)
    os.makedirs(os.path.dirname(ASYNC_PROFILER_LIB), exist_ok=True)
    if os.path.lexists(ASYNC_PROFILER_LIB):
        os.remove(ASYNC_PROFILER_LIB)
    try:
        os.symlink(library, ASYNC_PROFILER_LIB)
        verb = "linked"
    except OSError:
        shutil.copy2(library, ASYNC_PROFILER_LIB)
        verb = "copied"
    return CliCommandResult(payload=f"{verb} .profiler/libasyncProfiler -> {library}")
