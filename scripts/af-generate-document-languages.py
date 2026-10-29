import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sys

from huggingface_hub import snapshot_download
import torch

torch.set_num_threads(4)
torch.set_num_interop_threads(1)
revision = 'f8d333a098d19b4fd9a8b18f94170487ad3f821d'
model = snapshot_download('facebook/nllb-200-distilled-600M', revision=revision,
    allow_patterns=['config.json', 'generation_config.json', 'tokenizer.json',
        'tokenizer_config.json', 'special_tokens_map.json', 'sentencepiece.bpe.model', 'pytorch_model.bin'])
spec = importlib.util.spec_from_file_location('af_generator', 'scripts/generate-ui-translations.py')
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
module.MODEL_NAME = model
source = module.collect_catalog()
previous = module.load_catalog_index(module.INDEX_PATH)
assert set(previous.exact).issubset(source.exact)
assert previous.templates == source.templates
assert len(source.exact) - len(previous.exact) <= 128
inputs = [module.UI_TRANSLATOR, module.RUNTIME_TRANSLATIONS, module.DEFAULT_STRINGS,
    Path('scripts/generate-ui-translations.py'), module.OVERRIDES_PATH]
fingerprints = {str(p.relative_to(module.ROOT) if p.is_absolute() else p):
    hashlib.sha256(p.read_text(encoding='utf-8').replace('\r\n', '\n').encode('utf-8')).hexdigest() for p in inputs}
shard = int(os.environ['AF_LANGUAGE_SHARD'])
selected = sorted(module.LANGUAGES)[shard::8]
stage = Path('language-shard')
stage.mkdir()
shutil.copyfile('language-generation-dependencies.txt', stage / 'language-generation-dependencies.txt')
completed, outputs = [], [module.INDEX_PATH]
def checkpoint():
    output_hashes = {}
    for path in outputs:
        relative = path.relative_to(module.ROOT)
        target = stage / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if path == module.INDEX_PATH:
            # manual_overrides still reads the original positional packs/index.
            # Do not replace that index until every pack has been expanded.
            module.write_json(target, {'version': 1, 'exact': source.exact, 'templates': source.templates})
        else:
            shutil.copyfile(path, target)
        output_hashes[str(relative)] = hashlib.sha256(target.read_bytes()).hexdigest()
    manifest = {'source_commit': os.environ['GITHUB_SHA'], 'model_revision': revision,
        'inputs': fingerprints, 'exact_count': len(source.exact), 'template_count': len(source.templates),
        'shard': shard, 'shards': 8, 'languages': selected, 'completed_languages': completed,
        'status': 'complete' if completed == selected else 'partial',
        'outputs': output_hashes}
    (stage / 'language-generation-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
checkpoint()
translator = module.NllbTranslator(16)
for language in selected:
    print(f'Shard {shard}: starting {language}', flush=True)
    old_exact, old_templates = module.load_pack(module.ASSET_DIRECTORY / f'{language}.json')
    exact, templates = module.update_catalog(source, previous, old_exact, old_templates,
        language, module.LANGUAGES[language], translator, None, retry_english_fallbacks=False)
    module.validate_pack(source, language, exact, templates)
    path = module.ASSET_DIRECTORY / f'{language}.json'
    module.write_json(path, {'version': 1, 'language': language, 'exact': exact, 'templates': templates})
    module.write_android_strings(language, source, exact)
    outputs.append(path)
    for qualifier in (('tl', 'b+fil') if language == 'tl' else (language,)):
        outputs.append(module.RESOURCE_DIRECTORY / f'values-{qualifier}/strings.xml')
    completed.append(language)
    checkpoint()
    print(f'Shard {shard}: completed {language}, {len(completed)}/{len(selected)}', flush=True)
assert completed == selected
