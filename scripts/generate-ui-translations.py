#!/usr/bin/env python3
"""Generate AF File Manager's static offline interface language packs.

The generator is a development tool only. The Android app ships the resulting JSON/resources and
contains no translation SDK, model, telemetry, or runtime translation network call.
"""

from __future__ import annotations

import argparse
import json
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parents[1]
UI_TRANSLATOR = ROOT / "app/src/main/java/com/affilemanager/app/ui/localization/UiTranslator.kt"
RUNTIME_TRANSLATIONS = ROOT / "app/src/main/java/com/affilemanager/app/ui/localization/RuntimeMessageTranslations.kt"
DEFAULT_STRINGS = ROOT / "app/src/main/res/values/strings.xml"
ASSET_DIRECTORY = ROOT / "app/src/main/assets/i18n"
RESOURCE_DIRECTORY = ROOT / "app/src/main/res"
INDEX_PATH = ASSET_DIRECTORY / "index.json"
MODEL_NAME = "facebook/nllb-200-distilled-600M"

# BCP-47 tag -> NLLB language code. English and Lithuanian are maintained directly in source.
LANGUAGES = {
    "af": "afr_Latn",
    "ar": "arb_Arab",
    "be": "bel_Cyrl",
    "bg": "bul_Cyrl",
    "bn": "ben_Beng",
    "ca": "cat_Latn",
    "cs": "ces_Latn",
    "cy": "cym_Latn",
    "da": "dan_Latn",
    "de": "deu_Latn",
    "el": "ell_Grek",
    "eo": "epo_Latn",
    "es": "spa_Latn",
    "et": "est_Latn",
    "fa": "pes_Arab",
    "fi": "fin_Latn",
    "fr": "fra_Latn",
    "ga": "gle_Latn",
    "gl": "glg_Latn",
    "gu": "guj_Gujr",
    "he": "heb_Hebr",
    "hi": "hin_Deva",
    "hr": "hrv_Latn",
    "ht": "hat_Latn",
    "hu": "hun_Latn",
    "id": "ind_Latn",
    "is": "isl_Latn",
    "it": "ita_Latn",
    "ja": "jpn_Jpan",
    "ka": "kat_Geor",
    "kn": "kan_Knda",
    "ko": "kor_Hang",
    "lv": "lvs_Latn",
    "mk": "mkd_Cyrl",
    "mr": "mar_Deva",
    "ms": "zsm_Latn",
    "mt": "mlt_Latn",
    "nl": "nld_Latn",
    "no": "nob_Latn",
    "pl": "pol_Latn",
    "pt": "por_Latn",
    "ro": "ron_Latn",
    "ru": "rus_Cyrl",
    "sk": "slk_Latn",
    "sl": "slv_Latn",
    "sq": "als_Latn",
    "sv": "swe_Latn",
    "sw": "swh_Latn",
    "ta": "tam_Taml",
    "te": "tel_Telu",
    "th": "tha_Thai",
    "tl": "tgl_Latn",
    "tr": "tur_Latn",
    "uk": "ukr_Cyrl",
    "ur": "urd_Arab",
    "vi": "vie_Latn",
    "zh": "zho_Hans",
}

