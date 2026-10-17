"""Original registered 3-depth and constant-depth controls; no photos or AI content."""
import json
import math
import struct
import sys
import zipfile
import zlib
from pathlib import Path

out = Path(sys.argv[1] if len(sys.argv) > 1 else 'samples/spatial')
out.mkdir(parents=True, exist_ok=True)
w, h = 720, 1280
centers = [(240, 260, 150, 16), (480, 640, 180, 128), (240, 1020, 180, 240)]
image, depth, constant = bytearray(), bytearray(), bytearray()
for y in range(h):
    image.append(0); depth.append(0); constant.append(0)
    for x in range(w):
        r, g = round(x * 255 / (w - 1)), round(y * 255 / (h - 1))
        color = (35, 45, 60) if x % 60 < 3 or y % 60 < 3 else (160, 180, 195)
        z = 16
        for index, (cx, cy, radius, value) in enumerate(centers):
            distance = math.hypot(x - cx, y - cy)
            if distance <= radius:
                z = value
                if distance >= radius - 6:
                    color = (255, 255, 255) if index != 1 else (0, 0, 0)
                elif abs(x - cx) < 80 and abs(y - cy) < 100:
                    # Coordinate fiducials for measuring real shader sample positions.
                    color = (r, g, 255 if (x // 16 + y // 16) % 2 else 0)
                else:
                    color = (245, 245, 245) if (x // 14 + y // 14) % 2 else (10, 10, 10)
        image.extend(color); depth.extend((z, z, z)); constant.extend((128, 128, 128))

def png(raw):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b'')

original = png(image)
for name, title, values in [('diagnostic_3d', '3D 检测：上远中中下近', depth), ('diagnostic_constant', '3D 对照：恒定深度，无前后景差异', constant)]:
    manifest = {'format': 'floatdeck-spatial', 'version': 1, 'name': title,
                'assets': {'image': 'wallpaper.png', 'depth': 'depth.png'}, 'depthDirection': 'white-near',
                'preset': {'strength': 1.0, 'sensitivity': 1.0, 'maxAngleDegrees': 15.0, 'crop': 0.08}}
    with zipfile.ZipFile(out / f'{name}.spatial.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for filename, content in [('manifest.json', json.dumps(manifest, ensure_ascii=False, indent=2).encode()), ('wallpaper.png', original), ('depth.png', png(values))]:
            info = zipfile.ZipInfo(filename, (2026, 1, 1, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, content)
    print(f'{name}: {w}x{h}, depth={"16/128/240" if name == "diagnostic_3d" else "128"}')
