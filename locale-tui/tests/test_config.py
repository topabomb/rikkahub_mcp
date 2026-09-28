from pathlib import Path
import os

import pytest
import yaml

from config import Config


def test_load_glossary_limits_without_accessing_credentials(tmp_path, monkeypatch):
    path = tmp_path / "config.yml"
    path.write_text('''project_root: .
modules:
  - name: app
    res_path: res
languages:
  - code: values
    name: English
    is_source: true
  - code: values-zh
    name: Chinese
translation:
  concurrency: 2
  retries: 4
  batch_size: 3
  glossary:
    values-zh:
      prompt: 提示词
''', encoding="utf-8")
    monkeypatch.setattr("config.load_dotenv", lambda: None)
    getenv = os.getenv
    monkeypatch.setattr("config.os.getenv", lambda name, default=None: "" if name in ("OPENAI_API_KEY", "OPENAI_BASE_URL") else getenv(name, default))
    config = Config.load(path)
    assert config.openai_api_key == ""
    assert (config.concurrency, config.retries, config.batch_size) == (2, 4, 3)
    assert config.glossary == {"values-zh": {"prompt": "提示词"}}


@pytest.mark.parametrize("terms", [{"invalid": {"prompt": "x"}}, {"values-zh": "not a mapping"}, {"values-zh": {"prompt": 1}}])
def test_invalid_glossary_fails_before_client(config, terms):
    config.glossary = terms
    with pytest.raises(ValueError, match="[Gg]lossary"):
        config.validate()


def test_repository_configuration_only_lists_existing_modules_and_five_languages():
    tool = Path(__file__).parent.parent
    data = yaml.safe_load((tool / "config.yml").read_text(encoding="utf-8"))
    root = (tool / data["project_root"]).resolve()
    assert {lang["code"] for lang in data["languages"]} == {"values", "values-zh", "values-ja", "values-ko-rKR", "values-ru"}
    assert "rag" not in {module["name"] for module in data["modules"]}
    for module in data["modules"]:
        assert (root / module["res_path"]).is_dir()
        assert all(list(root.glob(pattern)) for pattern in module["source_patterns"])
