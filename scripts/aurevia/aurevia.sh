#!/usr/bin/env bash
# aurevia — Aurevia Care developer tool.
#
# Builds, runs, checks and monitors the Aurevia Care platform from one command.
# Install it with install.sh (see the repository root); it then lives on your
# PATH as `aurevia`. Run `aurevia help` for the command list.
#
# Usage: aurevia <command> [options]
#
# Container
#   build [tag]        Build the application image (default tag smartclinic:test).
#   up                 Start the application with Docker Compose.
#   down               Stop and remove the application container.
#   restart            Restart the application container.
#   status             Show the container state, image and port.
#   logs [N]           Show the last N log lines (default 100) and follow them.
#   shell              Open a shell inside the running container.
#
# Quality gates
#   check [quick|worker|full]
#                      quick  : scripts/quick-check.sh (fast gate, default)
#                      worker : scripts/worker-check.sh (adds PostgreSQL, frontend, SpotBugs)
#                      full   : apps/api/check.sh (complete Java gate with coverage)
#   test [api|web|mobile]
#                      Run one application's tests.
#
# Swarm
#   monitor [args]     Open the swarm dashboard (scripts/monitor.sh).
#   todo               Count open, taken and done tasks in TODO.md.
#
# Tool
#   doctor             Check that the required tools are installed.
#   version            Print the tool and repository versions.
#   completion [bash|zsh]
#                      Print the shell completion script.
#   install            Update this tool to the latest version.
#   uninstall          Remove this tool.
#   help               Show this text.
#
# Options
#   --repo DIR         Repository checkout to work on (default: auto-detected).
#   --image NAME       Image name for `up` and `build` (default smartclinic:test).
#   -n, --dry-run      Print the commands instead of running them.
#   -y, --yes          Do not ask before destructive actions.
#   -h, --help         Show this text.
#
# Environment
#   AUREVIA_REPO       Same as --repo.
#   AUREVIA_IMAGE      Same as --image.
#   NO_COLOR           Turn colours off.

set -eo pipefail

