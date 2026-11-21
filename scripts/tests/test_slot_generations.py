"""Immutable slot-image generations: picking the newest complete one.

Workers must never start from a cancelled or half-written ``:latest`` image. A
generation records every slot image and the digest recorded for it; a wave uses
the newest complete generation, falls back to the last good one and waits when
none is complete. All inputs here are synthetic.
"""
import json
import unittest
from unittest import mock

from helpers import load

swarm = load("swarm")


def generation(tag="g1-aaaaaaaaaaaa", *, exists=True, digest="sha256:abc", slots=None, **extra):
    """Build one synthetic generation record with both slot images present."""
    if slots is None:
        slots = [
            {"slot": 1, "exists": exists, "digest": digest},
            {"slot": 2, "exists": exists, "digest": digest},
        ]
    record = {"tag": tag, "run_number": 1, "sha": "a" * 40, "slots": slots}
    record.update(extra)
    return record


class GenerationTagTests(unittest.TestCase):
    def test_tag_is_run_number_and_twelve_char_sha(self):
        tag = swarm.generation_tag("abcdef1234567890abcdef1234567890abcdef12", 42)
        self.assertEqual(tag, "g42-abcdef123456")
        # Stable: the same inputs always produce the same immutable tag.
        self.assertEqual(tag, swarm.generation_tag("abcdef1234567890abcdef1234567890abcdef12", 42))

    def test_tag_lowercases_the_sha(self):
        self.assertEqual(swarm.generation_tag("ABCDEF1234567890", 7), "g7-abcdef123456")

    def test_short_sha_is_accepted_without_padding(self):
        self.assertEqual(swarm.generation_tag("abc", 9), "g9-abc")

    def test_tag_is_unique_per_run(self):
        self.assertNotEqual(swarm.generation_tag("a" * 40, 7), swarm.generation_tag("a" * 40, 8))

    def test_tag_is_unique_per_sha(self):
        self.assertNotEqual(swarm.generation_tag("a" * 40, 7), swarm.generation_tag("b" + "a" * 39, 7))


class GenerationCompletenessTests(unittest.TestCase):
    """A generation is complete only when a non-empty set of slots each has an
    image and a recorded digest."""

    def test_complete_generation(self):
        self.assertTrue(swarm._generation_complete(generation()))

    def test_non_dict_generation_is_incomplete(self):
        self.assertFalse(swarm._generation_complete(None))
        self.assertFalse(swarm._generation_complete("junk"))

    def test_missing_slots_is_incomplete(self):
        self.assertFalse(swarm._generation_complete({"tag": "g1-aaaaaaaaaaaa"}))

    def test_empty_slots_is_incomplete(self):
        self.assertFalse(swarm._generation_complete(generation(slots=[])))

    def test_non_dict_slot_is_incomplete(self):
        self.assertFalse(swarm._generation_complete(generation(slots=["junk"])))

    def test_slot_without_image_is_incomplete(self):
        self.assertFalse(swarm._generation_complete(generation(slots=[{"slot": 1, "exists": False, "digest": "sha256:x"}])))

    def test_slot_without_digest_is_incomplete(self):
        self.assertFalse(swarm._generation_complete(generation(slots=[{"slot": 1, "exists": True}])))

    def test_slot_with_empty_digest_is_incomplete(self):
        self.assertFalse(swarm._generation_complete(generation(slots=[{"slot": 1, "exists": True, "digest": ""}])))

    def test_all_slots_must_be_good(self):
        slots = [{"slot": 1, "exists": True, "digest": "sha256:x"},
                 {"slot": 2, "exists": False, "digest": "sha256:y"}]
        self.assertFalse(swarm._generation_complete(generation(slots=slots)))


class PickGenerationTests(unittest.TestCase):
    def test_complete_newest_generation_wins(self):
        newest, older = generation("g9-newest"), generation("g3-older")
        self.assertIs(swarm.pick_generation([newest, older]), newest)

    def test_empty_list_returns_none(self):
        self.assertIsNone(swarm.pick_generation([]))

    def test_incomplete_newest_returns_none_even_with_older_complete(self):
        newest = generation("g9-newest", exists=False)
        older = generation("g3-older")
        self.assertIsNone(swarm.pick_generation([newest, older]))

    def test_non_dict_newest_returns_none(self):
        self.assertIsNone(swarm.pick_generation(["junk", generation("g3-older")]))


