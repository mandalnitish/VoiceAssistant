import asyncio
import logging
import os
import threading
import time

from flask import Flask, jsonify, request
from miraie_ac import MirAIeBroker, MirAIeHub
from miraie_ac.enums import (
    HVACMode,
    FanMode,
    SwingMode,
    PresetMode,
    DisplayMode,
    ConvertiMode,
)


# ============================================================
# LOGGING
# ============================================================

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)

log = logging.getLogger("miraie-bridge")


# ============================================================
# FLASK
# ============================================================

app = Flask(__name__)


# ============================================================
# ENVIRONMENT VARIABLES
# ============================================================

MOBILE = os.getenv("MIRAIE_MOBILE")
PASSWORD = os.getenv("MIRAIE_PASSWORD")
API_KEY = os.getenv("BRIDGE_API_KEY")


# ============================================================
# MIRAIe GLOBAL STATE
# ============================================================

loop = None
loop_thread = None

hub = None
broker = None

connected = threading.Event()
loop_ready = threading.Event()
connection_started = threading.Event()

connect_error = None

# IMPORTANT:
# RLock prevents the deadlock that existed in the previous version.
state_lock = threading.RLock()


# ============================================================
# ASYNCIO LOOP
# ============================================================

def asyncio_worker():
    global loop

    try:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)

        log.info("MirAIe asyncio loop started")

        loop_ready.set()

        loop.run_forever()

    except Exception:
        log.exception("MirAIe asyncio loop crashed")

    finally:
        loop_ready.set()


def start_asyncio_loop():

    global loop_thread

    if loop_thread is not None and loop_thread.is_alive():
        return

    with state_lock:

        if loop_thread is not None and loop_thread.is_alive():
            return

        loop_thread = threading.Thread(
            target=asyncio_worker,
            daemon=True,
            name="miraie-asyncio",
        )

        loop_thread.start()

    if not loop_ready.wait(timeout=5):

        raise RuntimeError(
            "MirAIe asyncio loop failed to start"
        )


# ============================================================
# CONNECT TO MIRAIe
# ============================================================

async def connect_miraie():

    global hub
    global broker
    global connect_error

    try:

        if not MOBILE:
            raise RuntimeError(
                "MIRAIE_MOBILE is not configured"
            )

        if not PASSWORD:
            raise RuntimeError(
                "MIRAIE_PASSWORD is not configured"
            )

        log.info("Connecting to MirAIe...")

        broker = MirAIeBroker()
        hub = MirAIeHub()

        await hub.init(
            MOBILE,
            PASSWORD,
            broker,
        )

        # Wait for MQTT client.
        for _ in range(60):

            if getattr(broker, "client", None) is not None:
                break

            await asyncio.sleep(0.5)

        else:

            raise RuntimeError(
                "MirAIe MQTT client did not become available"
            )

        connected.set()
        connect_error = None

        log.info(
            "Connected to MirAIe successfully."
        )

        try:

            log.info(
                "Devices found: %s",
                hub.home.devices,
            )

        except Exception:

            log.exception(
                "Connected to MirAIe but could not list devices"
            )

    except Exception as exc:

        connected.clear()
        connect_error = str(exc)

        log.exception(
            "Failed to connect to MirAIe"
        )


def start_miraie_connection():

    # Start asyncio loop.
    start_asyncio_loop()

    if loop is None:
        raise RuntimeError(
            "MirAIe asyncio loop is unavailable"
        )

    if connected.is_set():
        return

    if connection_started.is_set():
        return

    with state_lock:

        if connection_started.is_set():
            return

        connection_started.set()

        asyncio.run_coroutine_threadsafe(
            connect_miraie(),
            loop,
        )

        log.info(
            "MirAIe connection task started"
        )


def ensure_miraie_started():

    if connected.is_set():
        return

    if connection_started.is_set():
        return

    try:

        start_miraie_connection()

    except Exception as exc:

        connection_started.clear()

        log.exception(
            "Could not start MirAIe connection: %s",
            exc,
        )


# ============================================================
# WAIT FOR CONNECTION
# ============================================================

def wait_for_connection(timeout=30):

    ensure_miraie_started()

    end_time = time.monotonic() + timeout

    while time.monotonic() < end_time:

        if connected.is_set():
            return True

        time.sleep(0.2)

    return connected.is_set()


# ============================================================
# DEVICE COMMAND
# ============================================================

