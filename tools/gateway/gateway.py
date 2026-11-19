"""CarDeck transport test gateway; no vehicle operations or tunnel coupling."""
import asyncio
import hashlib
import hmac
import json
import logging
import os
import signal
import time
import uuid
import re
import ipaddress
from datetime import datetime
from collections import deque
from urllib.parse import parse_qs, urlsplit
from http import HTTPStatus
from concurrent.futures import ThreadPoolExecutor
from functools import partial
from pathlib import Path
from log_archive import LogArchive, CursorExpired, MAX_TIMESTAMP_MS, validate_history_query

from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed

TOKEN = os.environ["CARDECK_GATEWAY_TOKEN"]
READ_TOKEN = os.environ["CARDECK_GATEWAY_READ_TOKEN"]
if len(TOKEN) < 32:
    raise RuntimeError("Gateway token must contain at least 32 characters")
if len(READ_TOKEN) < 32 or READ_TOKEN == TOKEN:
    raise RuntimeError("A separate read token is required")

# Bounded, Pod-lifetime status only. No addresses, raw logs or vehicle data.
devices = {}
owners = {}
accepted_batches = {}
archive = None
storage_worker = ThreadPoolExecutor(max_workers=1, thread_name_prefix="cardeck-log-storage")
storage_errors = 0
last_storage_error = None


async def configure_archive(settings):
    global archive
    archive = await asyncio.get_running_loop().run_in_executor(
        storage_worker, partial(LogArchive, **settings))


async def storage_call(method, *args):
    if archive is None:
        raise OSError("Persistent storage unavailable")
    return await asyncio.get_running_loop().run_in_executor(storage_worker, partial(getattr(archive, method), *args))


def storage_failure(kind):
    global storage_errors, last_storage_error
    storage_errors += 1
    last_storage_error = kind


async def close_archive():
    if archive is not None:
        await storage_call("close")
    storage_worker.shutdown(wait=True)


async def maintain_archive(interval):
    while True:
        await asyncio.sleep(interval)
        try:
            await storage_call("cleanup")
        except OSError:
            storage_failure("cleanup_failed")


def _redact_ipv6(match):
    # A clock value has colons too; only redact a parsed IPv6 address.
    candidate = match.group(0)
    address = candidate.rstrip('.')
    try:
        ipaddress.IPv6Address(address)
    except ValueError:
        return candidate
    return '[IPv6]' + candidate[len(address):]


def _redact_identifiers(text):
    # Preserve only explicitly labelled epoch values; unrelated long numbers
    # still follow the identifier rules. One pass avoids re-redacting the time.
    time_field = (r'(?P<time>(?<![\w])(?P<field>received_ms|occurred_ms|captured_at_ms|'
                  r'timestamp(?:_(?:ms|us|ns))?|time_(?:ms|us|ns)|'
                  r'event_time(?:_(?:ms|us|ns))?|ts(?:_(?:ms|us|ns))?)'
                  r'[\"\x27]?\s*[:=]\s*[\"\x27]?(?P<epoch>\d{10,19})(?!\d))')
    identifiers = (r'(?P<long>[A-Za-z0-9_+/=-]{32,})|'
                   r'(?P<number>\b\d{11,18}\b)|(?P<vin>\b[A-HJ-NPR-Z0-9]{17}\b)')

    def replacement(match):
        if match.group('time'):
            field = match.group('field').lower()
            value = match.group('epoch')
            unit = field.rsplit('_', 1)[-1]
            divisor = {'ms': 1000, 'us': 1000000, 'ns': 1000000000}.get(unit)
            if divisor is None:
                divisor = {10: 1, 13: 1000, 16: 1000000, 19: 1000000000}.get(len(value))
            if (divisor and 0 <= int(value) // divisor <= 253402300799
                    and (match.end() == len(text) or not (text[match.end()].isalnum() or text[match.end()] == '_'))):
                return match.group(0)
            prefix = match.group(0)[:match.start('epoch') - match.start()]
            return prefix + '[数字标识]'
        return '[长标识]' if match.group('long') else '[数字标识]' if match.group('number') else '[车辆标识]'

    return re.sub('(?i:' + time_field + ')|' + identifiers, replacement, text)


