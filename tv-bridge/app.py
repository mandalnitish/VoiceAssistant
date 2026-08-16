"""
Google TV / Android TV bridge server
=====================================
Wraps the `androidtvremote2` library, which speaks the same "Android TV
Remote protocol v2" that the Google TV mobile app uses. No ADB, no developer
mode on the TV needed — just the "Android TV Remote Service" that's
preinstalled on Google TV / most modern Android TV devices.

IMPORTANT — run this LOCALLY, not on Render:
  Your AC bridge lives on Render because Panasonic's MirAIe cloud is
  reachable from anywhere. Your TV is NOT reachable from the internet —
  it only exists on your home WiFi. So this server must run on something
  that's on your home network too: your Windows PC (the same one you already
  use for the TMS backend) is the natural choice, as long as it's on and
  awake when you want to give TV voice commands.

ONE-TIME PAIRING (do this first, via curl or Postman — see README.md):
  1. POST /tv/pair/start    {"host": "<TV's local IP>"}   -> TV shows a code
  2. POST /tv/pair/complete {"code": "<code from TV screen>"}

EVERYDAY ENDPOINTS (after pairing, all require header X-Api-Key: <key>):
  GET  /health
  POST /tv/power
  POST /tv/volume/<up|down|mute>
  POST /tv/nav/<up|down|left|right|select|home|back|play|pause>
  POST /tv/app/<package_or_deeplink>   e.g. /tv/app/com.netflix.ninja

ENV VARS:
  BRIDGE_API_KEY - a secret string you make up, shared with the Android app
  PORT           - defaults to 5001
"""

import asyncio
import json
import logging
import os
import threading
from pathlib import Path

from androidtvremote2 import AndroidTVRemote
from flask import Flask, jsonify, request

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("tv-bridge")

BASE_DIR = Path(__file__).parent
CERT_FILE = str(BASE_DIR / "tv_cert.pem")
KEY_FILE = str(BASE_DIR / "tv_key.pem")
CONFIG_FILE = BASE_DIR / "tv_config.json"

API_KEY = os.environ.get("BRIDGE_API_KEY", "")

app = Flask(__name__)

# --- Background asyncio loop (same pattern as the AC bridge) ---------------
loop = asyncio.new_event_loop()
remote: AndroidTVRemote | None = None
connected = threading.Event()
last_error: str | None = None


def _run_loop_forever():
    asyncio.set_event_loop(loop)
    loop.run_forever()


threading.Thread(target=_run_loop_forever, daemon=True, name="tv-loop").start()


def run_coro(coro, timeout=20):
    return asyncio.run_coroutine_threadsafe(coro, loop).result(timeout=timeout)


def call_on_loop(fn, *args, **kwargs):
    """Runs a (sync) call to `remote` on the bridge's event loop thread,
    since the remote object is bound to that loop and isn't thread-safe."""
    async def _wrap():
        return fn(*args, **kwargs)
    return run_coro(_wrap())


def load_host():
    if CONFIG_FILE.exists():
        return json.loads(CONFIG_FILE.read_text()).get("host")
    return None


def save_host(host):
    CONFIG_FILE.write_text(json.dumps({"host": host}))


async def _make_remote(host):
    global remote
    remote = AndroidTVRemote(
        client_name="Voice Assistant Bridge",
        certfile=CERT_FILE,
        keyfile=KEY_FILE,
        host=host,
        loop=loop,
    )
    await remote.async_generate_cert_if_missing()
    return remote


async def _connect_and_keep(host):
    global connected, last_error
    try:
        await _make_remote(host)
        await remote.async_connect()
        connected.set()
        last_error = None
        remote.keep_reconnecting()
    except Exception as e:  # noqa: BLE001
        last_error = str(e)
        connected.clear()
        log.exception("Failed to connect to TV")


_saved_host = load_host()
if _saved_host:
    asyncio.run_coroutine_threadsafe(_connect_and_keep(_saved_host), loop)


def ensure_connected():
    if not connected.is_set() or remote is None:
        raise RuntimeError(
            "TV isn't connected. Pair it first: POST /tv/pair/start then /tv/pair/complete."
        )


