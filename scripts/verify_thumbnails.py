"""Prove the v3 thumbnail feature works in *this* environment.

Run from the repository root::

    python scripts/verify_thumbnails.py

A v3 ``.ske`` file carries an encrypted JPEG preview between the header and the
body. Images are downscaled with Pillow; video frames are sampled with PyAV,
which bundles its own decoders.

Both paths must actually work in the environment that builds the release
binary. PyInstaller only bundles what is importable at build time, and
``crypto.py`` imports PyAV lazily inside a function, so a build machine without
PyAV still yields a fully working exe — one that silently drops every video
preview. That is a broken v3 release that no other check would catch, so this
script turns the silent downgrade into a failing build step.
"""

from __future__ import annotations

import os
import shutil
import sys
import tempfile
from pathlib import Path

# Imported at module level on purpose: a missing PyAV must fail loudly here
# instead of being swallowed by the lazy import inside the feature itself.
try:
    import av
except ImportError:  # pragma: no cover - only hit on a misconfigured build
    sys.exit("PyAV is not installed: install `.[video]` or video previews will be dropped.")

from PIL import Image

# Import the working tree rather than whatever happens to be installed, so this
# check exercises the code that is about to be packaged.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ske_cli.crypto import generate_thumbnail


def _write_sample_video(path: Path) -> None:
    """Encode a small video with PyAV's own encoder, without numpy."""
    with av.open(str(path), mode="w") as container:
        stream = container.add_stream("mpeg4", rate=10)
        stream.width, stream.height, stream.pix_fmt = 640, 480, "yuv420p"
        for index in range(120):
            frame = av.VideoFrame(640, 480, "yuv420p")
            for plane_index, plane in enumerate(frame.planes):
                level = (index * 7 + plane_index * 31) % 256
                plane.update(bytes([level]) * plane.buffer_size)
            for packet in stream.encode(frame):
                container.mux(packet)
        for packet in stream.encode():
            container.mux(packet)


def _check_video_preview(tmp: Path) -> str:
    sample = tmp / "sample.mp4"
    _write_sample_video(sample)
    thumb = generate_thumbnail(sample)
    if not thumb:
        sys.exit(f"no preview was produced for a {sample.stat().st_size}-byte video")
    if not thumb.startswith(b"\xff\xd8"):
        sys.exit("video preview is not a JPEG")
    backend = "PyAV" if shutil.which("ffmpeg") is None else "PyAV or system ffmpeg"
    return f"video {len(thumb)} B JPEG from {sample.stat().st_size} B ({backend})"


def _check_image_preview(tmp: Path) -> str:
    sample = tmp / "sample.png"
    # Noise rather than a flat fill: a solid colour compresses to a source
    # smaller than four times its own preview, which the quarter-size rule in
    # generate_thumbnail() correctly refuses to preview.
    noise = os.urandom(1024 * 1024 * 3)
    Image.frombytes("RGB", (1024, 1024), noise).save(sample)
    thumb = generate_thumbnail(sample)
    if not thumb or not thumb.startswith(b"\xff\xd8"):
        sys.exit("no JPEG preview was produced for a 1024x1024 image")
    return f"image {len(thumb)} B JPEG (Pillow)"


def main() -> int:
    with tempfile.TemporaryDirectory() as tmpdir:
        tmp = Path(tmpdir)
        print(f"PyAV {av.__version__} (libavcodec {av.library_versions.get('libavcodec')})")
        print("v3 thumbnail:", _check_image_preview(tmp))
        print("v3 thumbnail:", _check_video_preview(tmp))
    print("v3 thumbnail capability OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
