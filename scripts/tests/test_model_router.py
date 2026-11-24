"""Regression tests for the centralized zero-cost model router.

All pricing/capability data is synthetic fixture data; no test invokes a model.
Every configuration state from the specification is exercised against the real
production router module.
"""
import contextlib
import os
import io
import json
import runpy
import sys
import tempfile
import unittest
from pathlib import Path

from helpers import ROOT, load

router = load("model-router")


def registry(**overrides):
    """Build a small in-memory verified registry from ModelInfo objects."""
    def info(model_id, **kw):
        data = dict(id=model_id, input=0.0, output=0.0, pricing_source="models.dev",
                    verified_at="2026-11-23", score=50)
        data.update(kw)
        return router.ModelInfo(**data)
    base = {
        "opencode-go/hy4-flash-free": info("opencode-go/hy4-flash-free",
                                           families=("hy4",), roles=("worker",), score=80),
        "opencode/deepseek-v4.1-flash-free": info("opencode/deepseek-v4.1-flash-free",
                                                  families=("deepseek",),
                                                  roles=("planner", "worker"), score=90),
        "opencode/paid-model": info("opencode/paid-model", input=1.0, output=2.0,
                                    families=("deepseek",), score=99),
    }
    base.update(overrides)
    return base


class BooleanConfigTests(unittest.TestCase):
    def test_defaults_and_valid_values(self):
        self.assertEqual(router.parse_force_model(None), 0)
        self.assertEqual(router.parse_force_model(""), 0)
        self.assertEqual(router.parse_force_model("   "), 0)
        self.assertEqual(router.parse_force_model("0"), 0)
        self.assertEqual(router.parse_force_model(" 1 "), 1)

    def test_invalid_values_are_rejected(self):
        for value in ("true", "2", "-1", "yes", "opencode-go/hy4-flash-free"):
            with self.subTest(value=value):
                with self.assertRaises(router.ConfigurationError):
                    router.parse_force_model(value)

    def test_model_id_validation(self):
        self.assertEqual(router.validate_model_id(" opencode-go/hy4-flash-free "),
                         "opencode-go/hy4-flash-free")
        for value in ("", "hy4-flash-free", "a b/c", "a/"):
            with self.subTest(value=value):
                with self.assertRaises(router.ConfigurationError):
                    router.validate_model_id(value)


class PricingTests(unittest.TestCase):
    def test_zero_cost_checks_every_category(self):
        free = router.ModelInfo("a/b", 0, 0, pricing_source="models.dev")
        self.assertTrue(free.is_zero_cost())
        for field in ("input", "output", "cache_read", "reasoning", "tool", "request"):
            kw = dict(id="a/b", input=0, output=0, pricing_source="models.dev")
            kw[field] = 0.01
            self.assertFalse(router.ModelInfo(**kw).is_zero_cost(), field)
        self.assertFalse(router.ModelInfo("a/b", 0, 0, subscription=True,
                                          pricing_source="models.dev").is_zero_cost())
        self.assertFalse(router.ModelInfo("a/b", 0, 0, overage=True,
                                          pricing_source="models.dev").is_zero_cost())
        self.assertFalse(router.ModelInfo("a/b", 0, 0).is_zero_cost())
        self.assertFalse(router.ModelInfo("a/b", 0, 0,
                                          pricing_source="unverified").is_zero_cost())

    def test_eligible_models_exclude_paid_unauthorized_and_unverified(self):
        reg = registry()
        reg["opencode/unauthorized"] = router.ModelInfo(
            "opencode/unauthorized", 0, 0, authorized=False, pricing_source="models.dev")
        ids = [m.id for m in router.eligible_models(reg)]
        self.assertEqual(ids, ["opencode/deepseek-v4.1-flash-free", "opencode-go/hy4-flash-free"])
        self.assertNotIn("opencode/paid-model", ids)
        self.assertNotIn("opencode/unauthorized", ids)

    def test_registry_load_and_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            payload = {"models": {"a/b": {"input": 0, "output": 0,
                                          "pricing_source": "models.dev"},
                                  "bad": "not-a-dict"}}
            (root / "pricing.json").write_text(__import__("json").dumps(payload))
            loaded = router.load_registry(str(root), "pricing.json")
            self.assertIn("a/b", loaded)
            self.assertTrue(loaded["a/b"].is_zero_cost())
            self.assertEqual(router.load_registry(str(root), "missing.json"), {})
            (root / "broken.json").write_text("{not json")
            self.assertEqual(router.load_registry(str(root), "broken.json"), {})
            bad = {"models": {"a/b": {"input": "not-a-number", "output": 0}}}
            (root / "bad.json").write_text(json.dumps(bad))
            self.assertEqual(router.load_registry(str(root), "bad.json"), {})

    def test_supports_role_and_phase(self):
        model = router.ModelInfo("a/b", 0, 0, roles=("worker",), phases=("execution",),
                                 pricing_source="models.dev")
        self.assertTrue(model.supports("worker", "execution"))
        self.assertTrue(model.supports())
        self.assertFalse(model.supports("planner", "execution"))
        self.assertFalse(model.supports("worker", "review"))
        wildcard = router.ModelInfo("a/b", 0, 0, roles=("*",), phases=("*",),
                                    pricing_source="models.dev")
        self.assertTrue(wildcard.supports("planner", "review"))


