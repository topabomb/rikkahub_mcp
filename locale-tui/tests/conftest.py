"""Default tests are offline; even an explicit test path requires --run-live."""
import socket
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent / "src"))


def pytest_addoption(parser):
    parser.addoption("--run-live", action="store_true", default=False, help="Explicitly allow real translation API tests")


def pytest_collection_modifyitems(config, items):
    if not config.getoption("--run-live"):
        for item in items:
            if "live_api" in item.keywords:
                item.add_marker(pytest.mark.skip(reason="Real API requires explicit --run-live"))


@pytest.fixture(autouse=True)
def offline_network(request, monkeypatch):
    if "live_api" not in request.node.keywords:
        original_connect = socket.socket.connect
        original_connect_ex = socket.socket.connect_ex
        def denied(sock, address):
            # Windows asyncio builds its wakeup socketpair through loopback TCP.
            if isinstance(address, tuple) and address[0] in ("127.0.0.1", "::1"):
                return original_connect(sock, address)
            raise AssertionError("Network access is forbidden in offline tests")
        def denied_ex(sock, address):
            if isinstance(address, tuple) and address[0] in ("127.0.0.1", "::1"):
                return original_connect_ex(sock, address)
            raise AssertionError("Network access is forbidden in offline tests")
        monkeypatch.setattr(socket.socket, "connect", denied)
        monkeypatch.setattr(socket.socket, "connect_ex", denied_ex)
        def no_real_client(*args, **kwargs):
            raise AssertionError("Real API clients are forbidden in offline tests")
        monkeypatch.setattr("services.translator.AsyncOpenAI", no_real_client)


@pytest.fixture
def config(tmp_path):
    from config import Config, LanguageConfig, ModuleConfig
    result = Config(
        openai_api_key="", openai_base_url="https://invalid.example/v1",
        project_root=tmp_path, modules=[ModuleConfig("app", "res")],
        languages=[LanguageConfig("values", "English", True), LanguageConfig("values-zh", "Chinese"),
                   LanguageConfig("values-ja", "Japanese")],
        translation_model="offline", translation_prompt="{target_language}\n{source_strings}\nGlossary: {glossary}",
        batch_size=2, column_widths={}, page_size=50, retries=1, concurrency=2,
        glossary={"values-zh": {"prompt": "提示词"}},
    )
    (tmp_path / "res").mkdir()
    return result
