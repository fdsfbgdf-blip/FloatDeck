"""Build real FloatDeck Spatial ZIP v1 packages from our original geometric fixtures."""
import json
import runpy
import struct
import sys
import tempfile
import zlib
import zipfile
from pathlib import Path

output = Path(sys.argv[1] if len(sys.argv) > 1 else 'samples/spatial')
output.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory() as temp:
    old_args = sys.argv
    sys.argv = ['generate-spatial-fixtures.py', temp]
    runpy.run_path(str(Path(__file__).with_name('generate-spatial-fixtures.py')), run_name='__main__')
    sys.argv = old_args
    fixtures = Path(temp)

    def invert_png(data, channels):
        offset, chunks, payload, header = 8, [], bytearray(), None
        while offset < len(data):
            length = struct.unpack_from('>I', data, offset)[0]
            kind = data[offset + 4:offset + 8]
            content = data[offset + 8:offset + 8 + length]
            if kind == b'IHDR': header = content
            if kind == b'IDAT': payload.extend(content)
            offset += 12 + length
        width, height = struct.unpack_from('>II', header)
        raw = bytearray(zlib.decompress(payload))
        for y in range(height):
            start = y * (width * 3 + 1)
            assert raw[start] == 0
            for x in range(width):
                for channel in channels:
                    index = start + 1 + x * 3 + channel
                    raw[index] = 255 - raw[index]
        def chunk(kind, content):
            return struct.pack('>I', len(content)) + kind + content + struct.pack('>I', zlib.crc32(kind + content) & 0xffffffff)
        return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b'')

    for variant, name, direction, strength in [('sunrise', '晨光森林 · 示例空间壁纸', 'white-near', 0.45), ('dusk', '暮色森林 · 示例空间壁纸', 'black-near', 0.65)]:
        image = (fixtures / 'spatial-original.png').read_bytes()
        depth = (fixtures / 'spatial-depth.png').read_bytes()
        if variant == 'dusk':
            image = invert_png(image, [0])
            depth = invert_png(depth, [0, 1, 2])
        manifest = {
            'format': 'floatdeck-spatial', 'version': 1, 'name': name,
            'assets': {'image': 'wallpaper.png', 'depth': 'depth.png', 'mask': 'mask.png'},
            'depthDirection': direction,
            'preset': {'strength': strength, 'sensitivity': 1.0, 'maxAngleDegrees': 8.0, 'crop': 0.04},
        }
        path = output / f'{variant}.spatial.zip'
        with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as archive:
            for filename, content in [('manifest.json', json.dumps(manifest, ensure_ascii=False, indent=2).encode()), ('wallpaper.png', image), ('depth.png', depth), ('mask.png', (fixtures / 'spatial-mask.png').read_bytes())]:
                info = zipfile.ZipInfo(filename, date_time=(2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, content)
        print(f'{path}: {path.stat().st_size} bytes')