PROG=$(basename "$0")
SELF=$0
# Resolve symlinks so the tool finds its own directory when run through ~/.local/bin.
while [[ -L "$SELF" ]]; do
  link=$(readlink "$SELF")
  case $link in /*) SELF=$link ;; *) SELF=$(dirname "$SELF")/$link ;; esac
done
HOME_DIR=$(cd "$(dirname "$SELF")" && pwd)

IMAGE=${AUREVIA_IMAGE:-smartclinic:test}
REPO=${AUREVIA_REPO:-}
DRY=0
YES=0

# ---- output ---------------------------------------------------------------
if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
  C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_DIM=$'\033[2m'; C_OFF=$'\033[0m'
else
  C_RED=; C_GRN=; C_YEL=; C_DIM=; C_OFF=
fi
die()  { printf '%serror:%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; exit 1; }
warn() { printf '%swarning:%s %s\n' "$C_YEL" "$C_OFF" "$*" >&2; }
ok()   { printf '%s✔%s %s\n' "$C_GRN" "$C_OFF" "$*"; }
step() { printf '%s▸%s %s\n' "$C_DIM" "$C_OFF" "$*"; }

help_text() { sed -n '2,/^$/p' "$SELF" | sed 's/^# \{0,1\}//;/^!/d'; }

run() {
  if [[ $DRY -eq 1 ]]; then printf '%s+ %s%s\n' "$C_DIM" "$*" "$C_OFF"; else "$@"; fi
}

confirm() {
  [[ $YES -eq 1 ]] && return 0
  [[ -t 0 ]] || die "$1 (use -y to confirm without a prompt)"
  printf '%s [y/N] ' "$1"
  read -r answer
  [[ $answer == y || $answer == Y ]]
}

# ---- repository -----------------------------------------------------------
find_repo() {
  local dir
  for dir in "$REPO" "$PWD" "$HOME_DIR/../.." ; do
    [[ -n "$dir" && -f "$dir/compose.yaml" && -f "$dir/TODO.md" ]] && { (cd "$dir" && pwd); return 0; }
  done
  if [[ -f "$HOME_DIR/repo" ]]; then
    dir=$(cat "$HOME_DIR/repo")
    [[ -f "$dir/compose.yaml" ]] && { printf '%s\n' "$dir"; return 0; }
  fi
  return 1
}

need_repo() {
  ROOT=$(find_repo) || die "no Aurevia Care checkout found. Run from the repository, or use --repo DIR"
}

need() { command -v "$1" >/dev/null 2>&1 || die "$1 is required but not installed"; }

compose() {
  need docker
  SMARTCLINIC_IMAGE=$IMAGE run docker compose -f "$ROOT/compose.yaml" "$@"
}

# ---- commands -------------------------------------------------------------
cmd_build() {
  need_repo; need docker
  local tag=${1:-$IMAGE}
  step "building $tag"
  run docker build -t "$tag" "$ROOT"
  ok "built $tag"
}

cmd_up() {
  need_repo
  step "starting $IMAGE"
  compose up -d
  ok "up: http://127.0.0.1:8080 (image $IMAGE)"
}

cmd_down() {
  need_repo
  confirm "Stop and remove the Aurevia Care container?" || die "cancelled"
  compose down
  ok "stopped"
}

cmd_restart() { need_repo; compose restart; ok "restarted"; }

cmd_status() {
  need_repo; need docker
  docker compose -f "$ROOT/compose.yaml" ps
}

cmd_logs() {
  need_repo
  compose logs --tail "${1:-100}" -f
}

cmd_shell() {
  need_repo; need docker
  local id
  id=$(docker compose -f "$ROOT/compose.yaml" ps -q app)
  [[ -n "$id" ]] || die "the application is not running (aurevia up)"
  run docker exec -it "$id" sh
}

cmd_check() {
  need_repo
  case ${1:-quick} in
    quick)  run bash "$ROOT/scripts/quick-check.sh" ;;
    worker) run bash "$ROOT/scripts/worker-check.sh" ;;
    full)   run bash "$ROOT/apps/api/check.sh" ;;
    *) die "unknown check '$1' (quick, worker or full)" ;;
  esac
}

cmd_test() {
  need_repo
  case ${1:-api} in
    api)    run bash "$ROOT/apps/api/check.sh" ;;
    web)    run bash "$ROOT/apps/staff-web/check.sh" ;;
    mobile) run bash "$ROOT/apps/patient-mobile/check.sh" ;;
    *) die "unknown target '$1' (api, web or mobile)" ;;
  esac
}

cmd_monitor() { need_repo; run bash "$ROOT/scripts/monitor.sh" "$@"; }

cmd_todo() {
  need_repo
  local open taken done_
  open=$(grep -c '^- \[ \]' "$ROOT/TODO.md" || true)
  taken=$(grep -c '^- \[-\]' "$ROOT/TODO.md" || true)
  done_=$(grep -c '^- \[x\]' "$ROOT/TODO.md" || true)
  printf 'open %s · taken %s · done %s\n' "$open" "$taken" "$done_"
  if [[ -f "$ROOT/docs/archive/TODO-DONE.md" ]]; then
    printf 'archived done %s\n' "$(grep -c '^- \[x\]' "$ROOT/docs/archive/TODO-DONE.md" || true)"
  fi
}

cmd_doctor() {
  local missing=0 tool
  for tool in git bash python3 docker curl; do
    if command -v "$tool" >/dev/null 2>&1; then ok "$tool"; else warn "$tool is missing"; missing=1; fi
  done
  for tool in gh node npm mvn; do
    if command -v "$tool" >/dev/null 2>&1; then ok "$tool"; else printf '%s- %s not found (optional)%s\n' "$C_DIM" "$tool" "$C_OFF"; fi
  done
  if find_repo >/dev/null; then ok "repository: $(find_repo)"; else warn "no checkout found (use --repo DIR)"; fi
  return $missing
}

cmd_version() {
  local v=unknown
  [[ -f "$HOME_DIR/VERSION" ]] && v=$(cat "$HOME_DIR/VERSION")
  printf 'aurevia tool %s\n' "$v"
  if find_repo >/dev/null 2>&1; then
    printf 'repository %s\n' "$(git -C "$(find_repo)" describe --always --dirty 2>/dev/null || echo unknown)"
  fi
}

cmd_completion() {
  local words="build up down restart status logs shell check test monitor todo doctor version completion install uninstall help"
  case ${1:-bash} in
    bash) printf 'complete -W "%s" aurevia\n' "$words" ;;
    zsh)  printf 'autoload -Uz compinit && compinit\ncompdef "_arguments '"'"'1:command:(%s)'"'"'" aurevia\n' "$words" ;;
    *) die "unknown shell '$1' (bash or zsh)" ;;
  esac
}

cmd_install()   { [[ -x "$HOME_DIR/installer" ]] || die "no installer next to the tool; re-run install.sh"; exec "$HOME_DIR/installer" --replace "$@"; }
cmd_uninstall() { [[ -x "$HOME_DIR/installer" ]] || die "no installer next to the tool"; confirm "Remove the aurevia tool?" || die "cancelled"; exec "$HOME_DIR/installer" --uninstall; }

# ---- arguments ------------------------------------------------------------
ARGS=()
while [[ $# -gt 0 ]]; do
  case $1 in
    --repo)   REPO=${2:?--repo needs a directory}; shift 2 ;;
    --image)  IMAGE=${2:?--image needs a name}; shift 2 ;;
    -n|--dry-run) DRY=1; shift ;;
    -y|--yes) YES=1; shift ;;
    -h|--help) help_text; exit 0 ;;
    *) ARGS+=("$1"); shift ;;
  esac
done
set -- "${ARGS[@]}"

[[ $# -gt 0 ]] || { help_text; exit 0; }
command=$1; shift
case $command in
  build|up|down|restart|status|logs|shell|check|test|monitor|todo|doctor|version|completion|install|uninstall)
    "cmd_$command" "$@" ;;
  help) help_text ;;
  *) die "unknown command '$command' (see: $PROG help)" ;;
esac
