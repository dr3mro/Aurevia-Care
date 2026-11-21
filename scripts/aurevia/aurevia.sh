#!/usr/bin/env bash
# aurevia — Aurevia Care developer tool.
#
# Builds, runs, checks and monitors the Aurevia Care platform from one command.
# Install it with install.sh (see the repository root); it then lives on your
# PATH as `aurevia`. Run `aurevia help` for the command list.
#
# Usage: aurevia <command> [options]
#
# Container (one image: staff web on 3000, API on 8080, PostgreSQL inside)
#   build [tag]        Build the image from Dockerfile.app (default aurevia-app:latest).
#   run [tag]          Create and start the container (pulls the image if it is missing).
#   stop               Stop the container. With persistence the database is saved first.
#   restart            Stop and start again.
#   delete             Remove the container (asks first). Saved databases are kept.
#   status             State, image, ports and health.
#   logs [N]           Show the last N log lines (default 100) and follow them.
#   shell              Open a shell inside the container.
#   psql               Open psql on the in-container database.
#   dump [file]        Write a SQL dump of the running database (default aurevia-<date>.sql).
#   restore <file>     Use a dump as the saved database, then restart so it is loaded.
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
#   --name NAME        Container name (default aurevia).
#   --image NAME       Image for `run` and `build` (default ghcr.io/dr3mro/aurevia-care-app:latest;
#                      `build` tags aurevia-app:latest and `run` prefers it when it exists).
#   --web-port N       Host port for the staff web (default 3000).
#   --api-port N       Host port for the API (default 8080).
#   -p, --persist      Save the database on stop and restore it on start (default off:
#                      the database is in memory and starts empty).
#   --no-persist       Turn persistence off for this run.
#   -n, --dry-run      Print the commands instead of running them.
#   -y, --yes          Do not ask before destructive actions.
#   -h, --help         Show this text.
#
# Environment
#   AUREVIA_REPO       Same as --repo.
#   AUREVIA_IMAGE, AUREVIA_NAME, AUREVIA_WEB_PORT, AUREVIA_API_PORT, AUREVIA_PERSIST   Same as the options.
#   AUREVIA_DATA_DIR   Where saved databases live (default ~/.local/share/aurevia/dumps).
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

IMAGE=${AUREVIA_IMAGE:-}
NAME=${AUREVIA_NAME:-aurevia}
WEB_PORT=${AUREVIA_WEB_PORT:-3000}
API_PORT=${AUREVIA_API_PORT:-8080}
PERSIST=${AUREVIA_PERSIST:-0}
DATA_DIR=${AUREVIA_DATA_DIR:-$HOME/.local/share/aurevia/dumps}
REMOTE_IMAGE=ghcr.io/dr3mro/aurevia-care-app:latest
LOCAL_IMAGE=aurevia-app:latest
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
  if [[ $DRY -eq 1 ]]; then printf '%s+ %s%s\n' "$C_DIM" "$*" "$C_OFF" >&2; else "$@"; fi
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

image_ref() {
  [[ -n "$IMAGE" ]] && { printf '%s\n' "$IMAGE"; return; }
  if docker image inspect "$LOCAL_IMAGE" >/dev/null 2>&1; then printf '%s\n' "$LOCAL_IMAGE"; else printf '%s\n' "$REMOTE_IMAGE"; fi
}

exists()  { docker container inspect "$NAME" >/dev/null 2>&1; }
running() { [[ "$(docker container inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" == true ]]; }

cmd_build() {
  need_repo; need docker
  local tag=${1:-$LOCAL_IMAGE}
  [[ -f "$ROOT/Dockerfile.app" ]] || die "Dockerfile.app not found in $ROOT"
  step "building $tag (staff web, API and PostgreSQL in one image)"
  run docker build -f "$ROOT/Dockerfile.app" -t "$tag" \
    --build-arg "VERSION=$(git -C "$ROOT" describe --always --dirty 2>/dev/null || echo dev)" \
    --build-arg "REVISION=$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)" "$ROOT"
  ok "built $tag"
}

