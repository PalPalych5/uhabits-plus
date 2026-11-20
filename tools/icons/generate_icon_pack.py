import os
import sys
import re
import json
import xml.etree.ElementTree as ET
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor

sys.path.append(str(Path(__file__).resolve().parent.parent / "qa"))
from svg_geometry_normalizer import normalize_svg_path

ROOT_DIR = Path(__file__).resolve().parent.parent.parent
RES_DRAWABLE = ROOT_DIR / "uhabits-android/src/main/res/drawable"
ICONS_KT_DIR = ROOT_DIR / "uhabits-android/src/main/java/org/isoron/uhabits/activities/common/icons"
TEMP_DIR = Path("D:/DevData/Temp")

LUCIDE_DIR = TEMP_DIR / "lucide_icons"
TABLER_DIR = TEMP_DIR / "tabler_icons"

# 1. Load Baseline 196 Icons from Git HEAD
import subprocess
text = subprocess.check_output(
    ["git", "show", "HEAD:uhabits-android/src/main/java/org/isoron/uhabits/activities/common/icons/IconPack.kt"],
    encoding="utf-8"
)

pattern = re.compile(
    r'IconEntry\(\s*key\s*=\s*"([^"]+)",\s*drawableRes\s*=\s*R\.drawable\.([a-zA-Z0-9_]+),\s*category\s*=\s*IconCategory\.([A-Z_]+),\s*labelRu\s*=\s*"([^"]+)",\s*labelEn\s*=\s*"([^"]+)",\s*tags\s*=\s*listOf\(([^)]*)\)\s*\)',
    re.MULTILINE
)

LUCIDE_RENAMES = {
    "check-square": "square-check",
    "check-circle-2": "circle-check",
    "building-2": "building",
    "code-2": "code-xml",
    "trash-2": "trash",
    "wand-2": "wand-sparkles",
    "smile": "face-slightly-smiling",
    "smile-plus": "face-slightly-smiling-plus",
    "stars": "sparkles",
    "home": "house",
    "music": "eighth-notes",
    "train": "train-front",
    "sun-medium": "sun-medium",
    "tree-pine": "tree-pine",
    "flower-2": "flower-2",
    "cup-soda": "cup-soda",
    "ice-cream-2": "ice-cream-cone",
}

baseline_entries = []
baseline_keys = set()
baseline_stems = set()
for m in pattern.finditer(text):
    k, res, cat, lru, len_, raw_tags = m.groups()
    tags = [t.strip().strip('"') for t in raw_tags.split(',') if t.strip()]
    baseline_keys.add(k)
    stem = LUCIDE_RENAMES.get(k, k)
    baseline_stems.add(stem)
    baseline_entries.append({
        "key": f"lucide:{k}",
        "legacyKey": k,
        "res": res,
        "namespace": "LUCIDE",
        "category": cat,
        "labelRu": lru,
        "labelEn": len_,
        "tags": tags,
        "source": "lucide",
        "file_stem": stem
    })

print(f"Loaded {len(baseline_entries)} baseline entries.")

# 2. Curate Lucide Expansion Icons (~750 icons)
CATEGORY_MAP_LUCIDE = {
    "time": "ROUTINE",
    "notifications": "ROUTINE",
    "medical": "HEALTH",
    "sports": "SPORT",
    "food-beverage": "FOOD",
    "science": "STUDY",
    "development": "CODE",
    "home": "HOME",
    "buildings": "HOME",
    "finance": "FINANCE",
    "shopping": "FINANCE",
    "people": "PEOPLE",
    "social": "PEOPLE",
    "account": "PEOPLE",
    "communication": "PEOPLE",
    "multimedia": "ART",
    "photography": "ART",
    "design": "ART",
    "gaming": "HOBBIES",
    "travel": "TRAVEL",
    "transportation": "TRAVEL",
    "navigation": "TRAVEL",
    "nature": "NATURE",
    "weather": "NATURE",
    "animals": "NATURE",
    "sustainability": "NATURE"
}

