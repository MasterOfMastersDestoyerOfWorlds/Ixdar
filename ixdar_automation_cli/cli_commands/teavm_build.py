"""Package the TeaVM JavaScript bundle for the web build, failing on any TeaVM error.

TeaVM 0.15 bundles an ASM that reads class files up to Java 25 (major 69), so the build must run
on a JDK 25 even when the desktop toolchain is newer. JDK selection, first match wins: the
``TEAVM_JAVA_HOME`` environment variable, ``mise where java@25`` (install with
``mise install java@25``), then whatever JDK Maven already uses, with a warning when it is newer
than 25.

The build fails loudly rather than shipping a broken bundle. The pom sets ``stopOnErrors`` false,
so TeaVM reports "Output file built with errors", writes a ~36-byte stub ``classes.js`` and lets
Maven succeed when a method it cannot compile is reachable; that once masked a broken web build
for an unknown stretch. So any ``[ERROR]`` line fails the run, every one is repeated after the
build output where a tail sees it, a missing or stub ``classes.js`` fails it too, and the run ends
with one summary line. The whole reactor is packaged, never ``-pl``: maven-compiler-plugin 3.13
resolves the ``annotationProcessorPaths`` entry ``IXDAR:annotations`` from the reactor, and a
module left out would come from the ``~/.m2`` every worktree shares.

Maven's output streams to stderr, so the CLI's JSON stdout stays valid. ``--hugo`` then runs
``hugo -D`` in Krieg Eterna's web directory, the sibling ``KriegEterna/web`` checkout unless
``KRIEG_ETERNA_WEB`` names another.
"""

import os
import re
import shutil
import subprocess
import sys
import time

from ..build_state import IXDAR_APP_DIR, REPO_DIR
from ..checkout import run_maven
from ..cli_registry import CliCommandResult, cli_command

CLASSES_JS = os.path.join(IXDAR_APP_DIR, "target", "teavm", "ixdar", "classes.js")
MIN_CLASSES_JS_BYTES = 100000
BUILD_LOG = os.path.join(IXDAR_APP_DIR, "target", "teavm-build.log")
TEAVM_JAVA_MAJOR = 25
JAVA_HOME_VARIABLE = "TEAVM_JAVA_HOME"
KRIEG_WEB_VARIABLE = "KRIEG_ETERNA_WEB"
ERROR_MARKER = "[ERROR]"
JAVA_VERSION = re.compile(r'version "(\d+)')
LOG_PREFIX = "[teavm-build]"


def teavm_environment() -> dict:
    """Choose the JDK the build runs on, as the module docstring orders it.

    :return: The environment for Maven: this process's, with ``JAVA_HOME`` and ``PATH`` pointing
        at the chosen JDK when one was found, warning on stderr when the fallback is too new.
    """
    environment = dict(os.environ)
    jdk = environment.get(JAVA_HOME_VARIABLE, "")
    mise = shutil.which("mise")
    if not jdk and mise:
        found = subprocess.run([mise, "where", f"java@{TEAVM_JAVA_MAJOR}"], capture_output=True, text=True)
        jdk = found.stdout.strip() if found.returncode == 0 else ""
    if jdk and shutil.which("java", path=os.path.join(jdk, "bin")):
        environment["JAVA_HOME"] = jdk
        environment["PATH"] = os.pathsep.join((os.path.join(jdk, "bin"), environment.get("PATH", "")))
        print(f"{LOG_PREFIX} Using JDK at {jdk}", file=sys.stderr)
        return environment
    java = shutil.which("java")
    version = subprocess.run([java, "-version"], capture_output=True, text=True) if java else None
    major = JAVA_VERSION.search(version.stderr + version.stdout) if version else None
    if major and int(major.group(1)) > TEAVM_JAVA_MAJOR:
        print(f"{LOG_PREFIX} WARNING: JDK {major.group(1)} is newer than TeaVM 0.15 can read; set "
              f"{JAVA_HOME_VARIABLE} or run 'mise install java@{TEAVM_JAVA_MAJOR}'", file=sys.stderr)
    return environment


