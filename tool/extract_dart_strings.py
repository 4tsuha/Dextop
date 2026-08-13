#!/usr/bin/env python3
import concurrent.futures
import hashlib
import json
import re
import urllib.parse
import urllib.request
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FILES = [
    ROOT / "lib" / name
    for name in (
        "app_info.dart", "overlay_entry.dart", "home_screen.dart",
        "home_content.dart", "resolution_ui.dart", "settings_screen.dart",
        "features_page.dart",
    )
]
JP = re.compile(r"[ぁ-んァ-ヶ一-龠]")
LITERAL = re.compile(r"(?P<quote>['\"])(?P<text>(?:\\.|(?!\1).)*?)(?P=quote)")


def translate(value: str, language: str) -> str:
    query = urllib.parse.urlencode(
        {"client": "gtx", "sl": "ja", "tl": language, "dt": "t", "q": value}
    )
    error = None
    for attempt in range(6):
        try:
            with urllib.request.urlopen(
                "https://translate.googleapis.com/translate_a/single?" + query,
                timeout=30,
            ) as response:
                payload = json.loads(response.read())
            return "".join(part[0] for part in payload[0] if part[0])
        except Exception as caught:
            error = caught
            time.sleep(0.4 * (attempt + 1))
    raise error


def key_for(japanese: str, english: str, used: set[str]) -> str:
    words = re.findall(r"[A-Za-z0-9]+", english)
    base = "ui" + "".join(word.capitalize() for word in words[:7])
    if len(base) < 4:
        base = "uiText"
    key = base[0].lower() + base[1:]
    if key in used:
        key += hashlib.sha1(japanese.encode()).hexdigest()[:7]
    used.add(key)
    return key


def main() -> None:
    ja_path = ROOT / "lib/l10n/app_ja.arb"
    catalogs = {
        locale: json.loads((ROOT / f"lib/l10n/app_{locale}.arb").read_text())
        for locale in ("ja", "en", "zh", "ko", "ru")
    }
    reverse = {value: key for key, value in catalogs["ja"].items() if not key.startswith("@")}
    values: set[str] = set()
    sources = {}
    for path in FILES:
        source = path.read_text()
        sources[path] = source
        for match in LITERAL.finditer(source):
            value = match.group("text")
            if JP.search(value) and "$" not in value and "\\" not in value:
                values.add(value)

    missing = sorted(value for value in values if value not in reverse)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        english_values = list(pool.map(lambda value: translate(value, "en"), missing))
    used = set(catalogs["ja"])
    new_keys = {}
    for value, english in zip(missing, english_values):
        key = key_for(value, english, used)
        new_keys[value] = key
        reverse[value] = key
        catalogs["ja"][key] = value
        catalogs["en"][key] = english
    targets = {"zh": "zh-CN", "ko": "ko", "ru": "ru"}
    jobs = [(locale, value) for locale in targets for value in missing]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        translated = list(pool.map(lambda job: translate(job[1], targets[job[0]]), jobs))
    for (locale, value), output in zip(jobs, translated):
        catalogs[locale][new_keys[value]] = output

    for locale, catalog in catalogs.items():
        (ROOT / f"lib/l10n/app_{locale}.arb").write_text(
            json.dumps(catalog, ensure_ascii=False, indent=2) + "\n"
        )

    for path, source in sources.items():
        def replace(match):
            value = match.group("text")
            if value in reverse and JP.search(value) and "$" not in value and "\\" not in value:
                return f"AppStrings.tr('{reverse[value]}')"
            return match.group(0)
        updated = LITERAL.sub(replace, source)
        updated = re.sub(r"\bconst\s+", "", updated)
        path.write_text(updated)


if __name__ == "__main__":
    main()
