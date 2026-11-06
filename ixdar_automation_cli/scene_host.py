"""Run a checkout's scene JVMs on another machine when the checkout says so.

``ixd wt new`` writes ``tmp/scene-host.json`` (``{"host": "user@machine", "directory":
"ixd-scenes/<worktree>"}``) into a worktree when the machine's ``ixd setup --scene-host`` names a
host. Every ixdar-cli command that starts, drives, screenshots or stops a scene then works on a JVM
on that host with nothing else changed: the build is shipped there with rsync (delta copies after
the first), the JVM runs under a supervisor that kills it when its ssh session dies, its output
streams back into the usual local log, its automation port is tunnelled to the same local port,
path arguments are mapped onto the host's copy of the checkout, and what the scene writes under
``tmp/`` or the module resources is copied back after every command that can write. An
unreachable host is a refusal, never a local run.

The host is a macOS machine: the classpath carries the ``macos-aarch64`` profile's LWJGL natives
(and no MKL, so the solver falls back to Accelerate or EJML there) and the JVM gets
``-XstartOnFirstThread``. Headless scenes need no GUI session; ``launchctl asuser``, which a
windowed one would need, requires root, so windowed scenes stay local.
"""

import json
import os
import posixpath
import re
import shlex
import signal
import subprocess
import sys

from .build_state import BUILD_OUTPUTS, CLASSPATH_FILE, REPO_DIR, RESOURCES_INPUT
from .checkout import MODULE_DIRECTORY, POM_FILE, checkout_root, run_maven

SCENE_HOST_FILE = os.path.join("tmp", "scene-host.json")
DEFAULT_REMOTE_PARENT = "ixd-scenes"
# Shared by every worktree on the host: a jar is immutable at its version, so one copy serves all.
REMOTE_JARS_DIRECTORY = posixpath.join(DEFAULT_REMOTE_PARENT, "m2")
LOCAL_JARS_DIRECTORY = os.path.join(os.path.expanduser("~"), ".m2", "repository")
CLASSPATH_INPUT = os.path.relpath(CLASSPATH_FILE, REPO_DIR)
MAC_CLASSPATH_INPUT = os.path.join(MODULE_DIRECTORY, "target", "CP-macos-arm64")
MAC_PROFILES = "!linux,!windows,macos-aarch64"
# The reactor sibling a launch replaces with this checkout's annotations/target/classes.
REACTOR_ANNOTATIONS_JAR = os.path.join("IXDAR", "annotations") + os.sep
REMOTE_PROFILER_LINK = posixpath.join(".profiler", "libasyncProfiler")
CLASSPATH_FLAGS = ("-cp", "-classpath", "--class-path")
AGENT_PATH_FLAG = "-agentpath:"
JAVA_AGENT_FLAG = "-javaagent:"
AGENT_OUTPUT_OPTION = re.compile(r"(?:^|,)(?:dest)?file=([^,]+)")
FIRST_THREAD_FLAG = "-XstartOnFirstThread"
DATA_ROOT_VARIABLE = "IXDAR_DATA_ROOT"
RECORD_DIRECTORY = posixpath.join("tmp", "automation")
SSH_FAILURE = 255
SSH_TIMEOUT_SECONDS = 60
SSH_OPTIONS = (
    "-o", "BatchMode=yes",
    "-o", "ConnectTimeout=10",
    "-o", "ServerAliveInterval=15",
    "-o", "ServerAliveCountMax=4",
    "-o", "LogLevel=ERROR",
)
# Files the scene reads from the checkout besides its classpath: the poms mark the checkout root
# for AutomationPortFile, and the resources hold the shaders scenes hot-reload by relative path.
SHIPPED_PATHS = (*BUILD_OUTPUTS, RESOURCES_INPUT, POM_FILE, os.path.join(MODULE_DIRECTORY, POM_FILE))
# Where a scene writes: copied back after every command that can write, the records aside.
PULLED_PATHS = ("tmp", RESOURCES_INPUT)
PULL_EXCLUDED = "/" + RECORD_DIRECTORY + "/"

