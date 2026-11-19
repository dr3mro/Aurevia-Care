"""Load the hyphen-named tooling scripts as modules for testing."""
import importlib.util
import shutil
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load(name):
    spec = importlib.util.spec_from_file_location(
        name.replace("-", "_"), ROOT / "scripts" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def copy_pack():
    """Copy the files the pack checks use into a scratch repository."""
    tmp = Path(tempfile.mkdtemp())
    for name in ("opencode.json", "SMARTCLINIC_V1_SPEC.md", "TODO.md"):
        shutil.copy(ROOT / name, tmp / name)
    for folder in (".opencode", "scripts"):
        shutil.copytree(ROOT / folder, tmp / folder,
                        ignore=shutil.ignore_patterns("__pycache__", "tests"))
    return tmp
