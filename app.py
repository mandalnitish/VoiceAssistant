"""
MirAIe AC Bridge Server
=======================

Flask HTTP bridge for controlling a Panasonic MirAIe AC
using the community miraie-ac Python library.

Public endpoints:
    GET  /
    GET  /health

Protected endpoints:
    GET  /ac/inspect
    GET  /ac/status

    POST /ac/on
    POST /ac/off
    POST /ac/temperature/<temperature>
    POST /ac/mode/<mode>

Required Render environment variables:
    MIRAIE_MOBILE
    MIRAIE_PASSWORD
    BRIDGE_API_KEY
"""

import asyncio
import logging
import os
import threading
import time

from flask import Flask, jsonify, request

from miraie_ac import MirAIeBroker, MirAIeHub
from miraie_ac.enums import HVACMode


# ============================================================================
# LOGGING
# ============================================================================

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)

log = logging.getLogger("miraie-bridge")


# ============================================================================
# FLASK
# ============================================================================

app = Flask(__name__)


# ============================================================================
# ENVIRONMENT VARIABLES
# ============================================================================

MOBILE = os.getenv("MIRAIE_MOBILE")
PASSWORD = os.getenv("MIRAIE_PASSWORD")
API_KEY = os.getenv("BRIDGE_API_KEY")


# ============================================================================
# GLOBAL MIRAIe STATE
# ============================================================================

loop = None
loop_thread = None

hub = None
broker = None

connected = threading.Event()
loop_ready = threading.Event()
startup_started = threading.Event()

connect_error = None

state_lock = threading.Lock()


# ============================================================================
# ASYNCIO THREAD
# ============================================================================

def asyncio_thread_worker():
    """
    Dedicated asyncio event loop for miraie-ac.
    """

    global loop

    try:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)

        log.info("MirAIe asyncio loop started")

        # Tell the Flask side that the loop exists.
        loop_ready.set()

        # Keep the loop alive.
        loop.run_forever()

    except Exception as exc:
        log.exception(
            "MirAIe asyncio loop crashed: %s",
            exc,
        )

    finally:
        loop_ready.set()


def start_asyncio_loop():
    """
    Start the asyncio thread once.

    IMPORTANT:
    This function does NOT use a lock that can deadlock during
    Flask/Gunicorn startup.
    """

    global loop_thread

    if loop_thread is not None and loop_thread.is_alive():
        return

    with state_lock:

        if loop_thread is not None and loop_thread.is_alive():
            return

        loop_thread = threading.Thread(
            target=asyncio_thread_worker,
            daemon=True,
            name="miraie-asyncio",
        )

        loop_thread.start()

    # Wait only for the asyncio loop itself.
    # This does NOT wait for MirAIe authentication.
    if not loop_ready.wait(timeout=5):
        raise RuntimeError(
            "MirAIe asyncio loop did not start"
        )


# ============================================================================
# MIRAIe CONNECTION
# ============================================================================

async def connect_miraie():
    """
    Connect to the MirAIe service.

    This runs inside the dedicated asyncio event loop.
    """

    global hub
    global broker
    global connect_error

    try:

        if not MOBILE:
            raise RuntimeError(
                "MIRAIE_MOBILE environment variable is missing"
            )

        if not PASSWORD:
            raise RuntimeError(
                "MIRAIE_PASSWORD environment variable is missing"
            )

        log.info("Connecting to MirAIe...")

        # Create these objects inside the asyncio thread.
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
                "Connected, but could not list devices"
            )

    except Exception as exc:

        connected.clear()
        connect_error = str(exc)

        log.exception(
            "Failed to connect to MirAIe"
        )


def start_miraie_connection():
    """
    Start the asyncio loop and schedule MirAIe connection.

    This function returns immediately after scheduling the connection.
    """

    start_asyncio_loop()

    if loop is None:
        raise RuntimeError(
            "MirAIe asyncio loop is unavailable"
        )

    # Do not start multiple connection attempts.
    if connected.is_set():
        return

    # Schedule connection on the dedicated asyncio loop.
    asyncio.run_coroutine_threadsafe(
        connect_miraie(),
        loop,
    )


def ensure_miraie_started():
    """
    Start MirAIe connection in the background.

    This is deliberately non-blocking.
    """

    if startup_started.is_set():
        return

    with state_lock:

        if startup_started.is_set():
            return

        startup_started.set()

        try:

            start_miraie_connection()

        except Exception as exc:

            startup_started.clear()

            log.exception(
                "Could not start MirAIe: %s",
                exc,
            )


# ============================================================================
# WAIT FOR MIRAIe
# ============================================================================

def wait_for_connection(timeout=30):
    """
    Wait for MirAIe to become connected.

    Used only by AC control commands.
    /health never waits for this.
    """

    ensure_miraie_started()

    end_time = time.monotonic() + timeout

    while time.monotonic() < end_time:

        if connected.is_set():
            return True

        time.sleep(0.2)

    return connected.is_set()


# ============================================================================
# DEVICE COMMAND
# ============================================================================

