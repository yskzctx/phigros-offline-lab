"""Check the rendered panel actually shows the forced-settlement card.

Reading the screenshot is not possible in this session, so the rendered DOM is
inspected instead: the card must be present, visible (not display:none), and contain
every input the save handler reads. A layout that silently collapses - for example a
card left in a hidden page - would look fine in the HTML source but not on the phone.
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SOURCE = Path(r'I:\dsh\work\phigros-offline-lab\ui\controls-ui.html')

EXPECTED_INPUTS = [
    'force-enabled', 'force-score', 'force-accuracy',
    'force-perfect', 'force-good', 'force-bad', 'force-miss', 'force-combo',
    'force-symbol', 'force-early-late-on', 'force-early', 'force-late',
]
EXPECTED_BUTTONS = ['force-save', 'force-clear']


def find_chrome():
    for candidate in (
        r'C:\Program Files\Google\Chrome\Application\chrome.exe',
        r'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe',
        r'C:\Program Files\Microsoft\Edge\Application\msedge.exe',
    ):
        if Path(candidate).is_file():
            return candidate
    return shutil.which('chrome') or shutil.which('chromium') or shutil.which('msedge')


PROBE = r"""
const ids = %s;
const buttons = %s;
const out = {inputs: {}, buttons: {}, cardVisible: false, cardTitle: null, errors: []};

// Find the card that holds the forced-settlement controls.
const first = document.getElementById('force-enabled');
if (!first) {
  out.errors.push('force-enabled input missing');
} else {
  const card = first.closest('.card');
  if (!card) {
    out.errors.push('no .card ancestor');
  } else {
    const style = getComputedStyle(card);
    const rect = card.getBoundingClientRect();
    out.cardTitle = card.querySelector('h2') ? card.querySelector('h2').textContent.trim() : null;
    out.cardVisible = style.display !== 'none' && rect.height > 0;
    out.cardHeight = Math.round(rect.height);
  }
}
for (const id of ids) {
  const el = document.getElementById(id);
  if (!el) { out.errors.push('missing input ' + id); continue; }
  const r = el.getBoundingClientRect();
  out.inputs[id] = {height: Math.round(r.height), width: Math.round(r.width), type: el.type};
}
for (const id of buttons) {
  const el = document.getElementById(id);
  if (!el) { out.errors.push('missing button ' + id); continue; }
  const r = el.getBoundingClientRect();
  out.buttons[id] = {height: Math.round(r.height), text: el.textContent.trim()};
}
document.title = JSON.stringify(out);
"""


def main() -> int:
    # The Windows console defaults to GBK, which cannot encode the panel's Chinese
    # labels; force UTF-8 so the report is readable instead of raising mid-run.
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

    chrome = find_chrome()
    if not chrome:
        print('no Chromium browser; cannot inspect the rendered panel')
        return 2

    probe = PROBE % (json.dumps(EXPECTED_INPUTS), json.dumps(EXPECTED_BUTTONS))
    with tempfile.TemporaryDirectory() as tmp:
        page = Path(tmp) / 'probe.html'
        html = SOURCE.read_text(encoding='utf-8')
        # Show the history tab so its card is laid out.
        html = html.replace('<section id="play" class="page active">',
                            '<section id="play" class="page">')
        html = html.replace('<section id="history" class="page">',
                            '<section id="history" class="page active">')
        html = html.replace('<script>', f'<script>{probe}\n', 1)
        # Write with the BOM-less UTF-8 the page itself declares; writing through a
        # locale-default encoder would mangle the Chinese labels.
        page.write_text(html, encoding='utf-8', newline='\n')

        command = [chrome, '--headless=new', '--disable-gpu', '--window-size=420,1400',
                   '--virtual-time-budget=2000', '--dump-dom',
                   '--user-data-dir=' + str(Path(tmp) / 'profile'), page.as_uri()]
        result = subprocess.run(command, capture_output=True, text=True, errors='replace',
                                encoding='utf-8')
        dom = result.stdout

    marker = '<title>'
    start = dom.find(marker)
    if start < 0:
        print('FAIL could not read the probe result from the rendered page')
        print(result.stderr[:300])
        return 1
    start += len(marker)
    end = dom.find('</title>', start)
    raw = dom[start:end].replace('&quot;', '"').replace('&amp;', '&').replace('&lt;', '<').replace('&gt;', '>')
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as error:
        print(f'FAIL probe output was not JSON: {error}')
        print(raw[:300])
        return 1

    problems = list(data['errors'])
    print(f'card title : {data.get("cardTitle")}')
    print(f'card shown : {data.get("cardVisible")} (height {data.get("cardHeight")} px)')
    if not data.get('cardVisible'):
        problems.append('the forced-settlement card is not visible on its own tab')
    # Compare by substring so an encoding round-trip through the DOM cannot fail the
    # check; the label text itself is asserted in the source-level test.
    title = data.get('cardTitle') or ''
    if '强制显示' not in title:
        problems.append(f'unexpected card title {title!r}')

    print('\ninputs:')
    for name, info in data['inputs'].items():
        # A checkbox is deliberately 22 px; the tap target is the wrapping .switch
        # label, so only text inputs need the full touch height.
        if info['type'] == 'checkbox':
            ok = info['width'] > 0 and info['height'] > 0
            target = 'checkbox'
        else:
            ok = info['height'] >= 40 and info['width'] > 0
            target = f'{info["height"]} px tall'
        print(f'  {name:<16} {info["width"]:>4}x{info["height"]:<4} {info["type"]:<9} '
              f'{target}  {"ok" if ok else "TOO SMALL"}')
        if not ok:
            problems.append(f'{name} renders at {info["width"]}x{info["height"]}')

    print('\nbuttons:')
    for name, info in data['buttons'].items():
        ok = info['height'] >= 40
        print(f'  {name:<16} {info["height"]:>4} px  {info["text"]}  {"ok" if ok else "TOO SMALL"}')
        if not ok:
            problems.append(f'{name} renders at {info["height"]}px tall')

    print()
    if problems:
        for problem in problems:
            print(f'FAIL {problem}')
        return 1
    print(f'PASS forced-settlement card renders with {len(data["inputs"])} inputs and '
          f'{len(data["buttons"])} buttons, all at touch-target size')
    return 0


if __name__ == '__main__':
    sys.exit(main())