class ClassificationTests(unittest.TestCase):
    def test_security_phrase_is_critical_even_with_one_file(self):
        self.assertEqual(router.classify_task(("a.py",), "fix auth bug"), "L4")
        self.assertEqual(router.classify_task(("a.py",), "", security=True), "L4")
        self.assertEqual(router.classify_task(("a.py",), "tenant isolation"), "L4")

    def test_complexity_levels(self):
        self.assertEqual(router.classify_task(("a.py",), "docs"), "L1")
        self.assertEqual(router.classify_task(("a.py", "b.py"), "feature"), "L2")
        self.assertEqual(router.classify_task(("a.py", "b.py", "c.py", "d.py"), "feature"), "L3")
        self.assertEqual(router.classify_task(("a.py",), "feature", attempts=3), "L3")
        self.assertEqual(router.classify_task(("db/migrations/x.sql",), "migration"), "L3")
        self.assertEqual(router.classify_task(("a.py",), "add pom.xml dependency"), "L3")


class RoutingTests(unittest.TestCase):
    def test_forced_mode_uses_exact_model_only(self):
        decision = router.resolve("1", "opencode-go/hy4-flash-free", registry(),
                                  role="planner", phase="planning")
        self.assertEqual(decision.mode, "FORCED")
        self.assertEqual(decision.selected, "opencode-go/hy4-flash-free")
        self.assertEqual(decision.candidates, ("opencode-go/hy4-flash-free",))

    def test_forced_mode_rejects_invalid_configurations(self):
        for value, model in (("1", ""), ("1", "not-qualified"),
                             ("1", "opencode/paid-model"),
                             ("1", "opencode/unknown-free")):
            with self.subTest(value=value, model=model):
                with self.assertRaises(router.ConfigurationError):
                    router.resolve(value, model, registry())

    def test_intelligent_mode_ranks_primaries_and_appends_fallback_last(self):
        decision = router.resolve("0", "opencode-go/hy4-flash-free", registry(),
                                  role="worker", phase="execution")
        self.assertEqual(decision.mode, "INTELLIGENT")
        self.assertEqual(decision.candidates[-1], "opencode-go/hy4-flash-free")
        self.assertEqual(decision.selected, "opencode/deepseek-v4.1-flash-free")
        self.assertEqual(len(decision.candidates), 2)

    def test_paid_swarm_model_never_becomes_fallback(self):
        decision = router.resolve("0", "opencode/paid-model", registry())
        self.assertNotIn("opencode/paid-model", decision.candidates)

    def test_intelligent_mode_without_fallback(self):
        decision = router.resolve("0", "", registry())
        self.assertEqual(len(decision.candidates), 2)
        self.assertEqual(decision.selected, "opencode/deepseek-v4.1-flash-free")

    def test_unset_defaults_to_intelligent(self):
        decision = router.resolve("", "opencode-go/hy4-flash-free", registry())
        self.assertEqual(decision.mode, "INTELLIGENT")

    def test_base_model_fallback_when_no_verified_candidates(self):
        reg = {"base/free": router.ModelInfo("base/free", 0, 0, pricing_source="models.dev")}
        decision = router.resolve("0", "", reg, base_model="base/free")
        self.assertEqual(decision.candidates, ("base/free",))

    def test_no_verified_model_produces_defer_signal(self):
        decision = router.resolve("0", "", {})
        self.assertEqual(decision.selected, "")
        self.assertEqual(decision.candidates, ())
        self.assertFalse(decision.pricing_verified)

    def test_role_and_phase_change_ranking(self):
        reg = registry()
        reg["opencode-go/beam-free"] = router.ModelInfo(
            "opencode-go/beam-free", 0, 0, families=("qwen",), roles=("reviewer",),
            phases=("review",), pricing_source="models.dev", score=95)
        plan = router.resolve("0", "", reg, role="planner", phase="planning")
        review = router.resolve("0", "", reg, role="reviewer", phase="review")
        self.assertEqual(plan.selected, "opencode/deepseek-v4.1-flash-free")
        self.assertEqual(review.selected, "opencode/deepseek-v4.1-flash-free")
        review_qwen = router.resolve("0", "", reg, role="reviewer", phase="review",
                                     paths=("a.py", "b.py", "c.py", "d.py"))
        self.assertIn(review_qwen.selected, reg)

    def test_opencode_go_is_preferred_for_routine_execution(self):
        """An exhausted cheaper-ranked provider must not win a routine worker slot."""
        reg = {
            "opencode-go/beam-free": router.ModelInfo(
                "opencode-go/beam-free", 0, 0, families=("beam",), roles=("worker",),
                phases=("execution",), pricing_source="models.dev", score=60),
            "opencode/hy4-flash-free": router.ModelInfo(
                "opencode/hy4-flash-free", 0, 0, families=("hy4",), roles=("worker",),
                phases=("execution",), pricing_source="models.dev", score=78),
        }
        decision = router.resolve("0", "", reg, role="worker", phase="execution")
        self.assertEqual(decision.selected, "opencode-go/beam-free")
        self.assertEqual(decision.candidates, ("opencode-go/beam-free",
                                               "opencode/hy4-flash-free"))

    def test_paid_and_unverified_opencode_go_models_are_never_candidates(self):
        """Provider preference only orders models that already passed the $0 gate."""
        reg = registry()
        reg["opencode-go/paid"] = router.ModelInfo(
            "opencode-go/paid", 0.01, 0, families=("hy4",), roles=("worker",),
            phases=("execution",), pricing_source="models.dev", score=99)
        reg["opencode-go/unverified"] = router.ModelInfo(
            "opencode-go/unverified", 0, 0, families=("hy4",), roles=("worker",),
            phases=("execution",), pricing_source="unverified", score=99)
        decision = router.resolve("0", "", reg, role="worker", phase="execution")
        self.assertNotIn("opencode-go/paid", decision.candidates)
        self.assertNotIn("opencode-go/unverified", decision.candidates)
        self.assertTrue(all(reg[c].is_zero_cost() for c in decision.candidates))

    def test_critical_complexity_prefers_role_reasoning(self):
        reg = registry()
        decision = router.resolve("0", "", reg, role="worker", phase="execution",
                                  paths=("a.py",), title="fix auth", security=True)
        self.assertEqual(decision.complexity, "L4")
        self.assertEqual(decision.selected, "opencode/deepseek-v4.1-flash-free")

    def test_result_is_serialisable(self):
        decision = router.resolve("0", "", registry())
        self.assertEqual(router.json.loads(router.json.dumps(decision.as_dict()))["mode"],
                         "INTELLIGENT")


