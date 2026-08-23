#!/usr/bin/env python3
"""Empaqueta PNGs en un .ico de Windows (entradas comprimidas en PNG).

Uso:
    python3 tools/make_ico.py salida.ico entrada1.png [entrada2.png ...]

Los tamaños se deducen de cada PNG; los lados deben ser <= 256.
"""
import struct
import sys
from pathlib import Path


def png_size(data):
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "no es un PNG"
    w = struct.unpack(">I", data[16:20])[0]
    h = struct.unpack(">I", data[20:24])[0]
    return w, h


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    out = Path(sys.argv[1])
    entries = []
    for arg in sys.argv[2:]:
        data = Path(arg).read_bytes()
        w, h = png_size(data)
        if w > 256 or h > 256:
            sys.exit("lado > 256 en %s" % arg)
        entries.append((w, h, data))
    entries.sort(key=lambda e: e[0])

    header = struct.pack("<HHH", 0, 1, len(entries))
    body = b""
    directory = b""
    offset = 6 + 16 * len(entries)
    for w, h, data in entries:
        directory += struct.pack(
            "<BBBBHHII",
            w % 256,          # 0 significa 256
            h % 256,
            0,                # paleta
            0,                # reservado
            1,                # planos
            32,               # bits por pixel
            len(data),
            offset,
        )
        body += data
        offset += len(data)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(header + directory + body)
    print("ICO: %s (%d tamanos)" % (out, len(entries)))


if __name__ == "__main__":
    main()