def run(hugo: bool = False) -> dict:
    """Install the annotations, package the bundle, judge it, and optionally rebuild the site.

    :param hugo: Run ``hugo -D`` in the Krieg Eterna web directory once the bundle is good.
    :return: ``{"ok", "verdict", "classesJs", "classesJsBytes", "errorCount", "errors", "seconds",
        "log", "summary", "hugo"?}``; ``ok`` is false on a Maven failure, any ``[ERROR]`` line, a
        missing or stub ``classes.js``, or a failed or impossible Hugo run.
    """
    started = time.monotonic()
    environment = teavm_environment()
    os.makedirs(os.path.dirname(BUILD_LOG), exist_ok=True)
    # Yesterday's bundle must not be mistaken for today's: a failing build would otherwise leave
    # the last good classes.js in place and pass the size check.
    if os.path.exists(CLASSES_JS):
        os.remove(CLASSES_JS)

    with open(BUILD_LOG, "w", encoding="utf-8") as log:
        package = run_maven(["package", "-P", "web-teavm", "-DskipTests"],
                            f"{LOG_PREFIX} Packaging the TeaVM bundle",
                            echo=True, check=False, env=environment)
        log.write(package.stdout)
        status = package.returncode

    with open(BUILD_LOG, encoding="utf-8", errors="replace") as log:
        errors = [line.rstrip() for line in log if ERROR_MARKER in line]
    if errors:
        print(f"\n{LOG_PREFIX} {len(errors)} {ERROR_MARKER} line(s), repeated from the build output:",
              file=sys.stderr)
        print("\n".join(errors) + "\n", file=sys.stderr)

    size = os.path.getsize(CLASSES_JS) if os.path.exists(CLASSES_JS) else 0
    if status != 0:
        verdict = f"FAILED (maven exit {status})"
    elif errors:
        verdict = f"FAILED (TeaVM reported {len(errors)} {ERROR_MARKER} line(s); the bundle is not trustworthy)"
    elif size < MIN_CLASSES_JS_BYTES:
        verdict = (f"FAILED (classes.js is missing or a {size}-byte stub, under the "
                   f"{MIN_CLASSES_JS_BYTES}-byte minimum)")
    else:
        verdict = "OK"
    seconds = round(time.monotonic() - started)
    summary = (f"{LOG_PREFIX} {verdict}: {CLASSES_JS} {size} bytes, {len(errors)} {ERROR_MARKER} "
               f"line(s), {seconds}s, full log {BUILD_LOG}")
    print(summary, file=sys.stderr)
    result = {"ok": verdict == "OK", "verdict": verdict, "classesJs": CLASSES_JS, "classesJsBytes": size,
              "errorCount": len(errors), "errors": errors, "seconds": seconds, "log": BUILD_LOG,
              "summary": summary}

    if hugo and result["ok"]:
        configured = os.environ.get(KRIEG_WEB_VARIABLE, "").strip()
        web = os.path.abspath(configured) if configured else os.path.join(
            os.path.dirname(REPO_DIR), "KriegEterna", "web")
        hugo_executable = shutil.which("hugo")
        if not os.path.isdir(web):
            result["hugo"] = {"ok": False, "error": f"Krieg Eterna web dir not found: {web}; "
                                                    f"set {KRIEG_WEB_VARIABLE} to your KriegEterna/web path"}
        elif not hugo_executable:
            result["hugo"] = {"ok": False, "error": "hugo is not on PATH"}
        else:
            print(f"{LOG_PREFIX} Running hugo -D in {web}", file=sys.stderr)
            site = subprocess.run([hugo_executable, "-D"], cwd=web, stdout=sys.stderr, stderr=sys.stderr)
            result["hugo"] = {"ok": site.returncode == 0, "dir": web, "returncode": site.returncode}
        result["ok"] = result["hugo"]["ok"]
    return result


@cli_command(name="teavm-build")
def teavm_build(hugo: bool = False) -> CliCommandResult:
    """Package the TeaVM web bundle on a JDK 25, failing on any TeaVM [ERROR] or a stub classes.js.

    :param hugo: Then run hugo -D in Krieg Eterna's web directory (KRIEG_ETERNA_WEB overrides the sibling KriegEterna/web).
    """
    payload = run(hugo=hugo)
    return CliCommandResult(payload=payload, exit_code=0 if payload["ok"] else 1)