# --- Auth --------------------------------------------------------------

@app.before_request
def check_auth():
    if request.path == "/health":
        return
    if API_KEY and request.headers.get("X-Api-Key") != API_KEY:
        return jsonify({"error": "unauthorized"}), 401


# --- Routes --------------------------------------------------------------

@app.route("/health")
def health():
    return jsonify({"connected": connected.is_set(), "error": last_error, "host": load_host()})


@app.route("/tv/pair/start", methods=["POST"])
def pair_start():
    data = request.get_json(force=True, silent=True) or {}
    host = data.get("host")
    if not host:
        return jsonify({"error": "provide 'host': the TV's local IP address"}), 400
    save_host(host)
    try:
        async def _start():
            await _make_remote(host)
            await remote.async_start_pairing()
        run_coro(_start())
    except Exception as e:  # noqa: BLE001
        return jsonify({"error": str(e)}), 500
    return jsonify({"status": "check your TV screen for a pairing code, then POST it to /tv/pair/complete"})


@app.route("/tv/pair/complete", methods=["POST"])
def pair_complete():
    data = request.get_json(force=True, silent=True) or {}
    code = data.get("code")
    if not code:
        return jsonify({"error": "provide 'code': the pairing code shown on the TV"}), 400
    if remote is None:
        return jsonify({"error": "call /tv/pair/start first"}), 400
    try:
        run_coro(remote.async_finish_pairing(code))
    except Exception as e:  # noqa: BLE001
        return jsonify({"error": str(e)}), 500
    host = load_host()
    run_coro(_connect_and_keep(host))
    return jsonify({"status": "paired and connected"})


@app.route("/tv/power", methods=["POST"])
def power():
    try:
        ensure_connected()
        call_on_loop(remote.send_key_command, "KEYCODE_POWER")
    except Exception as e:  # noqa: BLE001
        return jsonify({"error": str(e)}), 500
    return jsonify({"status": "ok"})


@app.route("/tv/volume/<action>", methods=["POST"])
def volume(action):
    keymap = {"up": "KEYCODE_VOLUME_UP", "down": "KEYCODE_VOLUME_DOWN", "mute": "KEYCODE_VOLUME_MUTE"}
    key = keymap.get(action)
    if not key:
        return jsonify({"error": "action must be up, down, or mute"}), 400
    try:
        ensure_connected()
        call_on_loop(remote.send_key_command, key)
    except Exception as e:  # noqa: BLE001
        return jsonify({"error": str(e)}), 500
    return jsonify({"status": "ok"})


@app.route("/tv/nav/<direction>", methods=["POST"])
def nav(direction):
    keymap = {
        "up": "KEYCODE_DPAD_UP",
        "down": "KEYCODE_DPAD_DOWN",
        "left": "KEYCODE_DPAD_LEFT",
        "right": "KEYCODE_DPAD_RIGHT",
        "select": "KEYCODE_DPAD_CENTER",
        "ok": "KEYCODE_DPAD_CENTER",
        "home": "KEYCODE_HOME",
        "back": "KEYCODE_BACK",
        "play": "KEYCODE_MEDIA_PLAY_PAUSE",
        "pause": "KEYCODE_MEDIA_PLAY_PAUSE",
    }
    key = keymap.get(direction)
    if not key:
        return jsonify({"error": f"unknown direction '{direction}'"}), 400
    try:
        ensure_connected()
        call_on_loop(remote.send_key_command, key)
    except Exception as e:  # noqa: BLE001
        return jsonify({"error": str(e)}), 500
    return jsonify({"status": "ok"})


@app.route("/tv/app/<path:app_id>", methods=["POST"])
def launch_app(app_id):
    try:
        ensure_connected()
        call_on_loop(remote.send_launch_app_command, app_id)
    except Exception as e:  # noqa: BLE001
        return jsonify({"error": str(e)}), 500
    return jsonify({"status": "ok", "app": app_id})


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5001))
    app.run(host="0.0.0.0", port=port, threaded=True)
