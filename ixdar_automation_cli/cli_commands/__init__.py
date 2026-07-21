"""Command modules for the Ixdar automation CLI.

Every module in this package is imported by :func:`import_all_commands`, so a new command is added by
dropping a new module here with an ``@cli_command``-decorated function — there is no list to update.
"""

import importlib
import pkgutil

_IMPORTED = False


def import_all_commands() -> None:
    """Import every module in this package so ``@cli_command`` decorators self-register.

    Import failures propagate: a module that cannot be imported is a command silently missing from
    the CLI, which is worse than a traceback.
    """
    global _IMPORTED
    if _IMPORTED:
        return
    _IMPORTED = True

    for module_info in pkgutil.walk_packages(__path__, prefix=f"{__name__}."):
        if module_info.name.rpartition(".")[2].startswith("_"):
            continue
        importlib.import_module(module_info.name)
