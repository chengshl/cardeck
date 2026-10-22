"""CarDeck transport test gateway; no vehicle operations or tunnel coupling."""
import asyncio
import hmac
import json
import logging
import os
import signal
import time
import uuid
import re
from collections import deque
from urllib.parse import parse_qs, urlsplit
from http import HTTPStatus
from concurrent.futures import ThreadPoolExecutor
from functools import partial
from pathlib import Path
from log_archive import LogArchive

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


def redact(text):
    for secret in (TOKEN, READ_TOKEN):
        text = text.replace(secret, "[已隐藏凭证]")
    if re.search(r"(?i)authorization|bearer|password|passwd|token|secret|private.?key|pairing.?key|session.?key|credential|nonce|salt|signature|密码|凭证", text):
        return "[已隐藏含凭证或密钥的日志行]"
    text = re.sub(r"(?i)\b[a-z][a-z0-9+.-]*://\S+", "[地址]", text)
    text = re.sub(r"(?i)\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\b", "[MAC]", text)
    text = re.sub(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", "[IP]", text)
    text = re.sub(r"(?i)(?:[0-9a-f]{1,4}:){2,}[0-9a-f:]+", "[IPv6]", text)
    text = re.sub(r"\b[\w.+-]+@[\w.-]+\.[a-zA-Z]{2,}\b", "[邮箱]", text)
    text = re.sub(r"[A-Za-z0-9_+/=-]{32,}", "[长标识]", text)
    text = re.sub(r"\b\d{11,18}\b", "[数字标识]", text)
    text = re.sub(r"\b[A-HJ-NPR-Z0-9]{17}\b", "[车辆标识]", text)
    text = re.sub(r"(?i)(ssid|serial|device.?id|vin|latitude|longitude)\s*[:=].*", r"\1=[已隐藏]", text)
    return ''.join(c if ord(c) >= 32 and ord(c) != 127 else ' ' for c in text)[:256]


def authorized(request, token):
    values = request.headers.get_all("Authorization")
    return len(values) == 1 and hmac.compare_digest(
        values[0].encode(), ("Bearer " + token).encode())


def integer(value, maximum):
    return type(value) is int and 0 <= value <= maximum


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
        query = parse_qs(urlsplit(request.path).query)
        if (not set(query) <= {"device_id", "source"} or "device_id" not in query
                or any(len(value) != 1 for value in query.values())):
            return connection.respond(HTTPStatus.BAD_REQUEST, "device_id required\n")
        device_id = query["device_id"][0]
        source = query.get("source", [None])[0]
        try:
            if str(uuid.UUID(device_id)) != device_id or source not in (None, "app", "system"):
                raise ValueError()
        except (ValueError, TypeError, AttributeError):
            return connection.respond(HTTPStatus.BAD_REQUEST, "invalid query\n")
        try:
            entries = await storage_call("tail", device_id, source)
        except OSError:
            storage_failure("read_failed")
            return connection.respond(HTTPStatus.SERVICE_UNAVAILABLE, "storage unavailable\n")
        response = connection.respond(HTTPStatus.OK, json.dumps({
            "retention": "persistent-seven-days", "device_id": device_id,
            "limit": 200, "logs": entries}))
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
                              "capabilities": ["device_status_v1", "logs_v1"],
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
                if (not device_id or set(payload) != {"type", "heartbeat_count", "latency_ms"}
                    or not integer(payload.get("heartbeat_count"), 2147483647)
                    or not integer(payload.get("latency_ms"), 86400000)):
                    await ws.close(1008, "invalid status")
                    return
                record = devices[device_id]
                record.update(heartbeat_count=payload["heartbeat_count"],
                    latency_ms=payload["latency_ms"], last_seen_ms=int(time.time() * 1000),
                    last_report_ms=int(time.time() * 1000),
                    report_count=record["report_count"] + 1, connection_state="healthy")
                await ws.send(json.dumps({"type": "status_ack", "report_count": record["report_count"]}))
            elif kind == "logs":
                lines = payload.get("lines")
                source = payload.get("source")
                batch_id = payload.get("batch_id")
                try:
                    valid_id = str(uuid.UUID(batch_id)) == batch_id
                except (ValueError, TypeError, AttributeError):
                    valid_id = False
                if (not device_id or set(payload) != {"type", "source", "batch_id", "user_confirmed", "lines"}
                    or not valid_id or source not in ("app", "system")
                    or type(payload.get("user_confirmed")) is not bool
                    or payload["user_confirmed"] != (source == "system")
                    or not isinstance(lines, list) or not 1 <= len(lines) <= 8
                    or any(not isinstance(line, str) or not 1 <= len(line) <= 256 for line in lines)):
                    await ws.close(1008, "invalid log batch")
                    return
                recent = accepted_batches.setdefault(device_id, deque(maxlen=64))
                if batch_id not in recent:
                    try:
                        entries = await storage_call("append", device_id, source, [redact(line) for line in lines], batch_id)
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
                await ws.send(json.dumps({"type": "logs_ack", "source": source,
                    "batch_id": batch_id, "accepted_lines": len(lines)}))
            else:
                await ws.send('{"type":"error","code":"unsupported_test_message"}')
    except ConnectionClosed:
        pass
    finally:
        if device_id and owners.get(device_id) is ws:
            del owners[device_id]
            devices[device_id].update(online=False, last_seen_ms=int(time.time() * 1000))


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
                         origins=[None, "https://cardeck.once.wiki:8443", "https://cardeck.once.wiki"],
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
