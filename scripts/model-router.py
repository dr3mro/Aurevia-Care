"""Centralized, fail-closed model configuration and routing for the swarm.

Every production ``opencode run`` invocation obtains its model from this module,
so pricing and forced-mode policy cannot be bypassed by a workflow expression,
an agent default, or a worker. The policy is deliberately small and deterministic:

* ``SWARM_FORCE_MODEL`` is a strict Boolean: ``0`` (default when unset/empty) or
  ``1``. Any other value is a configuration error.
* In forced mode (``1``), only a verified zero-cost, authorized ``SWARM_MODEL``
  may be used, for every role and phase; no alternative is ever selected.
* In intelligent mode (``0``), eligible zero-cost primary models are ranked by
  role and execution phase; ``SWARM_MODEL`` is reserved strictly for the final
  fallback stage, after all eligible primaries are exhausted.

No model is assumed free from its name. Zero-cost eligibility comes from a
checked-in registry whose pricing is independently maintained and which workers
cannot edit. When pricing cannot be established, the model is rejected.

Usage (from scripts/agent-run.sh)::

    python3 -I scripts/model-router.py resolve \
        --agent "$agent" --phase "$phase" --model "$BASE_MODEL" ...
"""

import argparse
import fnmatch
import json
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path

FORCE_OFF = 0
FORCE_ON = 1
MODEL_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*$")

DEFAULT_REGISTRY = ".opencode/model-pricing.json"


class ConfigurationError(ValueError):
    """Raised for an invalid forced-mode or fallback configuration."""


@dataclass(frozen=True)
class ModelInfo:
    """A model's verified pricing and capability metadata (never fabricated)."""

    id: str
    input: float
    output: float
    cache_read: float = 0.0
    reasoning: float = 0.0
    tool: float = 0.0
    request: float = 0.0
    subscription: bool = False
    overage: bool = False
    authorized: bool = True
    pricing_source: str = "unverified"
    verified_at: str = ""
    families: tuple = ()
    roles: tuple = ()
    phases: tuple = ()
    score: int = 50

    def is_zero_cost(self):
        """True only when every applicable price is exactly zero and no paid plan applies."""
        prices = (self.input, self.output, self.cache_read, self.reasoning,
                  self.tool, self.request)
        return (all(price == 0 for price in prices)
                and not self.subscription and not self.overage
                and bool(self.pricing_source) and self.pricing_source != "unverified")

    def supports(self, role="", phase=""):
        """True when the model declares support for the requested role and phase."""
        role_ok = not role or not self.roles or role in self.roles or "*" in self.roles
        phase_ok = not phase or not self.phases or phase in self.phases or "*" in self.phases
        return role_ok and phase_ok


def parse_force_model(value):
    """Parse ``SWARM_FORCE_MODEL`` to 0/1. Unset/empty defaults to 0; invalid raises.

    Only the exact strings ``0`` and ``1`` (with surrounding whitespace) are valid,
    so ``true``, ``2``, a model id, or a typo can never silently enable forced mode.
    """
    if value is None or str(value).strip() == "":
        return FORCE_OFF
    text = str(value).strip()
    if text == "0":
        return FORCE_OFF
    if text == "1":
        return FORCE_ON
    raise ConfigurationError(f"SWARM_FORCE_MODEL must be 0 or 1, got {text!r}")


def validate_model_id(value):
    """Return a provider-qualified model id or raise for a malformed identifier."""
    text = "" if value is None else str(value).strip()
    if not MODEL_ID.fullmatch(text):
        raise ConfigurationError(f"invalid provider-qualified model id: {text!r}")
    return text


