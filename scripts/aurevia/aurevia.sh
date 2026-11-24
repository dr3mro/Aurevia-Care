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
#                      A tag such as v0.0.2 selects that release of the image; a name with / or : is a full image.
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
#   --runtime RT       container (Apple) or docker. Default: container when installed, else docker.
#   --name NAME        Container name (default aurevia).
#   --image NAME       Image for `run` and `build` (default ghcr.io/dr3mro/aurevia-care-app:latest;
#                      `build` tags aurevia-app:latest and `run` prefers it when it exists).
#   --web-port N       Host port for the staff web (default 3000).
#   --api-port N       Host port for the API (default 8080).
#   -p, --persist      Save the database on stop and restore it on start (default off:
#                      the database is in memory and starts empty).
#   --no-persist       Turn persistence off for this run.
#   --memory GB        Memory for the container (default 3; Apple container defaults to 1, too little).
#   --cpus N           CPUs for the container (default 2).
#   -n, --dry-run      Print the commands instead of running them.
#   -y, --yes          Do not ask before destructive actions.
#   -h, --help         Show this text.
#
# Environment
#   AUREVIA_REPO       Same as --repo.
#   AUREVIA_IMAGE, AUREVIA_NAME, AUREVIA_WEB_PORT, AUREVIA_API_PORT, AUREVIA_PERSIST   Same as the options.
#   AUREVIA_RUNTIME, AUREVIA_MEMORY_GB, AUREVIA_CPUS   Same as the options.
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
RT=${AUREVIA_RUNTIME:-}
MEMORY_GB=${AUREVIA_MEMORY_GB:-3}
CPUS=${AUREVIA_CPUS:-2}
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

pick_runtime() {
  if [[ -z "$RT" ]]; then
    if command -v container >/dev/null 2>&1; then RT=container   # Apple's runtime wins when installed.
    else RT=docker; fi
  fi
  case $RT in
    docker|container) ;;
    *) die "unknown runtime '$RT' (docker or container)" ;;
  esac
  need "$RT"
  if [[ $RT == container ]]; then
    need python3   # container inspect prints JSON; python3 reads it.
    if ! container system status >/dev/null 2>&1; then
      step "starting the Apple container service"
      run container system start
    fi
  fi
}

rt() { if [[ $RT == docker ]]; then docker "$@"; else container "$@"; fi; }

# Read one field of `container inspect` (JSON) with a python expression over d.
apple_inspect() {
  container inspect "$NAME" 2>/dev/null | python3 -c '
import json, sys
d = json.load(sys.stdin)
d = d[0] if isinstance(d, list) else d
print(eval(sys.argv[1]))' "$1" 2>/dev/null
}

exists() { rt inspect "$NAME" >/dev/null 2>&1; }

state() {
  if [[ $RT == docker ]]; then
    docker container inspect -f '{{.State.Status}}' "$NAME" 2>/dev/null
  else
    apple_inspect '(lambda s: s if isinstance(s, str) else s.get("state", ""))(d.get("status", ""))'
  fi
}

running() { [[ "$(state)" == running ]]; }

image_exists() { rt image inspect "$1" >/dev/null 2>&1; }

image_ref() {
  [[ -n "$IMAGE" ]] && { printf '%s\n' "$IMAGE"; return; }
  if image_exists "$LOCAL_IMAGE"; then printf '%s\n' "$LOCAL_IMAGE"; else printf '%s\n' "$REMOTE_IMAGE"; fi
}

pull_image() { if [[ $RT == docker ]]; then run docker pull "$1"; else run container image pull "$1"; fi; }

cmd_build() {
  need_repo; pick_runtime
  local tag=${1:-$LOCAL_IMAGE}
  [[ -f "$ROOT/Dockerfile.app" ]] || die "Dockerfile.app not found in $ROOT"
  if [[ $RT == container ]]; then
    # The default builder has little memory; the Vite and Maven builds need more.
    run container builder start --cpus 4 --memory 8g >/dev/null 2>&1 || true
  fi
  step "building $tag with $RT (staff web, API and PostgreSQL in one image)"
  run rt build -f "$ROOT/Dockerfile.app" -t "$tag" \
    --build-arg "VERSION=$(git -C "$ROOT" describe --always --dirty 2>/dev/null || echo dev)" \
    --build-arg "REVISION=$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)" "$ROOT"
  ok "built $tag"
}

