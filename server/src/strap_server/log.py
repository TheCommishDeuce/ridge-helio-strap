"""Logging entry point. Standard library logging; `extra=` fields ride along as usual."""

import logging


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(name)