async def execute_device_command(
    method_name,
    *args,
    device_index=0,
):

    if not connected.is_set():

        raise RuntimeError(
            connect_error
            or "MirAIe is not connected"
        )

    if hub is None:

        raise RuntimeError(
            "MirAIe hub is not initialized"
        )

    devices = hub.home.devices

    if not devices:

        raise RuntimeError(
            "No MirAIe devices found"
        )

    if device_index >= len(devices):

        raise RuntimeError(
            "Invalid device index"
        )

    device = devices[device_index]

    method = getattr(
        device,
        method_name,
        None,
    )

    if method is None:

        return False, None

    log.info(
        "Calling device.%s(%s)",
        method_name,
        args,
    )

    result = method(*args)

    if asyncio.iscoroutine(result):
        result = await result

    return True, result


def call_device(
    method_name,
    *args,
    timeout=30,
):

    if not wait_for_connection(timeout):

        raise RuntimeError(
            connect_error
            or "MirAIe is still connecting"
        )

    if loop is None:

        raise RuntimeError(
            "MirAIe asyncio loop is not running"
        )

    future = asyncio.run_coroutine_threadsafe(
        execute_device_command(
            method_name,
            *args,
        ),
        loop,
    )

    try:

        return future.result(
            timeout=timeout
        )

    except TimeoutError:

        future.cancel()

        raise RuntimeError(
            f"Command '{method_name}' timed out"
        )

    except Exception as exc:

        log.exception(
            "AC command failed"
        )

        raise RuntimeError(
            str(exc)
        )


# ============================================================
# AUTHENTICATION
# ============================================================

@app.before_request
def check_auth():

    # Public endpoints.
    if request.path in (
        "/",
        "/health",
    ):
        return None

    if not API_KEY:

        return jsonify({
            "error": "BRIDGE_API_KEY is not configured"
        }), 500

    supplied_key = request.headers.get(
        "X-Api-Key"
    )

    if supplied_key != API_KEY:

        return jsonify({
            "error": "unauthorized"
        }), 401

    return None


# ============================================================
# ROOT
# ============================================================

@app.route(
    "/",
    methods=["GET", "HEAD"],
)
def root():

    ensure_miraie_started()

    return jsonify({
        "service": "MirAIe AC Bridge",
        "status": "running",
        "health": "/health",
    })


# ============================================================
# HEALTH
# ============================================================

@app.route(
    "/health",
    methods=["GET", "HEAD"],
)
def health():

    # IMPORTANT:
    # Start MirAIe but DON'T wait for it.
    ensure_miraie_started()

    return jsonify({
        "status": "ok",
        "connected": connected.is_set(),
        "error": connect_error,
    })


# ============================================================
# INSPECT
# ============================================================

@app.route(
    "/ac/inspect",
    methods=["GET"],
)
def inspect():

    try:

        if not wait_for_connection(30):

            return jsonify({
                "error": connect_error
                or "MirAIe is still connecting"
            }), 503

        device = hub.home.devices[0]

        members = sorted(
            x
            for x in dir(device)
            if not x.startswith("_")
        )

        return jsonify({
            "device_repr": repr(device),
            "members": members,
        })

    except Exception as exc:

        log.exception(
            "Inspection failed"
        )

        return jsonify({
            "error": str(exc)
        }), 503


# ============================================================
# AC STATUS
# ============================================================

@app.route(
    "/ac/status",
    methods=["GET"],
)
def ac_status():

    try:

        if not wait_for_connection(30):

            return jsonify({
                "connected": False,
                "error": connect_error
                or "MirAIe is still connecting",
            }), 503

        device = hub.home.devices[0]

        return jsonify({
            "connected": True,
            "device": repr(device),
            "status": str(
                getattr(
                    device,
                    "status",
                    None,
                )
            ),
        })

    except Exception as exc:

        return jsonify({
            "error": str(exc)
        }), 503


# ============================================================
# AC ON
# ============================================================

@app.route(
    "/ac/on",
    methods=["POST"],
)
def turn_on():

    try:

        found, result = call_device(
            "turn_on"
        )

        if not found:

            return jsonify({
                "error": "turn_on() not found"
            }), 500

        return jsonify({
            "status": "on",
            "method_used": "turn_on",
            "result": str(result),
        })

    except Exception as exc:

        log.exception(
            "Turn ON failed"
        )

        return jsonify({
            "error": str(exc)
        }), 500


# ============================================================
# AC OFF
# ============================================================

