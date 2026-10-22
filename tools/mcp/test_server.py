"""Read-only MCP contract, transport and stdio tests; no live services required."""
import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import unittest
from unittest.mock import patch


SERVER = Path(__file__).with_name('server.py')
SPEC = importlib.util.spec_from_file_location('cardeck_mcp_under_test', SERVER)
server = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(server)
DEVICE = '6f2e0c7b-1e8f-4a2c-9f11-123456789abc'
TOKEN = 'test-reader-' + 'x' * 36
HOST = 'gateway.example.svc.cluster.local'
BASE_URL = 'http://' + HOST + ':8080'


def tool_request(name, args=None):
    return {'jsonrpc': '2.0', 'id': 7, 'method': 'tools/call',
            'params': {'name': name, 'arguments': {} if args is None else args}}


def payload(reply):
    return json.loads(reply['result']['content'][0]['text'])


def device_response():
    # These names match gateway.py registration/status/log records.
    return {'devices': [{'device_id': DEVICE, 'app_version': '20261022103822',
                         'sdk': 35, 'online': True, 'last_seen_ms': 1000,
                         'heartbeat_count': 34, 'latency_ms': 347,
                         'last_report_ms': 999, 'report_count': 12,
                         'connection_state': 'healthy', 'app_log_lines': 8,
                         'system_log_lines': 2, 'last_log_ms': 998}]}


class ScriptedSocket:
    """A raw HTTP response without opening a network connection."""
    def __init__(self, body=b'{}', status='200 OK', content_type='application/json', headers=()):
        fields = [('Content-Type', content_type), ('Content-Length', str(len(body))), *headers]
        head = 'HTTP/1.1 ' + status + '\r\n' + ''.join(k + ': ' + v + '\r\n' for k, v in fields) + '\r\n'
        self.stream = io.BytesIO(head.encode('ascii') + body)
        self.sent = b''
        self.timeouts = []
        self.closed = False

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.closed = True

    def settimeout(self, seconds):
        self.timeouts.append(seconds)

    def sendall(self, data):
        self.sent += data

    def makefile(self, mode):
        if mode != 'rb':
            raise AssertionError('Only a response reader is expected')
        return self.stream


class ProtocolTests(unittest.TestCase):
    def test_initialize_versions_and_read_only_contract(self):
        for requested in ('2024-11-05', '2025-03-26', '2025-06-18', '2025-11-25', 'unsupported'):
            reply = server.reply({'jsonrpc': '2.0', 'id': 1, 'method': 'initialize',
                                  'params': {'protocolVersion': requested}})['result']
            self.assertEqual(reply['protocolVersion'], requested if requested != 'unsupported' else '2025-03-26')
            self.assertEqual(reply['serverInfo']['version'], '0.2.0')
            self.assertIn('untrusted data', reply['instructions'])
            self.assertIn('previously user-confirmed', reply['instructions'])
        listing = server.reply({'jsonrpc': '2.0', 'id': 2, 'method': 'tools/list'})['result']['tools']
        self.assertEqual({t['name'] for t in listing}, {
            'diagnostics_status', 'summarize_log', 'list_devices', 'get_device_logs', 'get_log_storage_status'})
        for entry in listing:
            self.assertEqual(entry['annotations'], {
                'readOnlyHint': True, 'destructiveHint': False, 'openWorldHint': False})
            self.assertFalse(entry['inputSchema']['additionalProperties'])

    def test_starter_summary_stays_local_and_returns_counts_only(self):
        with patch.object(server, 'fetch_gateway') as fetch:
            response = server.reply(tool_request('summarize_log', {'text': 'error WARN timed out\nBluetooth Wi-Fi'}))
        fetch.assert_not_called()
        value = payload(response)
        self.assertFalse(response['result']['isError'])
        self.assertEqual(value['lines'], 2)
        self.assertEqual(value['keyword_counts']['timeout'], 1)
        self.assertEqual(value['keyword_counts']['warning'], 1)
        self.assertEqual(value['keyword_counts']['wifi'], 1)
        self.assertNotIn('text', value)

    def test_invalid_arguments_never_contact_gateway(self):
        invalid = [
            ('list_devices', {'url': 'https://example.invalid'}),
            ('get_log_storage_status', {'path': '/etc/passwd'}),
            ('diagnostics_status', {'capture': True}),
            ('summarize_log', {'text': 'x' * 32769}),
            ('summarize_log', {'text': None}),
            ('summarize_log', {'text': 'ok', 'command': 'ignored'}),
            ('get_device_logs', {'device_id': DEVICE}),
            ('get_device_logs', {'device_id': DEVICE.upper(), 'source': 'app'}),
            ('get_device_logs', {'device_id': DEVICE + '&source=system', 'source': 'app'}),
            ('get_device_logs', {'device_id': 1, 'source': 'app'}),
            ('get_device_logs', {'device_id': DEVICE, 'source': 'all'}),
            ('get_device_logs', {'device_id': DEVICE, 'source': ['app']}),
            ('get_device_logs', {'device_id': DEVICE, 'source': 'app', 'limit': True}),
            ('get_device_logs', {'device_id': DEVICE, 'source': 'app', 'limit': 0}),
            ('get_device_logs', {'device_id': DEVICE, 'source': 'app', 'limit': 201}),
            ('get_device_logs', {'device_id': DEVICE, 'source': 'app', 'limit': 1.0}),
            ('get_device_logs', {'device_id': DEVICE, 'source': 'app', 'url': '/api/storage'}),
            ('capture_system_logs', {}),
        ]
        with patch.object(server, 'fetch_gateway') as fetch:
            for name, args in invalid:
                with self.subTest(name=name, args=args):
                    self.assertEqual(server.reply(tool_request(name, args))['error']['code'], -32602)
        fetch.assert_not_called()

    def test_jsonrpc_errors_notifications_and_ping(self):
        for request in ([], {}, {'jsonrpc': '1.0', 'method': 'ping'}, {'jsonrpc': '2.0', 'method': 9}):
            self.assertEqual(server.reply(request)['error']['code'], -32600)
        self.assertIsNone(server.reply({'jsonrpc': '2.0', 'method': 'notifications/initialized'}))
        self.assertEqual(server.reply({'jsonrpc': '2.0', 'id': 3, 'method': 'ping'})['result'], {})
        self.assertEqual(server.reply({'jsonrpc': '2.0', 'id': 3, 'method': 'missing'})['error']['code'], -32601)
        self.assertEqual(server.reply({'jsonrpc': '2.0', 'id': 3, 'method': 'ping', 'params': []})['error']['code'], -32602)
        self.assertEqual(server.reply(tool_request('list_devices', []))['error']['code'], -32602)


