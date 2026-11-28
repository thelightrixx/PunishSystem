"""Explicit version update; ordinary builds never change versions."""
from project_tools import main

if __name__ == "__main__":
    raise SystemExit(main("version"))
