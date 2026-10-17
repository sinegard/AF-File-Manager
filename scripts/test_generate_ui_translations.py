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
    def test_lost_marker_retries_the_natural_sentence_without_changing_protected_terms(self):
        source = generator.CatalogSource(["Open Android storage settings"], [], {})
        class Translator:
            def translate_many(self, values, target):
                return {value: {"Open <AFPH0> storage settings": "marqueur perdu",
                    "Open Android storage settings": "Ouvrir les paramètres de stockage Android"}.get(value, value) for value in values}
        exact, _ = generator.translate_catalog(source, "fr", "fra_Latn", Translator(), None)
        self.assertEqual(["Ouvrir les paramètres de stockage Android"], exact)

    def test_protected_phrase_fallback_keeps_the_original_terms_and_format_arguments(self):
        source = generator.CatalogSource(["Open Android settings"], ["Saved to {0}"], {})
        class Translator:
            def translate_many(self, values, target):
                return {value: {"Open <AFPH0> settings": "marqueur perdu", "Open Android settings": "Ouvrir les paramètres Androïde",
                    "Open": "Ouvrir", "settings": "paramètres", "Saved to <AFPH0>": "marqueur perdu",
                    "Saved to {0}": "Enregistré ici", "Saved to": "Enregistré dans"}.get(value, value) for value in values}
        exact, templates = generator.translate_catalog(source, "fr", "fra_Latn", Translator(), None)
        self.assertEqual(["Ouvrir Android paramètres"], exact)
        self.assertEqual(["Enregistré dans {0}"], templates)
        generator.validate_pack(source, "fr", exact, templates)

    def test_opt_in_retries_only_unchanged_prose_and_preserves_good_translations(self):
        source = generator.CatalogSource(["Files", "Open Android storage settings", "FTP"], ["Saved to {0}", "Copied {0}"] , {})
        class Translator:
            def translate_many(self, values, target):
                return {value: {"Open <AFPH0> storage settings": "Ouvrir les paramètres de stockage <AFPH0>",
                    "Saved to <AFPH0>": "Enregistré dans <AFPH0>"}.get(value, value) for value in values}
        previous_exact = ["Fichiers", "Open Android storage settings", "FTP"]
        previous_templates = ["Saved to {0}", "Copié {0}"]
        exact, templates = generator.update_catalog(source, source, previous_exact, previous_templates,
            "fr", "fra_Latn", Translator(), None, retry_english_fallbacks=True)
        self.assertEqual(["Fichiers", "Ouvrir les paramètres de stockage Android", "FTP"], exact)
        self.assertEqual(["Enregistré dans {0}", "Copié {0}"], templates)

    def test_default_update_does_not_silently_retranslate_existing_copy(self):
        source = generator.CatalogSource(["Open Android storage settings"], [], {})
        exact, _ = generator.update_catalog(source, source, source.exact, [], "fr", "fra_Latn", None, None)
        self.assertEqual(source.exact, exact)

    def test_technical_names_and_single_word_loans_are_not_english_prose(self):
        for value in ("AF File Manager", "SMB 2/3 · SFTP · WebDAV · FTP/FTPS", "Stereo", "Mono", "Rotation"):
            self.assertFalse(generator.is_english_prose_fallback(value, value), value)
        self.assertTrue(generator.is_english_prose_fallback("Open Android storage settings", "Open Android storage settings"))
        self.assertTrue(generator.is_english_prose_fallback("Files: {0}", "Files: {0}"))
        self.assertFalse(generator.is_english_prose_fallback("Open settings", "Ouvrir les paramètres"))

    def test_unsafe_direct_result_cannot_introduce_or_drop_an_argument(self):
        source = generator.CatalogSource([], ["Saved to {0}"], {})
        class Translator:
            def translate_many(self, values, target):
                return {value: {"Saved to <AFPH0>": "ici", "Saved to {0}": "Enregistré dans {1}",
                    "Saved to": "Enregistré dans"}.get(value, value) for value in values}
        _, templates = generator.translate_catalog(source, "fr", "fra_Latn", Translator(), None)
        self.assertEqual(["Enregistré dans {0}"], templates)

    def test_manual_entry_covers_every_shipped_pack(self):
        for language in generator.LANGUAGES:
            self.assertTrue(generator.manual_overrides(language)["Terminal"].strip())

    def test_clear_is_an_action_not_an_adjective(self):
        self.assertEqual("Leeren", generator.manual_overrides("de")["Clear"])
        self.assertEqual("مسح", generator.manual_overrides("ar")["Clear"])
        self.assertEqual("Effacer", generator.manual_overrides("fr")["Clear"])
        self.assertEqual("清除", generator.manual_overrides("zh")["Clear"])

    def test_every_shipped_pack_has_the_explicit_clear_action(self):
        index = generator.load_catalog_index(generator.INDEX_PATH)
        position = index.exact.index("Clear")
        for language in generator.LANGUAGES:
            expected = generator.manual_overrides(language)["Clear"]
            self.assertTrue(expected.strip())
            exact, _ = generator.load_pack(generator.ASSET_DIRECTORY / f"{language}.json")
            self.assertEqual(expected, exact[position], language)

    def test_pairing_actions_are_contextual_and_preserve_the_af_name(self):
        index = generator.load_catalog_index(generator.INDEX_PATH)
        for language in ("de", "ar"):
            overrides = generator.manual_overrides(language)
            self.assertIn("AF", overrides["Find nearby AF devices"])
            self.assertEqual(overrides["Scan QR code"], overrides["Scan QR"])
            exact, _ = generator.load_pack(generator.ASSET_DIRECTORY / f"{language}.json")
            for key in ("Scan QR code", "Scan QR", "Find nearby AF devices"):
                self.assertEqual(overrides[key], exact[index.exact.index(key)])

    def test_german_transfer_buttons_use_actions_not_receipt_nouns(self):
        overrides = generator.manual_overrides("de")
        self.assertEqual("Senden", overrides["Send"])
        self.assertEqual("Empfangen", overrides["Receive"])
        index = generator.load_catalog_index(generator.INDEX_PATH)
        exact, _ = generator.load_pack(generator.ASSET_DIRECTORY / "de.json")
        for key in ("Send", "Receive"):
            self.assertEqual(overrides[key], exact[index.exact.index(key)])

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
