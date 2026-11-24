#!/ usr / bin / env sh
#install.sh — install the Aurevia Care developer tool(`aurevia`).
#
#curl -                                                                        \
    fsSL https: // raw.githubusercontent.com/dr3mro/Aurevia-Care/main/install.sh
                // | sh
#
#Running it again updates the tool, as does `aurevia install`.
#
#Options
#-- ref REF Branch, tag or commit to install(default : main).
#-- dir DIR Install directory(default : ~/.local / share / aurevia).
#-- bin -                                                                      \
    dir DIR Where to link the `aurevia` command(default : ~/.local / bin).
#-- no - rc Do not edit ~/.zshrc, ~/.bashrc or ~/.bash_profile.
#-- uninstall Remove the tool(keeps nothing it did not create).
#-- replace Download first, then uninstall and install again.
#- h, -- help Show this text.
#
#Environment
#AUREVIA_REF, AUREVIA_INSTALL_DIR, AUREVIA_BIN_DIR Same as the options.
#AUREVIA_SOURCE Install from this local checkout instead of GitHub.
#GITHUB_TOKEN Optional; raises GitHub's anonymous API rate limit.

set - eu

          REPO = dr3mro / Aurevia - Care REF = ${AUREVIA_REF : -main} DIR =
${AUREVIA_INSTALL_DIR : -$HOME /.local / share / aurevia} BIN_DIR =
${AUREVIA_BIN_DIR : -$HOME /.local / bin} SOURCE =
${AUREVIA_SOURCE : -} NO_RC = 0 UNINSTALL = 0 REPLACE = 0 BEGIN =
    '# >>> aurevia >>>' END = '# <<< aurevia <<<'

                              if[-t 1] &&
                              [-z "${NO_COLOR:-}"];
then RED = $(printf '\033[31m');
OFF = $(printf '\033[0m');
else RED = ;
OFF = ;
fi say() { printf '%s\n' "$*"; }
die() {
  printf '%serror:%s %s\n' "$RED"
                           "$OFF"
                           "$*" > &2;
  exit 1;
}

while
  [$ # - gt 0];
do
  case $1 in
    --ref)       [ $# -ge 2 ] || die "--ref needs a value"; REF=$2; shift 2 ;;
    --dir)       [ $# -ge 2 ] || die "--dir needs a value"; DIR=$2; shift 2 ;;
    --bin-dir)   [ $# -ge 2 ] || die "--bin-dir needs a value"; BIN_DIR=$2; shift 2 ;;
    --no-rc)     NO_RC=1; shift ;;
    --uninstall) UNINSTALL=1; shift ;;
    --replace)   REPLACE=1; shift ;;
    -h|--help)   sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

#Files installed, as "path-in-repo:name-in-install-dir".
FILES="scripts/aurevia/aurevia.sh:aurevia install.sh:installer"

rc_files() {
  printf '%s\n' "$HOME/.zshrc" "$HOME/.bashrc"
  [ "$(uname -s)" = Darwin ] && printf '%s\n' "$HOME/.bash_profile"
  return 0
}

strip_rc() {
#Remove our marked block from one file, leaving everything else untouched.
  [ -f "$1" ] || return 0
  grep -q "$BEGIN" "$1" 2>/dev/null || return 0
  tmp=$(mktemp)
  awk -v b="$BEGIN" -v e="$END" '$0==b{skip=1;next} $0==e{skip=0;next} !skip' "$1" > "$tmp"
  cat "$tmp" > "$1"
  rm -f "$tmp"
}

do_uninstall() {
  rc_files | while IFS= read -r f; do strip_rc "$f"; done
  link=$BIN_DIR/aurevia
  if [ -L "$link" ] && [ "$(readlink "$link")" = "$DIR/aurevia" ]; then rm -f "$link"; fi
  for pair in $FILES; do rm -f "$DIR/${pair#*:}"; done
  rm -f "$DIR/VERSION" "$DIR/repo"
  rmdir "$DIR" 2>/dev/null || say "kept $DIR (it holds files this installer did not create)"
  say "Aurevia tool removed."
}

if [ "$UNINSTALL" -eq 1 ]; then do_uninstall; exit 0; fi

command -v curl >/dev/null 2>&1 || [ -n "$SOURCE" ] || die "curl is required"

#Stage first : nothing in the live install changes until every file is in    \
      hand.
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT INT TERM

api() {
  if [ -n "${GITHUB_TOKEN:-}" ]; then curl -fsSL -H "Authorization: Bearer $GITHUB_TOKEN" "$@"; else curl -fsSL "$@"; fi
}

if [ -n "$SOURCE" ]; then
  say "Installing from $SOURCE"
  for pair in $FILES; do
    cp "$SOURCE/${pair%%:*}" "$stage/${pair#*:}" || die "missing ${pair%%:*} in $SOURCE"
  done
  version=$(git -C "$SOURCE" describe --always --dirty 2>/dev/null || echo local)
  repo_dir=$(cd "$SOURCE" && pwd)
else
  say "Resolving $REF"
  sha=$(api -H "Accept: application/vnd.github.sha" "https://api.github.com/repos/$REPO/commits/$REF") \
    || die "could not resolve $REF in $REPO"
  say "Downloading $(printf '%s' "$sha" | cut -c1-7)"
  for pair in $FILES; do
    api "https://raw.githubusercontent.com/$REPO/$sha/${pair%%:*}" -o "$stage/${pair#*:}" \
      || die "could not download ${pair%%:*}"
  done
  version=$(printf '%s' "$sha" | cut -c1-12)
  repo_dir=
fi
printf '%s\n' "$version" > "$stage/VERSION"
[ -z "$repo_dir" ] || printf '%s\n' "$repo_dir" > "$stage/repo"

[ "$REPLACE" -eq 0 ] || do_uninstall

mkdir -p "$DIR" "$BIN_DIR"
for f in "$stage"/*; do
  name=$(basename "$f")
  cp "$f" "$DIR/$name"
done
chmod 755 "$DIR/aurevia" "$DIR/installer"

link=$BIN_DIR/aurevia
if [ -e "$link" ] && [ ! -L "$link" ]; then
  say "warning: $link is a real file; left alone. Run $DIR/aurevia instead."
else
  ln -sfn "$DIR/aurevia" "$link"
fi

if [ "$NO_RC" -eq 0 ]; then
  rc_files | while IFS= read -r f; do
    [ -f "$f" ] || continue
    strip_rc "$f"
    {
      printf '\n%s\n' "$BEGIN"
      printf 'case ":$PATH:" in *":%s:"*) ;; *) export PATH="%s:$PATH" ;; esac\n' "$BIN_DIR" "$BIN_DIR"
      case $f in
        *zshrc) printf 'command -v aurevia >/dev/null 2>&1 && eval "$(aurevia completion zsh)"\n' ;;
        *)      printf 'command -v aurevia >/dev/null 2>&1 && eval "$(aurevia completion bash)"\n' ;;
      esac
      printf '%s\n' "$END"
    } >> "$f"
  done
fi

say "Installed aurevia $version into $DIR"
say "Next: open a new shell, then run: aurevia help"
case ":$PATH:" in *":$BIN_DIR:"*) ;; *) say "Note: $BIN_DIR is not on your PATH yet; a new shell picks it up." ;; esac