def _redacted_log_line(text):
    # Keep a validated leading clock/date even when the sensitive body is dropped.
    match = re.match(r'^(\[?(?:(?:\d{4}-\d{2}-\d{2}|\d{2}-\d{2})[T ])?'
                     r'\d{2}:\d{2}:\d{2}(?:[.,]\d{1,9})?(?:Z|[+-]\d{2}:\d{2})?\]?)(?=\s|$)', text)
    if match:
        stamp = match.group(1).strip('[]').replace(',', '.')
        try:
            if re.match(r'^\d{2}-\d{2} ', stamp):
                stamp = '2000-' + stamp
            if re.match(r'^\d{2}:', stamp):
                stamp = '2000-01-01T' + stamp
            datetime.fromisoformat(stamp.replace('Z', '+00:00'))
            return match.group(1) + ' [已隐藏含凭证或密钥的日志行]'
        except ValueError:
            pass
    return '[已隐藏含凭证或密钥的日志行]'


def redact(text):
    for secret in (TOKEN, READ_TOKEN):
        text = text.replace(secret, "[已隐藏凭证]")
    if re.search('(?i)authorization|bearer|password|passwd|token|secret|private.?key|pairing.?key|session.?key|write.?key|read.?key|credential|nonce|salt|signature|密码|凭证', text):
        return _redacted_log_line(text)
    text = re.sub(r'(?i)\b[a-z][a-z0-9+.-]*://\S+', '[地址]', text)
    text = re.sub(r'(?i)\b(?:BUICK|CHEVROLET|CADILLAC)[_-][0-9]{3,}\b', '[车机蓝牙名]', text)
    text = re.sub(r'(?i)\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\b', '[MAC]', text)
    text = re.sub(r'(?i)(?<![\w:])(?:[0-9a-f]{0,4}:){2,}[0-9a-f:.]*(?:%[\w.-]+)?(?![\w:])', _redact_ipv6, text)
    text = re.sub(r'\b(?:\d{1,3}\.){3}\d{1,3}\b', '[IP]', text)
    text = re.sub(r'\b[\w.+-]+@[\w.-]+\.[a-zA-Z]{2,}\b', '[邮箱]', text)
    text = _redact_identifiers(text)
    text = re.sub(r'(?i)(ssid|serial|device.?id|vin|latitude|longitude)\s*[:=].*', r'\1=[已隐藏]', text)
    return ''.join(c if ord(c) >= 32 and ord(c) != 127 else ' ' for c in text)


def authorized(request, token):
    values = request.headers.get_all("Authorization")
    return len(values) == 1 and hmac.compare_digest(
        values[0].encode(), ("Bearer " + token).encode())


def valid_vehicle_id(value):
    if not isinstance(value, str):
        return False
    try:
        ident = uuid.UUID(value)
        return ident.version == 4 and str(ident) == value
    except (TypeError, ValueError, AttributeError):
        return False


def integer(value, maximum):
    return type(value) is int and 0 <= value <= maximum


LOG_QUEUE_FIELDS = {"pending_lines", "pending_bytes", "dropped_lines", "overflow_dropped_lines",
                    "write_failures", "write_failed_lines", "corrupt_dropped_lines"}
OPTIONAL_LOG_QUEUE_FIELDS = {"scope_changed_dropped_lines", "recovery_failures"}


def valid_log_queue(value):
    return (isinstance(value, dict) and LOG_QUEUE_FIELDS <= set(value)
            and set(value) <= LOG_QUEUE_FIELDS | OPTIONAL_LOG_QUEUE_FIELDS
            and all(integer(n, 9007199254740991) for n in value.values()))


def registration(payload):
    if set(payload) != {"type", "device_id", "app_version", "sdk"}:
        return None
    device_id = payload.get("device_id")
    try:
        if str(uuid.UUID(device_id, version=4)) != device_id:
            return None
    except (ValueError, TypeError, AttributeError):
        return None
    version = payload.get("app_version")
    if not isinstance(version, str) or not 1 <= len(version) <= 64:
        return None
    if any(ord(c) < 32 or ord(c) > 126 for c in version):
        return None
    if not integer(payload.get("sdk"), 1000):
        return None
    return {"device_id": device_id, "app_version": version, "sdk": payload["sdk"]}

# Never log request headers, paths, credentials, or device payloads.
logging.getLogger("websockets").setLevel(logging.CRITICAL)