KOTLIN_PAIR = re.compile(r'"((?:\\.|[^"\\])*)"\s+to\s+"((?:\\.|[^"\\])*)"')
KOTLIN_STRING = re.compile(r'"((?:\\.|[^"\\])*)"')
TEMPLATE_TOKEN = re.compile(r"\{(\d+)}")
FORMAT_TOKEN = re.compile(r"(%\d+\$[a-zA-Z]|%%)")
PROTECTED_TEXT = re.compile(
    r"(%\d+\$[a-zA-Z]|%%|\{\d+}|AF File Manager|AF Plan|Copy more|"
    r"(?<![A-Za-z0-9])(?:Android|Shizuku|WebDAV|FTPS|SFTP|SMB|FTP|HTTPS|HTTP|TLS|SHA-256|"
    r"Wi-Fi|Ethernet|USB OTG|Keystore|Catppuccin|Material|AMOLED|EXIF|"
    r"UTF-8|APK|PDF|ZIP|RAR|7Z|TAR|GZ|LAN|NAS|SAF|UID|NUL|Root)(?![A-Za-z0-9]))",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class CatalogSource:
    exact: list[str]
    templates: list[str]
    android_strings: dict[str, str]


def kotlin_unescape(value: str) -> str:
    return json.loads(f'"{value}"')


def map_block(source: str, start_marker: str, end_marker: str) -> str:
    return source.split(start_marker, 1)[1].split(end_marker, 1)[0]


def map_values(source: str, start_marker: str, end_marker: str) -> list[str]:
    block = map_block(source, start_marker, end_marker)
    return [kotlin_unescape(match.group(2)) for match in KOTLIN_PAIR.finditer(block)]


def normalize_kotlin_template(value: str) -> str | None:
    output: list[str] = []
    position = 0
    placeholder_index = 0
    while position < len(value):
        if value.startswith("${", position):
            depth = 1
            cursor = position + 2
            quote: str | None = None
            escaped = False
            while cursor < len(value) and depth:
                character = value[cursor]
                if quote is not None:
                    if escaped:
                        escaped = False
                    elif character == "\\":
                        escaped = True
                    elif character == quote:
                        quote = None
                elif character in {'"', "'"}:
                    quote = character
                elif character == "{":
                    depth += 1
                elif character == "}":
                    depth -= 1
                cursor += 1
            if depth:
                return None
            output.append(f"{{{placeholder_index}}}")
            placeholder_index += 1
            position = cursor
            continue
        if value[position] == "$" and position + 1 < len(value) and (value[position + 1].isalpha() or value[position + 1] == "_"):
            cursor = position + 2
            while cursor < len(value) and (value[cursor].isalnum() or value[cursor] == "_"):
                cursor += 1
            output.append(f"{{{placeholder_index}}}")
            placeholder_index += 1
            position = cursor
            continue
        output.append(value[position])
        position += 1

    normalized = "".join(output)
    if placeholder_index == 0 or not re.search(r"[A-Za-z]", normalized):
        return None
    return normalized


def output_templates(ui_source: str) -> list[str]:
    block = map_block(ui_source, "        val patterns = listOf(", "    private val lithuanianPatterns")
    templates = {
        template
        for match in KOTLIN_STRING.finditer(block)
        if (template := normalize_kotlin_template(kotlin_unescape(match.group(1)))) is not None
    }
    # More literal text first reduces ambiguity when the runtime compiles matchers.
    return sorted(templates, key=lambda value: (-len(TEMPLATE_TOKEN.sub("", value)), value))


def android_strings() -> dict[str, str]:
    root = ET.parse(DEFAULT_STRINGS).getroot()
    return {element.attrib["name"]: "".join(element.itertext()) for element in root.findall("string")}


def collect_catalog() -> CatalogSource:
    ui_source = UI_TRANSLATOR.read_text(encoding="utf-8")
    runtime_source = RUNTIME_TRANSLATIONS.read_text(encoding="utf-8")
    resources = android_strings()
    values = set(
        map_values(ui_source, "    private val english = mapOf(", "\n    fun translate(")
    )
    values.update(
        map_values(runtime_source, "    val english = mapOf(", "\n    val lithuanian = mapOf(")
    )
    values.update(resources.values())
    values.discard("")
    return CatalogSource(
        exact=sorted(values),
        templates=output_templates(ui_source),
        android_strings=resources,
    )


class NllbTranslator:
    def __init__(self, batch_size: int) -> None:
        import torch
        from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

        self.torch = torch
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        self.batch_size = batch_size
        self.tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME, src_lang="eng_Latn")
        dtype = torch.float16 if self.device == "cuda" else torch.float32
        self.model = AutoModelForSeq2SeqLM.from_pretrained(MODEL_NAME, dtype=dtype)
        self.model.to(self.device)
        self.model.eval()

    def translate_many(self, values: Iterable[str], target_code: str) -> dict[str, str]:
        values = list(dict.fromkeys(values))
        translated: dict[str, str] = {}
        target_token = self.tokenizer.convert_tokens_to_ids(target_code)
        ordered = sorted(values, key=len)
        for start in range(0, len(ordered), self.batch_size):
            batch = ordered[start : start + self.batch_size]
            encoded = self.tokenizer(
                batch,
                return_tensors="pt",
                padding=True,
                truncation=True,
                max_length=256,
            ).to(self.device)
            with self.torch.inference_mode():
                generated = self.model.generate(
                    **encoded,
                    forced_bos_token_id=target_token,
                    max_length=256,
                    num_beams=1,
                )
            decoded = self.tokenizer.batch_decode(generated, skip_special_tokens=True)
            for source, target in zip(batch, decoded, strict=True):
                cleaned = target.strip()
                translated[source] = cleaned if cleaned and "<unk>" not in cleaned else source
            print(f"  translated {min(start + len(batch), len(ordered))}/{len(ordered)}", flush=True)
        return translated


