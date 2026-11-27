import asyncio
import base64
import io
import ipaddress
import json
import os
import shutil
import socket
import subprocess
import tempfile
from pathlib import Path
from urllib.parse import urljoin, urlsplit

import httpx
from bs4 import BeautifulSoup
from fastapi import HTTPException
from PIL import Image, ImageOps

Image.MAX_IMAGE_PIXELS = 16_000_000


async def fetch_html(url):
    # Resolve once per hop and connect to the checked IP. Preserve TLS SNI
    # and the Host header for HTTPS certificates and virtual hosting.
    async with asyncio.timeout(15), httpx.AsyncClient(trust_env=False, timeout=5) as client:
        for _ in range(5):
            if len(url) > 2048 or any(ord(c) <= 32 for c in url) or chr(92) in url:
                raise ValueError("Invalid URL")
            parts = urlsplit(url)
            host = parts.hostname
            port = 443 if parts.scheme == "https" else 80
            if (parts.scheme not in ("http", "https") or not host or
                    parts.username is not None or parts.port not in (None, port)):
                raise ValueError("Public HTTP(S) article required")
            host = host.encode("idna").decode("ascii")
            addresses = await asyncio.to_thread(socket.getaddrinfo, host, port, 0, socket.SOCK_STREAM)
            ips = list(dict.fromkeys(ipaddress.ip_address(row[4][0]) for row in addresses))
            if not ips or any(not ip.is_global or ip.is_multicast or
                              (ip.version == 6 and (ip.ipv4_mapped or ip.sixtofour or ip.teredo)) for ip in ips):
                raise ValueError("Public HTTP(S) article required")
            for ip in ips:
                pinned = f"[{ip}]" if ip.version == 6 else str(ip)
                try:
                    async with client.stream(
                        "GET", f"{parts.scheme}://{pinned}{parts.path or '/'}" +
                        (f"?{parts.query}" if parts.query else ""),
                        headers={"Host": f"[{host}]" if ":" in host else host, "Connection": "close",
                                 "Accept-Encoding": "identity", "User-Agent": "NewsMeter/1.0"},
                        extensions={"sni_hostname": host},
                    ) as response:
                        if response.status_code in (301, 302, 303, 307, 308):
                            url = urljoin(url, response.headers["location"])
                            break
                        response.raise_for_status()
                        if "html" not in response.headers.get("content-type", "").lower():
                            raise ValueError("Expected an HTML article")
                        data = bytearray()
                        async for chunk in response.aiter_bytes(chunk_size=16384):
                            data.extend(chunk)
                            if len(data) > 2_000_000:
                                raise ValueError("Article response too large")
                        return url, bytes(data)
                except (httpx.ConnectError, httpx.ConnectTimeout):
                    continue
            else:
                raise ValueError("Could not connect to article")
        raise ValueError("Too many redirects")


async def extract_url(url):
    final_url, html = await fetch_html(url)
    page = BeautifulSoup(html, "html.parser")

    def meta(*keys):
        for key in keys:
            node = page.find("meta", attrs={"property": key}) or page.find("meta", attrs={"name": key})
            if node and node.get("content"):
                return node["content"].strip()[:300]
        return ""

    article_data = {}
    for script in page.find_all("script", type="application/ld+json"):
        try:
            parsed = json.loads(script.string or "")
        except (TypeError, ValueError):
            continue
        nodes = parsed if isinstance(parsed, list) else parsed.get("@graph", [parsed]) if isinstance(parsed, dict) else []
        for node in nodes:
            if isinstance(node, dict) and any("Article" in str(kind) for kind in [node.get("@type")]):
                article_data = node
                break
        if article_data:
            break

    for item in page.select("nav, header, footer, aside, script, style, form, [role=navigation], [aria-hidden=true]"):
        item.decompose()
    content = page.select_one("article") or page.select_one("main") or page.body or page
    paragraphs = [p.get_text(" ", strip=True) for p in content.select("p")]
    words = article_data.get("articleBody")
    text = words if isinstance(words, str) and len(words) > 100 else "\n\n".join(p for p in paragraphs if len(p) >= 40)
    heading = content.find("h1") or page.title
    publisher = article_data.get("publisher") or {}
    return {
        "headline": (meta("og:title", "twitter:title") or str(article_data.get("headline") or "") or
                     (heading.get_text(" ", strip=True) if heading else ""))[:300],
        "text": text.strip()[:20000],
        "publisher": (meta("og:site_name") or (str(publisher.get("name") or "") if isinstance(publisher, dict) else "") or
                      urlsplit(final_url).hostname)[:300],
        "date": (meta("article:published_time") or str(article_data.get("datePublished") or ""))[:100],
        "url": final_url,
    }


def ocr(encoded):
    local_install = r"C:\Program Files\Tesseract-OCR\tesseract.exe"
    executable = os.getenv("TESSERACT_CMD") or shutil.which("tesseract")
    if not executable and os.path.isfile(local_install):
        executable = local_install
    if not executable and os.name != "nt":
        raise HTTPException(503, "Image analysis needs Tesseract OCR. Use the Docker launcher or install Tesseract.")
    try:
        raw = base64.b64decode(encoded, validate=True)
        if len(raw) > 5 * 1024 * 1024:
            raise ValueError("Image too large")
        with Image.open(io.BytesIO(raw), formats=["PNG", "JPEG", "WEBP"]) as image:
            if image.width * image.height > Image.MAX_IMAGE_PIXELS:
                raise ValueError("Image has too many pixels")
            pixels = ImageOps.exif_transpose(image).convert("L")
            pixels.thumbnail((2800, 2800))
            buffer = io.BytesIO()
            pixels.save(buffer, format="PNG")
        if executable:
            result = subprocess.run([executable, "stdin", "stdout", "-l", "eng", "--psm", "3"],
                                    input=buffer.getvalue(), capture_output=True, check=True, timeout=20)
        else:
            with tempfile.TemporaryDirectory(prefix="news-ocr-") as directory:
                path = Path(directory) / "screenshot.png"
                path.write_bytes(buffer.getvalue())
                script = Path(__file__).with_name("windows_ocr.ps1")
                result = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                                         "-File", str(script), "-ImagePath", str(path)],
                                        capture_output=True, timeout=25)
                if result.returncode:
                    raise HTTPException(503, "Windows English OCR is unavailable. Install its language pack or use Docker.")
        return result.stdout.decode("utf-8").strip()[:20000]
    except (OSError, ValueError, Image.DecompressionBombError, subprocess.SubprocessError):
        raise HTTPException(422, "The screenshot could not be read. Try a clear PNG, JPEG, or WebP, or paste the text.") from None

