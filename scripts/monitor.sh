#!/usr/bin/env bash
# monitor — live, colorful dashboard for issues, PRs and workflow runs.
# Usage:  bash monitor [--once] [--interval SECONDS]
set -u

INTERVAL=30
ONCE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --once) ONCE=1; shift ;;
    --interval) INTERVAL="$2"; shift 2 ;;
    -h|--help) echo "Usage: bash monitor [--once] [--interval SECONDS]"; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

# ---- colors ----
RST='\033[0m'
BOLD='\033[1m'
DIM='\033[2m'
RED='\033[31m'
GREEN='\033[32m'
YELLOW='\033[33m'
BLUE='\033[34m'
MAGENTA='\033[35m'
CYAN='\033[36m'
WHITE='\033[37m'

color_state() {
  case "$1" in
    success|completed|merged) printf '%s' "$GREEN" ;;
    failure|failed) printf '%s' "$RED" ;;
    in_progress|queued|pending|running) printf '%s' "$YELLOW" ;;
    cancelled|skipped) printf '%s' "$DIM" ;;
    *) printf '%s' "$WHITE" ;;
  esac
}

play_sound() {
  # macOS
  if command -v afplay >/dev/null 2>&1; then
    afplay /System/Library/Sounds/Glass.aiff >/dev/null 2>&1 &
  elif command -v paplay >/dev/null 2>&1; then
    paplay /usr/share/sounds/freedesktop/stereo/complete.oga >/dev/null 2>&1 &
  else
    printf '\a'
  fi
}

need_gh() {
  if ! command -v gh >/dev/null 2>&1; then
    echo "gh CLI not found. Install: https://cli.github.com" >&2
    exit 1
  fi
  if ! gh auth status >/dev/null 2>&1; then
    echo "gh not authenticated. Run: gh auth login" >&2
    exit 1
  fi
}

render() {
  clear 2>/dev/null || true
  local now repo
  now=$(date '+%Y-%m-%d %H:%M')
  repo=$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || echo 'repo')
  local W=66
  local t1="  🔭  ${repo} GitHub Monitor"
  local t2="  ${now}"
  local pad1=$((W - ${#t1} - 1)); [[ $pad1 -lt 0 ]] && pad1=0
  local pad2=$((W - ${#t2} - 1)); [[ $pad2 -lt 0 ]] && pad2=0
  echo -e "${BOLD}${CYAN}+$(printf '=%.0s' $(seq 1 $W))+${RST}"
  echo -e "${BOLD}${CYAN}${t1}${RST}"
  echo -e "${DIM}  ${t2}${RST}"
  echo -e "${BOLD}${CYAN}+$(printf '=%.0s' $(seq 1 $W))+${RST}"

  echo -e "${BOLD}${MAGENTA}📋 Open Issues${RST}"
  echo -e "${DIM}──────────────────────────────────────────────────────────────────────${RST}"
  local issues
  issues=$(gh issue list --state open --limit 20 --json number,title,labels,url 2>/dev/null)
  if [[ -z "$issues" || "$issues" == "[]" ]]; then
    echo -e "  ${GREEN}✅ None — all clear${RST}"
  else
    echo "$issues" | python3 -c "
import json,sys
d=json.load(sys.stdin)
for i in d:
    labels=','.join(l['name'] for l in i.get('labels',[]))
    print(f\"  {i['number']:>4}  {i['title'][:70]}\")
    print(f\"       [{labels}]\")
    print(f\"       {i.get('url','')}\")
    print()
"
  fi
  echo

  # ---- PRs ----
  echo -e "${BOLD}${BLUE}🔀 Open Pull Requests${RST}"
  echo -e "${DIM}──────────────────────────────────────────────────────────────────────${RST}"
  local prs
  prs=$(gh pr list --state open --limit 20 --json number,title,isDraft,state,url 2>/dev/null)
  if [[ -z "$prs" || "$prs" == "[]" ]]; then
    echo -e "  ${GREEN}✅ None${RST}"
  else
    echo "$prs" | python3 -c "
import json,sys
d=json.load(sys.stdin)
for p in d:
    draft='📝 DRAFT' if p.get('isDraft') else '✅ ready'
    print(f\"  {p['number']:>4}  {p['title'][:55]:55}  {draft}\")
    print(f\"       {p.get('url','')}\")
"
  fi
  echo

  # ---- Workflow runs ----
  echo -e "${BOLD}${YELLOW}⚙️  Recent Workflow Runs${RST}"
  echo -e "${DIM}──────────────────────────────────────────────────────────────────────${RST}"
  local runs
  runs=$(gh run list --limit 30 --json databaseId,workflowName,conclusion,status,headSha,createdAt,url 2>/dev/null)
  if [[ -z "$runs" || "$runs" == "[]" ]]; then
    echo -e "  ${DIM}(none)${RST}"
  else
    echo "$runs" | python3 -c "
import json,sys
d=json.load(sys.stdin)
for r in d:
    st = r.get('conclusion') or r.get('status') or ''
    col = {'success':'\033[32m','failure':'\033[31m','completed':'\033[32m','in_progress':'\033[33m','queued':'\033[33m','cancelled':'\033[2m','skipped':'\033[2m'}.get(st,'\033[37m')
    emoji = {'success':'✅','failure':'❌','in_progress':'🔄','queued':'⏳','cancelled':'⚪','skipped':'⏭️'}.get(st,'•')
    print(f\"  {col}{emoji}  {r['databaseId']}  {r['workflowName'][:26]:26}  {st:12}  {r['headSha'][:8]}\033[0m\")
    print(f\"       {r.get('url','')}\")
"
  fi
  echo
  echo -e "${DIM}Refresh every ${INTERVAL}s. Ctrl+C to exit. (--once for single shot)${RST}"
}

need_gh

# track previous state to play sound on new failures
PREV=""
while true; do
  render
  CUR=$(gh run list --limit 7 --json databaseId,conclusion 2>/dev/null)
  if [[ "$CUR" != "$PREV" ]]; then
    # play sound if any new failure
    if echo "$CUR" | grep -q '"conclusion":"failure"'; then
      play_sound
    fi
    PREV="$CUR"
  fi
  [[ $ONCE -eq 1 ]] && break
  sleep "$INTERVAL"
done