class QueryTests(unittest.TestCase):
    def test_device_fields_match_real_gateway_and_unknown_fields_are_dropped(self):
        value = device_response()
        value['devices'][0].update(password='never-return-me', arbitrary={'private': 'data'}, token=TOKEN)
        with patch.object(server, 'fetch_gateway', return_value=value) as fetch:
            result = payload(server.reply(tool_request('list_devices')))
        fetch.assert_called_once_with('/api/devices')
        row = result['devices'][0]
        self.assertEqual(row['latency_ms'], 347)
        self.assertEqual(row['last_report_ms'], 999)
        self.assertEqual(row['report_count'], 12)
        self.assertEqual(row['connection_state'], 'healthy')
        self.assertEqual(result['state_scope'], 'current_gateway_process')
        self.assertEqual(result['log_retention_days'], 7)
        self.assertNotIn('password', row)
        self.assertNotIn('token', row)
        self.assertNotIn('arbitrary', row)

    def test_device_response_bounds_and_numeric_types(self):
        for invalid in ({}, {'devices': [None]}, {'devices': [{'device_id': 'invalid'}]},
                        {'devices': [{'device_id': DEVICE}] * 101}):
            with self.subTest(value=invalid), self.assertRaises(server.GatewayError):
                server.devices_result(invalid)
        row = device_response()['devices'][0]
        row.update(sdk=True, last_seen_ms=-1, heartbeat_count=2**63, app_version='token:unsafe')
        actual = server.devices_result({'devices': [row]})['devices'][0]
        for key in ('sdk', 'last_seen_ms', 'heartbeat_count', 'app_version'):
            self.assertNotIn(key, actual)

    def test_app_and_confirmed_system_history_are_separate_and_bounded(self):
        for source in ('app', 'system'):
            rows = [{'source': source, 'received_ms': n, 'text': 'line ' + str(n), 'batch_id': DEVICE,
                     'extra': 'never-return'} for n in range(60)]
            upstream = {'device_id': DEVICE, 'logs': rows, 'private': 'never-return'}
            with patch.object(server, 'fetch_gateway', return_value=upstream) as fetch:
                reply = server.reply(tool_request('get_device_logs', {'device_id': DEVICE, 'source': source}))
            fetch.assert_called_once_with('/api/logs?device_id=' + DEVICE + '&source=' + source)
            result = payload(reply)
            self.assertFalse(reply['result']['isError'])
            self.assertEqual(result['returned'], 50)
            self.assertEqual(len(result['logs']), 50)
            self.assertEqual(result['logs'][0]['received_ms'], 10)
            self.assertTrue(result['untrusted_data'])
            self.assertEqual(set(result['logs'][0]), {'source', 'received_ms', 'text'})
            self.assertIn('不触发', result['note'])
        for limit in (1, 200):
            result = server.logs_result({'device_id': DEVICE, 'logs': rows}, DEVICE, 'system', limit)
            self.assertEqual(len(result['logs']), min(limit, 60))

    def test_log_response_identity_source_shape_and_count_are_validated(self):
        valid = {'source': 'app', 'received_ms': 1, 'text': 'ok'}
        invalid = [
            {'device_id': 'another', 'logs': [valid]},
            {'device_id': DEVICE, 'logs': [valid] * 201},
            {'device_id': DEVICE, 'logs': [None]},
            {'device_id': DEVICE, 'logs': [dict(valid, source='system')]},
            {'device_id': DEVICE, 'logs': [dict(valid, received_ms=True)]},
            {'device_id': DEVICE, 'logs': [dict(valid, received_ms=-1)]},
            {'device_id': DEVICE, 'logs': [dict(valid, text={'token': TOKEN})]},
        ]
        for value in invalid:
            with self.subTest(value=value), self.assertRaises(server.GatewayError):
                server.logs_result(value, DEVICE, 'app', 50)

    def test_logs_are_redacted_again_and_control_characters_removed(self):
        sensitive = ['Authorization: Bearer fake', 'password=hunter', '凭证=隐藏', TOKEN]
        with patch.dict(os.environ, {'CARDECK_GATEWAY_READ_TOKEN': TOKEN}):
            for text in sensitive:
                with self.subTest(text=text):
                    result = server.redact(text)
                    self.assertNotIn(text, result)
                    self.assertIn('隐藏', result)
            self.assertEqual(server.redact('connect https://example.invalid/a 192.0.2.1 AA:BB:CC:DD:EE:FF'),
                             'connect [地址] [IP] [MAC]')
            self.assertEqual(server.redact('one\x00two\nthree\x7f'), 'one two three ')
            self.assertEqual(len(server.redact('汉' * 300)), 256)

    def test_storage_allowlist_and_errors_do_not_expose_paths(self):
        upstream = {'persistent': True, 'retention_seconds': 604800, 'max_file_bytes': 16777216,
                    'total_size_limit_bytes': None, 'files': 2, 'bytes': 100, 'free_bytes': 200,
                    'storage_errors': 0, 'last_error': None, 'path': '/private/never-return',
                    'secret': TOKEN, 'root': '/private/root'}
        with patch.object(server, 'fetch_gateway', return_value=upstream) as fetch:
            result = payload(server.reply(tool_request('get_log_storage_status')))
        fetch.assert_called_once_with('/api/storage')
        self.assertEqual(result['retention_seconds'], 604800)
        self.assertIsNone(result['total_size_limit_bytes'])
        self.assertNotIn('path', result)
        self.assertNotIn('root', result)
        self.assertNotIn('secret', result)
        for changed in ({'persistent': False}, {'total_size_limit_bytes': 100}):
            with patch.object(server, 'fetch_gateway', return_value={**upstream, **changed}):
                self.assertTrue(server.reply(tool_request('get_log_storage_status'))['result']['isError'])

    def test_gateway_failures_are_tools_errors_but_status_remains_available(self):
        with patch.object(server, 'fetch_gateway', side_effect=server.GatewayError('gateway_timeout')):
            failure = server.reply(tool_request('list_devices'))
            self.assertTrue(failure['result']['isError'])
            self.assertEqual(payload(failure), {'error': 'gateway_timeout'})
            status = server.reply(tool_request('diagnostics_status'))
        self.assertFalse(status['result']['isError'])
        self.assertEqual(payload(status)['gateway_query'], 'gateway_timeout')
        self.assertFalse(payload(status)['system_log_capture'])


