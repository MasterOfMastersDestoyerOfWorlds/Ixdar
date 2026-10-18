"""Find async-profiler's agent library, and link it where the launch entries load it from.

The launch entries and ``run-scene`` load the agent from one fixed, extension-free path,
``.profiler/libasyncProfiler`` under the checkout, so a single setting works on every platform.
``-agentpath`` is fatal when that file is missing, so a fresh checkout runs ``link-profiler`` once;
``ixd wt new`` does it for every worktree.
"""

import os
import shutil

from ..checkout import checkout_root
from ..cli_registry import CliCommandResult, cli_command

ASYNC_PROFILER_LIB = os.path.join(checkout_root(), ".profiler", "libasyncProfiler")
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


@cli_command(name="link-profiler")
def link_profiler() -> CliCommandResult:
    """Point .profiler/libasyncProfiler at this platform's async-profiler library, in one line.

    The link is replaced when it exists. Where symlinks need a privilege the process lacks (Windows
    without developer mode), the library is copied there instead. Exits 1 with the install hint
    when no library is found.
    """
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
