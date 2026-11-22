"""Render the in-game control panel so its layout can be reviewed as an image.

The panel is a single self-contained HTML file the game loads in a WebView. Rendering
it here shows the actual layout the user will see on the phone, without needing the
device.

The screenshot is produced by a headless Chromium if one is available; otherwise the
script reports that and exits, rather than pretending a preview exists.
"""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

SOURCE = Path(r'I:\dsh\work\phigros-offline-lab\ui\controls-ui.html')
OUT_DIR = Path(r'I:\dsh\work\release\ui-preview')

# The panel has three tabs; the history page holds the forced-settlement card, so it
# is the one worth showing first.
PAGES = {
    'history': '结算页强制显示',
    'play': '自动游玩与判定',
}


def find_chrome():
    candidates = [
        r'C:\Program Files\Google\Chrome\Application\chrome.exe',
        r'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe',
        r'C:\Program Files\Microsoft\Edge\Application\msedge.exe',
        r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
    ]
    for candidate in candidates:
        if Path(candidate).is_file():
            return candidate
    for name in ('chrome', 'chromium', 'msedge'):
        found = shutil.which(name)
        if found:
            return found
    return None


def build_page(page: str) -> Path:
    """Copy the panel and force one tab active so a screenshot shows that page."""
    html = SOURCE.read_text(encoding='utf-8')
    for other in PAGES:
        if other == page:
            continue
        html = html.replace(f'<section id="{other}" class="page active">',
                            f'<section id="{other}" class="page">')
    html = html.replace(f'<section id="{page}" class="page">',
                        f'<section id="{page}" class="page active">')
    # Tab buttons carry an active class; move it to the page being shown.
    html = html.replace('class="tab active"', 'class="tab"')
    html = html.replace(f'data-page="{page}" class="tab"', f'data-page="{page}" class="tab active"')
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    target = OUT_DIR / f'panel-{page}.html'
    target.write_text(html, encoding='utf-8')
    return target


def main() -> int:
    chrome = find_chrome()
    if not chrome:
        print('no Chromium-based browser found; cannot render a screenshot')
        print(f'panel source: {SOURCE}')
        return 2

    print(f'browser: {chrome}')
    for page in PAGES:
        target = build_page(page)
        shot = OUT_DIR / f'panel-{page}.png'
        command = [chrome, '--headless=new', '--disable-gpu', '--hide-scrollbars',
                   '--force-device-scale-factor=2',
                   '--window-size=420,1400',
                   f'--screenshot={shot}',
                   target.as_uri()]
        result = subprocess.run(command, capture_output=True, text=True, errors='replace')
        if shot.is_file():
            print(f'  {page}: {shot} ({shot.stat().st_size} bytes)')
        else:
            print(f'  {page}: FAILED {result.stderr[:200]}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