class TransportTests(unittest.TestCase):
    def setUp(self):
        self.environment = patch.dict(os.environ, {
            'CARDECK_GATEWAY_READ_TOKEN': TOKEN, 'CARDECK_GATEWAY_URL': BASE_URL,
            'HTTP_PROXY': 'http://192.0.2.1:9999', 'HTTPS_PROXY': 'http://192.0.2.1:9999',
            'ALL_PROXY': 'http://192.0.2.1:9999'})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def request(self, response):
        with patch.object(server.socket, 'create_connection', return_value=response) as connect:
            value = server.fetch_gateway('/api/devices')
        return value, connect

    def test_fixed_destination_get_and_reader_token_without_proxy(self):
        fake = ScriptedSocket(b'{"devices":[]}')
        value, connect = self.request(fake)
        self.assertEqual(value, {'devices': []})
        connect.assert_called_once_with((HOST, 8080), timeout=5)
        self.assertTrue(fake.sent.startswith(b'GET /api/devices HTTP/1.1\r\n'))
        self.assertIn(('Host: ' + HOST + ':8080\r\n').encode('ascii'), fake.sent)
        self.assertIn(('Authorization: Bearer ' + TOKEN + '\r\n').encode('ascii'), fake.sent)
        self.assertNotIn(b'192.0.2.1', fake.sent)
        self.assertIn(b'Connection: close\r\n', fake.sent)
        self.assertTrue(fake.closed)
        self.assertTrue(all(0 < timeout <= 5 for timeout in fake.timeouts))

    def test_missing_bad_configuration_and_paths_fail_before_network(self):
        invalid_urls = (
            '', 'http://example.invalid',
            'https://' + HOST + ':8080',
            'http://192.0.2.1:8080',
            'http://[2001:db8::1]:8080',
            'http://' + HOST, 'http://' + HOST + ':80', 'http://' + HOST + ':8081',
            'http://user@' + HOST + ':8080', 'http://user:password@' + HOST + ':8080',
            BASE_URL + '/', BASE_URL + '/api/devices', BASE_URL + '?source=app', BASE_URL + '#fragment',
            'http://' + HOST.upper() + ':8080',
            'http://' + HOST + '.:8080',
            'http://' + HOST + '.example.invalid:8080',
            'http://-gateway.example.svc.cluster.local:8080',
        )
        changes = [{'CARDECK_GATEWAY_URL': value} for value in invalid_urls] + [
                       {'CARDECK_GATEWAY_READ_TOKEN': ''}, {'CARDECK_GATEWAY_READ_TOKEN': 'short'},
                       {'CARDECK_GATEWAY_READ_TOKEN': TOKEN + '\r\nX: bad'},
                       {'CARDECK_GATEWAY_READ_TOKEN': 'x' * 513}]
        for change in changes:
            with self.subTest(change=change), patch.dict(os.environ, change), patch.object(server.socket, 'create_connection') as connect:
                with self.assertRaisesRegex(server.GatewayError, 'gateway_not_configured'):
                    server.fetch_gateway('/api/devices')
                connect.assert_not_called()
        for path in ('https://example.invalid', '/ws/device', '/api/devices?extra=1',
                     '/api/logs?device_id=' + DEVICE + '&source=app&extra=1'):
            with self.subTest(path=path), patch.object(server.socket, 'create_connection') as connect:
                with self.assertRaisesRegex(server.GatewayError, 'invalid_gateway_path'):
                    server.fetch_gateway(path)
                connect.assert_not_called()

    def test_redirects_are_not_followed_and_auth_errors_are_generic(self):
        for status, expected in (('301 Moved', 'gateway_unavailable'), ('302 Found', 'gateway_unavailable'),
                                 ('401 Unauthorized', 'gateway_auth_failed'), ('403 Forbidden', 'gateway_auth_failed'),
                                 ('500 Internal Server Error', 'gateway_unavailable')):
            fake = ScriptedSocket(TOKEN.encode(), status=status, headers=[('Location', 'http://example.invalid')])
            with self.subTest(status=status), patch.object(server.socket, 'create_connection', return_value=fake) as connect:
                with self.assertRaisesRegex(server.GatewayError, '^' + expected + '$'):
                    server.fetch_gateway('/api/devices')
                self.assertEqual(connect.call_count, 1)

    def test_bad_content_type_json_and_shape_are_bounded_generic_failures(self):
        for fake in (ScriptedSocket(b'{}', content_type='text/html'), ScriptedSocket(b'not json ' + TOKEN.encode()),
                     ScriptedSocket(b'[]'), ScriptedSocket(b'\xff')):
            with self.subTest(fake=fake), patch.object(server.socket, 'create_connection', return_value=fake):
                with self.assertRaises(server.GatewayError) as error:
                    server.fetch_gateway('/api/devices')
                self.assertNotIn(TOKEN, str(error.exception))
                self.assertNotIn('not json', str(error.exception))

    def test_existing_gateway_duplicate_content_type_is_accepted_narrowly(self):
        fake = ScriptedSocket(b'{"devices":[]}', content_type='text/plain; charset=utf-8',
                              headers=[('Content-Type', 'application/json')])
        value, _ = self.request(fake)
        self.assertEqual(value, {'devices': []})
        for extra in ('text/html', 'application/octet-stream'):
            fake = ScriptedSocket(b'{}', content_type=extra, headers=[('Content-Type', 'application/json')])
            with patch.object(server.socket, 'create_connection', return_value=fake):
                with self.assertRaisesRegex(server.GatewayError, '^invalid_gateway_response$'):
                    server.fetch_gateway('/api/devices')

    def test_response_size_exact_boundary_and_overflow(self):
        prefix, suffix = b'{"blob":"', b'"}'
        body = prefix + b'a' * (server.MAX_RESPONSE - len(prefix) - len(suffix)) + suffix
        self.assertEqual(len(body), server.MAX_RESPONSE)
        value, _ = self.request(ScriptedSocket(body))
        self.assertEqual(len(value['blob']), server.MAX_RESPONSE - len(prefix) - len(suffix))
        with patch.object(server.socket, 'create_connection', return_value=ScriptedSocket(body + b' ')):
            with self.assertRaisesRegex(server.GatewayError, '^gateway_response_too_large$'):
                server.fetch_gateway('/api/devices')

    def test_socket_timeout_io_failure_and_total_budget_are_sanitized(self):
        for failure, expected in ((socket.timeout(TOKEN), 'gateway_timeout'),
                                  (OSError('private address ' + TOKEN), 'gateway_unavailable')):
            with self.subTest(failure=type(failure)), patch.object(server.socket, 'create_connection', side_effect=failure):
                with self.assertRaisesRegex(server.GatewayError, '^' + expected + '$'):
                    server.fetch_gateway('/api/devices')
        fake = ScriptedSocket()
        with patch.object(server.socket, 'create_connection', return_value=fake), patch.object(server.time, 'monotonic', side_effect=[0, 0, 6]):
            with self.assertRaisesRegex(server.GatewayError, '^gateway_timeout$'):
                server.fetch_gateway('/api/devices')


