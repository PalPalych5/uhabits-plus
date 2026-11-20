import urllib.request
import json
import re
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor

headers = {'User-Agent': 'Mozilla/5.0'}
url = 'https://api.github.com/repos/tabler/tabler-icons/git/trees/main?recursive=1'
req = urllib.request.Request(url, headers=headers)
tree = json.loads(urllib.request.urlopen(req).read().decode('utf-8'))
all_stems = sorted([item['path'].replace('icons/outline/', '').replace('.svg', '') for item in tree.get('tree', []) if 'icons/outline/' in item['path'] and item['path'].endswith('.svg')])

keywords = [
    'ball-', 'run', 'swim', 'bike', 'skat', 'gym', 'yoga', 'sport',
    'cook', 'food', 'bread', 'fruit', 'meat', 'egg', 'cheese', 'coffee', 'tea', 'bottle', 'cup', 'glass',
    'sofa', 'lamp', 'desk', 'door', 'window', 'clean', 'wash', 'vacuum', 'mop', 'soap', 'bath', 'shower', 'iron',
    'paint', 'draw', 'music', 'guitar', 'piano', 'drum', 'camera', 'movie', 'photo', 'pencil', 'scissors',
    'book', 'school', 'math', 'atom', 'dna', 'microscope', 'calculator',
    'money', 'coin', 'cash', 'card', 'safe', 'chart', 'report', 'receipt', 'wallet', 'diamond',
    'paw', 'dog', 'cat', 'bird', 'fish', 'flower', 'tree', 'plant', 'leaf', 'cactus', 'clover',
    'plane', 'car', 'bus', 'train', 'ship', 'map', 'travel', 'camp', 'mountain', 'beach', 'ticket', 'passport',
    'game', 'dice', 'chess', 'puzzle', 'lego', 'fishing',
    'sleep', 'bed', 'moon', 'stars', 'zzz', 'relax'
]

curated = []
for s in all_stems:
    # Exclude negative / disabled / off variants
    if s.endswith('-off') or s.startswith('brand-') or '-filled' in s or '-dashed' in s:
        continue
    if any(kw in s for kw in keywords):
        curated.append(s)

# Keep top 350
curated = sorted(list(set(curated)))[:350]
print(f"Selected {len(curated)} clean Tabler icons.")

tabler_dir = Path("D:/DevData/Temp/tabler_icons")
tabler_dir.mkdir(parents=True, exist_ok=True)

def dl(stem):
    p = tabler_dir / f"{stem}.svg"
    if p.exists() and p.stat().st_size > 0:
        return stem, True
    raw_url = f"https://raw.githubusercontent.com/tabler/tabler-icons/main/icons/outline/{stem}.svg"
    try:
        r = urllib.request.Request(raw_url, headers=headers)
        with urllib.request.urlopen(r, timeout=8) as resp:
            data = resp.read()
        p.write_bytes(data)
        return stem, True
    except Exception as e:
        return stem, False

print(f"Downloading {len(curated)} Tabler SVGs...")
with ThreadPoolExecutor(max_workers=20) as pool:
    res = list(pool.map(dl, curated))

ok_count = sum(1 for r in res if r[1])
fail_count = sum(1 for r in res if not r[1])
print(f"Tabler Download Complete: {ok_count} succeeded, {fail_count} failed.")

Path("D:/DevData/Temp/tabler_selected_stems.json").write_text(json.dumps([r[0] for r in res if r[1]], indent=2), encoding='utf-8')
