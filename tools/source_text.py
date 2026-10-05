"""Extract visible source text without executing scripts or fetching subresources."""

from html.parser import HTMLParser
from pathlib import Path
import sys


class SourceTextParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.hidden = 0
        self.parts = []

    def handle_starttag(self, tag, attrs):
        if tag in {"script", "style", "noscript"}:
            self.hidden += 1
        if tag in {"p", "li", "h1", "h2", "h3", "div", "br"}:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in {"script", "style", "noscript"} and self.hidden:
            self.hidden -= 1
        if tag in {"p", "li", "h1", "h2", "h3", "div"}:
            self.parts.append("\n")

    def handle_data(self, data):
        if not self.hidden:
            self.parts.append(data)


def extract_text(path):
    parser = SourceTextParser()
    parser.feed(Path(path).read_text(encoding="utf-8"))
    return "\n".join(line.strip() for line in "".join(parser.parts).splitlines() if line.strip())


if __name__ == "__main__":
    print(extract_text(sys.argv[1]))
