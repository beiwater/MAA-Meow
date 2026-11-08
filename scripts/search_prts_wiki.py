#!/usr/bin/env python3
"""
CLI tool to query the Arknights PRTS Wiki (https://prts.wiki) in real-time.
Usage:
    python scripts/search_prts_wiki.py <query> [--limit 5] [--json]
"""

import argparse
import json
import re
import sys
import urllib.parse
import urllib.request


USER_AGENT = "MaaMeow-AI/1.0 (https://github.com/Aliothmoon/MAA-Meow)"
API_ENDPOINT = "https://prts.wiki/api.php"


def search_prts_wiki(query: str, limit: int = 5) -> dict:
    params = {
        "action": "query",
        "list": "search",
        "srsearch": query,
        "format": "json",
        "srlimit": max(1, min(limit, 50)),
        "utf8": "1",
    }
    url = f"{API_ENDPOINT}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data


def format_markdown(query: str, search_data: dict) -> str:
    hits = search_data.get("query", {}).get("search", [])
    if not hits:
        return f"No PRTS wiki results found for \"{query}\"."

    lines = [f"PRTS Wiki results for **{query}**:\n"]
    tag_pattern = re.compile(r"<[^>]+>")

    for idx, hit in enumerate(hits, 1):
        title = hit.get("title", "")
        raw_snippet = hit.get("snippet", "")
        clean_snippet = tag_pattern.sub("", raw_snippet).replace("\n", " ").strip()
        encoded_title = urllib.parse.quote(title)
        link = f"https://prts.wiki/w/{encoded_title}"

        lines.append(f"[{idx}] **[{title}]({link})**")
        if clean_snippet:
            lines.append(clean_snippet)
        lines.append("")

    return "\n".join(lines).strip()


def main():
    parser = argparse.ArgumentParser(description="Query PRTS Wiki MediaWiki API.")
    parser.add_argument("query", help="Keywords to search (e.g. '银灰', '1-7')")
    parser.add_argument("--limit", "-l", type=int, default=5, help="Number of results (1-50, default 5)")
    parser.add_argument("--json", action="store_true", help="Print raw JSON response")

    args = parser.parse_args()

    try:
        data = search_prts_wiki(args.query, args.limit)
    except Exception as e:
        sys.stderr.write(f"Error querying PRTS Wiki: {e}\n")
        sys.exit(1)

    if args.json:
        print(json.dumps(data, ensure_ascii=False, indent=2))
    else:
        print(format_markdown(args.query, data))


if __name__ == "__main__":
    main()