async def process_request(connection, request):
    if request.path in ("/healthz", "/readyz"):
        return connection.respond(HTTPStatus.OK, "ok\n")
    if request.path == "/api/devices":
        if not authorized(request, READ_TOKEN):
            return connection.respond(HTTPStatus.UNAUTHORIZED, "unauthorized\n")
        response = connection.respond(HTTPStatus.OK, json.dumps({
            "retention": "persistent-seven-days", "devices": list(devices.values())}))
        response.headers["Content-Type"] = "application/json"
        response.headers["Cache-Control"] = "no-store"
        return response
    if request.path == "/api/storage":
        if not authorized(request, READ_TOKEN):
            return connection.respond(HTTPStatus.UNAUTHORIZED, "unauthorized\n")
        try:
            stats = await storage_call("stats")
            stats.update(storage_errors=storage_errors, last_error=last_storage_error)
        except OSError:
            return connection.respond(HTTPStatus.SERVICE_UNAVAILABLE, "storage unavailable\n")
        response = connection.respond(HTTPStatus.OK, json.dumps(stats))
        response.headers["Content-Type"] = "application/json"
        response.headers["Cache-Control"] = "no-store"
        return response
    if urlsplit(request.path).path == "/api/logs":
        if not authorized(request, READ_TOKEN):
            return connection.respond(HTTPStatus.UNAUTHORIZED, "unauthorized\n")
        query = parse_qs(urlsplit(request.path).query, keep_blank_values=True)
        if (len(request.path) > 8192 or not set(query) <= {
                "device_id", "source", "vehicle_id", "limit", "start_ms", "end_ms",
                "keyword", "cursor", "mode", "time_field"} or "device_id" not in query
                or any(len(value) != 1 for value in query.values())):
            return connection.respond(HTTPStatus.BAD_REQUEST, "device_id required\n")
        device_id = query["device_id"][0]
        source = query.get("source", [None])[0]
        vehicle_id = query.get("vehicle_id", [None])[0]
        try:
            def numeric(name, default=None):
                if name not in query:
                    return default
                raw = query[name][0]
                if not re.fullmatch(r"0|[1-9][0-9]{0,14}", raw):
                    raise ValueError()
                return int(raw)
            limit = numeric("limit", 200)
            start_ms, end_ms = numeric("start_ms"), numeric("end_ms")
            keyword = query.get("keyword", [None])[0]
            cursor = query.get("cursor", [None])[0]
            time_field = query.get("time_field", ["received_ms"])[0]
            mode = query.get("mode", [None])[0]
            history_args = bool(set(query) & {"start_ms", "end_ms", "keyword", "cursor", "time_field"})
            if mode not in (None, "tail", "history") or mode == "tail" and history_args:
                raise ValueError()
            history = mode == "history" or history_args
            validate_history_query(device_id, source, limit, vehicle_id, start_ms, end_ms, keyword, time_field)
            if cursor is not None and not re.fullmatch(r"[A-Za-z0-9_-]{1,4096}", cursor):
                raise ValueError()
        except (ValueError, TypeError, AttributeError):
            return connection.respond(HTTPStatus.BAD_REQUEST, "invalid query\n")
        try:
            if history:
                page = await storage_call("query", device_id, source, limit, vehicle_id,
                    start_ms, end_ms, keyword, cursor, READ_TOKEN.encode(), time_field)
            else:
                page = {"logs": await storage_call("tail", device_id, source, limit, vehicle_id)}
        except CursorExpired:
            return connection.respond(HTTPStatus.CONFLICT, "history cursor expired; restart query\n")
        except (ValueError, UnicodeError):
            return connection.respond(HTTPStatus.BAD_REQUEST, "invalid query\n")
        except OSError:
            storage_failure("read_failed")
            return connection.respond(HTTPStatus.SERVICE_UNAVAILABLE, "storage unavailable\n")
        response = connection.respond(HTTPStatus.OK, json.dumps({
            "retention": "persistent-seven-days", "device_id": device_id,
            "limit": limit, **page,
            **({"vehicle_id": vehicle_id} if vehicle_id is not None else {})}))
        response.headers["Content-Type"] = "application/json"
        response.headers["Cache-Control"] = "no-store"
        return response
    if urlsplit(request.path).path == "/api/vehicles":
        if not authorized(request, READ_TOKEN):
            return connection.respond(HTTPStatus.UNAUTHORIZED, "unauthorized\n")
        query = parse_qs(urlsplit(request.path).query)
        if set(query) != {"device_id"} or len(query["device_id"]) != 1:
            return connection.respond(HTTPStatus.BAD_REQUEST, "device_id required\n")
        requested = query["device_id"][0]
        if not valid_vehicle_id(requested):
            return connection.respond(HTTPStatus.BAD_REQUEST, "invalid device id\n")
        try:
            vehicles = await storage_call("list_vehicles", requested)
        except OSError:
            storage_failure("read_failed")
            return connection.respond(HTTPStatus.SERVICE_UNAVAILABLE, "storage unavailable\n")
        response = connection.respond(HTTPStatus.OK, json.dumps({
            "device_id": requested, "vehicles": vehicles, "retention": "persistent-seven-days"}))
        response.headers["Content-Type"] = "application/json"
        response.headers["Cache-Control"] = "no-store"
        return response
    if request.path != "/ws/device":
        return connection.respond(HTTPStatus.NOT_FOUND, "not found\n")
    if not authorized(request, TOKEN):
        response = connection.respond(HTTPStatus.UNAUTHORIZED, "unauthorized\n")
        response.headers["WWW-Authenticate"] = 'Bearer realm="cardeck-test"'
        return response


