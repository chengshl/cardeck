import asyncio
import importlib.util
import json
import os
import sys
import urllib.request
import urllib.error
import uuid
import tempfile
from unittest.mock import patch
from pathlib import Path

os.environ['CARDECK_GATEWAY_TOKEN'] = 'local-test-device-token-' + 'x' * 32
os.environ['CARDECK_GATEWAY_READ_TOKEN'] = 'local-test-reader-token-' + 'y' * 32
spec = importlib.util.spec_from_file_location('gateway', Path(__file__).with_name('gateway.py'))
gateway = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gateway)
from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed
from log_archive import initialize_root

async def verify():
    async with gateway.serve(gateway.handler, '127.0.0.1', 0, process_request=gateway.process_request) as server:
        port = server.sockets[0].getsockname()[1]
        def get(token, path='/api/devices'):
            request = urllib.request.Request(f'http://127.0.0.1:{port}' + path, headers={'Authorization': 'Bearer ' + token})
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
            capabilities = json.loads(await ws.recv())['capabilities']
            assert 'device_status_v1' in capabilities and 'vehicle_scope_v1' in capabilities
            await ws.send(json.dumps(dict(type='register', device_id=device_id, app_version='synthetic-test', sdk=35)))
            assert json.loads(await ws.recv())['type'] == 'register_ack'
            await ws.send('{"type":"heartbeat"}')
            assert json.loads(await ws.recv())['type'] == 'heartbeat_ack'
            await ws.send('{"type":"status","heartbeat_count":1,"latency_ms":27}')
            assert json.loads(await ws.recv()) == {'type': 'status_ack', 'report_count': 1}
            record = (await asyncio.to_thread(get, gateway.READ_TOKEN))[1]['devices'][0]
            assert record['online'] and record['heartbeat_count'] == 1 and record['latency_ms'] == 27
            assert record['app_version'] == 'synthetic-test' and record['report_count'] == 1
            app_batch = dict(type='logs', source='app', batch_id=str(uuid.uuid4()), user_confirmed=False,
                lines=['connection 192.0.2.10 user@example.com', 'Authorization: Bearer test-secret'])
            testCarName = 'BUICK_' + '1234'
            assert gateway.redact('BT-C: connected ' + testCarName) == 'BT-C: connected [车机蓝牙名]'
            assert '20:43:53.468+0800' in gateway.redact('Timestamp: 2026-10-23 20:43:53.468+0800')
            assert '20:44:07.465' in gateway.redact('10-23 20:44:07.465 system_server_crash')
            assert '12:44:53.664Z' in gateway.redact('2026-10-23T12:44:53.664Z upload')
            assert 'fe80::1234' not in gateway.redact('remote=fe80::1234')
            assert '2001:db8' not in gateway.redact('remote=2001:db8::1')
            assert '[IPv6]' in gateway.redact('remote=fe80::1234')

            await ws.send(json.dumps(app_batch))
            ack = json.loads(await ws.recv())
            assert ack['type'] == 'logs_ack' and ack['accepted_lines'] == 2
            path = '/api/logs?device_id=' + device_id
            assert (await asyncio.to_thread(get, gateway.TOKEN, path))[0] == 401
            entries = (await asyncio.to_thread(get, gateway.READ_TOKEN, path))[1]['logs']
            assert len(entries) == 2 and all(item['source'] == 'app' for item in entries)
            assert not any('192.0.2' in item['text'] or 'test-secret' in item['text'] or 'user@example.com' in item['text'] for item in entries)
            await ws.send(json.dumps(app_batch))
            assert json.loads(await ws.recv())['batch_id'] == app_batch['batch_id']
            assert len((await asyncio.to_thread(get, gateway.READ_TOKEN, path))[1]['logs']) == 2
            await ws.send(json.dumps(dict(type='logs', source='system', batch_id=str(uuid.uuid4()), user_confirmed=True, lines=['confirmed-preview'])))
            assert json.loads(await ws.recv())['accepted_lines'] == 1
            entries = (await asyncio.to_thread(get, gateway.READ_TOKEN, path))[1]['logs']
            assert entries[-1]['source'] == 'system' and entries[-1]['text'] == 'confirmed-preview'
            car_a, car_b = str(uuid.uuid4()), str(uuid.uuid4())
            await ws.send(json.dumps(dict(type='status', heartbeat_count=2, latency_ms=1, vehicle_id=car_a)))
            assert (await ws.recv() and True)
            status_record = (await asyncio.to_thread(get, gateway.READ_TOKEN))[1]['devices'][0]
            assert status_record.get('vehicle_id') == car_a
            await ws.send(json.dumps(dict(type='logs', source='app', batch_id=str(uuid.uuid4()),
                vehicle_id=car_a, user_confirmed=False, lines=['from vehicle A'])))
            assert json.loads(await ws.recv())['accepted_lines'] == 1
            await ws.send(json.dumps(dict(type='logs', source='app', batch_id=str(uuid.uuid4()),
                vehicle_id=car_b, user_confirmed=False, lines=['from vehicle B'])))
            assert json.loads(await ws.recv())['accepted_lines'] == 1
            filtered_a = (await asyncio.to_thread(get, gateway.READ_TOKEN, path + '&vehicle_id=' + car_a))[1]['logs']
            filtered_b = (await asyncio.to_thread(get, gateway.READ_TOKEN, path + '&vehicle_id=' + car_b))[1]['logs']
            assert [item['text'] for item in filtered_a] == ['from vehicle A']
            assert [item['text'] for item in filtered_b] == ['from vehicle B']
            inventory = (await asyncio.to_thread(get, gateway.READ_TOKEN,
                '/api/vehicles?device_id=' + device_id))[1]['vehicles']
            assert {item['vehicle_id'] for item in inventory} == {car_a, car_b}
            assert (await asyncio.to_thread(get, gateway.READ_TOKEN, path + '&vehicle_id=invalid'))[0] == 400
            stats = (await asyncio.to_thread(get, gateway.READ_TOKEN, '/api/storage'))[1]
            assert stats['persistent'] and stats['total_size_limit_bytes'] is None
            assert stats['retention_seconds'] == 7 * 86400
            for index in range(26):
                await ws.send(json.dumps(dict(type='logs', source='app', batch_id=str(uuid.uuid4()), user_confirmed=False, lines=[f'bounded-line-{index}'] * 8)))
                assert json.loads(await ws.recv())['accepted_lines'] == 8
            assert len((await asyncio.to_thread(get, gateway.READ_TOKEN, path))[1]['logs']) == 200
            await ws.send('{"type":"status","heartbeat_count":true,"latency_ms":27,"raw_log":"forbidden"}')
            try:
                await ws.recv()
                raise AssertionError('Malformed report accepted')
            except ConnectionClosed as error:
                assert error.code == 1008
        await asyncio.sleep(.05)
        record = (await asyncio.to_thread(get, gateway.READ_TOKEN))[1]['devices'][0]
        assert not record['online'] and 'raw_log' not in record
        async with connect(f'ws://127.0.0.1:{port}/ws/device', additional_headers=auth, proxy=None) as ws:
            await ws.recv()
            await ws.send(json.dumps(dict(type='register', device_id=device_id, app_version='synthetic-test', sdk=35)))
            assert json.loads(await ws.recv())['type'] == 'register_ack'
            await ws.send(json.dumps(dict(type='logs', source='system', batch_id=str(uuid.uuid4()), user_confirmed=False, lines=['must-not-store'])))
            try:
                await ws.recv()
                raise AssertionError('Unconfirmed system logs accepted')
            except ConnectionClosed as error:
                assert error.code == 1008
            assert not any(item['text'] == 'must-not-store' for item in (await asyncio.to_thread(get, gateway.READ_TOKEN, path))[1]['logs'])
        async with connect(f'ws://127.0.0.1:{port}/ws/device', additional_headers=auth, proxy=None) as ws:
            await ws.recv()
            await ws.send(json.dumps(dict(type='register', device_id=device_id, app_version='synthetic-test', sdk=35)))
            assert json.loads(await ws.recv())['type'] == 'register_ack'
            with patch.object(gateway.archive, 'append', side_effect=OSError('synthetic unavailable storage')):
                await ws.send(json.dumps(dict(type='logs', source='app', batch_id=str(uuid.uuid4()), user_confirmed=False, lines=['must-not-ack'])))
                try:
                    await ws.recv()
                    raise AssertionError('Failed storage was acknowledged')
                except ConnectionClosed as error:
                    assert error.code == 1011
            assert not any(item['text'] == 'must-not-ack' for item in (await asyncio.to_thread(get, gateway.READ_TOKEN, path))[1]['logs'])
        print('PASS isolated credentials, status query, app logs, confirmed system logs, unconfirmed rejection, deduplication, redaction, offline state')

async def main():
    with tempfile.TemporaryDirectory(prefix='cardeck-owned-http-test-', dir=os.environ.get('CARDECK_TEST_TEMP')) as root:
        initialize_root(root)
        await gateway.configure_archive({'root': root})
        try:
            await verify()
            import time
            gateway.archive.clock = lambda: time.time() + 7 * 86400 + 1
            task = asyncio.create_task(gateway.maintain_archive(.02))
            await asyncio.sleep(.08)
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
            assert (await gateway.storage_call('stats'))['files'] == 0
            print('PASS failed storage never ACKs; idle background cleanup expires files')
        finally:
            await gateway.close_archive()

asyncio.run(main())