TRANSLATIONS_RU = {
    "flame": ("Огонь / Стрик", ["огонь", "стрик", "серия", "пламя", "мотивация", "burn", "streak"]),
    "droplet": ("Капля / Вода", ["вода", "пить", "капля", "гидратация", "water", "drink"]),
    "glass-water": ("Стакан воды", ["вода", "пить", "стакан", "питье", "зож", "water", "glass"]),
    "footprints": ("Шаги / Прогулка", ["шаги", "ходьба", "гулять", "прогулка", "10000", "steps", "walk"]),
    "person-standing": ("Осанка / Стоя", ["стоять", "осанка", "разминка", "стол", "stand", "posture"]),
    "sparkles": ("Искры / Успех", ["искры", "сияние", "магия", "чистота", "успех", "блеск"]),
    "pill": ("Таблетка / Лекарство", ["таблетка", "витамины", "лекарство", "пить", "pill", "vitamins"]),
    "pills": ("Таблетки / Курс", ["таблетки", "курс", "витамины", "аптека", "pills"]),
    "heart-pulse": ("Пульс / Сердце", ["пульс", "сердце", "кардио", "давление", "зож", "pulse", "cardio"]),
    "lungs": ("Легкие / Дыхание", ["легкие", "дыхание", "воздух", "не курить", "breathe", "lungs"]),
    "cigarette-off": ("Без курения", ["не курить", "бросить", "без сигарет", "зож", "quit smoking"]),
    "wine-off": ("Трезвость", ["трезвость", "без алкоголя", "не пить", "зож", "sober"]),
    "salad": ("Салат / Овощи", ["салат", "овощи", "пп", "диета", "зелень", "зож", "salad"]),
    "chef-hat": ("Кулинария / Готовка", ["готовить", "рецепт", "кухня", "повар", "обед", "cook"]),
    "scale": ("Вес / Взвешивание", ["вес", "взвешивание", "весы", "форма", "масса", "weight"]),
    "shield-check": ("Защита / Безопасность", ["защита", "надежность", "безопасность", "контроль"]),
    "crosshair": ("Фокус / Цель", ["фокус", "цель", "прицел", "концентрация", "focus"]),
    "hourglass": ("Песочные часы", ["время", "таймер", "помодоро", "ожидание", "терпение"]),
    "sunrise": ("Рассвет / Подъем", ["рассвет", "ранний подъем", "утро", "заря", "sunrise"]),
    "sunset": ("Закат / Вечер", ["закат", "вечер", "итоги дня", "sunset"]),
    "moon-star": ("Ночь / Сон", ["ночь", "сон", "звезды", "отбой", "night", "sleep"]),
    "briefcase-medical": ("Аптечка", ["аптечка", "первая помощь", "здоровье"]),
    "shower-head": ("Душ", ["душ", "купаться", "гигиена", "утро", "shower"]),
    "washing-machine": ("Стирка", ["стирка", "стирать", "чистота", "белье", "laundry"]),
    "spray-can": ("Уборка / Спрей", ["уборка", "мыть", "дезинфекция", "чистота"]),
    "wallet-cards": ("Кошелек / Карты", ["деньги", "бюджет", "карты", "траты", "wallet"]),
    "piggy-bank": ("Копилка / Накопления", ["копилка", "копить", "сбережения", "инвестиции", "save"]),
    "receipt": ("Чек / Расходы", ["чек", "расходы", "учет", "бюджет", "receipt"]),
    "landmark": ("Банк / Капитал", ["банк", "капитал", "инвестиции", "налоги", "bank"]),
    "languages": ("Иностранные языки", ["языки", "английский", "словарь", "учить"]),
    "pen-tool": ("Графика / Вектор", ["дизайн", "вектор", "графика", "рисовать"]),
    "headphones": ("Наушники / Подкаст", ["наушники", "подкаст", "аудиокнига", "слушать"]),
    "gamepad-2": ("Видеоигры", ["игры", "гейминг", "консоль", "отдых"]),
    "puzzle": ("Пазл / Головоломка", ["пазл", "логика", "мозаика", "задача"]),
    "dice-5": ("Кубик / Настолки", ["кубик", "настолки", "игра", "вечер"]),
    "mountain-snow": ("Горы / Треккинг", ["горы", "поход", "треккинг", "вершина"]),
    "git-branch": ("Ветка Git", ["гит", "ветка", "код", "коммит"]),
    "terminal": ("Консоль / Терминал", ["терминал", "консоль", "команды", "код"]),
    "keyboard": ("Клавиатура / Печать", ["клавиатура", "печать", "слепая печать", "текст"])
}