cmd_run() {
  pick_runtime
  if [[ -n "${1:-}" ]]; then
    # `run v0.0.2` names a tag of the release image; a name with / or : is a full image.
    case $1 in */*|*:*) IMAGE=$1 ;; *) IMAGE=${REMOTE_IMAGE%:*}:$1 ;; esac
  fi
  if exists; then
    if running; then ok "already running: web http://127.0.0.1:$WEB_PORT"; return 0; fi
    warn "$NAME already exists; its image, ports and persistence setting are kept (use delete to change them)"
    step "starting the existing container $NAME"
    run rt start "$NAME" >/dev/null
  else
    local image data
    image=$(image_ref)
    image_exists "$image" || { step "pulling $image"; pull_image "$image"; }
    data=$DATA_DIR/$NAME
    mkdir -p "$data"; chmod 777 "$data"
    local -a args=(-d --name "$NAME"
      -p "127.0.0.1:$WEB_PORT:3000" -p "127.0.0.1:$API_PORT:8080"
      -v "$data:/dumps" -e "AUREVIA_PERSIST=$PERSIST")
    if [[ $RT == docker ]]; then
      # The database lives in a memory-backed directory; Apple container has no tmpfs option, so
      # there the database sits on the container's own disk and is wiped at every start.
      args+=(--cpus "$CPUS" --memory "${MEMORY_GB}g" --tmpfs /pgdata:rw,size=1g,uid=70,gid=70,mode=0700)
    else
      args+=(-c "$CPUS" -m "${MEMORY_GB}G")
    fi
    step "creating $NAME from $image with $RT (persistence $([[ $PERSIST == 1 ]] && echo on || echo off))"
    run rt run "${args[@]}" "$image" >/dev/null
  fi
  ok "started: web http://127.0.0.1:$WEB_PORT · API http://127.0.0.1:$API_PORT"
}

cmd_stop() {
  pick_runtime
  exists || die "container $NAME does not exist"
  step "stopping $NAME (a persistent database is saved first)"
  run rt stop -t 60 "$NAME" >/dev/null
  ok "stopped"
}

cmd_restart() { cmd_stop; cmd_run; }

remove_container() { if [[ $RT == docker ]]; then run docker rm "$NAME" >/dev/null; else run container delete "$NAME" >/dev/null; fi; }

cmd_delete() {
  pick_runtime
  exists || die "container $NAME does not exist"
  confirm "Remove the container $NAME? (saved databases in $DATA_DIR/$NAME are kept)" || die "cancelled"
  running && { step "stopping $NAME first (a persistent database is saved)"; run rt stop -t 60 "$NAME" >/dev/null; }
  remove_container
  ok "removed"
}

cmd_status() {
  pick_runtime
  exists || { echo "$NAME: not created (aurevia run)"; return 0; }
  if [[ $RT == docker ]]; then
    docker container inspect -f '{{.Name}}: {{.State.Status}} · image {{.Config.Image}} · health {{if .State.Health}}{{.State.Health.Status}}{{else}}n/a{{end}}' "$NAME"
    docker port "$NAME" 2>/dev/null || true
  else
    echo "$NAME: $(state) · runtime container · web http://127.0.0.1:$WEB_PORT · API http://127.0.0.1:$API_PORT"
  fi
  [[ -f "$DATA_DIR/$NAME/persist.sql" ]] && echo "saved database: $DATA_DIR/$NAME/persist.sql"
  return 0
}

cmd_logs() {
  pick_runtime; exists || die "container $NAME does not exist"
  if [[ $RT == docker ]]; then run docker logs --tail "${1:-100}" -f "$NAME"; else run container logs -f -n "${1:-100}" "$NAME"; fi
}

cmd_shell() { pick_runtime; running || die "$NAME is not running (aurevia run)"; run rt exec -it "$NAME" sh; }
cmd_psql()  { pick_runtime; running || die "$NAME is not running (aurevia run)"; run rt exec -it "$NAME" psql -h 127.0.0.1 -U postgres -d aurevia; }

cmd_dump() {
  pick_runtime; running || die "$NAME is not running (aurevia run)"
  local out=${1:-aurevia-$(date +%Y%m%d-%H%M%S).sql}
  run rt exec "$NAME" pg_dump -h 127.0.0.1 -U postgres aurevia > "$out"
  ok "wrote $out"
}

cmd_restore() {
  pick_runtime
  local file=${1:-}
  [[ -n "$file" && -f "$file" ]] || die "usage: $PROG restore <dump.sql>"
  confirm "Replace the saved database with $file and restart?" || die "cancelled"
  mkdir -p "$DATA_DIR/$NAME"; chmod 777 "$DATA_DIR/$NAME"
  [[ -f "$DATA_DIR/$NAME/persist.sql" ]] && cp "$DATA_DIR/$NAME/persist.sql" "$DATA_DIR/$NAME/persist.sql.prev"
  cp "$file" "$DATA_DIR/$NAME/persist.sql"
  PERSIST=1
  if exists; then running && cmd_stop; remove_container; fi
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
  for tool in git bash python3 curl; do
    if command -v "$tool" >/dev/null 2>&1; then ok "$tool"; else warn "$tool is missing"; missing=1; fi
  done
  if command -v container >/dev/null 2>&1; then ok "container (Apple)"; elif command -v docker >/dev/null 2>&1; then ok docker; else warn "neither container nor docker is installed"; missing=1; fi
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
    --runtime) RT=${2:?--runtime needs docker or container}; shift 2 ;;
    --memory) MEMORY_GB=${2:?--memory needs a number}; shift 2 ;;
    --cpus)   CPUS=${2:?--cpus needs a number}; shift 2 ;;
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
