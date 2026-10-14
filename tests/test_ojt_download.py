import io
import zipfile

import httpx
import pytest

from live.ojt_download import RangeReader


def client_for(data, mode='normal'):
    def handle(request):
        start, end = map(int, request.headers['range'].removeprefix('bytes=').split('-'))
        if mode == 'ignored':
            return httpx.Response(200, content=data)
        body = data[start:end + 1]
        if mode == 'truncated':
            body = body[:-1]
        header = f'bytes {start}-{end}/{len(data)}' if mode != 'wrong' else f'bytes 1-2/{len(data)}'
        return httpx.Response(206, headers={'content-range': header}, content=body)
    return httpx.Client(transport=httpx.MockTransport(handle))


def test_seekable_remote_zip_checks_crc_and_reads_selected_member():
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('images/1.jpg', b'jpeg sample')
    data = buffer.getvalue()
    with client_for(data) as client:
        reader = RangeReader(client, 'https://example.test/images.zip')
        with zipfile.ZipFile(reader) as archive:
            assert archive.read('images/1.jpg') == b'jpeg sample'
        reader.seek(len(data) + 1)
        assert reader.read(1) == b''


@pytest.mark.parametrize('mode', ['ignored', 'wrong', 'truncated'])
def test_bad_range_server_is_rejected(mode):
    with client_for(b'12345', mode) as client, pytest.raises(ValueError):
        RangeReader(client, 'https://example.test/images.zip')


def test_download_budget_prevents_large_read():
    with client_for(b'x' * 100) as client:
        reader = RangeReader(client, 'https://example.test/images.zip', budget=10)
        with pytest.raises(ValueError, match='budget'):
            reader.read(100)