async def execute_device_command(
    device_index,
    method_name,
    *args,
):
    """
    Execute a method on the MirAIe device.

    Runs inside the MirAIe asyncio event loop.
    """

    if not connected.is_set():
        raise RuntimeError(
            connect_error
            or "MirAIe is not connected"
        )

    if hub is None:
        raise RuntimeError(
            "MirAIe hub is not initialized"
        )

    if not hasattr(hub, "home"):
        raise RuntimeError(
            "MirAIe home is not available"
        )

    devices = hub.home.devices

    if not devices:
        raise RuntimeError(
            "No MirAIe devices found"
        )

    if device_index < 0 or device_index >= len(devices):
        raise RuntimeError(
            f"Invalid device index: {device_index}"
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

    log.info(
        "device.%s completed",
        method_name,
    )

    return True, result


def call_device(
    method_name,
    *args,
    device_index=0,
    timeout=30,
):
    """
    Safely call an async device method from Flask.
    """

    if not wait_for_connection(timeout=timeout):
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
            device_index,
            method_name,
            *args,
        ),
        loop,
    )

    try:

        return future.result(
            timeout=timeout,
        )

    except TimeoutError:

        future.cancel()

        raise RuntimeError(
            f"MirAIe command '{method_name}' timed out"
        )

    except Exception as exc:

        log.exception(
            "Device command failed"
        )

        raise RuntimeError(
            str(exc)
        ) from exc


# ============================================================================
# AUTHENTICATION
# ============================================================================

@app.before_request
def check_auth():
    """
    / and /health are public.

    All AC endpoints require:
        X-Api-Key: <BRIDGE_API_KEY>
    """

    if request.path in (
        "/",
        "/health",
    ):
        return None

    if not API_KEY:

        return jsonify({
            "error": "BRIDGE_API_KEY is not configured on Render"
        }), 500

    supplied_key = request.headers.get(
        "X-Api-Key"
    )

    if supplied_key != API_KEY:

        return jsonify({
            "error": "unauthorized"
        }), 401

    return None


# ============================================================================
# ROOT
# ============================================================================

@app.route(
    "/",
    methods=["GET", "HEAD"],
)
def root():

    # Start MirAIe in background.
    ensure_miraie_started()

    return jsonify({
        "service": "MirAIe AC Bridge",
        "status": "running",
        "health": "/health",
    })


# ============================================================================
# HEALTH
# ============================================================================

@app.route(
    "/health",
    methods=["GET", "HEAD"],
)
def health():
    """
    IMPORTANT:
    This endpoint NEVER waits for MirAIe.

    Render can therefore detect the HTTP service immediately.
    """

    # Start connection in the background.
    ensure_miraie_started()

    return jsonify({
        "status": "ok",
        "connected": connected.is_set(),
        "error": connect_error,
    }), 200


# ============================================================================
# AC INSPECT
# ============================================================================

@app.route(
    "/ac/inspect",
    methods=["GET"],
)
def inspect_device():

    try:

        if not wait_for_connection(30):

            return jsonify({
                "error": connect_error
                or "MirAIe is still connecting"
            }), 503

        device = hub.home.devices[0]

        members = sorted(
            member
            for member in dir(device)
            if not member.startswith("_")
        )

        return jsonify({
            "device_repr": repr(device),
            "members": members,
        })

    except Exception as exc:

        log.exception(
            "Device inspection failed"
        )

        return jsonify({
            "error": str(exc)
        }), 503


# ============================================================================
# AC STATUS
# ============================================================================

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

        status = getattr(
            device,
            "status",
            None,
        )

        return jsonify({
            "connected": True,
            "status": str(status)
            if status is not None
            else None,
        })

    except Exception as exc:

        log.exception(
            "AC status failed"
        )

        return jsonify({
            "error": str(exc)
        }), 503


# ============================================================================
# AC ON
# ============================================================================

@app.route(
    "/ac/on",
    methods=["POST"],
)
def turn_on():

    try:

        found, result = call_device(
            "turn_on",
            timeout=30,
        )

        if not found:

            return jsonify({
                "error": "turn_on() not found on device"
            }), 500

        return jsonify({
            "status": "on",
            "method_used": "turn_on",
            "result": str(result),
        })

    except Exception as exc:

        log.exception(
            "AC ON failed"
        )

        return jsonify({
            "error": str(exc)
        }), 500


# ============================================================================
# AC OFF
# ============================================================================

@app.route(
    "/ac/off",
    methods=["POST"],
)
def turn_off():

    try:

        found, result = call_device(
            "turn_off",
            timeout=30,
        )

        if not found:

            return jsonify({
                "error": "turn_off() not found on device"
            }), 500

        return jsonify({
            "status": "off",
            "method_used": "turn_off",
            "result": str(result),
        })

    except Exception as exc:

        log.exception(
            "AC OFF failed"
        )

        return jsonify({
            "error": str(exc)
        }), 500


# ============================================================================
# SET TEMPERATURE
# ============================================================================

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
            timeout=30,
        )

        if not found:

            return jsonify({
                "error": (
                    "set_temperature() "
                    "not found on device"
                )
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


# ============================================================================
# SET MODE
# ============================================================================

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
                "Use cool, auto, dry, fan, heat"
            )
        }), 400

    try:

        found, result = call_device(
            "set_hvac_mode",
            modes[mode],
            timeout=30,
        )

        if not found:

            return jsonify({
                "error": (
                    "set_hvac_mode() "
                    "not found on device"
                )
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


# ============================================================================
# LOCAL DEVELOPMENT ONLY
# ============================================================================

if __name__ == "__main__":

    port = int(
        os.getenv(
            "PORT",
            "5000",
        )
    )

    log.info(
        "Starting Flask server on port %s",
        port,
    )

    app.run(
        host="0.0.0.0",
        port=port,
        threaded=True,
    )