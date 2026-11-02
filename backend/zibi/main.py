"""uvicorn entrypoint:  uvicorn zibi.main:app --reload"""
import logging

from .config import Settings, all_model_names, load_model_config, missing_api_keys, model_chain
from .server import create_app

logging.basicConfig(level=logging.INFO)
_log = logging.getLogger("zibi.main")

_settings = Settings.from_env()
_models = load_model_config(_settings.models_file)
for _key in missing_api_keys(all_model_names(_models)):
    _log.warning("%s is not set. Models that need it will fail. Export it in this terminal and restart.", _key)
_chain = model_chain(_models, "orchestrator")
_log.info("orchestrator model: %s (fallbacks: %s)", _chain[0], ", ".join(_chain[1:]) or "none")

app = create_app(settings=_settings)
