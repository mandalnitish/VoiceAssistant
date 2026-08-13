"""
MirAIe AC bridge server
========================
Wraps the community `miraie-ac` Python library (MQTT-based, reverse-engineered —
Panasonic has no public official API) and exposes plain HTTP endpoints so the
Android voice assistant app can control the AC with simple POST requests.

Why a separate server? `miraie-ac` is Python-only (asyncio + MQTT), there is no
Android/Kotlin equivalent, so this small always-on service is the bridge.

ENDPOINTS
  GET  /health                 -> {"status": "ok", "connected": true/false}
  GET  /ac/inspect             -> lists every public method on the device object.
                                   Run this ONCE after deploying to confirm the
                                   real method names for temperature/mode control
                                   (they aren't documented anywhere public).
  POST /ac/on
  POST /ac/off
  POST /ac/temperature/<int>   -> e.g. /ac/temperature/24
  POST /ac/mode/<mode>         -> mode is one of cool/dry/heat/auto/fan

All POST/inspect endpoints require header:  X-Api-Key: <BRIDGE_API_KEY>
(so a random person who finds your Render URL can't switch your AC off at 2am)

ENV VARS (set these on Render, never hardcode them):
  MIRAIE_MOBILE     - the mobile number you log into the MirAIe app with (with +91)
  MIRAIE_PASSWORD   - your MirAIe app password
  BRIDGE_API_KEY    - a secret string you make up, shared with the Android app
"""

import asyncio
import logging
import os
import threading

from flask import Flask, jsonify, request
from miraie_ac import MirAIeBroker, MirAIeHub

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("miraie-bridge")

MOBILE = os.environ.get("MIRAIE_MOBILE")
PASSWORD = os.environ.get("MIRAIE_PASSWORD")
API_KEY = os.environ.get("BRIDGE_API_KEY", "")

app = Flask(__name__)

# --- Background asyncio loop -------------------------------------------------
# miraie-ac keeps a persistent MQTT connection alive via asyncio. Flask itself
# is synchronous, so we run one event loop forever in a background thread and
# hand coroutines to it from the (sync) Flask request handlers.

loop = asyncio.new_event_loop()
hub: MirAIeHub | None = None
broker: MirAIeBroker | None = None
connected = threading.Event()
connect_error: str | None = None


def _run_loop_forever():
    asyncio.set_event_loop(loop)
    loop.run_forever()


threading.Thread(target=_run_loop_forever, daemon=True, name="miraie-loop").start()


async def _connect():
    global hub, broker, connect_error
    try:
        if not MOBILE or not PASSWORD:
            raise RuntimeError("MIRAIE_MOBILE / MIRAIE_PASSWORD env vars are not set")
        broker = MirAIeBroker()
        hub = MirAIeHub()
        await hub.init(MOBILE, PASSWORD, broker)
        while not hasattr(broker, "client") or getattr(broker, "client") is None:
            await asyncio.sleep(1)
        connected.set()
        log.info("Connected to MirAIe. Devices: %s", hub.home.devices)
    except Exception as e:  # noqa: BLE001
        connect_error = str(e)
        log.exception("Failed to connect to MirAIe")


asyncio.run_coroutine_threadsafe(_connect(), loop)


async def _call_device_method(device_index: int, method_name: str, *args):
    """Runs on the bridge's event loop. Calls method_name on the device if it
    exists, awaiting it if it's a coroutine. Returns (found, result)."""
    if not connected.is_set():
        raise RuntimeError(connect_error or "Still connecting to MirAIe, try again shortly")
    device = hub.home.devices[device_index]
    method = getattr(device, method_name, None)
    if method is None:
        return False, None
    result = method(*args)
    if asyncio.iscoroutine(result):
        result = await result
    return True, result


def call_device(method_name: str, *args, device_index=0, timeout=15):
    future = asyncio.run_coroutine_threadsafe(
        _call_device_method(device_index, method_name, *args), loop
    )
    return future.result(timeout=timeout)


def try_candidates(candidate_methods, *args, device_index=0):
    """Tries each candidate method name in order, returns the first one that
    exists on the device. This is here because the exact method names for
    temperature/mode aren't documented publicly for this library."""
    for name in candidate_methods:
        found, result = call_device(name, *args, device_index=device_index)
        if found:
            return name, result
    return None, None


# --- Auth ---------------------------------------------------------------

@app.before_request
def check_auth():
    if request.path == "/health":
        return
    if API_KEY and request.headers.get("X-Api-Key") != API_KEY:
        return jsonify({"error": "unauthorized"}), 401


# --- Routes ---------------------------------------------------------------

@app.route("/health")
def health():
    return jsonify({"status": "ok", "connected": connected.is_set(), "error": connect_error})


@app.route("/ac/inspect")
def inspect():
    """One-time diagnostic: lists every public attribute/method on the device
    object so we can confirm exact names for temperature/mode control."""
    if not connected.is_set():
        return jsonify({"error": connect_error or "not connected yet"}), 503
    device = hub.home.devices[0]
    members = sorted(m for m in dir(device) if not m.startswith("_"))
    return jsonify({"device_repr": repr(device), "members": members})


@app.route("/ac/on", methods=["POST"])
def turn_on():
    found, _ = call_device("turn_on")
    if not found:
        return jsonify({"error": "turn_on() not found on device"}), 500
    return jsonify({"status": "on"})


@app.route("/ac/off", methods=["POST"])
def turn_off():
    found, _ = call_device("turn_off")
    if not found:
        return jsonify({"error": "turn_off() not found on device"}), 500
    return jsonify({"status": "off"})


@app.route("/ac/temperature/<int:value>", methods=["POST"])
def set_temperature(value):
    method_used, _ = try_candidates(
        ["set_temperature", "set_temp", "set_target_temperature"], float(value)
    )
    if not method_used:
        return jsonify({
            "error": "no temperature-setting method found on the device. "
                     "Call /ac/inspect and update the candidate list in app.py."
        }), 500
    return jsonify({"status": "ok", "method_used": method_used, "temperature": value})


@app.route("/ac/mode/<mode>", methods=["POST"])
def set_mode(mode):
    method_used, _ = try_candidates(
        ["set_mode", "set_hvac_mode", "set_operation_mode"], mode
    )
    if not method_used:
        return jsonify({
            "error": "no mode-setting method found on the device. "
                     "Call /ac/inspect and update the candidate list in app.py."
        }), 500
    return jsonify({"status": "ok", "method_used": method_used, "mode": mode})


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    app.run(host="0.0.0.0", port=port, threaded=True)
