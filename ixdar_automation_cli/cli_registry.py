"""Decorator-driven command registry for the Ixdar automation CLI."""

from __future__ import annotations

import inspect
import re
from dataclasses import dataclass
from typing import Annotated, Any, Callable, Literal, get_args, get_origin


@dataclass(frozen=True)
class CliOption:
    """Additional argparse metadata for a CLI parameter."""

    choices: tuple[Any, ...] | None = None
    multiple: bool = False


@dataclass(frozen=True)
class CliParameter:
    """Parsed metadata for a single CLI command parameter."""

    name: str
    annotation: Any
    has_default: bool
    default: Any
    help_text: str
    cli_flag: str
    choices: tuple[Any, ...] | None = None
    multiple: bool = False


@dataclass(frozen=True)
class CliCommandResult:
    """Structured command return value with an optional non-zero exit code."""

    payload: dict
    exit_code: int = 0


@dataclass(frozen=True)
class CliCommand:
    """Metadata for a decorated CLI command."""

    name: str
    function: Callable[..., dict | CliCommandResult]
    summary: str
    docstring: str
    params: list[CliParameter]
    needs_client: bool = True


_REGISTRY: dict[str, CliCommand] = {}
_PARAM_RE = re.compile(r"^\s*:param\s+(\w+)\s*:\s*(.+)\s*$")
_BUILTIN_TYPE_NAMES = {"bool": bool, "str": str, "int": int, "float": float}


def _normalize_builtin_annotation(annotation: Any) -> Any:
    if annotation is inspect._empty:
        return str
    if isinstance(annotation, str):
        return _BUILTIN_TYPE_NAMES.get(annotation, str)
    return annotation


def _extract_cli_option(annotation: Any) -> tuple[Any, CliOption]:
    """Split Annotated metadata from the concrete parameter annotation."""
    normalized = _normalize_builtin_annotation(annotation)
    if get_origin(normalized) is not Annotated:
        return normalized, CliOption()

    args = get_args(normalized)
    base_annotation = args[0]
    option = CliOption()
    for metadata in args[1:]:
        if isinstance(metadata, CliOption):
            option = metadata
    return base_annotation, option


def _normalize_annotation(annotation: Any, option: CliOption) -> tuple[Any, tuple[Any, ...] | None, bool]:
    """Normalize annotations into argparse-friendly types and metadata."""
    normalized = _normalize_builtin_annotation(annotation)
    origin = get_origin(normalized)

    if origin is Literal:
        choices = tuple(get_args(normalized))
        if not choices:
            return str, option.choices, option.multiple
        parser_type = type(choices[0]) if len({type(choice) for choice in choices}) == 1 else str
        return parser_type, option.choices or choices, option.multiple

    if origin in {list, tuple}:
        args = get_args(normalized)
        inner = args[0] if args else str
        parser_type, choices, _ = _normalize_annotation(inner, option)
        return parser_type, option.choices or choices, True

    if origin is None:
        return normalized, option.choices, option.multiple

    args = [arg for arg in get_args(normalized) if arg is not type(None)]
    if len(args) == 1:
        return _normalize_annotation(args[0], option)
    return str, option.choices, option.multiple


def _parse_docstring(function: Callable[..., dict | CliCommandResult]) -> tuple[str, str, dict[str, str]]:
    """Extract a summary and :param docs from a command docstring."""
    doc = inspect.getdoc(function) or ""
    if not doc.strip():
        raise ValueError(
            f"CLI command '{function.__name__}' is missing a docstring. "
            "Add a summary and :param docs."
        )

    lines = doc.splitlines()
    summary = lines[0].strip()
    if not summary:
        raise ValueError(f"CLI command '{function.__name__}' has an empty docstring summary.")

    param_help: dict[str, str] = {}
    for line in lines:
        match = _PARAM_RE.match(line)
        if match:
            param_help[match.group(1)] = match.group(2).strip()

    return summary, doc, param_help


def cli_command(
    function: Callable[..., dict | CliCommandResult] | None = None,
    *,
    name: str | None = None,
) -> Callable[..., dict | CliCommandResult]:
    """Register a function as a CLI command."""

    def decorator(
        wrapped_function: Callable[..., dict | CliCommandResult],
    ) -> Callable[..., dict | CliCommandResult]:
        summary, docstring, param_help = _parse_docstring(wrapped_function)
        signature = inspect.signature(wrapped_function)
        params: list[CliParameter] = []

        first_param = next(iter(signature.parameters.values()), None)
        needs_client = first_param is not None and first_param.name == "client"

        for index, parameter in enumerate(signature.parameters.values()):
            if index == 0 and needs_client:
                continue

            base_annotation, option = _extract_cli_option(parameter.annotation)
            annotation, choices, multiple = _normalize_annotation(base_annotation, option)
            has_default = parameter.default is not inspect._empty
            default = parameter.default if has_default else None
            help_text = param_help.get(parameter.name, "")
            if not help_text:
                raise ValueError(
                    f"CLI command '{wrapped_function.__name__}' is missing ':param {parameter.name}:' documentation."
                )
            params.append(
                CliParameter(
                    name=parameter.name,
                    annotation=annotation,
                    has_default=has_default,
                    default=default,
                    help_text=help_text,
                    cli_flag=f"--{parameter.name.replace('_', '-')}",
                    choices=choices,
                    multiple=multiple,
                )
            )

        command_name = name or wrapped_function.__name__.replace("_", "-")
        if command_name in _REGISTRY:
            raise ValueError(f"CLI command '{command_name}' is already registered.")

        _REGISTRY[command_name] = CliCommand(
            name=command_name,
            function=wrapped_function,
            summary=summary,
            docstring=docstring,
            params=params,
            needs_client=needs_client,
        )
        return wrapped_function

    if function is not None:
        return decorator(function)
    return decorator


def get_registry() -> dict[str, CliCommand]:
    """Return all registered CLI commands."""
    return _REGISTRY.copy()
