"""Create original geometric test assets using only Python's standard library.
Usage: python3 scripts/generate-spatial-fixtures.py work/test-assets
No photographs, AI content, or third-party artwork.
"""
import struct
import sys
import zlib
from pathlib import Path

width, height = 1080, 1920
out = Path(sys.argv[1] if len(sys.argv) > 1 else 'work/test-assets')
out.mkdir(parents=True, exist_ok=True)

def png(path, data):
    def chunk(kind, payload):
        return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', zlib.crc32(kind + payload) & 0xffffffff)
    header = struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)
    path.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(data)) + chunk(b'IEND', b''))

image, depth, mask = bytearray(), bytearray(), bytearray()
for y in range(height):
    image.append(0); depth.append(0); mask.append(0)
    for x in range(width):
        color, d, m = (75, 130, 170), 25, 0
        if (x - 800)**2 + (y - 370)**2 <= 140**2:
            color = (240, 204, 120)
        if ((x - 600)/900)**2 + ((y - 1725)/875)**2 <= 1:
            color, d = (58, 100, 110), 125
        if ((x - 355)/175)**2 + ((y - 1015)/435)**2 <= 1 or (320 <= x <= 380 and y >= 1200):
            color, d, m = (30, 62, 60), 240, 255
        image.extend(color); depth.extend((d, d, d)); mask.extend((m, m, m))
png(out / 'spatial-original.png', image)
png(out / 'spatial-depth.png', depth)
png(out / 'spatial-mask.png', mask)
print(f'Generated 1080x1920 original, depth (25/125/240), and subject mask in {out}')
