#!/usr/bin/env python3
import argparse
import json
import sys
import urllib.error

try:
    from .automation_client import DEFAULT_BASE_URL, AutomationClient
    from .cli_registry import CliCommand, CliCommandResult, CliParameter, get_registry
    from .server_routes import add_server_parsers, dispatch_server_command, load_manifest, server_command_map
except ImportError:
    from automation_client import DEFAULT_BASE_URL, AutomationClient
    from cli_registry import CliCommand, CliCommandResult, CliParameter, get_registry
    from server_routes import add_server_parsers, dispatch_server_command, load_manifest, server_command_map


def _server_commands() -> dict:
    """Resolve the dynamic server-backed commands, skipping names owned by registry commands."""
    return server_command_map(load_manifest(), set(get_registry()))


def _build_parser() -> argparse.ArgumentParser:

    parser = argparse.ArgumentParser(
        prog="ixdar",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    subparsers = parser.add_subparsers(dest="command_name", required=True)

    for command_name, command in sorted(get_registry().items()):
        _add_command_parser(subparsers, command_name, command)

    add_server_parsers(subparsers, _server_commands())
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


def _execute_registry_command(command: CliCommand, args: argparse.Namespace, client: AutomationClient) -> CliCommandResult:
    kwargs = {parameter.name: getattr(args, parameter.name) for parameter in command.params}
    if command.needs_client:
        result = command.function(client, **kwargs)
    else:
        result = command.function(**kwargs)
    if isinstance(result, CliCommandResult):
        return result
    return CliCommandResult(payload=result)


def main(argv: list[str]) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    client = AutomationClient(args.base_url)

    try:
        registry = get_registry()
        server_commands = _server_commands()
        if args.command_name in registry:
            command_result = _execute_registry_command(registry[args.command_name], args, client)
        elif args.command_name in server_commands:
            command_result = dispatch_server_command(server_commands[args.command_name], args, client)
        else:
            parser.print_help()
            return 1
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


def cli() -> int:
    """Console-script entrypoint for installed package execution."""
    return main(sys.argv[1:])


if __name__ == "__main__":
    raise SystemExit(cli())
