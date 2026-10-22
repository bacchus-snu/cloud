#!/usr/bin/env python3
"""Renders favicon.png and favicon.ico of a brand directory from its favicon.svg.

usage: make-favicons.py <brand dir>      (needs rsvg-convert; make-favicons.sh runs it in a container)

favicon.png is 64x64; favicon.ico holds 16, 32 and 48 pixel PNG frames. The PNG and every frame
of the ICO record the SHA-256 of the SVG they were rendered from in a tEXt chunk, so a test can
tell when the SVG was replaced without rendering the rasters again. The rasters must not depend on
fonts, so the SVG may not hold live text.
"""
import hashlib
import struct
import subprocess
import sys
import zlib
from pathlib import Path

PNG_SIZE = 64
ICO_SIZES = (16, 32, 48)
SOURCE_KEYWORD = b'Source-SHA256'


def render(svg, size):
    return subprocess.run(['rsvg-convert', '-w', str(size), '-h', str(size), str(svg)],
                          check=True, stdout=subprocess.PIPE).stdout


def with_source(png, digest):
    # a tEXt chunk just before IEND
    assert png[-12:-8] == b'\x00\x00\x00\x00' and png[-8:-4] == b'IEND'
    data = SOURCE_KEYWORD + b'\x00' + digest.encode('ascii')
    chunk = struct.pack('>I', len(data)) + b'tEXt' + data + struct.pack('>I', zlib.crc32(b'tEXt' + data))
    return png[:-12] + chunk + png[-12:]


def ico(frames):
    header = struct.pack('<HHH', 0, 1, len(frames))
    offset = len(header) + 16 * len(frames)
    entries, images = b'', b''
    for size, png in frames:
        entries += struct.pack('<BBBBHHII', size, size, 0, 0, 1, 32, len(png), offset + len(images))
        images += png
    return header + entries + images


def main():
    brand = Path(sys.argv[1])
    svg = brand / 'favicon.svg'
    if b'<text' in svg.read_bytes():
        sys.exit('favicon.svg holds live text; convert it to paths so that the rendering does not depend on fonts')
    digest = hashlib.sha256(svg.read_bytes()).hexdigest()
    (brand / 'favicon.png').write_bytes(with_source(render(svg, PNG_SIZE), digest))
    (brand / 'favicon.ico').write_bytes(ico([(size, with_source(render(svg, size), digest)) for size in ICO_SIZES]))


main()