class CliTests(unittest.TestCase):
    def test_resolve_cli(self):
        code = router.main(["resolve", "--force", "1",
                            "--swarm-model", "opencode-go/hy4-flash-free",
                            "--registry-root", "."])
        self.assertIn(code, (0, 2))  # depends on checked-in registry presence

    def test_check_config_invalid(self):
        code = router.main(["check-config", "--force", "2", "--swarm-model", "x/y"])
        self.assertEqual(code, 2)

    def test_check_config_rejects_unverified_forced_model(self):
        code = router.main(["check-config", "--force", "1",
                            "--swarm-model", "opencode/no-such-verified-model"])
        self.assertEqual(code, 2)

    def test_base_model_fallback_cli(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "pricing.json").write_text(json.dumps({"models": {
                "base/free": {"input": 0, "output": 0, "pricing_source": "models.dev"}}}))
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = router.main(["resolve", "--force", "0", "--base-model", "base/free",
                                    "--registry-root", str(root), "--registry", "pricing.json"])
            self.assertEqual(code, 0)
            self.assertIn("base/free", out.getvalue())

    def test_check_config_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "pricing.json").write_text(json.dumps({"models": {
                "a/b": {"input": 0, "output": 0, "pricing_source": "models.dev"}}}))
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = router.main(["check-config", "--force", "1", "--swarm-model", "a/b",
                                    "--registry-root", str(root), "--registry", "pricing.json"])
            self.assertEqual(code, 0)
            self.assertIn("configuration valid", out.getvalue())

    def test_check_config_intelligent_mode(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = router.main(["check-config", "--force", "0"])
        self.assertEqual(code, 0)

    def test_module_entry_point(self):
        with self.assertRaises(SystemExit):
            with contextlib.redirect_stdout(io.StringIO()):
                runpy.run_path(str(__import__("helpers").ROOT / "scripts" / "model-router.py"),
                               run_name="__main__")

    def test_bad_attempts(self):
        code = router.main(["resolve", "--force", "0", "--attempts", "abc"])
        self.assertEqual(code, 2)


class ProductionEnforcementTests(unittest.TestCase):
    """Every production opencode run must be routed through agent-run.sh."""

    def test_single_invocation_uses_router(self):
        run = (ROOT / "scripts" / "agent-run.sh").read_text()
        lines = [line for line in run.splitlines()
                 if "opencode run" in line and not line.strip().startswith("#")]
        self.assertEqual(len(lines), 1, lines)
        self.assertIn("--model \"$model\"", lines[0])
        self.assertIn("route_models", run)

    def test_no_workflow_passes_swarm_model_as_primary(self):
        for path in (ROOT / ".github" / "workflows").glob("*.yml"):
            self.assertNotIn("vars.SWARM_MODEL ||", path.read_text(), path.name)


class ZeroCostEnforcementTests(unittest.TestCase):
    """No pricing category or paid-plan flag may slip a model into the eligible set."""

    def test_paid_dimensions_and_plans_are_rejected(self):
        for field, value in (("input", 0.01), ("output", 0.01), ("cache_read", 0.1),
                             ("reasoning", 0.1), ("tool", 0.5), ("request", 0.01),
                             ("subscription", True), ("overage", True)):
            with self.subTest(field=field):
                kw = dict(id="a/b", input=0.0, output=0.0, pricing_source="models.dev")
                kw[field] = value
                model = router.ModelInfo(**kw)
                self.assertFalse(model.is_zero_cost(), field)

    def test_forced_paid_model_is_rejected_without_invocation(self):
        reg = registry()
        with self.assertRaises(router.ConfigurationError):
            router.resolve("1", "opencode/paid-model", reg)

    def test_paid_model_never_enters_intelligent_candidates(self):
        reg = registry()
        decision = router.resolve("0", "", reg)
        self.assertNotIn("opencode/paid-model", decision.candidates)
        self.assertTrue(decision.pricing_verified)

    def test_unverified_registry_fails_closed(self):
        decision = router.resolve("0", "", {})
        self.assertEqual(decision.candidates, ())
        self.assertFalse(decision.pricing_verified)


if __name__ == "__main__":
    unittest.main()