@app.route(
    "/ac/off",
    methods=["POST"],
)
def turn_off():

    try:

        found, result = call_device(
            "turn_off"
        )

        if not found:

            return jsonify({
                "error": "turn_off() not found"
            }), 500

        return jsonify({
            "status": "off",
            "method_used": "turn_off",
            "result": str(result),
        })

    except Exception as exc:

        log.exception(
            "Turn OFF failed"
        )

        return jsonify({
            "error": str(exc)
        }), 500


# ============================================================
# TEMPERATURE
# ============================================================

@app.route(
    "/ac/temperature/<int:value>",
    methods=["POST"],
)
def set_temperature(value):

    if value < 16 or value > 30:

        return jsonify({
            "error": "Temperature must be between 16 and 30°C"
        }), 400

    try:

        found, result = call_device(
            "set_temperature",
            float(value),
        )

        if not found:

            return jsonify({
                "error": "set_temperature() not found"
            }), 500

        return jsonify({
            "status": "ok",
            "method_used": "set_temperature",
            "temperature": value,
            "result": str(result),
        })

    except Exception as exc:

        log.exception(
            "Temperature command failed"
        )

        return jsonify({
            "error": str(exc)
        }), 500


# ============================================================
# MODE
# ============================================================

@app.route(
    "/ac/mode/<mode>",
    methods=["POST"],
)
def set_mode(mode):

    mode = mode.lower().strip()

    modes = {
        "cool": HVACMode.COOL,
        "auto": HVACMode.AUTO,
        "dry": HVACMode.DRY,
        "fan": HVACMode.FAN,
        "heat": HVACMode.HEAT,
    }

    if mode not in modes:

        return jsonify({
            "error": (
                "Invalid mode. "
                "Use: cool, auto, dry, fan, heat"
            )
        }), 400

    try:

        found, result = call_device(
            "set_hvac_mode",
            modes[mode],
        )

        if not found:

            return jsonify({
                "error": "set_hvac_mode() not found"
            }), 500

        return jsonify({
            "status": "ok",
            "method_used": "set_hvac_mode",
            "mode": mode,
            "result": str(result),
        })

    except Exception as exc:

        log.exception(
            "Mode command failed"
        )

        return jsonify({
            "error": str(exc)
        }), 500


# ============================================================
# FAN SPEED
# ============================================================

@app.route(
    "/ac/fan/<speed>",
    methods=["POST"],
)
def set_fan_speed(speed):

    speed = speed.lower().strip()

    speeds = {
        "auto": FanMode.AUTO,
        "low": FanMode.LOW,
        "medium": FanMode.MEDIUM,
        "high": FanMode.HIGH,
        "quiet": FanMode.QUIET,
    }

    if speed not in speeds:

        return jsonify({
            "error": (
                "Invalid fan speed. "
                "Use: auto, low, medium, high, quiet"
            )
        }), 400

    try:

        found, result = call_device(
            "set_fan_mode",
            speeds[speed],
        )

        if not found:

            return jsonify({
                "error": "set_fan_mode() not found"
            }), 500

        return jsonify({
            "status": "ok",
            "method_used": "set_fan_mode",
            "speed": speed,
            "result": str(result),
        })

    except Exception as exc:

        log.exception(
            "Fan speed command failed"
        )

        return jsonify({
            "error": str(exc)
        }), 500


# ============================================================
# SWING
# ============================================================

@app.route(
    "/ac/swing/<mode>",
    methods=["POST"],
)
def set_swing(mode):

    mode = mode.lower().strip()

    modes = {
        "auto": SwingMode.AUTO,
        "one": SwingMode.ONE,
        "two": SwingMode.TWO,
        "three": SwingMode.THREE,
        "four": SwingMode.FOUR,
        "five": SwingMode.FIVE,
    }

    if mode not in modes:

        return jsonify({
            "error": (
                "Invalid swing mode. "
                "Use: auto, one, two, three, four, five"
            )
        }), 400

    try:

        found, result = call_device(
            "set_v_swing_mode",
            modes[mode],
        )

        if not found:

            return jsonify({
                "error": "set_v_swing_mode() not found"
            }), 500

        return jsonify({
            "status": "ok",
            "method_used": "set_v_swing_mode",
            "mode": mode,
            "result": str(result),
        })

    except Exception as exc:

        log.exception(
            "Swing command failed"
        )

        return jsonify({
            "error": str(exc)
        }), 500


# ============================================================
# PRESET (POWERFUL / ECO MODE / CLEAN)
# ============================================================