exclude_prefixes = (
    'align-', 'a-arrow-', 'between-', 'distribute-', 'fold-', 'unfold-',
    'panel-', 'columns-', 'rows-', 'table-', 'grid-', 'corner-',
    'chevrons-', 'chevron-', 'arrow-', 'arrows-', 'move-'
)

lucide_expansion_entries = []
for j in sorted((LUCIDE_DIR / "icons").glob("*.json")):
    stem = j.stem
    if stem in baseline_stems or stem in baseline_keys:
        continue
    if any(stem.startswith(p) for p in exclude_prefixes):
        continue
    if stem.endswith("-1") or stem.endswith("-2") or stem.endswith("-3") or stem.endswith("-4"):
        if stem not in ["gamepad-2", "plant-2", "music-2"]:
            continue

    try:
        mdata = json.loads(j.read_text(encoding="utf-8"))
        cats = mdata.get("categories", [])
        tags = [t for t in mdata.get("tags", []) if not t.startswith('$')]
        if "arrows" in cats or "letters-digits" in cats:
            continue
    except Exception:
        cats, tags = [], []

    cat = "ROUTINE"
    for c in cats:
        if c in CATEGORY_MAP_LUCIDE:
            cat = CATEGORY_MAP_LUCIDE[c]
            break

    label_en = stem.replace("-", " ").title()
    if stem in TRANSLATIONS_RU:
        label_ru, extra_tags = TRANSLATIONS_RU[stem]
    else:
        label_ru = label_en
        extra_tags = []

    combined_tags = list(set([stem, label_en.lower()] + [t.lower() for t in tags[:5]] + extra_tags))

    # Safe resource name
    res_name = f"ic_l_{stem.replace('-', '_')}"
    lucide_expansion_entries.append({
        "key": f"lucide:{stem}",
        "legacyKey": None,
        "res": res_name,
        "namespace": "LUCIDE",
        "category": cat,
        "labelRu": label_ru,
        "labelEn": label_en,
        "tags": combined_tags,
        "source": "lucide",
        "file_stem": stem
    })

# Keep top 750 Lucide expansion icons
lucide_expansion_entries = lucide_expansion_entries[:750]
print(f"Selected {len(lucide_expansion_entries)} Lucide expansion icons.")

# 3. Load Tabler Icons
tabler_stems = json.loads(Path("D:/DevData/Temp/tabler_selected_stems.json").read_text(encoding='utf-8'))

CATEGORY_KEYWORDS = {
    "SPORT": ["ball", "run", "swim", "bike", "skat", "gym", "yoga", "sport"],
    "FOOD": ["cook", "food", "bread", "fruit", "meat", "egg", "cheese", "coffee", "tea", "bottle", "cup", "glass"],
    "HOME": ["sofa", "lamp", "desk", "door", "window", "clean", "wash", "vacuum", "mop", "soap", "bath", "shower", "iron"],
    "ART": ["paint", "draw", "music", "guitar", "piano", "drum", "camera", "movie", "photo", "pencil", "scissors"],
    "STUDY": ["book", "school", "math", "atom", "dna", "microscope", "calculator"],
    "FINANCE": ["money", "coin", "cash", "card", "safe", "chart", "report", "receipt", "wallet", "diamond"],
    "NATURE": ["paw", "dog", "cat", "bird", "fish", "flower", "tree", "plant", "leaf", "cactus", "clover"],
    "TRAVEL": ["plane", "car", "bus", "train", "ship", "map", "travel", "camp", "mountain", "beach", "ticket", "passport"],
    "HOBBIES": ["game", "dice", "chess", "puzzle", "lego", "fishing"],
    "SLEEP": ["sleep", "bed", "moon", "stars", "zzz", "relax"]
}

