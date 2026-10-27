"""Verify a built PyInstaller bundle still carries the preview dependencies.

Usage::

    python scripts/verify_bundle.py build/<artifact_name>

The dangerous failure mode is silent: PyInstaller happily produces an exe
without PyAV, tkdnd or Pillow's codecs, and the only symptom shows up on a user
machine — video previews missing, drag-and-drop dead, images with no preview.
Checking the analysis manifests PyInstaller writes next to the build catches
that before the artifact is uploaded, and costs nothing compared with launching
the GUI.

``PKG-00.toc`` lists the files packed into the onefile archive; ``PYZ-00.toc``
lists the Python modules inside it.
"""

from __future__ import annotations

import sys
from pathlib import Path

# (manifest, needle, what it proves)
CHECKS = (
    ("PKG-00.toc", "tkdnd", "tkdnd native library (drag-and-drop)"),
    ("PKG-00.toc", "avcodec", "FFmpeg libavcodec (video frame sampling)"),
    ("PKG-00.toc", "_imaging.", "Pillow imaging codec (image previews)"),
    ("PYZ-00.toc", "'av.container", "PyAV container module"),
    ("PYZ-00.toc", "'PIL.JpegImagePlugin", "Pillow JPEG encoder"),
)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__.strip().splitlines()[2].strip(), file=sys.stderr)
        return 2

    build_dir = Path(argv[1])
    if not build_dir.is_dir():
        print(f"::error::build directory not found: {build_dir}", file=sys.stderr)
        return 2

    manifests: dict[str, str | None] = {}
    for manifest, _, _ in CHECKS:
        path = build_dir / manifest
        manifests[manifest] = (
            path.read_text(encoding="utf-8", errors="replace") if path.is_file() else None
        )

    failures = []
    for manifest, needle, description in CHECKS:
        text = manifests[manifest]
        if text is None:
            missing = f"{manifest} is missing from {build_dir}"
            if missing not in failures:
                failures.append(missing)
        elif needle in text:
            print(f"ok    {description}")
        else:
            failures.append(f"{description} is missing from the bundle ({needle!r})")

    if failures:
        for failure in failures:
            print(f"::error::{failure}")
        return 1

    print("bundle carries the preview and drag-and-drop dependencies")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
