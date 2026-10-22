import asyncio
import importlib.util
import json
import os
import urllib.request
import urllib.error
import uuid
from pathlib import Path

os.environ['CARDECK_GATEWAY_TOKEN'] = 'local-test-device-token-' + 'x' * 32
os.environ['CARDECK_GATEWAY_READ_TOKEN'] = 'local-test-reader-token-' + 'y' * 32
spec = importlib.util.spec_from_file_location('gateway', Path(__file__).with_name('gateway.py'))
gateway = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gateway)
from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed

async def main():
    async with gateway.serve(gateway.handler, '127.0.0.1', 0, process_request=gateway.process_request) as server:
        port = server.sockets[0].getsockname()[1]
        def get(token):
            request = urllib.request.Request(f'http://127.0.0.1:{port}/api/devices', headers={'Authorization': 'Bearer ' + token})
            try:
                with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=3) as response:
                    return response.status, json.load(response)
            except urllib.error.HTTPError as error:
                return error.code, None
        assert (await asyncio.to_thread(get, gateway.TOKEN))[0] == 401
        assert (await asyncio.to_thread(get, 'invalid'))[0] == 401
        assert (await asyncio.to_thread(get, gateway.READ_TOKEN))[1]['devices'] == []
        device_id = str(uuid.uuid4())
        auth = {'Authorization': 'Bearer ' + gateway.TOKEN}
        async with connect(f'ws://127.0.0.1:{port}/ws/device', additional_headers=auth, proxy=None) as ws:
            assert 'device_status_v1' in json.loads(await ws.recv())['capabilities']
            await ws.send(json.dumps(dict(type='register', device_id=device_id, app_version='synthetic-test', sdk=35)))
            assert json.loads(await ws.recv())['type'] == 'register_ack'
            await ws.send('{"type":"heartbeat"}')
            assert json.loads(await ws.recv())['type'] == 'heartbeat_ack'
            await ws.send('{"type":"status","heartbeat_count":1,"latency_ms":27}')
            assert json.loads(await ws.recv()) == {'type': 'status_ack', 'report_count': 1}
            record = (await asyncio.to_thread(get, gateway.READ_TOKEN))[1]['devices'][0]
            assert record['online'] and record['heartbeat_count'] == 1 and record['latency_ms'] == 27
            assert record['app_version'] == 'synthetic-test' and record['report_count'] == 1
            await ws.send('{"type":"status","heartbeat_count":true,"latency_ms":27,"raw_log":"forbidden"}')
            try:
                await ws.recv()
                raise AssertionError('Malformed report accepted')
            except ConnectionClosed as error:
                assert error.code == 1008
        await asyncio.sleep(.05)
        record = (await asyncio.to_thread(get, gateway.READ_TOKEN))[1]['devices'][0]
        assert not record['online'] and 'raw_log' not in record
        print('PASS reader/device credential isolation, registration, acknowledged report query, field validation, disconnect state')

asyncio.run(main())