tabler_entries = []
for stem in tabler_stems:
    # Determine category
    cat = "ROUTINE"
    for c, kws in CATEGORY_KEYWORDS.items():
        if any(kw in stem for kw in kws):
            cat = c
            break

    label_en = stem.replace("-", " ").title()
    label_ru = label_en
    tags = [stem, label_en.lower()]

    # Add category-specific habit search tags
    if cat == "SPORT":
        tags.extend(["спорт", "тренировка", "зал", "активность", "упражнения", "workout", "fitness"])
    elif cat == "FOOD":
        tags.extend(["еда", "питание", "кушать", "диета", "кухня", "food", "nutrition"])
    elif cat == "HOME":
        tags.extend(["дом", "уборка", "чистота", "быт", "порядок", "home", "clean"])
    elif cat == "STUDY":
        tags.extend(["учеба", "наука", "книги", "знания", "учиться", "study", "learn"])
    elif cat == "ART":
        tags.extend(["творчество", "музыка", "рисовать", "арт", "хобби", "art", "music"])
    elif cat == "FINANCE":
        tags.extend(["деньги", "финансы", "копить", "бюджет", "доход", "money", "finance"])
    elif cat == "NATURE":
        tags.extend(["природа", "животные", "питомец", "растения", "зелень", "nature"])
    elif cat == "TRAVEL":
        tags.extend(["путешествия", "поездка", "дорога", "отпуск", "транспорт", "travel"])
    elif cat == "SLEEP":
        tags.extend(["сон", "спать", "отдых", "релакс", "ночь", "sleep", "rest"])

    res_name = f"ic_t_{stem.replace('-', '_')}"
    tabler_entries.append({
        "key": f"tabler:{stem}",
        "legacyKey": None,
        "res": res_name,
        "namespace": "TABLER",
        "category": cat,
        "labelRu": label_ru,
        "labelEn": label_en,
        "tags": list(set(tags)),
        "source": "tabler",
        "file_stem": stem
    })

print(f"Loaded {len(tabler_entries)} Tabler entries.")

ALL_ENTRIES = baseline_entries + lucide_expansion_entries + tabler_entries
print(f"Total Combined Entries: {len(ALL_ENTRIES)}")

# 4. Convert and Write VectorDrawables
def convert_svg_to_vector_xml(svg_text, license_comment):
    root = ET.fromstring(svg_text)
    paths = []

    for el in root.iter():
        tag = el.tag.split('}')[-1]
        if tag == 'path':
            d = el.get('d', '').strip()
            if el.get('stroke') == 'none' or d == 'M0 0h24v24H0z':
                continue
            if d:
                norm_d = normalize_svg_path(d)
                if norm_d:
                    paths.append(norm_d)
        elif tag == 'circle':
            cx = float(el.get('cx', 0))
            cy = float(el.get('cy', 0))
            r = float(el.get('r', 0))
            paths.append(f"M {cx} {cy-r} a {r} {r} 0 1 0 0 {2*r} a {r} {r} 0 1 0 0 {-2*r}")
        elif tag == 'line':
            x1, y1 = el.get('x1'), el.get('y1')
            x2, y2 = el.get('x2'), el.get('y2')
            paths.append(f"M {x1} {y1} L {x2} {y2}")
        elif tag in ('polyline', 'polygon'):
            raw_pts = el.get('points', '').strip().replace(',', ' ').split()
            pairs = [f"{raw_pts[i]} {raw_pts[i+1]}" for i in range(0, len(raw_pts)-1, 2)]
            d = "M " + " L ".join(pairs)
            if tag == 'polygon':
                d += " Z"
            paths.append(d)
        elif tag == 'rect':
            x = float(el.get('x', 0))
            y = float(el.get('y', 0))
            w = float(el.get('width', 0))
            h = float(el.get('height', 0))
            rx = float(el.get('rx', 0))
            ry = float(el.get('ry', rx))
            if rx > 0 or ry > 0:
                paths.append(f"M {x+rx} {y} h {w-2*rx} a {rx} {ry} 0 0 1 {rx} {ry} v {h-2*ry} a {rx} {ry} 0 0 1 -{rx} {ry} h -{w-2*rx} a {rx} {ry} 0 0 1 -{rx} -{ry} v -{h-2*ry} a {rx} {ry} 0 0 1 {rx} -{ry} Z")
            else:
                paths.append(f"M {x} {y} h {w} v {h} h -{w} Z")
        elif tag == 'ellipse':
            cx = float(el.get('cx', 0))
            cy = float(el.get('cy', 0))
            rx = float(el.get('rx', 0))
            ry = float(el.get('ry', 0))
            paths.append(f"M {cx} {cy-ry} a {rx} {ry} 0 1 0 0 {2*ry} a {rx} {ry} 0 1 0 0 {-2*ry}")

    lines = [
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp"',
        '    android:height="24dp"',
        '    android:viewportWidth="24"',
        '    android:viewportHeight="24">',
        f'    <!-- {license_comment} -->'
    ]
    for p in paths:
        lines.append('    <path')
        lines.append('        android:fillColor="#00000000"')
        lines.append('        android:strokeColor="#FFFFFFFF"')
        lines.append('        android:strokeWidth="2"')
        lines.append('        android:strokeLineCap="round"')
        lines.append('        android:strokeLineJoin="round"')
        lines.append(f'        android:pathData="{p}" />')
    lines.append('</vector>\n')
    return "\n".join(lines)

