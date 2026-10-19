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
def png(raw):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b'')


# Three deterministic scene classes, entirely original procedural pixels.
for name, title, near_edge, textured in [
    ('v13_clear_depth','V1.3 清晰近中远景',False,False),
    ('v13_textured_edge','V1.3 纹理延伸到图像边缘',False,True),
    ('v13_foreground_edge','V1.3 近景贴左边界保护',True,True),
]:
    raw=bytearray(); zraw=bytearray()
    for y in range(h):
        raw.append(0); zraw.append(0)
        for x in range(w):
            z=16
            if (x-460)**2+(y-630)**2<115**2: z=128
            if (x-240)**2+(y-1000)**2<110**2: z=240
            if near_edge and x<110 and 430<y<760: z=240
            # Coordinate ramp + high-contrast fiducials, including the true source border.
            color=(round(x*255/(w-1)),round(y*255/(h-1)),220 if z==240 else 100 if z==128 else 35)
            # Calibrated coordinate fiducials must not mix with the white grid:
            # RGB is a source-coordinate encoding only inside these known patches.
            fiducial=any(abs(x-cx)<90 and abs(y-cy)<90 for cx,cy in [(240,260),(460,630),(240,1000)])
            if textured and not fiducial and (x%48<2 or y%48<2): color=(245,245,245)
            raw.extend(color); zraw.extend((z,z,z))
    manifest={'format':'floatdeck-spatial','version':1,'name':title,
        'assets':{'image':'wallpaper.png','depth':'depth.png'},'depthDirection':'white-near',
        'preset':{'strength':1.0,'sensitivity':1.0,'maxAngleDegrees':12.0,'crop':0.04}}
    with zipfile.ZipFile(out/f'{name}.spatial.zip','w',zipfile.ZIP_DEFLATED) as archive:
        for filename,content in [('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2).encode()),('wallpaper.png',png(raw)),('depth.png',png(zraw))]:
            info=zipfile.ZipInfo(filename,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
            archive.writestr(info,content)
    print(name)

