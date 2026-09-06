"""Markdown to PDF rendering for exports (Section 9.9).

PyMuPDF is already a dependency of this service for reading PDFs, and it can
write them too, so export lives here rather than pulling a second PDF library
into the application backend for one endpoint.
"""

from __future__ import annotations

import html
import io
import re

import fitz

_A4 = fitz.paper_rect("a4")
_MARGIN = 56

_CSS = """
body { font-family: sans-serif; font-size: 10.5pt; line-height: 1.5; color: #1a1a1a; }
h1 { font-size: 19pt; margin: 0 0 4pt 0; }
h2 { font-size: 13pt; margin: 14pt 0 4pt 0; color: #1f3a5f; }
h3 { font-size: 11pt; margin: 10pt 0 3pt 0; }
p  { margin: 0 0 7pt 0; }
li { margin: 0 0 3pt 0; }
blockquote { margin: 0 0 7pt 10pt; color: #444; font-style: italic; }
table { width: 100%; border-collapse: collapse; margin: 0 0 10pt 0; font-size: 9pt; }
th, td { border: 1px solid #c8c8c8; padding: 4pt; text-align: left; vertical-align: top; }
th { background-color: #eef2f7; }
hr { margin: 12pt 0; }
em { color: #555; }
"""

_BOLD_RE = re.compile(r"\*\*(.+?)\*\*")
_ITALIC_RE = re.compile(r"(?<!\*)\*(?!\s)(.+?)(?<!\s)\*(?!\*)")
_CODE_RE = re.compile(r"`([^`]+)`")


_MAX_PAGES = 200


def markdown_to_pdf(markdown: str, title: str) -> bytes:
    buffer = io.BytesIO()
    story = fitz.Story(html=_to_html(markdown, title), user_css=_CSS)
    writer = fitz.DocumentWriter(buffer)
    frame = fitz.Rect(_MARGIN, _MARGIN, _A4.width - _MARGIN, _A4.height - _MARGIN)

    more = True
    pages = 0
    # place() reports whether content remains, so pages are emitted until the
    # document is exhausted rather than to a guessed page count. The cap is a
    # guard against a pathological layout looping forever.
    while more and pages < _MAX_PAGES:
        device = writer.begin_page(_A4)
        more, _ = story.place(frame)
        story.draw(device)
        writer.end_page()
        pages += 1
    writer.close()
    return buffer.getvalue()


def _to_html(markdown: str, title: str) -> str:
    lines = markdown.splitlines()
    parts: list[str] = []
    list_open = False
    table_rows: list[list[str]] = []

    def close_list() -> None:
        nonlocal list_open
        if list_open:
            parts.append("</ul>")
            list_open = False

    def flush_table() -> None:
        nonlocal table_rows
        if not table_rows:
            return
        header, *body = table_rows
        parts.append("<table><tr>" + "".join(f"<th>{c}</th>" for c in header) + "</tr>")
        for row in body:
            parts.append("<tr>" + "".join(f"<td>{c}</td>" for c in row) + "</tr>")
        parts.append("</table>")
        table_rows = []

    for raw in lines:
        line = raw.rstrip()

        if line.startswith("|") and line.endswith("|"):
            cells = [_inline(c.strip()) for c in line.strip("|").split("|")]
            # The |---|---| separator row carries no content.
            if all(set(c.replace(" ", "")) <= {"-", ":"} and c for c in cells):
                continue
            close_list()
            table_rows.append(cells)
            continue
        flush_table()

        if not line.strip():
            close_list()
            continue
        if line.startswith("### "):
            close_list()
            parts.append(f"<h3>{_inline(line[4:])}</h3>")
        elif line.startswith("## "):
            close_list()
            parts.append(f"<h2>{_inline(line[3:])}</h2>")
        elif line.startswith("# "):
            close_list()
            parts.append(f"<h1>{_inline(line[2:])}</h1>")
        elif line.startswith("---"):
            close_list()
            parts.append("<hr/>")
        elif line.startswith("> "):
            close_list()
            parts.append(f"<blockquote>{_inline(line[2:])}</blockquote>")
        elif line.lstrip().startswith(("- ", "* ")):
            if not list_open:
                parts.append("<ul>")
                list_open = True
            parts.append(f"<li>{_inline(line.lstrip()[2:])}</li>")
        elif re.match(r"^\d+\.\s", line):
            if not list_open:
                parts.append("<ul>")
                list_open = True
            parts.append(f"<li>{_inline(re.sub(r'^\\d+\\.\\s', '', line))}</li>")
        else:
            close_list()
            parts.append(f"<p>{_inline(line)}</p>")

    flush_table()
    close_list()
    safe_title = html.escape(title)
    return f"<html><head><title>{safe_title}</title></head><body>{''.join(parts)}</body></html>"


def _inline(text: str) -> str:
    # Escaped first: the content originates from model output and must never be
    # able to inject markup into the rendered document.
    escaped = html.escape(text)
    escaped = _BOLD_RE.sub(r"<b>\1</b>", escaped)
    escaped = _ITALIC_RE.sub(r"<i>\1</i>", escaped)
    escaped = _CODE_RE.sub(r"<code>\1</code>", escaped)
    return escaped
