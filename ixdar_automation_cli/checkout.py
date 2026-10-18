"""The Ixdar checkout a command acts on, and the one way to run Maven against it.

Every path the CLI builds starts at :func:`checkout_root`, and every Maven invocation goes through
:func:`run_maven` (or :func:`maven_executable` for a process that outlives the call), so the
executable is found on ``PATH`` the same way on every platform: ``mvn`` on Linux and macOS,
``mvn.cmd`` on Windows.
"""

import os
import shutil
import subprocess
import sys

PACKAGE_ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
MODULE_DIRECTORY = "ixdar-app"
POM_FILE = "pom.xml"
MAVEN_FAILURE_TAIL_LINES = 25


def checkout_root() -> str:
    """The Ixdar checkout this call belongs to, so parallel worktrees never share a scene or a build.

    The working directory wins over the installed package, which is what makes a globally
    installed CLI still talk to the worktree it is invoked from.

    :return: Absolute path to the nearest ancestor of the working directory holding ``pom.xml``
        and ``ixdar-app``, or the checkout this package lives in when there is none.
    """
    current = os.path.abspath(os.getcwd())
    while True:
        if (os.path.isfile(os.path.join(current, POM_FILE))
                and os.path.isdir(os.path.join(current, MODULE_DIRECTORY))):
            return current
        parent = os.path.dirname(current)
        if parent == current:
            return PACKAGE_ROOT
        current = parent


def maven_executable() -> str:
    """Find Maven on ``PATH``, honouring Windows' ``PATHEXT`` so ``mvn.cmd`` is found there.

    :return: Absolute path to the Maven launcher.
    :raises FileNotFoundError: When no ``mvn`` is on ``PATH``.
    """
    executable = shutil.which("mvn")
    if not executable:
        raise FileNotFoundError("mvn is not on PATH; install Maven and put its bin directory on PATH")
    return executable


def run_maven(args: list[str], description: str, cwd: str = "", echo: bool = False,
              check: bool = True, env: dict | None = None,
              timeout: float | None = None) -> subprocess.CompletedProcess:
    """Run Maven in the checkout, announcing the step on stderr so the CLI's JSON stdout stays clean.

    :param args: Maven arguments after the executable.
    :param description: Step name, printed before the run and named in a failure.
    :param cwd: Working directory; empty uses :func:`checkout_root`.
    :param echo: Stream the combined output to stderr line by line as Maven prints it, for a long
        build a person watches; the result's ``stdout`` then holds both streams and ``timeout``
        is not applied.
    :param check: Raise when Maven exits non-zero.
    :param env: Environment for Maven; ``None`` inherits this process's.
    :param timeout: Seconds before a captured run is killed.
    :return: The completed run, its output captured as text.
    :raises RuntimeError: With ``check``, when Maven fails, carrying the last
        :data:`MAVEN_FAILURE_TAIL_LINES` lines of its output.
    """
    print(f"{description}...", file=sys.stderr)
    command = [maven_executable(), *args]
    directory = cwd or checkout_root()
    if echo:
        lines: list[str] = []
        with subprocess.Popen(command, cwd=directory, env=env, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, errors="replace") as process:
            for line in process.stdout:
                print(line, end="", file=sys.stderr)
                lines.append(line)
        completed = subprocess.CompletedProcess(command, process.returncode, "".join(lines), "")
    else:
        completed = subprocess.run(command, cwd=directory, env=env, capture_output=True, text=True,
                                   timeout=timeout)
    if check and completed.returncode != 0:
        tail = (completed.stdout + completed.stderr).strip().splitlines()[-MAVEN_FAILURE_TAIL_LINES:]
        raise RuntimeError(f"{description} failed (maven exit {completed.returncode}):\n"
                           + "\n".join(tail))
    return completed