async def handler(ws):
    device_id = None
    await ws.send(json.dumps({"type": "hello", "mode": "transport-test",
                              "capabilities": ["device_status_v1", "logs_v1", "logs_v2", "vehicle_scope_v1",
                                               "log_queue_status_v1", "log_timestamps_v1"],
                              "ping_interval_seconds": 20, "ping_timeout_seconds": 10}))
    try:
        async for message in ws:
            if not isinstance(message, str):
                await ws.close(1003, "text JSON required")
                return
            try:
                payload = json.loads(message)
            except (ValueError, TypeError):
                await ws.close(1007, "invalid JSON")
                return
            if not isinstance(payload, dict):
                await ws.close(1007, "JSON object required")
                return
            kind = payload.get("type")
            if kind in ("heartbeat", "ping"):
                if device_id:
                    devices[device_id]["last_seen_ms"] = int(time.time() * 1000)
                await ws.send(json.dumps({
                    "type": "heartbeat_ack" if kind == "heartbeat" else "pong",
                    "server_time_ms": int(time.time() * 1000),
                    "transport_latency_ms": round(ws.latency * 1000, 3),
                }))
            elif kind == "register":
                record = registration(payload)
                if device_id or record is None or record["device_id"] in owners:
                    await ws.close(1008, "invalid registration")
                    return
                if len(devices) >= 100 and record["device_id"] not in devices:
                    offline = [key for key in devices if key not in owners]
                    if not offline:
                        await ws.close(1013, "registry full")
                        return
                    oldest = min(offline, key=lambda key: devices[key]["last_seen_ms"])
                    del devices[oldest]
                    accepted_batches.pop(oldest, None)
                device_id = record["device_id"]
                owners[device_id] = ws
                devices[device_id] = dict(record, online=True,
                    last_seen_ms=int(time.time() * 1000), report_count=0)
                await ws.send('{"type":"register_ack"}')
            elif kind == "status":
                if (not device_id or not set(payload) <= {"type", "heartbeat_count", "latency_ms", "vehicle_id", "app_log_queue"}
                    or not {"type", "heartbeat_count", "latency_ms"} <= set(payload)
                    or ("vehicle_id" in payload and not valid_vehicle_id(payload["vehicle_id"]))
                    or ("app_log_queue" in payload and not valid_log_queue(payload["app_log_queue"]))
                    or not integer(payload.get("heartbeat_count"), 2147483647)
                    or not integer(payload.get("latency_ms"), 86400000)):
                    await ws.close(1008, "invalid status")
                    return
                record = devices[device_id]
                if "app_log_queue" in payload:
                    record["app_log_queue"] = payload["app_log_queue"]
                if "vehicle_id" in payload:
                    record["vehicle_id"] = payload["vehicle_id"]
                else:
                    record.pop("vehicle_id", None)
                record.update(heartbeat_count=payload["heartbeat_count"],
                    latency_ms=payload["latency_ms"], last_seen_ms=int(time.time() * 1000),
                    last_report_ms=int(time.time() * 1000),
                    report_count=record["report_count"] + 1, connection_state="healthy")
                await ws.send(json.dumps({"type": "status_ack", "report_count": record["report_count"]}))
            elif kind == "logs":
                schema = payload.get("schema_version", 1)
                lines = payload.get("lines")
                line_times_ms = payload.get("line_times_ms")
                source = payload.get("source")
                batch_id = payload.get("batch_id")
                try:
                    valid_id = str(uuid.UUID(batch_id)) == batch_id
                except (ValueError, TypeError, AttributeError):
                    valid_id = False
                if (not device_id or not set(payload) <= {"type", "source", "batch_id", "user_confirmed", "lines", "vehicle_id", "schema_version", "line_times_ms"}
                    or not {"type", "source", "batch_id", "user_confirmed", "lines"} <= set(payload)
                    or ("vehicle_id" in payload and not valid_vehicle_id(payload["vehicle_id"]))
                    or not valid_id or source not in ("app", "system")
                    or type(payload.get("user_confirmed")) is not bool
                    or payload["user_confirmed"] != (source == "system")
                    or not isinstance(lines, list) or not 1 <= len(lines) <= 8
                    or ("line_times_ms" in payload and (not isinstance(line_times_ms, list)
                        or len(line_times_ms) != len(lines) or any(not integer(n, MAX_TIMESTAMP_MS) for n in line_times_ms)))
                    or type(schema) is not int or schema not in (1, 2)
                    or len(message.encode("utf-8")) > 16384
                    or any(not isinstance(line, str) or not line or
                        len(line.encode("utf-8")) > (12288 if schema == 2 else 1024) or
                        (schema == 1 and len(line) > 256) for line in lines)):
                    await ws.close(1008, "invalid log batch")
                    return
                recent = accepted_batches.setdefault(device_id, deque(maxlen=64))
                # V2 receipts are validated by the persistent archive even after a reconnect/restart.
                if schema == 2 or batch_id not in recent:
                    try:
                        safe_lines = [redact(line) for line in lines]
                        if schema == 2:
                            digest = hashlib.sha256(json.dumps(payload, ensure_ascii=False,
                                sort_keys=True, separators=(",", ":")).encode()).hexdigest()
                            entries = await storage_call("append_v2", device_id, source, safe_lines,
                                batch_id, payload.get("vehicle_id"), digest, line_times_ms)
                        else:
                            entries = await storage_call("append", device_id, source, safe_lines,
                                batch_id, payload.get("vehicle_id"), line_times_ms)
                    except (OSError, ValueError, UnicodeError):
                        storage_failure("write_failed")
                        await ws.close(1011, "persistent storage unavailable")
                        return  # Never ACK a batch that wasn't flushed to persistent storage.
                    recent.append(batch_id)
                    now = entries[-1]["received_ms"]
                    record = devices[device_id]
                    counter = "app_log_lines" if source == "app" else "system_log_lines"
                    record[counter] = record.get(counter, 0) + len(lines)
                    record["last_log_ms"] = now
                    if "vehicle_id" in payload:
                        record["vehicle_id"] = payload["vehicle_id"]
                await ws.send(json.dumps({"type": "logs_ack", "source": source,
                    "batch_id": batch_id, "accepted_lines": len(lines),
                    **({"schema_version": 2} if schema == 2 else {})}))
            else:
                await ws.send('{"type":"error","code":"unsupported_test_message"}')
    except ConnectionClosed:
        pass
    finally:
        if device_id and owners.get(device_id) is ws:
            del owners[device_id]
            devices[device_id].update(online=False, last_seen_ms=int(time.time() * 1000))


