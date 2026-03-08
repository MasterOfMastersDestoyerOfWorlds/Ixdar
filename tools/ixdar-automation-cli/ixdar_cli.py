#!/usr/bin/env python3
import argparse
import json
import sys
import urllib.error

from automation_client import DEFAULT_BASE_URL, AutomationClient
from cli_commands.registry_imports import import_all_commands
from cli_registry import CliCommand, CliCommandResult, CliParameter, get_registry
from scene_scaffolding import scaffold_new_scene
from trade_route_ops_validation import run_validation


def _build_parser() -> argparse.ArgumentParser:
    import_all_commands()

    parser = argparse.ArgumentParser(
        prog="ixdar",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    subparsers = parser.add_subparsers(dest="command_name", required=True)

    for command_name, command in sorted(get_registry().items()):
        _add_command_parser(subparsers, command_name, command)

    _add_compatibility_parsers(subparsers)
    return parser


def _add_command_parser(subparsers: argparse._SubParsersAction, command_name: str, command: CliCommand) -> None:
    command_parser = subparsers.add_parser(
        command_name,
        help=command.summary,
        description=_command_description(command),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    for parameter in command.params:
        _add_command_argument(command_parser, parameter)


def _command_description(command: CliCommand) -> str:
    detail_lines = []
    for line in command.docstring.splitlines()[1:]:
        stripped = line.strip()
        if not stripped or stripped.startswith(":param "):
            continue
        detail_lines.append(stripped)
    if not detail_lines:
        return command.summary
    return "\n\n".join([command.summary, "\n".join(detail_lines)])


def _add_command_argument(command_parser: argparse.ArgumentParser, parameter: CliParameter) -> None:
    kwargs: dict = {"help": parameter.help_text}
    if parameter.choices is not None:
        kwargs["choices"] = parameter.choices

    if parameter.annotation is bool:
        default = parameter.default if parameter.has_default else False
        kwargs["default"] = default
        kwargs["action"] = "store_false" if default else "store_true"
    elif parameter.multiple:
        kwargs["action"] = "append"
        kwargs["type"] = parameter.annotation
        if parameter.has_default:
            kwargs["default"] = parameter.default
        else:
            kwargs["required"] = True
    else:
        kwargs["type"] = parameter.annotation
        if parameter.has_default:
            kwargs["default"] = parameter.default
        else:
            kwargs["required"] = True

    command_parser.add_argument(parameter.cli_flag, **kwargs)


def _add_compatibility_parsers(subparsers: argparse._SubParsersAction) -> None:
    record = subparsers.add_parser("record", help="Compatibility shim for record subcommands")
    record_sub = record.add_subparsers(dest="record_command", required=True)
    record_sub.add_parser("start")
    record_sub.add_parser("status")
    record_stop = record_sub.add_parser("stop")
    record_stop.add_argument("--path", default="")

    replay = subparsers.add_parser("replay", help="Compatibility shim for replay subcommands")
    replay_sub = replay.add_subparsers(dest="replay_command", required=True)
    replay_sub.add_parser("status")
    replay_start = replay_sub.add_parser("start")
    replay_start.add_argument("--file", required=True)
    replay_start.add_argument("--mode", choices=["abstract", "raw"], default="abstract")
    replay_sub.add_parser("pause")
    replay_sub.add_parser("resume")
    replay_sub.add_parser("cancel")

    validate = subparsers.add_parser("validate", help="Compatibility shim for validation subcommands")
    validate_sub = validate.add_subparsers(dest="validate_command", required=True)
    validate_sub.add_parser("route-ops")

    new_scene = subparsers.add_parser("new-scene", help="Compatibility shim for scene scaffolding")
    new_scene.add_argument("--name", required=True)
    new_scene.add_argument("--id", required=True)
    new_scene.add_argument("--subfolder", required=True)
    new_scene.add_argument("--display-name", required=True)
    new_scene.add_argument("--base", choices=["Scene", "Canvas3D"], default="Scene")
    new_scene.add_argument("--camera", choices=["2d", "3d"], default="2d")
    new_scene.add_argument("--maven-profile", default="")
    new_scene.add_argument("--dry-run", action="store_true")


def _execute_registry_command(command: CliCommand, args: argparse.Namespace, client: AutomationClient) -> CliCommandResult:
    kwargs = {parameter.name: getattr(args, parameter.name) for parameter in command.params}
    if command.needs_client:
        result = command.function(client, **kwargs)
    else:
        result = command.function(**kwargs)
    if isinstance(result, CliCommandResult):
        return result
    return CliCommandResult(payload=result)


def _execute_compatibility_command(
    args: argparse.Namespace,
    parser: argparse.ArgumentParser,
    client: AutomationClient,
) -> CliCommandResult:
    if args.command_name == "record":
        if args.record_command == "start":
            return CliCommandResult(payload=client.request_json("/record/start", {}))
        if args.record_command == "status":
            return CliCommandResult(payload=client.request_json("/record/status"))
        return CliCommandResult(payload=client.request_json("/record/stop", {"path": args.path}))

    if args.command_name == "replay":
        if args.replay_command == "status":
            return CliCommandResult(payload=client.request_json("/replay/status"))
        if args.replay_command == "start":
            return CliCommandResult(payload=client.request_json("/replay/start", {"file": args.file, "mode": args.mode}))
        if args.replay_command == "pause":
            return CliCommandResult(payload=client.request_json("/replay/pause", {}))
        if args.replay_command == "resume":
            return CliCommandResult(payload=client.request_json("/replay/resume", {}))
        return CliCommandResult(payload=client.request_json("/replay/cancel", {}))

    if args.command_name == "validate":
        if args.validate_command == "route-ops":
            exit_code, payload = run_validation(args.base_url)
            return CliCommandResult(payload=payload, exit_code=exit_code)
        parser.print_help()
        return CliCommandResult(payload={"ok": False, "error": "Unknown validate command"}, exit_code=1)

    if args.command_name == "new-scene":
        return CliCommandResult(
            payload=scaffold_new_scene(
                name=args.name,
                scene_id=args.id,
                subfolder=args.subfolder,
                display_name=args.display_name,
                base=args.base,
                camera=args.camera,
                maven_profile=args.maven_profile,
                dry_run=args.dry_run,
            )
        )

    parser.print_help()
    return CliCommandResult(payload={"ok": False, "error": "Unknown command"}, exit_code=1)


def main(argv: list[str]) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    client = AutomationClient(args.base_url)

    try:
        registry = get_registry()
        if args.command_name in registry:
            command_result = _execute_registry_command(registry[args.command_name], args, client)
        else:
            command_result = _execute_compatibility_command(args, parser, client)
    except ValueError as exc:
        print(json.dumps({"ok": False, "error": str(exc)}))
        return 6
    except urllib.error.HTTPError as exc:
        print(json.dumps({"ok": False, "error": f"HTTP {exc.code}"}))
        return 2
    except urllib.error.URLError as exc:
        print(json.dumps({"ok": False, "error": str(exc.reason)}))
        return 3

    print(json.dumps(command_result.payload, indent=2))
    return command_result.exit_code


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
