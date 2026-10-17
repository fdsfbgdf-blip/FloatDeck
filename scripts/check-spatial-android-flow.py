"""Emulator smoke test through public UI: force-stop, restore, apply, verify wallpaper.
Requires the Android integration test to have selected the bundled sunrise package.
No app hidden API, shell permission adoption, root or security lock-screen changes.
"""
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

output = Path('work/android-smoke')
output.mkdir(parents=True, exist_ok=True)

def adb(*args):
    return subprocess.check_output(['adb', *args], stderr=subprocess.STDOUT)

def dump():
    adb('shell', 'uiautomator', 'dump', '/sdcard/spatial-smoke.xml')
    data = adb('shell', 'cat', '/sdcard/spatial-smoke.xml')
    (output / 'ui.xml').write_bytes(data)
    return ET.fromstring(data).iter('node')

def click(node):
    bounds = list(map(int, re.findall(r'\d+', node.attrib['bounds'])))
    adb('shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))

adb('shell', 'am', 'force-stop', 'app.floatdeck.dev')
adb('shell', 'am', 'start', '-W', '-n', 'app.floatdeck.dev/app.floatdeck.spatial.SpatialActivity')
for attempt in range(10):
    nodes = list(dump())
    texts = [node.attrib.get('text', '') for node in nodes]
    if any('晨光森林' in text for text in texts) and any('37%' in text for text in texts):
        apply = next((node for node in nodes if node.attrib.get('resource-id', '').endswith(':id/spatial_apply') and node.attrib.get('enabled') == 'true'), None)
        if apply is not None:
            click(apply)
            break
    time.sleep(1)
else:
    raise AssertionError('Selected wallpaper and strength did not survive a process force-stop')

applied = False
for attempt in range(12):
    state = adb('shell', 'dumpsys', 'wallpaper').decode(errors='replace')
    (output / 'wallpaper-state.txt').write_text(state)
    if re.search(r'mWallpaperComponent=ComponentInfo\{app\.floatdeck\.dev/app\.floatdeck\.service\.FloatDeckWallpaperService\}', state):
        applied = True
        break
    nodes = list(dump())
    preferred = ['set wallpaper', 'home screen', 'home screen and lock screen', 'set as wallpaper', 'apply', 'set']
    chosen = None
    for label in preferred:
        chosen = next((node for node in nodes if node.attrib.get('text', '').strip().lower() == label and node.attrib.get('enabled') == 'true'), None)
        if chosen is not None:
            break
    if chosen is not None:
        click(chosen)
    time.sleep(1)
assert applied, 'Public wallpaper picker did not confirm the live wallpaper component'
adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
time.sleep(1)
(output / 'applied-home.png').write_bytes(adb('exec-out', 'screencap', '-p'))
print('PASS: selected package and 37% strength survived process restart; public system picker applied FloatDeckWallpaperService')
