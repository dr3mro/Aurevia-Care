"""Append one sanitised work-trail record so later stages see earlier instructions.

A record is a single JSON line: stage, agent, instruction, outputs and UTC time.
Untrusted text may contain Actions workflow-command markers at the start of a
line, so those lines are quoted before writing. The helper never reads secrets
or the network and never interprets the text it stores.
"""

import argparse
import datetime
import json
import re
from pathlib import Path

COMMAND_LINE = re.compile(r"^([ \t]*)(##\[|::)")


def sanitize(text):
    """Quote line-start Actions command markers in untrusted instruction text."""
    return "\n".join(COMMAND_LINE.sub(r"\1> \2", line) for line in (text or "").splitlines())


def append(stage, agent, instruction, outputs, path=".agent/work-trail.jsonl"):
    """Write one JSONL record and return its path.

    Args: stage is the producing step, agent its OpenCode agent, instruction the
    prompt or brief text, outputs the ids it produced, and path the JSONL file.
    Returns: the path. Errors: none; every value is JSON-encoded before write.
    """
    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    record = {
        "step": stage,
        "agent": agent,
        "instruction": sanitize(instruction),
        "outputs": sanitize(outputs),
        "at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    }
    with destination.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(record, ensure_ascii=False) + "\n")
    return destination


def main():
    """CLI used by workflows and agent-run.sh; arguments arrive as plain strings."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", required=True)
    parser.add_argument("--agent", required=True)
    parser.add_argument("--instruction", default="")
    parser.add_argument("--outputs", default="")
    parser.add_argument("--path", default=".agent/work-trail.jsonl")
    args = parser.parse_args()
    append(args.stage, args.agent, args.instruction, args.outputs, args.path)


if __name__ == "__main__":
    main()
