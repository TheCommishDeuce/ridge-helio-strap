"""Database access: one connection per unit of work, one transaction per connection."""

from collections.abc import Iterator
from contextlib import contextmanager

import psycopg
from psycopg.rows import TupleRow

from strap_server.config import get_settings


@contextmanager
def connection(conninfo: str | None = None) -> Iterator[psycopg.Connection[TupleRow]]:
    """A connection whose block is one transaction: committed on success, rolled back on error."""
    with psycopg.connect(conninfo or get_settings().conninfo()) as conn:
        yield conn