cmd_run() {
  need docker
  [[ -n "${1:-}" ]] && IMAGE=$1
  if exists; then
    if running; then ok "already running: web http://127.0.0.1:$WEB_PORT"; return 0; fi
    step "starting the existing container $NAME"
    run docker start "$NAME" >/dev/null
  else
    local image data
    image=$(image_ref)
    docker image inspect "$image" >/dev/null 2>&1 || { step "pulling $image"; run docker pull "$image"; }
    data=$DATA_DIR/$NAME
    mkdir -p "$data"; chmod 777 "$data"
    step "creating $NAME from $image (persistence $([[ $PERSIST == 1 ]] && echo on || echo off))"
    run docker run -d --name "$NAME" --stop-timeout 60 \
      -p "127.0.0.1:$WEB_PORT:3000" -p "127.0.0.1:$API_PORT:8080" \
      --tmpfs /pgdata:rw,size=1g,uid=70,gid=70,mode=0700 \
      -v "$data:/dumps" -e "AUREVIA_PERSIST=$PERSIST" "$image" >/dev/null
  fi
  ok "started: web http://127.0.0.1:$WEB_PORT · API http://127.0.0.1:$API_PORT"
}

cmd_stop() {
  need docker
  exists || die "container $NAME does not exist"
  step "stopping $NAME (a persistent database is saved first)"
  run docker stop "$NAME" >/dev/null
  ok "stopped"
}

cmd_restart() { cmd_stop; cmd_run; }

cmd_delete() {
  need docker
  exists || die "container $NAME does not exist"
  confirm "Remove the container $NAME? (saved databases in $DATA_DIR/$NAME are kept)" || die "cancelled"
  run docker rm -f "$NAME" >/dev/null
  ok "removed"
}

cmd_status() {
  need docker
  exists || { echo "$NAME: not created (aurevia run)"; return 0; }
  docker container inspect -f '{{.Name}}: {{.State.Status}} · image {{.Config.Image}} · health {{if .State.Health}}{{.State.Health.Status}}{{else}}n/a{{end}}' "$NAME"
  docker port "$NAME" 2>/dev/null || true
  [[ -f "$DATA_DIR/$NAME/persist.sql" ]] && echo "saved database: $DATA_DIR/$NAME/persist.sql"
  return 0
}

cmd_logs()  { need docker; exists || die "container $NAME does not exist"; run docker logs --tail "${1:-100}" -f "$NAME"; }
cmd_shell() { need docker; running || die "$NAME is not running (aurevia run)"; run docker exec -it "$NAME" sh; }
cmd_psql()  { need docker; running || die "$NAME is not running (aurevia run)"; run docker exec -it "$NAME" psql -h 127.0.0.1 -U postgres -d smartclinic; }

cmd_dump() {
  need docker; running || die "$NAME is not running (aurevia run)"
  local out=${1:-aurevia-$(date +%Y%m%d-%H%M%S).sql}
  run docker exec "$NAME" pg_dump -h 127.0.0.1 -U postgres smartclinic > "$out"
  ok "wrote $out"
}

cmd_restore() {
  need docker
  local file=${1:-}
  [[ -n "$file" && -f "$file" ]] || die "usage: $PROG restore <dump.sql>"
  confirm "Replace the saved database with $file and restart?" || die "cancelled"
  mkdir -p "$DATA_DIR/$NAME"; chmod 777 "$DATA_DIR/$NAME"
  [[ -f "$DATA_DIR/$NAME/persist.sql" ]] && cp "$DATA_DIR/$NAME/persist.sql" "$DATA_DIR/$NAME/persist.sql.prev"
  cp "$file" "$DATA_DIR/$NAME/persist.sql"
  PERSIST=1
  if exists; then running && cmd_stop; run docker rm -f "$NAME" >/dev/null; fi
  cmd_run
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
  local words="build run stop restart delete status logs shell psql dump restore check test monitor todo doctor version completion install uninstall help"
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
    --name)   NAME=${2:?--name needs a value}; shift 2 ;;
    --web-port) WEB_PORT=${2:?--web-port needs a number}; shift 2 ;;
    --api-port) API_PORT=${2:?--api-port needs a number}; shift 2 ;;
    -p|--persist) PERSIST=1; shift ;;
    --no-persist) PERSIST=0; shift ;;
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
  build|run|stop|restart|delete|status|logs|shell|psql|dump|restore|check|test|monitor|todo|doctor|version|completion|install|uninstall)
    "cmd_$command" "$@" ;;
  help) help_text ;;
  *) die "unknown command '$command' (see: $PROG help)" ;;
esac
