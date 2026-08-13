#!/usr/bin/env python3
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
L10N = ROOT / "lib" / "l10n"
LOCALES = ("ja", "en", "zh", "ko", "ru")


def dart_quote(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def main() -> None:
    catalogs = {
        locale: json.loads((L10N / f"app_{locale}.arb").read_text())
        for locale in LOCALES
    }
    keys = [key for key in catalogs["ja"] if not key.startswith("@")]
    missing = {
        locale: sorted(set(keys) - set(catalogs[locale]))
        for locale in LOCALES
    }
    if any(missing.values()):
        raise SystemExit(f"Translation keys are missing: {missing}")

    lines = [
        "// Generated from lib/l10n/app_*.arb. Do not edit by hand.",
        "import 'dart:ui';",
        "",
        "abstract final class AppStrings {",
        "  static String tr(String key) {",
        "    final language = PlatformDispatcher.instance.locale.languageCode;",
        "    return (_values[language] ?? _values['en']!)[key] ?? _values['en']![key] ?? key;",
        "  }",
        "",
        "  static const Map<String, Map<String, String>> _values = {",
    ]
    for locale in LOCALES:
        lines.append(f"    {dart_quote(locale)}: {{")
        for key in keys:
            lines.append(
                f"      {dart_quote(key)}: {dart_quote(str(catalogs[locale][key]))},"
            )
        lines.append("    },")
    lines.extend(("  };", "}", ""))
    (ROOT / "lib" / "app_strings.dart").write_text("\n".join(lines))


if __name__ == "__main__":
    main()
