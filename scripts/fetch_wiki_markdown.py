#!/usr/bin/env python3
"""
CLI tool to fetch full Arknights Wiki articles converted into clean Markdown text.
Supports:
  - PRTS Wiki (https://prts.wiki)
  - Arknights Terra Wiki (https://arknights.wiki.gg)

Usage:
    python scripts/fetch_wiki_markdown.py "SilverAsh" --wiki wiki_gg
    python scripts/fetch_wiki_markdown.py "1-7" --wiki prts
    python scripts/fetch_wiki_markdown.py "纯金年代"
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
    },
    "wiki_gg": {
        "name": "Arknights Terra Wiki (wiki.gg)",
        "api": "https://arknights.wiki.gg/api.php",
    },
}


def has_cjk(text: str) -> bool:
    for char in text:
        if 0x4E00 <= ord(char) <= 0x9FFF:
            return True
    return False


def fetch_article(api_url: str, title: str, timeout: float = 15.0) -> tuple[str, str]:
    """
    Fetches article content via MediaWiki API.
    Returns (resolved_title, text_content).
    """
    # 1. Try prop=extracts&explaintext=1
    params = {
        "action": "query",
        "prop": "extracts",
        "explaintext": "1",
        "titles": title,
        "format": "json",
        "utf8": "1",
    }
    url = f"{api_url}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        pages = data.get("query", {}).get("pages", {})
        for _, page in pages.items():
            if "missing" in page:
                continue
            resolved_title = page.get("title", title)
            extract = page.get("extract", "").strip()
            if extract:
                return resolved_title, extract
    except Exception:
        pass

    # 2. Fallback to action=parse&prop=wikitext
    parse_params = {
        "action": "parse",
        "page": title,
        "prop": "wikitext",
        "format": "json",
        "utf8": "1",
    }
    parse_url = f"{api_url}?{urllib.parse.urlencode(parse_params)}"
    parse_req = urllib.request.Request(parse_url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(parse_req, timeout=timeout) as resp:
        parse_data = json.loads(resp.read().decode("utf-8"))

    parse_obj = parse_data.get("parse")
    if not parse_obj:
        error_msg = parse_data.get("error", {}).get("info", "Article not found")
        raise RuntimeError(f"Failed to fetch article '{title}': {error_msg}")

    resolved_title = parse_obj.get("title", title)
    wikitext = parse_obj.get("wikitext", {}).get("*", "").strip()
    if not wikitext:
        raise RuntimeError(f"Empty article content for '{title}'")

    return resolved_title, wikitext


def get_wiki_markdown(title: str, wiki: str = "auto") -> tuple[str, str]:
    target = wiki.lower().strip()
    if target == "auto":
        if not has_cjk(title):
            resolved_title, text = fetch_article(CONFIG["wiki_gg"]["api"], title)
            return resolved_title, text
        else:
            try:
                resolved_title, text = fetch_article(CONFIG["prts"]["api"], title)
                return resolved_title, text
            except Exception as e_prts:
                try:
                    resolved_title, text = fetch_article(CONFIG["wiki_gg"]["api"], title)
                    return resolved_title, text
                except Exception as e_wiki_gg:
                    raise RuntimeError(f"Failed to fetch from PRTS ({e_prts}) and fallback wiki.gg ({e_wiki_gg})")
    elif target in CONFIG:
        resolved_title, text = fetch_article(CONFIG[target]["api"], title)
        return resolved_title, text
    else:
        raise ValueError(f"Unknown wiki source '{wiki}'. Choose from: auto, prts, wiki_gg")


def main():
    parser = argparse.ArgumentParser(description="Fetch Arknights Wiki article in Markdown format.")
    parser.add_argument("title", help="Article title (e.g. 'SilverAsh', '1-7', '纯金年代')")
    parser.add_argument("--wiki", "-w", choices=["auto", "prts", "wiki_gg"], default="auto", help="Wiki source")
    parser.add_argument("--output", "-o", help="Optional output file path")

    args = parser.parse_args()

    try:
        resolved_title, content = get_wiki_markdown(args.title, args.wiki)
        markdown = f"# {resolved_title}\n\n{content}\n"
        if args.output:
            with open(args.output, "w", encoding="utf-8") as f:
                f.write(markdown)
            print(f"Article saved to {args.output}")
        else:
            print(markdown)
    except Exception as e:
        sys.stderr.write(f"Error: {e}\n")
        sys.exit(1)


if __name__ == "__main__":
    main()