def mask_protected_text(value: str) -> tuple[str, list[tuple[str, str]]]:
    replacements: list[tuple[str, str]] = []

    def replace(match: re.Match[str]) -> str:
        marker = f"<AFPH{len(replacements)}>"
        replacements.append((marker, match.group(0)))
        return marker

    return PROTECTED_TEXT.sub(replace, value), replacements


def restore_protected_text(source: str, translated: str, replacements: list[tuple[str, str]]) -> str:
    restored = translated
    for marker, value in replacements:
        if restored.count(marker) != 1:
            return source
        restored = restored.replace(marker, value)

    quote_pairs = (("“", "”"), ("\"", "\""), ("'", "'"))
    for _, value in replacements:
        if not (TEMPLATE_TOKEN.fullmatch(value) or FORMAT_TOKEN.fullmatch(value)):
            continue
        for opening, closing in quote_pairs:
            if f"{opening}{value}{closing}" in source:
                quoted = re.compile(rf"[\"'“”„«»]*{re.escape(value)}[\"'“”„«»]*")
                restored = quoted.sub(f"{opening}{value}{closing}", restored, count=1)
                break
    return restored


def android_string_value(element: ET.Element) -> str:
    value = "".join(element.itertext())
    if len(value) >= 2 and value.startswith('"') and value.endswith('"'):
        value = value[1:-1]
    return value.replace("\\'", "'").replace("\\n", "\n").replace("\\t", "\t")


def aosp_overrides(android_res: Path | None, language: str, source_values: set[str]) -> dict[str, str]:
    if android_res is None or not android_res.is_dir():
        return {}
    qualifier = {"he": "iw", "id": "in", "no": "nb", "tl": "fil", "zh": "zh-rCN"}.get(language, language)
    default_file = android_res / "values/strings.xml"
    localized_file = android_res / f"values-{qualifier}/strings.xml"
    if not default_file.is_file() or not localized_file.is_file():
        return {}

    default = {
        element.attrib["name"]: android_string_value(element)
        for element in ET.parse(default_file).getroot().findall("string")
    }
    localized = {
        element.attrib["name"]: android_string_value(element)
        for element in ET.parse(localized_file).getroot().findall("string")
    }
    overrides: dict[str, str] = {}
    for name, english in default.items():
        translated = localized.get(name)
        if english in source_values and translated and translated.strip():
            overrides.setdefault(english, translated)
    return overrides


def translate_catalog(
    source: CatalogSource,
    language: str,
    target_code: str,
    translator: NllbTranslator,
    android_res: Path | None,
) -> tuple[list[str], list[str]]:
    all_values = source.exact + source.templates
    masked = {value: mask_protected_text(value) for value in all_values}
    meaningful = [
        masked_value
        for value, (masked_value, _) in masked.items()
        if re.search(r"[A-Za-z]", PROTECTED_TEXT.sub("", value))
    ]
    translated_values = translator.translate_many(meaningful, target_code)

    def translated(value: str) -> str:
        masked_value, replacements = masked[value]
        if masked_value not in translated_values:
            return value
        return restore_protected_text(value, translated_values[masked_value], replacements)

    exact = [translated(value) for value in source.exact]
    overrides = aosp_overrides(android_res, language, set(source.exact))
    exact = [overrides.get(value, translated_value) for value, translated_value in zip(source.exact, exact, strict=True)]
    templates = [translated(value) for value in source.templates]
    return exact, templates


