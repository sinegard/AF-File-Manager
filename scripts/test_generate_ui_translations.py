"""Offline checks for incremental catalog updates; no model, network, or APK runtime dependency."""

import importlib.util
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("ui_generator", Path(__file__).with_name("generate-ui-translations.py"))
generator = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = generator
spec.loader.exec_module(generator)


class CatalogUpdatesTest(unittest.TestCase):
    def test_manual_entry_covers_every_shipped_pack(self):
        for language in generator.LANGUAGES:
            self.assertTrue(generator.manual_overrides(language)["Terminal"].strip())

    def test_contextual_failures_are_composed_from_the_selected_pack_not_english_fragments(self):
        for language in generator.LANGUAGES:
            overrides = generator.manual_overrides(language)
            previous = generator.load_catalog_index(generator.INDEX_PATH)
            exact, _ = generator.load_pack(generator.ASSET_DIRECTORY / f"{language}.json")
            values = dict(zip(previous.exact, exact, strict=True))
            self.assertEqual(values["Trash"] + " · " + values["Folder cannot be read"],
                             overrides["The Trash folder cannot be read"])
            self.assertNotIn("must be empty", overrides["The top-level Trash path must be empty"])

    def test_manual_update_preserves_existing_values_and_templates(self):
        previous = generator.CatalogSource(["Files"], ["Saved as {0}"], {})
        current = generator.CatalogSource(["Files", "Terminal"], ["Saved as {0}"], {})
        exact, templates = generator.update_catalog(
            current, previous, ["Failid"], ["Salvestatud kui {0}"], "et", "est_Latn", None, None,
        )
        self.assertEqual(["Failid", "Terminal"], exact)
        self.assertEqual(["Salvestatud kui {0}"], templates)

    def test_missing_manual_translation_fails_instead_of_leaking_english(self):
        source = generator.CatalogSource(["A new untranslated action"], [], {})
        with self.assertRaisesRegex(ValueError, "manual translations are missing"):
            generator.translate_catalog(source, "ar", "arb_Arab", None, None)

    def test_manual_template_retains_its_placeholders(self):
        source = generator.CatalogSource([], ["Saved as {0}"], {})
        with patch.object(generator, "manual_overrides", return_value={"Saved as {0}": "Išsaugota kaip {0}"}):
            exact, templates = generator.translate_catalog(source, "lt", "lit_Latn", None, None)
        self.assertEqual([], exact)
        self.assertEqual(["Išsaugota kaip {0}"], templates)

    def test_broken_manual_placeholder_is_rejected(self):
        source = generator.CatalogSource(["Files"], ["Saved as {0}"], {})
        with self.assertRaisesRegex(ValueError, "placeholder mismatch"):
            generator.validate_pack(source, "lt", ["Failai"], ["Išsaugota kaip {1}"])

    def test_protected_protocol_names_are_not_translated(self):
        source = generator.CatalogSource(["FTP", "WebDAV"], [], {})
        exact, templates = generator.translate_catalog(source, "ar", "arb_Arab", None, None)
        self.assertEqual(["FTP", "WebDAV"], exact)
        self.assertEqual([], templates)

    def test_verification_rejects_a_stale_index_even_when_counts_match(self):
        source = generator.CatalogSource(["New name"], [], {})
        old = generator.CatalogSource(["Old name"], [], {})
        with patch.object(sys, "argv", ["generator", "--verify-only"]), \
                patch.object(generator, "collect_catalog", return_value=source), \
                patch.object(generator, "load_catalog_index", return_value=old):
            with self.assertRaisesRegex(SystemExit, "index does not match"):
                generator.main()


if __name__ == "__main__":
    unittest.main()