def convert_and_write(entry):
    stem = entry["file_stem"]
    res = entry["res"]
    out_file = RES_DRAWABLE / f"{res}.xml"

    if entry["source"] == "lucide":
        svg_file = LUCIDE_DIR / "icons" / f"{stem}.svg"
        license_comment = "Copyright (c) Lucide Contributors (ISC License)"
    else:
        svg_file = TABLER_DIR / f"{stem}.svg"
        license_comment = "Copyright (c) Tabler Icons Contributors (MIT License)"

    if not svg_file.exists():
        return False

    try:
        svg_text = svg_file.read_text(encoding="utf-8")
        vector_xml = convert_svg_to_vector_xml(svg_text, license_comment)
        out_file.write_text(vector_xml, encoding="utf-8")
        return True
    except Exception as e:
        print(f"Error converting {stem} ({res}): {e}")
        return False

print("Converting and writing VectorDrawables in parallel...")
with ThreadPoolExecutor(max_workers=20) as pool:
    vd_results = list(pool.map(convert_and_write, ALL_ENTRIES))

written_count = sum(1 for r in vd_results if r)
print(f"Generated {written_count}/{len(ALL_ENTRIES)} drawables.")

FINAL_ENTRIES = [e for e, ok in zip(ALL_ENTRIES, vd_results) if ok]
print(f"Final catalog entries: {len(FINAL_ENTRIES)}")

# 5. Generate IconCatalogData.kt with separate functions to prevent JVM 64KB method limit
CHUNK_SIZE = 100
chunks = [FINAL_ENTRIES[i:i + CHUNK_SIZE] for i in range(0, len(FINAL_ENTRIES), CHUNK_SIZE)]
print(f"Chunking catalog into {len(chunks)} sub-functions of max {CHUNK_SIZE} items.")

def escape_str(s):
    return s.replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$')

# Generated UHabit Next source: SPDX-only header. Never copy the upstream
# Loop Habit Tracker copyright banner into newly generated files.
kt_lines = [
    "/* SPDX-License-Identifier: GPL-3.0-or-later */",
    "",
    "package org.isoron.uhabits.activities.common.icons",
    "",
    "import org.isoron.uhabits.R",
    "",
    "object IconCatalogData {",
    "    val ALL_ICONS: List<IconEntry> by lazy {",
    "        " + " + ".join(f"getPart{i+1}()" for i in range(len(chunks))),
    "    }",
    ""
]

for idx, chunk in enumerate(chunks):
    kt_lines.append(f"    private fun getPart{idx+1}(): List<IconEntry> = listOf(")
    for e in chunk:
        tags_str = ", ".join(f'"{escape_str(t)}"' for t in e["tags"])
        leg_str = f'"{escape_str(e["legacyKey"])}"' if e["legacyKey"] else "null"
        kt_lines.append("        IconEntry(")
        kt_lines.append(f'            key = "{escape_str(e["key"])}",')
        kt_lines.append(f'            legacyKey = {leg_str},')
        kt_lines.append(f'            drawableRes = R.drawable.{e["res"]},')
        kt_lines.append(f'            namespace = IconNamespace.{e["namespace"]},')
        kt_lines.append(f'            category = IconCategory.{e["category"]},')
        kt_lines.append(f'            labelRu = "{escape_str(e["labelRu"])}",')
        kt_lines.append(f'            labelEn = "{escape_str(e["labelEn"])}",')
        kt_lines.append(f'            tags = listOf({tags_str})')
        kt_lines.append("        ),")
    kt_lines.append("    )")
    kt_lines.append("")

kt_lines.append("}")
kt_lines.append("")

catalog_kt_file = ICONS_KT_DIR / "IconCatalogData.kt"
catalog_kt_file.write_text("\n".join(kt_lines), encoding="utf-8")
print(f"Generated {catalog_kt_file} with {len(FINAL_ENTRIES)} icons across {len(chunks)} functions.")