def write_json(path: Path, payload: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")


def android_escape(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "\\'")
    )


def write_android_strings(language: str, source: CatalogSource, translated_exact: list[str]) -> None:
    translations = dict(zip(source.exact, translated_exact, strict=True))
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for name, english in source.android_strings.items():
        translated = "AF File Manager" if name == "app_name" else translations.get(english, english)
        lines.append(f'    <string name="{name}">{android_escape(translated)}</string>')
    lines.append("</resources>")
    directory = RESOURCE_DIRECTORY / f"values-{language}"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "strings.xml").write_text("\n".join(lines) + "\n", encoding="utf-8")


def validate_pack(source: CatalogSource, language: str, exact: list[str], templates: list[str]) -> None:
    if len(exact) != len(source.exact) or len(templates) != len(source.templates):
        raise ValueError(f"{language}: translation counts do not match the catalog")
    if any(not value.strip() for value in exact + templates):
        raise ValueError(f"{language}: blank translations are not allowed")
    if any("\ufffd" in value or "<AFPH" in value or "\x00" in value for value in exact + templates):
        raise ValueError(f"{language}: a generated translation contains a broken marker or character")
    eligible = [
        (source_value, translated_value)
        for source_value, translated_value in zip(source.exact, exact, strict=True)
        if re.search(r"[A-Za-z]", source_value)
    ]
    changed = sum(source_value != translated_value for source_value, translated_value in eligible)
    if not eligible or changed / len(eligible) < 0.90:
        raise ValueError(f"{language}: too much of the interface remained in English")
    for source_value, translated_value in zip(source.exact, exact, strict=True):
        expected_formats = sorted(FORMAT_TOKEN.findall(source_value))
        actual_formats = sorted(FORMAT_TOKEN.findall(translated_value))
        if actual_formats != expected_formats:
            raise ValueError(
                f"{language}: Android format mismatch: {source_value!r} -> {translated_value!r}"
            )
        protected_terms = [
            match.group(0)
            for match in PROTECTED_TEXT.finditer(source_value)
            if not FORMAT_TOKEN.fullmatch(match.group(0)) and not TEMPLATE_TOKEN.fullmatch(match.group(0))
        ]
        if any(term not in translated_value for term in protected_terms):
            raise ValueError(
                f"{language}: protected interface term changed: {source_value!r} -> {translated_value!r}"
            )
    for source_template, translated_template in zip(source.templates, templates, strict=True):
        expected = sorted(TEMPLATE_TOKEN.findall(source_template))
        actual = sorted(TEMPLATE_TOKEN.findall(translated_template))
        if actual != expected:
            raise ValueError(
                f"{language}: placeholder mismatch: {source_template!r} -> {translated_template!r}"
            )
        protected_terms = [
            match.group(0)
            for match in PROTECTED_TEXT.finditer(source_template)
            if not TEMPLATE_TOKEN.fullmatch(match.group(0))
        ]
        if any(term not in translated_template for term in protected_terms):
            raise ValueError(
                f"{language}: protected template term changed: {source_template!r} -> {translated_template!r}"
            )