class LastGoodTests(unittest.TestCase):
    def test_returns_previous_complete_generation(self):
        incomplete = generation("g9-newest", exists=False)
        previous = generation("g7-previous")
        oldest = generation("g3-oldest")
        self.assertIs(swarm.last_good([incomplete, previous, oldest]), previous)

    def test_skips_incomplete_generations_before_the_last_good(self):
        newest = generation("g9-newest", exists=False)
        middle = generation("g7-middle", digest="")
        good = generation("g5-good")
        self.assertIs(swarm.last_good([newest, middle, good]), good)

    def test_ignores_newest_when_it_is_complete(self):
        newest = generation("g9-newest")
        previous = generation("g7-previous")
        self.assertIs(swarm.last_good([newest, previous]), previous)

    def test_returns_none_when_no_complete_generation_after_newest(self):
        self.assertIsNone(swarm.last_good([generation("g9", exists=False),
                                           generation("g7", digest="")]))

    def test_returns_none_for_empty_or_single_generation(self):
        self.assertIsNone(swarm.last_good([]))
        self.assertIsNone(swarm.last_good([generation("g9")]))


class WaveGenerationTests(unittest.TestCase):
    def test_complete_newest_wins(self):
        newest = generation("g9-newest")
        chosen, source = swarm.wave_generation([newest, generation("g7-previous")])
        self.assertIs(chosen, newest)
        self.assertEqual(source, "newest")

    def test_incomplete_newest_falls_back_to_last_good(self):
        previous = generation("g7-previous")
        chosen, source = swarm.wave_generation([generation("g9-newest", exists=False), previous])
        self.assertIs(chosen, previous)
        self.assertEqual(source, "last-good")

    def test_no_complete_generation_returns_none(self):
        chosen, source = swarm.wave_generation([generation("g9", exists=False)])
        self.assertIsNone(chosen)
        self.assertEqual(source, "none")

    def test_empty_list_returns_none(self):
        self.assertEqual(swarm.wave_generation([]), (None, "none"))


class ParseGenerationsTests(unittest.TestCase):
    def test_empty_input_is_unmanaged(self):
        self.assertIsNone(swarm.parse_generations(""))
        self.assertIsNone(swarm.parse_generations("   "))

    def test_invalid_json_is_unmanaged(self):
        self.assertIsNone(swarm.parse_generations("not json"))

    def test_non_list_json_is_unmanaged(self):
        self.assertIsNone(swarm.parse_generations('{"tag": "g1"}'))

    def test_valid_list_is_returned(self):
        records = [{"tag": "g1-aaaaaaaaaaaa"}]
        self.assertEqual(swarm.parse_generations(json.dumps(records)), records)


class CommandWiringTests(unittest.TestCase):
    def run_command(self, generations=None, images=True):
        with mock.patch.object(swarm, "image_runs", return_value=[]), \
                mock.patch.object(swarm, "slot_manifests_exist", return_value=images), \
                mock.patch.object(swarm, "load_slots", return_value=[{"slot": 1}]), \
                mock.patch.object(swarm, "gh") as gh, \
                mock.patch.object(swarm, "emit") as emit:
            result = swarm.images_ready_command(slots=None, generations=generations)
        return result, gh, emit

    def test_reports_newest_generation_when_complete(self):
        newest = generation("g9-newest")
        (ready, reason, dispatch), _, emit = self.run_command([newest, generation("g7-previous")])
        self.assertTrue(ready)
        self.assertFalse(dispatch)
        self.assertIn("g9-newest", reason)
        self.assertIn("newest", reason)
        emit.assert_any_call("generation", "g9-newest")
        emit.assert_any_call("generation_source", "newest")

    def test_reports_fallback_when_newest_incomplete(self):
        previous = generation("g7-previous")
        (ready, _, dispatch), _, emit = self.run_command([generation("g9-newest", exists=False), previous])
        self.assertTrue(ready)
        self.assertFalse(dispatch)
        emit.assert_any_call("generation", "g7-previous")
        emit.assert_any_call("generation_source", "last-good")

    def test_no_complete_generation_makes_the_wave_wait(self):
        (ready, reason, dispatch), _, emit = self.run_command([generation("g9", exists=False)])
        self.assertFalse(ready)
        self.assertFalse(dispatch)
        self.assertIn("wave waits", reason)
        emit.assert_any_call("generation", "")
        emit.assert_any_call("generation_source", "none")

    def test_unmanaged_when_no_generations_supplied(self):
        (ready, _, dispatch), _, emit = self.run_command(None)
        self.assertTrue(ready)
        self.assertFalse(dispatch)
        emit.assert_any_call("generation", "")
        emit.assert_any_call("generation_source", "unmanaged")

    def test_cli_parses_generations_and_passes_them_to_the_command(self):
        payload = json.dumps([generation("g9-newest")])
        with mock.patch.object(swarm, "images_ready_command", return_value=(True, "ok", False)) as command:
            swarm.main(["images-ready", "--generations", payload])
        command.assert_called_once()
        passed = command.call_args.args[1]
        self.assertEqual([g["tag"] for g in passed], ["g9-newest"])


if __name__ == "__main__":
    unittest.main()