SUPERVISOR_FILE = ".ixd-scene-supervisor.sh"
# Run once before each launch: the checkout's folder, the supervisor script, the profiler link the
# launch entries load, and the two roots paths map onto, $HOME and the host's data root.
REMOTE_PREPARE = """set -e
root="$HOME/$1"
mkdir -p "$root/tmp/automation" "$root/.profiler" "$HOME/$2"
printf '%s' "$3" > "$root/$4"
for library in /opt/homebrew/lib/libasyncProfiler.dylib /usr/local/lib/libasyncProfiler.dylib; do
  if [ -e "$library" ]; then ln -sfn "$library" "$root/.profiler/libasyncProfiler"; break; fi
done
printf '%s\\n%s\\n' "$HOME" "${IXDAR_DATA_ROOT:-$HOME}"
"""

# The scene's parent on the host: it refuses a port something there already holds (the tunnel
# would reach that instead), records the JVM's pid for a forced stop, and kills the JVM once the
# ssh session that carries its log and tunnel is gone, which reparents this shell to launchd.
REMOTE_SUPERVISOR = """cd "$1" || exit 1
pid_file=$2
port=$3
shift 3
exec 2>&1
if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "scene host: port $port is already taken there; run the command again for another"
  exit 75
fi
"$@" &
scene=$!
echo "$scene" > "$pid_file"
trap 'kill "$scene" 2>/dev/null' HUP INT TERM
while kill -0 "$scene" 2>/dev/null; do
  if [ "$(ps -o ppid= -p $$ | tr -d ' ')" = 1 ]; then kill "$scene" 2>/dev/null; fi
  sleep 2
done
wait "$scene"
status=$?
rm -f "$pid_file"
exit $status
"""


class SceneHostError(RuntimeError):
    """The scene host could not be reached or refused a step; never answered by running locally."""


def local_data_root() -> str:
    """This machine's data root, by MeshLoader's rule: ``IXDAR_DATA_ROOT``, else the home directory.

    :return: Absolute path.
    """
    return os.path.abspath(os.environ.get(DATA_ROOT_VARIABLE) or os.path.expanduser("~"))


def _under(path: str, root: str) -> bool:
    """Whether a path is a root or lies inside it.

    :param path: Absolute path.
    :param root: Absolute directory.
    :return: True when ``path`` is ``root`` or below it.
    """
    return path == root or path.startswith(root.rstrip(os.sep) + os.sep)


def rsync_filters(paths: list[str]) -> list[str]:
    """The rsync filter rules that transfer exactly these checkout-relative paths, files or trees.

    macOS's openrsync rejects the ``/./`` marker of ``--relative``, so a selection is expressed as
    include rules on the checkout root instead: every parent directory, then the path itself and
    everything below it, then an exclude of the rest. Excluded files are never deleted.

    :param paths: Paths relative to the checkout root.
    :return: ``--include``/``--exclude`` arguments.
    """
    rules: list[str] = []
    for path in paths:
        parts = path.strip(os.sep).split(os.sep)
        for depth in range(1, len(parts)):
            rule = "--include=/" + "/".join(parts[:depth]) + "/"
            if rule not in rules:
                rules.append(rule)
        rules.extend(["--include=/" + "/".join(parts), "--include=/" + "/".join(parts) + "/***"])
    return rules + ["--exclude=*"]


