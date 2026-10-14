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


def test_mobile_capture_ticket_protocol_and_invalid_ticket_type():
    import base64
    import io
    from PIL import Image
    image = io.BytesIO()
    Image.new('RGB', (8, 8), 'red').save(image, format='JPEG')
    jpeg = base64.b64encode(image.getvalue()).decode()
    with TestClient(app) as client:
        with client.websocket_connect('/ws') as ws:
            ready = ws.receive_json()
            assert ready['protocol_version'] == 1 and ready['frame_capture'] == 'ticket-v1'
            ws.send_json({'type': 'frame_request', 'request_id': 7})
            ticket = ws.receive_json()
            assert ticket['type'] == 'frame_ticket' and ticket['max_age_ms'] == 3000
            assert ticket['request_id'] == 7
            ws.send_json({'type': 'frame', 'ticket': ticket['ticket'], 'jpeg': jpeg})
            ws.send_json({'type': 'camera_off'})
            ws.send_json({'type': 'reset'})
            assert ws.receive_json()['type'] == 'playback_stop'
            assert ws.receive_json()['type'] == 'reset_done'
            ws.send_json({'type': 'frame', 'ticket': None, 'jpeg': jpeg})
            assert ws.receive_json()['type'] == 'error'


def test_mobile_capture_cannot_downgrade_to_arrival_time_freshness():
    with TestClient(app) as client:
        with client.websocket_connect('/ws') as ws:
            ws.receive_json()
            ws.send_json({'type': 'frame_request'})
            ws.receive_json()
            ws.send_json({'type': 'frame', 'jpeg': ''})
            assert ws.receive_json()['message'] == 'ticket required after frame_request'


def test_invalid_capture_request_id_is_rejected():
    with TestClient(app) as client:
        with client.websocket_connect('/ws') as ws:
            ws.receive_json()
            ws.send_json({'type': 'frame_request', 'request_id': True})
            assert ws.receive_json()['type'] == 'error'
