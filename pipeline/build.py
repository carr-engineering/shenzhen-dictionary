#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy"]
# ///
"""Dataset pipeline entry point.

    uv run pipeline/build.py [--fixture] [--only mandarin|cantonese] [--no-validate]

Outputs (contract/SCHEMA.md) go to pipeline/out/ (or pipeline/out/fixture/ with --fixture).
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from szdict.run import main  # noqa: E402

if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--fixture", action="store_true", help="build the small fixture dataset (ARCH-151)")
    ap.add_argument("--only", choices=["mandarin", "cantonese"])
    ap.add_argument("--no-validate", action="store_true")
    ap.add_argument("--update-golden", action="store_true", help="rewrite contract/golden-queries.json from this build")
    args = ap.parse_args()
    sys.exit(main(args))