def load_registry(root=".", path=None):
    """Load and validate the pricing registry from ``root/path``.

    Returns a mapping of model id to :class:`ModelInfo`. A missing, malformed or
    unreadable registry yields an empty mapping so callers fail closed.
    """
    location = Path(root) / (path or DEFAULT_REGISTRY)
    try:
        data = json.loads(location.read_text(encoding="utf-8"))
        entries = data.get("models", data) if isinstance(data, dict) else {}
    except (OSError, ValueError, AttributeError):
        return {}
    models = {}
    for identifier, raw in entries.items() if isinstance(entries, dict) else ():
        if not isinstance(raw, dict):
            continue
        try:
            info = ModelInfo(
                id=identifier,
                input=float(raw.get("input", 0)),
                output=float(raw.get("output", 0)),
                cache_read=float(raw.get("cache_read", 0)),
                reasoning=float(raw.get("reasoning", 0)),
                tool=float(raw.get("tool", 0)),
                request=float(raw.get("request", 0)),
                subscription=bool(raw.get("subscription", False)),
                overage=bool(raw.get("overage", False)),
                authorized=bool(raw.get("authorized", True)),
                pricing_source=str(raw.get("pricing_source", "unverified")),
                verified_at=str(raw.get("verified_at", "")),
                families=tuple(raw.get("families", ())),
                roles=tuple(raw.get("roles", ())),
                phases=tuple(raw.get("phases", ())),
                score=int(raw.get("score", 50)),
            )
        except (TypeError, ValueError):
            continue
        models[identifier] = info
    return models


def eligible_models(registry):
    """Return only verified zero-cost, authorized models, in stable id order."""
    return sorted((m for m in registry.values() if m.authorized and m.is_zero_cost()),
                  key=lambda m: (-m.score, m.id))


def classify_task(paths=(), title="", attempts=0, security=False):
    """Deterministically classify complexity from objective signals.

    A small diff in an auth, tenant, clinical or workflow path is still critical;
    size alone never downgrades L4 work.
    """
    text = " ".join(str(path) for path in paths) + " " + str(title or "")
    signals = ("auth", "security", "tenant", "patient", "phi", "workflow", "iam", "secret")
    if security or any(signal in text.lower() for signal in signals):
        return "L4"
    if "migration" in text.lower() or "pom.xml" in text or "concurrency" in text.lower():
        return "L3"
    if len(paths) > 3 or attempts >= 3:
        return "L3"
    if len(paths) <= 1 and attempts == 0:
        return "L1"
    return "L2"


# Initial role/phase preferences. These are configuration, not benchmark claims;
# a family is preferred only when a verified zero-cost model of that family is
# simultaneously eligible. Unknown families simply have no preference bonus.
ROLE_PREFERENCES = {
    "planner": ("deepseek", "qwen", "gemini"),
    "architect": ("deepseek", "gemini", "qwen"),
    "reviewer": ("deepseek", "qwen", "gemini"),
    "debugger": ("deepseek", "qwen", "glm"),
    "worker": ("qwen", "glm", "deepseek"),
    "documentation": ("gemini", "hy4", "glm"),
}
PHASE_PREFERENCES = {
    "planning": ("deepseek", "qwen", "gemini"),
    "briefing": ("deepseek", "gemini", "glm"),
    "execution": ("hy4", "glm", "gemini", "qwen"),
    "review": ("deepseek", "qwen", "gemini"),
}


def _family_bonus(model, preferences):
    """Return the rank of the first matching family, or a large sentinel."""
    for rank, family in enumerate(preferences):
        if any(family in candidate.lower() for candidate in model.families):
            return rank
    return len(preferences) + 1


def rank_primaries(models, role="", phase="", complexity="L2"):
    """Rank eligible primaries by role/phase preference, complexity and configured score.

    ``SWARM_MODEL`` is never passed here, so it can never outrank a primary.
    Ties break deterministically by model id.
    """
    preferences = PHASE_PREFERENCES.get(phase) or ROLE_PREFERENCES.get(role) or ()
    if complexity in ("L3", "L4"):
        preferences = ROLE_PREFERENCES.get(role, preferences) or preferences
    return sorted(models, key=lambda m: (_family_bonus(m, preferences), -m.score, m.id))


