#!/usr/bin/env python3
"""
CLI tool to query Arknights Wikis in real-time.
Supports:
  - PRTS Wiki (https://prts.wiki)
  - Arknights Terra Wiki (https://arknights.wiki.gg)

Usage:
    python scripts/search_arknights_wiki.py <query> [--limit 5] [--wiki auto|prts|wiki_gg] [--json]
"""

import argparse
import json
import re
import sys
import urllib.parse
import urllib.request


USER_AGENT = "MaaMeow-AI/1.0 (https://github.com/Aliothmoon/MAA-Meow)"

CONFIG = {
    "prts": {
        "name": "PRTS Wiki (中文)",
        "api": "https://prts.wiki/api.php",
        "url_prefix": "https://prts.wiki/w/",
    },
    "wiki_gg": {
        "name": "Arknights Terra Wiki (wiki.gg)",
        "api": "https://arknights.wiki.gg/api.php",
        "url_prefix": "https://arknights.wiki.gg/wiki/",
    },
}


def has_cjk(text: str) -> bool:
    for char in text:
        if "\u4e00" <= char <= "\u9fff":
            return True
    return False


def query_mediawiki(api_url: str, query: str, limit: int = 5, timeout: float = 10.0) -> dict:
    params = {
        "action": "query",
        "list": "search",
        "srsearch": query,
        "format": "json",
        "srlimit": max(1, min(limit, 50)),
        "utf8": "1",
    }
    url = f"{api_url}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def search_wiki(query: str, limit: int = 5, wiki: str = "auto") -> tuple[str, dict]:
    target = wiki.lower().strip()
    if target == "wiki_gg":
        data = query_mediawiki(CONFIG["wiki_gg"]["api"], query, limit)
        return "wiki_gg", data
    elif target == "prts":
        data = query_mediawiki(CONFIG["prts"]["api"], query, limit)
        return "prts", data
    else:  # "auto"
        if not has_cjk(query):
            # Pure English/Latin -> query wiki.gg
            data = query_mediawiki(CONFIG["wiki_gg"]["api"], query, limit)
            return "wiki_gg", data
        else:
            # Chinese query -> try PRTS first, fallback to wiki.gg on network error/timeout
            try:
                data = query_mediawiki(CONFIG["prts"]["api"], query, limit, timeout=8.0)
                return "prts", data
            except Exception as e:
                sys.stderr.write(f"[Notice] PRTS request failed ({e}); falling back to arknights.wiki.gg...\n")
                data = query_mediawiki(CONFIG["wiki_gg"]["api"], query, limit, timeout=10.0)
                return "wiki_gg", data


def format_markdown(source_key: str, query: str, search_data: dict) -> str:
    cfg = CONFIG[source_key]
    hits = search_data.get("query", {}).get("search", [])
    header = f"[{cfg['name']}]"
    if not hits:
        return f"{header}\nNo results found for \"{query}\"."

    lines = [f"{header}\nResults for **{query}**:\n"]
    tag_pattern = re.compile(r"<[^>]+>")

    for idx, hit in enumerate(hits, 1):
        title = hit.get("title", "")
        raw_snippet = hit.get("snippet", "")
        clean_snippet = tag_pattern.sub("", raw_snippet).replace("\n", " ").strip()
        encoded_title = urllib.parse.quote(title)
        link = f"{cfg['url_prefix']}{encoded_title}"

        lines.append(f"[{idx}] **[{title}]({link})**")
        if clean_snippet:
            lines.append(clean_snippet)
        lines.append("")

    return "\n".join(lines).strip()


def main():
    parser = argparse.ArgumentParser(description="Query Arknights Wikis (PRTS / wiki.gg).")
    parser.add_argument("query", help="Keywords to search (e.g. 'SilverAsh', '银灰', '1-7')")
    parser.add_argument("--limit", "-l", type=int, default=5, help="Number of results (1-50, default 5)")
    parser.add_argument("--wiki", "-w", choices=["auto", "prts", "wiki_gg"], default="auto", help="Wiki to query")
    parser.add_argument("--json", action="store_true", help="Print raw JSON response")

    args = parser.parse_args()

    try:
        source_key, data = search_wiki(args.query, args.limit, args.wiki)
    except Exception as e:
        sys.stderr.write(f"Error querying wiki: {e}\n")
        sys.exit(1)

    if args.json:
        result = {
            "source": CONFIG[source_key]["name"],
            "query": args.query,
            "data": data,
        }
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print(format_markdown(source_key, args.query, data))


if __name__ == "__main__":
    main()
