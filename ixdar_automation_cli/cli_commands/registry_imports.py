"""Deterministic command imports for registry-backed CLI startup."""

_COMMAND_MODULES_IMPORTED = False


def import_all_commands() -> None:
    """Import command modules once so decorators populate the registry."""
    global _COMMAND_MODULES_IMPORTED
    if _COMMAND_MODULES_IMPORTED:
        return

    try:
        from . import flat_commands, scenario_commands  # noqa: F401
    except ImportError:
        from cli_commands import flat_commands, scenario_commands  # noqa: F401

    _COMMAND_MODULES_IMPORTED = True
