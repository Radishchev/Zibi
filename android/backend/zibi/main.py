"""uvicorn entrypoint:  uvicorn zibi.main:app --reload"""
import logging

from .server import create_app

logging.basicConfig(level=logging.INFO)
app = create_app()
