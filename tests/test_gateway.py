from fastapi.testclient import TestClient
from live.app import app


def test_ui_and_websocket_validation():
    with TestClient(app) as client:
        assert client.get('/').status_code == 200
        assert client.get('/worklet.js').status_code == 200
        with client.websocket_connect('/ws') as ws:
            assert ws.receive_json()['type'] == 'ready'
            ws.send_json({'type': 'audio', 'pcm': 'AAAA'})
            assert ws.receive_json()['type'] == 'error'


def test_second_client_is_rejected_without_interrupting_owner():
    with TestClient(app) as client:
        with client.websocket_connect('/ws') as owner:
            assert owner.receive_json()['type'] == 'ready'
            with client.websocket_connect('/ws') as other:
                assert other.receive_json()['type'] == 'error'
            owner.send_json({'type': 'reset'})
            assert owner.receive_json()['type'] == 'playback_stop'
            assert owner.receive_json()['type'] == 'reset_done'


def test_cross_origin_websocket_is_rejected():
    import pytest
    from starlette.websockets import WebSocketDisconnect
    with TestClient(app) as client:
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect('/ws', headers={'origin':'https://evil.example'}):
                pass