@app.route(
    "/ac/preset/<mode>",
    methods=["POST"],
)
def set_preset(mode):

    mode = mode.lower().strip()

    modes = {
        "none": PresetMode.NONE,
        "eco": PresetMode.ECO,
        "boost": PresetMode.BOOST,
        "powerful": PresetMode.BOOST,
        "clean": PresetMode.CLEAN,
    }

    if mode not in modes:

        return jsonify({
            "error": (
                "Invalid preset. "
                "Use: none, eco, boost, clean"
            )
        }), 400

    try:

        found, result = call_device(
            "set_preset_mode",
            modes[mode],
        )

        if not found:

            return jsonify({
                "error": "set_preset_mode() not found"
            }), 500

        return jsonify({
            "status": "ok",
            "method_used": "set_preset_mode",
            "mode": mode,
            "result": str(result),
        })

    except Exception as exc:

        log.exception(
            "Preset command failed"
        )

        return jsonify({
            "error": str(exc)
        }), 500


# ============================================================
# HORIZONTAL SWING
# ============================================================

@app.route(
    "/ac/swing/horizontal/<mode>",
    methods=["POST"],
)
def set_swing_horizontal(mode):

    mode = mode.lower().strip()

    modes = {
        "auto": SwingMode.AUTO,
        "one": SwingMode.ONE,
        "two": SwingMode.TWO,
        "three": SwingMode.THREE,
        "four": SwingMode.FOUR,
        "five": SwingMode.FIVE,
    }

    if mode not in modes:

        return jsonify({
            "error": (
                "Invalid swing mode. "
                "Use: auto, one, two, three, four, five"
            )
        }), 400

    try:

        found, result = call_device(
            "set_h_swing_mode",
            modes[mode],
        )

        if not found:

            return jsonify({
                "error": "set_h_swing_mode() not found"
            }), 500

        return jsonify({
            "status": "ok",
            "method_used": "set_h_swing_mode",
            "mode": mode,
            "result": str(result),
        })

    except Exception as exc:

        log.exception(
            "Horizontal swing command failed"
        )

        return jsonify({
            "error": str(exc)
        }), 500


# ============================================================
# DISPLAY
# ============================================================

@app.route(
    "/ac/display/<mode>",
    methods=["POST"],
)
def set_display(mode):

    mode = mode.lower().strip()

    modes = {
        "on": DisplayMode.ON,
        "off": DisplayMode.OFF,
    }

    if mode not in modes:

        return jsonify({
            "error": "Invalid display mode. Use: on, off"
        }), 400

    try:

        found, result = call_device(
            "set_display_mode",
            modes[mode],
        )

        if not found:

            return jsonify({
                "error": "set_display_mode() not found"
            }), 500

        return jsonify({
            "status": "ok",
            "method_used": "set_display_mode",
            "mode": mode,
            "result": str(result),
        })

    except Exception as exc:

        log.exception(
            "Display command failed"
        )

        return jsonify({
            "error": str(exc)
        }), 500


# ============================================================
# CONVERTI 8
# ============================================================

@app.route(
    "/ac/converti/<mode>",
    methods=["POST"],
)
def set_converti(mode):

    mode = mode.lower().strip()

    modes = {
        "off": ConvertiMode.OFF,
        "on": ConvertiMode.FC,
        "fc": ConvertiMode.FC,
        "hc": ConvertiMode.HC,
        "90": ConvertiMode.C90,
        "80": ConvertiMode.C80,
        "70": ConvertiMode.C70,
        "55": ConvertiMode.C55,
        "40": ConvertiMode.C40,
    }

    if mode not in modes:

        return jsonify({
            "error": (
                "Invalid converti mode. "
                "Use: off, on, hc, 90, 80, 70, 55, 40"
            )
        }), 400

    try:

        found, result = call_device(
            "set_converti_mode",
            modes[mode],
        )

        if not found:

            return jsonify({
                "error": "set_converti_mode() not found"
            }), 500

        return jsonify({
            "status": "ok",
            "method_used": "set_converti_mode",
            "mode": mode,
            "result": str(result),
        })

    except Exception as exc:

        log.exception(
            "Converti8 command failed"
        )

        return jsonify({
            "error": str(exc)
        }), 500


# ============================================================
# LOCAL DEVELOPMENT
# ============================================================

if __name__ == "__main__":

    port = int(
        os.getenv(
            "PORT",
            "10000",
        )
    )

    log.info(
        "Starting server on 0.0.0.0:%s",
        port,
    )

    app.run(
        host="0.0.0.0",
        port=port,
        threaded=True,
    )