class SceneHost:
    """The machine a checkout's scenes run on, and the mapping between its copy and this one."""

    def __init__(self, host: str, directory: str, root: str, remote_home: str = "",
                 remote_data_root: str = ""):
        """Name the host and the folder under its home that mirrors this checkout.

        :param host: ``user@machine`` as ssh takes it.
        :param directory: The mirror's path relative to the host's home.
        :param root: This checkout's root.
        :param remote_home: The host's home directory, once :meth:`prepare` has asked.
        :param remote_data_root: The host's data root, once :meth:`prepare` has asked.
        """
        self.host = host
        self.directory = directory
        self.root = root
        self.remote_home = remote_home
        self.remote_data_root = remote_data_root

    @property
    def remote_root(self) -> str:
        """The host's copy of this checkout, as an absolute path there."""
        return posixpath.join(self.remote_home, self.directory)

    @property
    def remote_jars(self) -> str:
        """The host's shared copy of the dependency jars, laid out as ``~/.m2/repository`` is."""
        return posixpath.join(self.remote_home, REMOTE_JARS_DIRECTORY)

    @classmethod
    def from_record(cls, record: dict, root: str = "") -> "SceneHost":
        """Rebuild the host a scene record names, with the roots its launch learned.

        :param record: A ``tmp/automation/<pid>.json`` record carrying ``host``.
        :param root: Checkout root; empty uses :func:`checkout_root`.
        :return: The host, ready to map paths without asking it anything.
        """
        return cls(record["host"], record["remoteDirectory"], root or checkout_root(),
                   record["remoteHome"], record["remoteDataRoot"])

    def ssh(self, script: str, *arguments: str) -> str:
        """Run a shell script on the host and return its output.

        :param script: ``sh`` script text.
        :param arguments: Its positional arguments.
        :return: Standard output.
        :raises SceneHostError: When ssh cannot reach the host or the script fails, with the reason.
        """
        remote = " ".join(shlex.quote(part) for part in ("sh", "-c", script, "sh", *arguments))
        try:
            completed = subprocess.run(["ssh", *SSH_OPTIONS, self.host, remote], capture_output=True,
                                       text=True, stdin=subprocess.DEVNULL,
                                       timeout=SSH_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired as expired:
            raise SceneHostError(f"scene host {self.host} did not answer within "
                                 f"{SSH_TIMEOUT_SECONDS} s; nothing was run locally instead") from expired
        if completed.returncode == SSH_FAILURE:
            raise SceneHostError(f"scene host {self.host} (tmp/scene-host.json) is unreachable: "
                                 f"{completed.stderr.strip() or 'ssh failed'}; nothing was run "
                                 "locally instead")
        if completed.returncode != 0:
            raise SceneHostError(f"scene host {self.host} refused: "
                                 f"{(completed.stderr or completed.stdout).strip()}")
        return completed.stdout

    def rsync(self, *arguments: str, listing: str = "") -> None:
        """Run rsync over the same ssh options.

        :param arguments: rsync arguments after the shell option.
        :param listing: The file list a ``--files-from=-`` argument reads from standard input.
        :raises SceneHostError: When the copy fails, with rsync's message.
        """
        command = ["rsync", "-a", "-e", " ".join(("ssh", *SSH_OPTIONS)), *arguments]
        completed = subprocess.run(command, input=listing, capture_output=True, text=True)
        if completed.returncode != 0:
            raise SceneHostError(f"copying to or from scene host {self.host} failed: "
                                 f"{(completed.stderr or completed.stdout).strip()}")

    def prepare(self) -> None:
        """Reach the host, create the checkout's folder and profiler link, and learn its roots.

        :raises SceneHostError: When the host is unreachable.
        """
        lines = self.ssh(REMOTE_PREPARE, self.directory, REMOTE_JARS_DIRECTORY, REMOTE_SUPERVISOR,
                         SUPERVISOR_FILE).splitlines()
        self.remote_home, self.remote_data_root = lines[0], lines[1]

    def mac_classpath(self) -> list[str]:
        """The dependency jars a macOS JVM needs, from the ``macos-aarch64`` profile's resolution.

        Resolved once per build: it is refreshed whenever ixdar-app's compile has rewritten the
        Linux classpath file since. The reactor's own annotations jar is left out, since a launch
        puts this checkout's ``annotations/target/classes`` in its place.

        :return: Local jar paths, in classpath order.
        :raises SceneHostError: When a jar lies outside ``~/.m2/repository``.
        """
        mac_classpath = os.path.join(self.root, MAC_CLASSPATH_INPUT)
        if (not os.path.exists(mac_classpath) or os.path.getmtime(mac_classpath)
                < os.path.getmtime(os.path.join(self.root, CLASSPATH_INPUT))):
            run_maven(["-q", "dependency:build-classpath", "-P", MAC_PROFILES,
                       "-Dmdep.outputFile=" + os.path.relpath(MAC_CLASSPATH_INPUT, MODULE_DIRECTORY)],
                      "Resolving the macOS classpath for the scene host", cwd=self.root)
        with open(mac_classpath, encoding="utf-8") as handle:
            entries = [entry for entry in handle.read().strip().split(os.pathsep) if entry]
        jars = []
        for entry in entries:
            if not _under(entry, LOCAL_JARS_DIRECTORY):
                raise SceneHostError(f"classpath entry {entry} is outside {LOCAL_JARS_DIRECTORY}, "
                                     "so it cannot be shipped to the scene host")
            if os.path.relpath(entry, LOCAL_JARS_DIRECTORY).startswith(REACTOR_ANNOTATIONS_JAR):
                continue
            jars.append(entry)
        return jars

    def remote_path(self, path: str) -> str:
        """Map one absolute local path onto the host, or refuse a path the host has no copy of.

        :param path: Absolute local path.
        :return: The host's path for it.
        :raises SceneHostError: When the path is outside the checkout, the jars and the data root.
        """
        for local, remote in ((self.root, self.remote_root),
                              (LOCAL_JARS_DIRECTORY, self.remote_jars),
                              (local_data_root(), self.remote_data_root)):
            if _under(path, local):
                relative = os.path.relpath(path, local)
                return remote if relative == os.curdir else posixpath.join(
                    remote, *relative.split(os.sep))
        raise SceneHostError(f"{path} is outside this checkout and the data root "
                             f"({local_data_root()}), so scene host {self.host} has no copy of it")

    def remote_value(self, value: str) -> str:
        """Map a string handed to the scene: an absolute path moves to the host, anything else stays.

        :param value: A property value or route parameter.
        :return: The value the host's JVM should see.
        :raises SceneHostError: When it names a local file the host has no copy of.
        """
        return self.remote_path(value) if os.path.isabs(value) else value

    def remote_java_command(self, command: list[str], jars: list[str]) -> list[str]:
        """Rewrite a local JVM command line for the host.

        The classpath becomes the host's copy of both modules' classes and its jars, the profiler
        agent loads the host's own ``.profiler`` link, every occurrence of the checkout or the jar
        directory in an argument moves to the host's copy, and an absolute ``-D`` value maps by
        :meth:`remote_path`, so a scan under the data root is the host's own copy of it.

        :param command: The local argv, ``java`` first.
        :param jars: The jars :meth:`mac_classpath` resolved.
        :return: The host's argv.
        :raises SceneHostError: When an argument names a local file the host has no copy of.
        """
        classpath = ":".join([self.remote_path(os.path.join(self.root, output))
                              for output in BUILD_OUTPUTS] + [self.remote_path(jar) for jar in jars])
        remote = [command[0]]
        if FIRST_THREAD_FLAG not in command:
            remote.append(FIRST_THREAD_FLAG)
        after_classpath = False
        for argument in command[1:]:
            if after_classpath:
                remote.append(classpath)
                after_classpath = False
                continue
            after_classpath = argument in CLASSPATH_FLAGS
            if argument.startswith(AGENT_PATH_FLAG):
                _library, separator, options = argument[len(AGENT_PATH_FLAG):].partition("=")
                argument = (AGENT_PATH_FLAG + posixpath.join(self.remote_root, REMOTE_PROFILER_LINK)
                            + separator + options)
            for local, remote_prefix in ((self.root, self.remote_root),
                                         (LOCAL_JARS_DIRECTORY, self.remote_jars)):
                argument = re.sub(re.escape(local) + r"(?=$|[/=,:])",
                                  lambda _match, prefix=remote_prefix: prefix, argument)
            key, separator, value = argument.partition("=")
            if argument.startswith("-D") and separator and os.path.isabs(value) \
                    and not value.startswith(self.remote_home):
                argument = key + separator + self.remote_path(value)
            remote.append(argument)
        return remote

    def exit_outputs(self, command: list[str]) -> list[str]:
        """The files a JVM's agents write at exit, the profiler's and JaCoCo's, to copy back after.

        :param command: The local argv.
        :return: Absolute local paths named by an agent's ``file=`` or ``destfile=`` option.
        """
        outputs = []
        for argument in command:
            if argument.startswith((AGENT_PATH_FLAG, JAVA_AGENT_FLAG)):
                options = argument.partition("=")[2]
                outputs.extend(match.group(1) for match in AGENT_OUTPUT_OPTION.finditer(options))
        return outputs

    def input_paths(self, command: list[str]) -> list[str]:
        """The checkout files a launch names in its ``-D`` values, which must reach the host first.

        :param command: The local argv.
        :return: Checkout-relative paths of existing files or directories.
        """
        inputs = []
        for argument in command:
            _key, separator, value = argument.partition("=")
            if argument.startswith("-D") and separator and os.path.isabs(value) \
                    and _under(value, self.root) and os.path.exists(value):
                inputs.append(os.path.relpath(value, self.root))
        return inputs

    def ship(self, command: list[str]) -> list[str]:
        """Copy what the scene reads to the host: classes, resources, poms, named inputs and jars.

        :param command: The local argv, whose ``-D`` inputs under the checkout are copied too.
        :return: The jars on the host's classpath.
        :raises SceneHostError: When a copy fails.
        """
        jars = self.mac_classpath()
        print(f"  shipping the build to {self.host}:{self.remote_root}", file=sys.stderr)
        self.rsync("--delete", *rsync_filters([*SHIPPED_PATHS, *self.input_paths(command)]),
                   self.root + os.sep, f"{self.host}:{self.remote_root}/")
        self.rsync("--files-from=-", LOCAL_JARS_DIRECTORY + os.sep, f"{self.host}:{self.remote_jars}/",
                   listing="".join(os.path.relpath(jar, LOCAL_JARS_DIRECTORY) + "\n" for jar in jars))
        return jars

    def launch_command(self, command: list[str], cwd: str, port: int, jars: list[str],
                       pid_file: str) -> list[str]:
        """The local ssh command that runs a scene on the host and carries its log and tunnel.

        :param command: The local JVM argv.
        :param cwd: The local working directory the JVM would have had.
        :param port: The automation port, the same on both ends of the tunnel.
        :param jars: The jars :meth:`ship` copied.
        :param pid_file: Where the supervisor records the JVM's pid on the host.
        :return: The ssh argv.
        """
        remote = ["sh", posixpath.join(self.remote_root, SUPERVISOR_FILE), self.remote_path(cwd),
                  pid_file, str(port), *self.remote_java_command(command, jars)]
        forward = f"127.0.0.1:{port}:127.0.0.1:{port}"
        return ["ssh", *SSH_OPTIONS, "-o", "ExitOnForwardFailure=yes", "-T", "-L", forward,
                self.host, " ".join(shlex.quote(part) for part in remote)]

    def start(self, command: list[str], cwd: str, port: int,
              log_handle) -> tuple[subprocess.Popen, dict]:
        """Ship the build and start the scene on the host, its output streaming into the local log.

        The local process is the ssh session: it lives exactly as long as the JVM, so every
        liveness check and the scene limit work on its pid as on a local JVM's.

        :param command: The local JVM argv.
        :param cwd: The local working directory the JVM would have had.
        :param port: The automation port, the same on both ends of the tunnel.
        :param log_handle: The open local log the JVM's output goes to.
        :return: ``(ssh process, record fields)``: the port, the host, the roots, the supervisor's
            pid file and the local paths the JVM's agents write at exit.
        :raises SceneHostError: When the host cannot take the build or a path.
        """
        jars = self.ship(command)
        pid_file = posixpath.join(self.remote_root, RECORD_DIRECTORY, f"{port}.pid")
        process = subprocess.Popen(self.launch_command(command, cwd, port, jars, pid_file),
                                   stdout=log_handle, stderr=subprocess.STDOUT,
                                   stdin=subprocess.DEVNULL, start_new_session=True)
        print(f"  scene host: {self.host}, automation tunnelled to 127.0.0.1:{port}",
              file=sys.stderr)
        return process, {"port": port, "host": self.host, "remoteDirectory": self.directory,
                         "remoteHome": self.remote_home, "remoteDataRoot": self.remote_data_root,
                         "remotePidFile": pid_file, "outputs": self.exit_outputs(command)}

    def localize(self, payload):
        """Map the host's paths in a route's answer back onto this machine.

        :param payload: Decoded JSON.
        :return: The same structure with every host path replaced by its local one.
        """
        if isinstance(payload, dict):
            return {key: self.localize(value) for key, value in payload.items()}
        if isinstance(payload, list):
            return [self.localize(value) for value in payload]
        if isinstance(payload, str):
            for remote, local in ((self.remote_root, self.root),
                                  (self.remote_data_root, local_data_root())):
                if payload == remote or payload.startswith(remote + "/"):
                    return local + payload[len(remote):].replace("/", os.sep)
        return payload

    def named_paths(self, payload) -> list[str]:
        """The checkout paths a localized answer names, such as the file a screenshot was saved to.

        A route that picks its own output path writes outside ``tmp/`` (``screenshots/automation``),
        so :meth:`pull` copies these back too; the checkout root itself is never named.

        :param payload: Decoded JSON already passed through :meth:`localize`.
        :return: Absolute local paths under the checkout.
        """
        if isinstance(payload, dict):
            return [path for value in payload.values() for path in self.named_paths(value)]
        if isinstance(payload, list):
            return [path for value in payload for path in self.named_paths(value)]
        if isinstance(payload, str) and payload != self.root and _under(payload, self.root):
            return [payload]
        return []

    def push(self, paths: list[str]) -> None:
        """Copy local checkout files a route is about to read to the host.

        :param paths: Absolute local paths under the checkout.
        :raises SceneHostError: When the copy fails.
        """
        existing = [os.path.relpath(path, self.root) for path in paths if os.path.exists(path)]
        if existing:
            self.rsync(*rsync_filters(existing), self.root + os.sep,
                       f"{self.host}:{self.remote_root}/")

    def pull(self, paths: list[str] = ()) -> None:
        """Copy back what the scene wrote under ``tmp/`` and the resources, and any named paths.

        Only files newer on the host replace local ones, so a local edit made since the launch
        survives.

        :param paths: Further absolute local paths under the checkout, such as a profile output.
        :raises SceneHostError: When the copy fails.
        """
        relative = [*PULLED_PATHS, *(os.path.relpath(path, self.root) for path in paths
                                     if _under(path, self.root))]
        self.rsync("--update", f"--exclude={PULL_EXCLUDED}", *rsync_filters(relative),
                   f"{self.host}:{self.remote_root}/", self.root + os.sep)


def scene_host(root: str = "") -> SceneHost | None:
    """The host this checkout's scenes run on, from ``tmp/scene-host.json``, or None to run locally.

    :param root: Checkout root; empty uses :func:`checkout_root`.
    :return: The host, not yet contacted, or None when the checkout has no setting.
    :raises SceneHostError: When the setting is unreadable or names no host.
    """
    root = root or checkout_root()
    path = os.path.join(root, SCENE_HOST_FILE)
    if not os.path.exists(path):
        return None
    try:
        with open(path, encoding="utf-8") as handle:
            setting = json.load(handle)
        host = setting["host"].strip()
    except (OSError, ValueError, KeyError, AttributeError) as unreadable:
        raise SceneHostError(f"{path} names no scene host ({unreadable}); fix it or delete it to "
                             "run scenes locally") from unreadable
    if not host:
        raise SceneHostError(f"{path} names no scene host; fix it or delete it to run scenes locally")
    directory = setting.get("directory") or posixpath.join(DEFAULT_REMOTE_PARENT,
                                                           os.path.basename(root))
    return SceneHost(host, directory, root)


def signal_remote_scene(record: dict, signal_number: int, root: str = "") -> None:
    """Signal the host JVM behind a scene record; a local scene's record is left to its caller.

    The local pid of a remote scene is its ssh session, whose end makes the supervisor stop the
    JVM; signalling the JVM directly as well is what makes a forced stop certain.

    :param record: The scene's record.
    :param signal_number: ``SIGTERM`` or ``SIGKILL``.
    :param root: Checkout root; empty uses :func:`checkout_root`.
    """
    if not record.get("host"):
        return
    host = SceneHost.from_record(record, root)
    name = signal.Signals(signal_number).name.removeprefix("SIG")
    try:
        host.ssh('[ -f "$1" ] && kill -' + name + ' "$(cat "$1")" 2>/dev/null; exit 0',
                 record["remotePidFile"])
    except SceneHostError as unreachable:
        print(f"  {unreachable}; its supervisor stops the JVM once the session ends",
              file=sys.stderr)


def collect_remote_outputs(record: dict, root: str = "") -> None:
    """Copy back what a remote scene wrote, once its JVM is gone, the record's outputs included.

    :param record: The scene's record; a local one is ignored.
    :param root: Checkout root; empty uses :func:`checkout_root`.
    """
    if not record.get("host"):
        return
    try:
        SceneHost.from_record(record, root).pull(record.get("outputs", []))
    except SceneHostError as failed:
        print(f"  {failed}", file=sys.stderr)
