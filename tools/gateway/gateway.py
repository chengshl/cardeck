"""CarDeck transport test gateway; no vehicle operations or tunnel coupling."""
import asyncio
import hmac
import json
import logging
import os
import signal
import time
import uuid
from http import HTTPStatus

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


def process_request(connection, request):
    if request.path in ("/healthz", "/readyz"):
        return connection.respond(HTTPStatus.OK, "ok\n")
    if request.path == "/api/devices":
        if not authorized(request, READ_TOKEN):
            return connection.respond(HTTPStatus.UNAUTHORIZED, "unauthorized\n")
        response = connection.respond(HTTPStatus.OK, json.dumps({
            "retention": "pod-lifetime", "devices": list(devices.values())}))
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
                              "capabilities": ["device_status_v1"],
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
                    del devices[min(offline, key=lambda key: devices[key]["last_seen_ms"])]
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
            else:
                await ws.send('{"type":"error","code":"unsupported_test_message"}')
    except ConnectionClosed:
        pass
    finally:
        if device_id and owners.get(device_id) is ws:
            del owners[device_id]
            devices[device_id].update(online=False, last_seen_ms=int(time.time() * 1000))


async def main():
    stop = asyncio.get_running_loop().create_future()
    def terminate():
        if not stop.done():
            stop.set_result(None)
    for sig in (signal.SIGINT, signal.SIGTERM):
        asyncio.get_running_loop().add_signal_handler(sig, terminate)
    async with serve(handler, "0.0.0.0", 8080, process_request=process_request,
                     origins=[None, "https://cardeck.once.wiki:8443", "https://cardeck.once.wiki"],
                     ping_interval=20, ping_timeout=10, close_timeout=5,
                     open_timeout=5, max_size=16384, max_queue=4,
                     compression=None, server_header=None):
        print("CarDeck transport test gateway ready on 8080", flush=True)
        await stop


if __name__ == "__main__":
    asyncio.run(main())