def allowed_browser_origins():
    """Explicit HTTPS browser origins from private runtime configuration."""
    origins = [None]  # Native app WSS clients need no Origin.
    for entry in os.environ.get("CARDECK_GATEWAY_ALLOWED_ORIGINS", "").split(","):
        origin = entry.strip()
        if not origin:
            continue
        parsed = urlsplit(origin)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.username
                or parsed.password or parsed.path or parsed.query or parsed.fragment
                or "*" in origin or origin.endswith("/")):
            raise ValueError("Invalid browser Origin configuration")
        if origin not in origins:
            origins.append(origin)
    return origins


async def main():
    settings = json.loads(Path(os.environ.get("CARDECK_LOG_CONFIG", "/etc/cardeck-logs/storage.json")).read_text(encoding="utf-8"))
    interval = settings.pop("cleanup_interval_seconds", 60)
    if type(interval) is not int or not 1 <= interval <= 60:
        raise ValueError("Cleanup interval must be at most 60 seconds")
    await configure_archive(settings)
    cleanup = asyncio.create_task(maintain_archive(interval))
    stop = asyncio.get_running_loop().create_future()
    def terminate():
        if not stop.done():
            stop.set_result(None)
    for sig in (signal.SIGINT, signal.SIGTERM):
        asyncio.get_running_loop().add_signal_handler(sig, terminate)
    try:
        async with serve(handler, "0.0.0.0", 8080, process_request=process_request,
                         origins=allowed_browser_origins(),
                         ping_interval=20, ping_timeout=10, close_timeout=5,
                         open_timeout=5, max_size=16384, max_queue=4,
                         compression=None, server_header=None):
            print("CarDeck persistent log gateway ready on 8080", flush=True)
            await stop
    finally:
        cleanup.cancel()
        await asyncio.gather(cleanup, return_exceptions=True)
        await close_archive()


if __name__ == "__main__":
    asyncio.run(main())
