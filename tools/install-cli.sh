#!/usr/bin/env bash
# Install a global `ixdar-cli` wrapper into ~/.local/bin. Reproducible on a clean clone:
#   git clone ... && cd Ixdar && uv sync && bash tools/install-cli.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL_BIN="$HOME/.local/bin"
WRAPPER="$LOCAL_BIN/ixdar-cli"

mkdir -p "$LOCAL_BIN"
cat > "$WRAPPER" <<EOF
#!/usr/bin/env bash
exec uv run --project "$REPO_ROOT" ixdar-cli "\$@"
EOF
chmod +x "$WRAPPER"

echo "Installed $WRAPPER -> uv run --project $REPO_ROOT ixdar-cli"

case ":$PATH:" in
  *":$LOCAL_BIN:"*) ;;
  *) echo "WARNING: $LOCAL_BIN is not on your PATH. Add this to your shell rc:"
     echo "    export PATH=\"$LOCAL_BIN:\$PATH\"" ;;
esac
