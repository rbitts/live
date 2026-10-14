"""Bounded HTTP ranges for selecting files from the official GQA image ZIP."""
import hashlib
import io
import json
from pathlib import Path
import re
import time
import zipfile

import httpx
from PIL import Image


GQA_IMAGES = 'https://downloads.cs.stanford.edu/nlp/data/gqa/images.zip'


class RangeReader(io.RawIOBase):
    """Seekable read-only ZIP source; reject ignored ranges and cap network bytes."""

    def __init__(self, client, url, budget=128 * 1024 ** 2):
        self.client, self.url, self.budget = client, url, budget
        self.transferred = self.position = 0
        self.size = None
        self.cache_start, self.cache = 0, b''
        self._fetch(0, 0)

    def _fetch(self, start, end):
        for attempt in range(3):
            try:
                return self._fetch_once(start, end)
            except (httpx.TransportError, httpx.HTTPStatusError) as exc:
                transient = not isinstance(exc, httpx.HTTPStatusError) or exc.response.status_code in (429, 500, 502, 503, 504)
                if attempt == 2 or not transient:
                    raise
                time.sleep(.5 * (attempt + 1))

    def _fetch_once(self, start, end):
        expected = end - start + 1
        if expected > self.budget - self.transferred:
            raise ValueError('HTTP download byte budget exceeded')
        with self.client.stream('GET', self.url, headers={'Range': f'bytes={start}-{end}',
                                                        'Accept-Encoding': 'identity'}) as response:
            response.raise_for_status()
            if response.status_code != 206:
                raise ValueError(f'server returned HTTP {response.status_code}, not 206; full ZIP download refused')
            match = re.fullmatch(r'bytes (\d+)-(\d+)/(\d+)', response.headers.get('content-range', ''))
            if not match or tuple(map(int, match.groups()[:2])) != (start, end):
                raise ValueError('server returned the wrong byte range')
            size = int(match[3])
            if size <= end or (self.size is not None and self.size != size):
                raise ValueError('remote archive size changed or invalid')
            chunks = bytearray()
            for chunk in response.iter_bytes(chunk_size=65536):
                self.transferred += len(chunk)
                if len(chunks) + len(chunk) > expected or self.transferred > self.budget:
                    raise ValueError('HTTP response exceeds range or budget')
                chunks.extend(chunk)
            if len(chunks) != expected:
                raise ValueError('truncated HTTP range')
            self.size = size
            return bytes(chunks)

    def seekable(self):
        return True

    def readable(self):
        return True

    def tell(self):
        return self.position

    def seek(self, offset, whence=0):
        if whence not in (0, 1, 2):
            raise ValueError('invalid seek mode')
        position = (0, self.position, self.size)[whence] + offset
        if position < 0:
            raise ValueError('negative seek')
        self.position = position
        return position

    def read(self, size=-1):
        if size is None or size < 0:
            size = self.size - self.position
        size = min(size, max(0, self.size - self.position))
        if not size:
            return b''
        start = self.position
        if not (self.cache_start <= start and start + size <= self.cache_start + len(self.cache)):
            end = min(self.size, start + max(size, 128 * 1024)) - 1
            self.cache = self._fetch(start, end)
            self.cache_start = start
        self.position += size
        begin = start - self.cache_start
        return self.cache[begin:begin + size]


def fetch_gqa_images(requests: Path, output: Path, progress=None):
    """Download only requested JPEG members; preserve original bytes and their hashes."""
    rows = [json.loads(line) for line in requests.read_text().splitlines() if line.strip()]
    paths = [row['path'] for row in rows]
    if not 1 <= len(rows) <= 200 or len(set(paths)) != len(paths):
        raise ValueError('expected 1 to 200 unique media requests')
    if any(not re.fullmatch(r'[0-9]+\.jpg', path) for path in paths):
        raise ValueError('expected a numeric GQA JPEG filename')
    output.mkdir(parents=True, exist_ok=False)
    report = {'source_url': GQA_IMAGES, 'status': 'incomplete', 'files': [],
              'requests_sha256': hashlib.sha256(requests.read_bytes()).hexdigest()}
    try:
        with httpx.Client(timeout=40, follow_redirects=True) as client:
            remote = RangeReader(client, GQA_IMAGES)
            with zipfile.ZipFile(remote) as archive:
                # Locate by basename, never extract arbitrary archive paths.
                members = {}
                for info in archive.infolist():
                    name = Path(info.filename).name
                    if name in paths:
                        if name in members:
                            raise ValueError('ambiguous JPEG basename in image ZIP')
                        members[name] = info
                for i, row in enumerate(rows, 1):
                    info = members[row['path']]
                    if info.file_size > 8 * 1024 ** 2 or info.compress_size > 8 * 1024 ** 2:
                        raise ValueError('JPEG member exceeds 8 MiB limit')
                    raw = archive.read(info)  # Includes decompression and CRC verification.
                    with Image.open(io.BytesIO(raw)) as image:
                        if image.format != 'JPEG' or list(image.size) != row['image_size'] or max(image.size) > 1280:
                            raise ValueError(f'JPEG size/format differs from annotation: {row["path"]}')
                        image.verify()
                    (output / row['path']).write_bytes(raw)
                    report['files'].append({'path': row['path'], 'sha256': hashlib.sha256(raw).hexdigest(),
                                            'bytes': len(raw), 'zip_member': info.filename, 'crc32': info.CRC})
                    if progress:
                        progress(i, len(rows))
            report.update(status='downloaded_media_only', transferred_bytes=remote.transferred,
                          archive_bytes=remote.size, labels_approved=False, model_acceptance=False)
    finally:
        (output / 'download-manifest.json').write_text(json.dumps(report, indent=2) + '\n')
    return report