class StdioTests(unittest.TestCase):
    def run_server(self, data):
        environment = dict(os.environ, PYTHONIOENCODING='utf-8', PYTHONDONTWRITEBYTECODE='1',
                           CARDECK_GATEWAY_URL='disabled-for-stdio-test', CARDECK_GATEWAY_READ_TOKEN=TOKEN)
        return subprocess.run([sys.executable, str(SERVER)], input=data, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, env=environment, timeout=10)

    def test_multiple_frames_notifications_parse_error_and_local_tools(self):
        frames = [
            {'jsonrpc': '2.0', 'id': 1, 'method': 'initialize', 'params': {'protocolVersion': '2025-03-26'}},
            {'jsonrpc': '2.0', 'method': 'notifications/initialized'},
            {'jsonrpc': '2.0', 'id': 2, 'method': 'tools/list'},
            tool_request('summarize_log', {'text': 'error 蓝牙'}),
            tool_request('diagnostics_status'),
        ]
        data = ('\n'.join(json.dumps(frame, ensure_ascii=False) for frame in frames) + '\n{broken\n' +
                json.dumps({'jsonrpc': '2.0', 'id': 9, 'method': 'ping'}) + '\n').encode('utf-8')
        process = self.run_server(data)
        self.assertEqual(process.returncode, 0, process.stderr.decode('utf-8', errors='replace'))
        replies = [json.loads(line) for line in process.stdout.decode('utf-8').splitlines()]
        self.assertEqual(len(replies), 6)
        self.assertEqual(replies[0]['id'], 1)
        self.assertEqual(payload(replies[2])['keyword_counts']['error'], 1)
        self.assertEqual(payload(replies[3])['gateway_query'], 'gateway_not_configured')
        self.assertEqual(replies[4]['error']['code'], -32700)
        self.assertEqual(replies[5]['id'], 9)
        self.assertEqual(process.stderr, b'')
        self.assertNotIn(TOKEN.encode(), process.stdout)

    def test_oversized_frame_exits_without_echoing_input(self):
        process = self.run_server(b'x' * (server.MAX_LINE + 1))
        self.assertEqual(process.returncode, 2)
        self.assertEqual(process.stdout, b'')
        self.assertEqual(process.stderr.decode('utf-8').splitlines(), ['MCP frame exceeds maximum size'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
