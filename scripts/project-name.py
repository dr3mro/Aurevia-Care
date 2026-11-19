"""Read and validate the editable project identity."""
import argparse
import json
import re
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("field", choices=("displayName", "repositorySlug"))
args = parser.parse_args()
path = Path(__file__).resolve().parents[1] / "project.json"
project = json.loads(path.read_text(encoding="utf-8"))
name = project.get("displayName")
slug = project.get("repositorySlug")
if not isinstance(name, str) or not name.strip() or not name.isprintable():
    parser.error("displayName must be a nonempty, single-line printable string")
if not isinstance(slug, str) or not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", slug):
    parser.error("repositorySlug must be lowercase kebab-case")
print(project[args.field])