def load_pack(path: Path) -> tuple[list[str], list[str]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return payload["exact"], payload["templates"]


def load_catalog_index(path: Path) -> CatalogSource:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return CatalogSource(
        exact=payload["exact"],
        templates=payload["templates"],
        android_strings={},
    )


def update_catalog(
    source: CatalogSource,
    previous_source: CatalogSource,
    previous_exact: list[str],
    previous_templates: list[str],
    language: str,
    target_code: str,
    translator: NllbTranslator,
    android_res: Path | None,
) -> tuple[list[str], list[str]]:
    if len(previous_exact) != len(previous_source.exact) or len(previous_templates) != len(previous_source.templates):
        raise ValueError(f"{language}: existing pack does not match the previous catalog")

    exact_by_source = dict(zip(previous_source.exact, previous_exact, strict=True))
    template_by_source = dict(zip(previous_source.templates, previous_templates, strict=True))
    missing_source = CatalogSource(
        exact=[value for value in source.exact if value not in exact_by_source],
        templates=[value for value in source.templates if value not in template_by_source],
        android_strings={},
    )
    translated_exact, translated_templates = translate_catalog(
        missing_source,
        language,
        target_code,
        translator,
        android_res,
    )
    exact_by_source.update(zip(missing_source.exact, translated_exact, strict=True))
    template_by_source.update(zip(missing_source.templates, translated_templates, strict=True))
    return (
        [exact_by_source[value] for value in source.exact],
        [template_by_source[value] for value in source.templates],
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--languages", help="Comma-separated BCP-47 tags; defaults to every generated pack")
    parser.add_argument("--batch-size", type=int, default=48)
    parser.add_argument(
        "--android-res",
        type=Path,
        help="Optional Android platform data/res directory used for human-reviewed standard labels",
    )
    parser.add_argument("--force", action="store_true")
    parser.add_argument(
        "--update-existing",
        action="store_true",
        help="Translate only catalog entries missing from existing language packs",
    )
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()

    selected = list(LANGUAGES)
    if args.languages:
        selected = [tag.strip() for tag in args.languages.split(",") if tag.strip()]
    unknown = sorted(set(selected) - LANGUAGES.keys())
    if unknown:
        raise SystemExit(f"Unknown language tags: {', '.join(unknown)}")

    source = collect_catalog()
    previous_source = load_catalog_index(INDEX_PATH) if INDEX_PATH.is_file() else None
    catalog_changed = previous_source is None or (
        previous_source.exact != source.exact or previous_source.templates != source.templates
    )
    print(f"Catalog: {len(source.exact)} exact strings, {len(source.templates)} templates", flush=True)

    if args.verify_only:
        for language in selected:
            exact, templates = load_pack(ASSET_DIRECTORY / f"{language}.json")
            validate_pack(source, language, exact, templates)
        print(f"Verified {len(selected)} language packs", flush=True)
        return

    if catalog_changed and set(selected) != set(LANGUAGES):
        raise SystemExit("A changed catalog must update every language pack in one run")

    pending = [
        tag
        for tag in selected
        if args.force or args.update_existing or not (ASSET_DIRECTORY / f"{tag}.json").is_file()
    ]
    translator = NllbTranslator(args.batch_size) if pending else None
    generated: dict[str, tuple[list[str], list[str]]] = {}
    for language in selected:
        output = ASSET_DIRECTORY / f"{language}.json"
        if language not in pending:
            exact, templates = load_pack(output)
            validate_pack(source, language, exact, templates)
            generated[language] = (exact, templates)
            print(f"{language}: existing pack verified", flush=True)
            continue

        assert translator is not None
        if output.is_file() and not args.force:
            if not args.update_existing:
                raise ValueError(f"{language}: existing pack is stale; use --update-existing or --force")
            if previous_source is None:
                raise ValueError(f"{language}: the previous catalog is unavailable")
            print(f"{language}: translating new catalog entries", flush=True)
            previous_exact, previous_templates = load_pack(output)
            exact, templates = update_catalog(
                source,
                previous_source,
                previous_exact,
                previous_templates,
                language,
                LANGUAGES[language],
                translator,
                args.android_res,
            )
        else:
            print(f"{language}: generating", flush=True)
            exact, templates = translate_catalog(
                source,
                language,
                LANGUAGES[language],
                translator,
                args.android_res,
            )
        validate_pack(source, language, exact, templates)
        generated[language] = (exact, templates)

    for language, (exact, templates) in generated.items():
        output = ASSET_DIRECTORY / f"{language}.json"
        write_json(output, {"version": 1, "language": language, "exact": exact, "templates": templates})
        write_android_strings(language, source, exact)
        print(f"{language}: saved", flush=True)

    if set(selected) == set(LANGUAGES):
        write_json(INDEX_PATH, {"version": 1, "exact": source.exact, "templates": source.templates})


if __name__ == "__main__":
    main()
