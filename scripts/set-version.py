"""Ändert die Projektversion ausdrücklich; normale Builds erhöhen sie nicht."""
from project_tools import main

if __name__ == "__main__":
    raise SystemExit(main("version"))