@dataclass(frozen=True)
class RoutingDecision:
    """The complete, loggable result of one routing decision."""

    mode: str
    selected: str
    candidates: tuple
    reason: str
    complexity: str
    pricing_verified: bool

    def as_dict(self):
        """Return a JSON-serialisable summary (no credentials, no secrets)."""
        return {"mode": self.mode, "selected": self.selected,
                "candidates": list(self.candidates), "reason": self.reason,
                "complexity": self.complexity, "pricing_verified": self.pricing_verified}


def resolve(force_value, swarm_model, registry, role="", phase="", base_model="",
            paths=(), title="", attempts=0, security=False):
    """Resolve an ordered, zero-cost candidate list and the primary selection.

    Forced mode validates ``SWARM_MODEL`` strictly and returns it as the sole
    candidate. Intelligent mode ranks verified primaries and appends a validated
    ``SWARM_MODEL`` only as the final fallback. Invalid configuration raises
    :class:`ConfigurationError` so the caller can defer or fail safely.
    """
    force = parse_force_model(force_value)
    complexity = classify_task(paths, title, attempts, security)
    primaries = rank_primaries([m for m in eligible_models(registry) if m.id != swarm_model],
                               role, phase, complexity)
    if force == FORCE_ON:
        identifier = validate_model_id(swarm_model)
        info = registry.get(identifier)
        if info is None or not info.authorized or not info.is_zero_cost():
            raise ConfigurationError(f"forced model {identifier!r} is not a verified zero-cost model")
        return RoutingDecision("FORCED", identifier, (identifier,),
                               "forced mode: exclusively configured model", complexity, True)
    fallback = ()
    if swarm_model:
        identifier = validate_model_id(swarm_model)
        info = registry.get(identifier)
        if info is not None and info.authorized and info.is_zero_cost():
            fallback = (identifier,)
    candidates = tuple(m.id for m in primaries) + fallback
    if not candidates:
        return RoutingDecision("INTELLIGENT", "", (), "no verified zero-cost model available",
                               complexity, False)
    return RoutingDecision("INTELLIGENT", candidates[0], candidates,
                           "ranked verified zero-cost primary", complexity, True)


def main(argv=None):
    """CLI: print a routing decision as JSON; exit 2 on configuration error."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("resolve", "check-config"))
    parser.add_argument("--force", default=os.environ.get("SWARM_FORCE_MODEL", ""))
    parser.add_argument("--swarm-model", default=os.environ.get("SWARM_MODEL", ""))
    parser.add_argument("--registry-root", default=os.environ.get("SWARM_REGISTRY_ROOT", "."))
    parser.add_argument("--registry", default=os.environ.get("SWARM_REGISTRY", DEFAULT_REGISTRY))
    parser.add_argument("--agent", default="")
    parser.add_argument("--phase", default="")
    parser.add_argument("--base-model", default=os.environ.get("MODEL", ""))
    parser.add_argument("--paths", default="")
    parser.add_argument("--title", default="")
    parser.add_argument("--attempts", default="0")
    parser.add_argument("--security", action="store_true")
    args = parser.parse_args(argv)
    registry = load_registry(args.registry_root, args.registry)
    try:
        if args.command == "check-config":
            force = parse_force_model(args.force)
            if force == FORCE_ON:
                identifier = validate_model_id(args.swarm_model)
                if identifier not in registry or not registry[identifier].is_zero_cost():
                    raise ConfigurationError("forced model is not verified zero-cost")
            decision = RoutingDecision("CHECK", "ok", (), "configuration valid", "n/a", True)
        else:
            try:
                attempts = int(args.attempts)
            except ValueError:
                raise ConfigurationError("--attempts must be an integer")
            decision = resolve(args.force, args.swarm_model, registry, args.agent, args.phase,
                               args.base_model, tuple(args.paths.split()), args.title,
                               attempts, args.security)
    except ConfigurationError as error:
        print(f"model-router: configuration error: {error}", file=sys.stderr)
        return 2
    print(json.dumps(decision.as_dict(), sort_keys=True))
    return 0 if decision.selected or args.command == "check-config" else 1


if __name__ == "__main__":
    sys.exit(main())
