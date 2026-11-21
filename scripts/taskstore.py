#!/usr/bin/env python3
"""One file per task: the storage behind TODO.md.

TODO.md grew to over a thousand tasks, and every claim, merge and audit edited the same
file, so parallel writers conflicted. In task-file mode each task lives in its own file,
`tasks/<sequence>-<ID>.md`, so two writers only conflict when they edit the same task.
The sequence number keeps the order of the old file; new tasks get a number between their
neighbours, so inserting never renames anything else.

Everything else in the swarm still sees one text: `read()` joins the files and `save()`
splits an edited text back, rewriting only the files that changed. `TODO.md` stays as a
generated view for people, shell scripts and the documentation checks. If something still
edits the view (an old copy of the swarm code), the next read adopts that edit.

Run from the repository root, like the rest of the swarm scripts.
"""
import hashlib
import re
import sys
from pathlib import Path

ROOT = "tasks"
VIEW = "TODO.md"
DONE = "done"
HEAD = "_head.md"
MARK = ".sharded"           # holds the hash of the text the view was last generated from
STEP = 1000                 # gap between neighbouring sequence numbers
ENTRY = re.compile(r"^- \[([ x-])\] (\S+) · ")
NAME = re.compile(r"^(\d{8})-(.+)\.md$")


def split(text):
    """Return `(head, [(id, chunk)])`. Joined back together they are the text exactly.

    A chunk is an entry line and everything up to the next entry line, so section headings
    and prose stay with the entry above them."""
    head, chunks, current = [], [], None
    for line in text.splitlines(keepends=True):
        entry = ENTRY.match(line.rstrip("\n"))
        if entry:
            current = [entry.group(2), [line]]
            chunks.append(current)
        elif current is None:
            head.append(line)
        else:
            current[1].append(line)
    return "".join(head), [(task_id, "".join(lines)) for task_id, lines in chunks]


def safe(task_id):
    """The id as part of a file name."""
    return re.sub(r"[^A-Za-z0-9._-]", "_", task_id)


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def active(root=ROOT):
    """True once the tasks have moved into files."""
    return (Path(root) / MARK).exists()


def files(root=ROOT):
    """The live task files in order, as `(sequence, path)`."""
    found = []
    for path in Path(root).glob("*.md"):
        match = NAME.match(path.name)
        if match:
            found.append((int(match.group(1)), path))
    return sorted(found)


def joined(root=ROOT):
    """The tasks as one text."""
    head = Path(root) / HEAD
    parts = [head.read_text(encoding="utf-8") if head.exists() else ""]
    parts += [path.read_text(encoding="utf-8") for _, path in files(root)]
    return "".join(parts)


def first_id(path):
    """The task id on the first line of a task file."""
    entry = ENTRY.match(path.read_text(encoding="utf-8").split("\n", 1)[0])
    return entry.group(2) if entry else NAME.match(path.name).group(2)


def sequences(existing, chunks):
    """Sequence numbers for `chunks` in order, keeping every existing one that still fits.

    `existing` maps `(id, occurrence)` to its current number. A number is kept while the
    numbers stay increasing; new or moved chunks get an even share of the gap between their
    neighbours. When a gap is too small everything is numbered afresh."""
    keys, seen, kept, last = [], {}, [], 0
    for task_id, _ in chunks:
        seen[task_id] = seen.get(task_id, 0) + 1
        keys.append((task_id, seen[task_id]))
        value = existing.get(keys[-1])
        if value is not None and value > last:
            kept.append(value)
            last = value
        else:
            kept.append(None)
    numbers, index, low = [], 0, 0
    while index < len(kept):
        if kept[index] is not None:
            numbers.append(kept[index])
            low = kept[index]
            index += 1
            continue
        end = index
        while end < len(kept) and kept[end] is None:
            end += 1
        count = end - index
        high = kept[end] if end < len(kept) else low + (count + 1) * STEP
        gap = (high - low) // (count + 1)
        if gap < 1:
            return keys, [STEP * (n + 1) for n in range(len(keys))]
        numbers += [low + gap * (n + 1) for n in range(count)]
        index = end
    return keys, numbers


def write(text, root=ROOT):
    """Make the files match `text`: write what changed, remove what is gone."""
    base = Path(root)
    base.mkdir(parents=True, exist_ok=True)
    head, chunks = split(text)
    current = files(root)
    existing, seen = {}, {}
    for number, path in current:
        task_id = first_id(path)
        seen[task_id] = seen.get(task_id, 0) + 1
        existing[(task_id, seen[task_id])] = number
    keys, numbers = sequences(existing, chunks)
    wanted = {}
    for (task_id, _), number, (_, chunk) in zip(keys, numbers, chunks):
        wanted[f"{number:08d}-{safe(task_id)}.md"] = chunk
    for _, path in current:
        if path.name not in wanted:
            path.unlink()
    for name, chunk in wanted.items():
        path = base / name
        if not path.exists() or path.read_text(encoding="utf-8") != chunk:
            path.write_text(chunk, encoding="utf-8")
    (base / HEAD).write_text(head, encoding="utf-8")


def sync_view(root=ROOT, view=VIEW):
    """Regenerate the view from the files and remember what it was made from."""
    text = joined(root)
    Path(view).write_text(text, encoding="utf-8")
    (Path(root) / MARK).write_text(digest(text) + "\n", encoding="utf-8")
    return text


def read(root=ROOT, view=VIEW):
    """The tasks as one text.

    If the view was edited since it was generated while the files were not, the edit came
    from code that still writes the view: adopt it. If the files changed too, they win."""
    text = joined(root)
    marker = (Path(root) / MARK).read_text(encoding="utf-8").strip()
    path = Path(view)
    if digest(text) == marker and path.exists():
        edited = path.read_text(encoding="utf-8")
        if edited != text:
            write(edited, root)
            return sync_view(root, view)
    if digest(text) != marker or not path.exists() or path.read_text(encoding="utf-8") != text:
        sync_view(root, view)
    return text


def save(text, root=ROOT, view=VIEW):
    """Store an edited text and refresh the view."""
    write(text, root)
    return sync_view(root, view)


def migrate(root=ROOT, view=VIEW):
    """Move TODO.md into task files. Returns how many tasks it moved; 0 when already done."""
    if active(root) or not Path(view).exists():
        return 0
    text = Path(view).read_text(encoding="utf-8")
    write(text, root)
    if joined(root) != text:
        raise SystemExit("task files do not rebuild TODO.md exactly; nothing was kept")
    sync_view(root, view)
    return len(split(text)[1])


def archive(blocks, root=ROOT):
    """Keep finished entries under tasks/done, one file each."""
    folder = Path(root) / DONE
    folder.mkdir(parents=True, exist_ok=True)
    for block in blocks:
        entry = ENTRY.match(block.split("\n", 1)[0])
        (folder / f"{safe(entry.group(2))}.md").write_text(block, encoding="utf-8")


def archived(root=ROOT):
    """The text of every finished entry kept under tasks/done."""
    folder = Path(root) / DONE
    if not folder.is_dir():
        return ""
    return "\n".join(path.read_text(encoding="utf-8") for path in sorted(folder.glob("*.md")))


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if argv != ["migrate"]:
        print("usage: taskstore.py migrate", file=sys.stderr)
        return 2
    moved = migrate()
    print(f"moved {moved} task(s) into {ROOT}/" if moved else "nothing to migrate")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
