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
    try:
        return subprocess.check_output(['adb', *args], stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError as error:
        print(error.output.decode(errors='replace'))
        raise

def dump():
    # uiautomator can exit 0 without writing XML while a window is settling.
    # Remove the previous snapshot so a failed dump cannot pass on stale UI.
    errors = []
    for attempt in range(5):
        adb('shell', 'rm', '-f', '/sdcard/spatial-smoke.xml')
        result = adb('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/spatial-smoke.xml')
        read = subprocess.run(['adb', 'shell', 'cat', '/sdcard/spatial-smoke.xml'], capture_output=True)
        if read.returncode == 0:
            try:
                root = ET.fromstring(read.stdout)
                (output / 'ui.xml').write_bytes(read.stdout)
                return root.iter('node')
            except ET.ParseError as error:
                errors.append(str(error))
        errors.append(result.decode(errors='replace'))
        time.sleep(1)
    (output / 'dump-failure.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    raise AssertionError('Could not obtain fresh UI hierarchy: ' + repr(errors))

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
    # Destination choices must win over a dialog title also named 'Set wallpaper'.
    preferred = ['home screen', 'home screen and lock screen', 'set wallpaper', 'set as wallpaper', 'apply', 'set']
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

# Observe the installed Engine after foreground/background and screen-off/wake.
# Only ordinary input events on the emulator's default non-secure lock screen.
def diagnostic_engine():
    adb('shell', 'am', 'start', '-W', '-n', 'app.floatdeck.dev/app.floatdeck.spatial.SpatialActivity')
    for attempt in range(10):
        node = next((n for n in dump() if n.attrib.get('resource-id', '').endswith(':id/spatial_diagnostic_open') and n.attrib.get('enabled') == 'true'), None)
        if node is not None:
            click(node)
            break
        time.sleep(1)
    else:
        raise AssertionError('3D diagnostic entry not available')
    for attempt in range(10):
        nodes = list(dump())
        text = next((n.attrib.get('text', '') for n in nodes if n.attrib.get('resource-id', '').endswith(':id/spatial_diagnostic_info')), '')
        lines = re.findall(r'已安装壁纸引擎\[[^\]]+\]：[^\n]+', text)
        for line in lines:
            if '空间模式' in line and '晨光森林' in line and '强度0.370' in line and '深度25-240' in line:
                count = re.search(r'渲染帧(\d+)', line)
                if count and int(count.group(1)) > 0:
                    return line
        time.sleep(1)
    raise AssertionError('Installed spatial Engine did not report real frame/preset/depth state: ' + text)

before = diagnostic_engine()
adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
time.sleep(2)
adb('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
time.sleep(2)
adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
adb('shell', 'input', 'keyevent', 'KEYCODE_MENU')
adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
time.sleep(3)
after = diagnostic_engine()
(output / 'lifecycle-diagnostics.txt').write_text('before=' + before + '\nafter=' + after)
id_before = re.search(r'\[([^\]]+)\]', before).group(1)
id_after = re.search(r'\[([^\]]+)\]', after).group(1)
if id_before == id_after:
    assert int(re.search(r'渲染帧(\d+)', after).group(1)) > int(re.search(r'渲染帧(\d+)', before).group(1)), 'Engine failed to resume drawing after screen wake'
(output / 'diagnostic-status.png').write_bytes(adb('exec-out', 'screencap', '-p'))
print('PASS: installed wallpaper GL frame/preset/depth state restored after background and screen sleep/wake (emulator only)')
