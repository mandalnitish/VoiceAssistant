"""
MirAIe AC Bridge Server
=======================

Flask HTTP API for controlling a Panasonic MirAIe AC
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

Required environment variables:
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
# FLASK APPLICATION
# ============================================================================

app = Flask(__name__)


# ============================================================================
# ENVIRONMENT VARIABLES
# ============================================================================

MOBILE = os.getenv("MIRAIE_MOBILE")
PASSWORD = os.getenv("MIRAIE_PASSWORD")
API_KEY = os.getenv("BRIDGE_API_KEY")


# ============================================================================
# MIRAIe STATE
# ============================================================================

loop = None
loop_thread = None

hub = None
broker = None

connected = threading.Event()

connect_error = None

startup_lock = threading.Lock()
startup_complete = False


# ============================================================================
# ASYNCIO LOOP
# ============================================================================

def asyncio_worker():
    """
    Dedicated asyncio thread.

    The MirAIe MQTT library uses asyncio, while Flask/Gunicorn
    handles normal synchronous HTTP requests.
    """

    global loop

    loop = asyncio.new_event_loop()

    asyncio.set_event_loop(loop)

    log.info("MirAIe asyncio loop started")

    loop.run_forever()


def start_asyncio_loop():
    """
    Start the asyncio loop exactly once.
    """

    global loop_thread

    with startup_lock:

        if loop_thread is not None and loop_thread.is_alive():
            return

        loop_thread = threading.Thread(
            target=asyncio_worker,
            daemon=True,
            name="miraie-asyncio",
        )

        loop_thread.start()

    # Wait until the asyncio loop exists.
    for _ in range(100):

        if loop is not None:
            return

        time.sleep(0.01)

    raise RuntimeError(
        "MirAIe asyncio loop failed to start"
    )


# ============================================================================
# MIRAIe CONNECTION
# ============================================================================

async def connect_miraie():
    """
    Connect and authenticate with MirAIe.
    """

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

        # Create broker and hub inside the dedicated asyncio loop.
        broker = MirAIeBroker()
        hub = MirAIeHub()

        # Authenticate.
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
    """
    Start the asyncio loop and then start the MirAIe connection.
    """

    start_asyncio_loop()

    asyncio.run_coroutine_threadsafe(
        connect_miraie(),
        loop,
    )


# ============================================================================
# CONNECTION CHECK
# ============================================================================

def require_connection():
    """
    Make sure MirAIe is ready before executing an AC command.
    """

    if not connected.is_set():

        raise RuntimeError(
            connect_error
            or "MirAIe is still connecting. Please try again."
        )

    if hub is None:

        raise RuntimeError(
            "MirAIe hub is not initialized"
        )

    if broker is None:

        raise RuntimeError(
            "MirAIe broker is not initialized"
        )

    if not hasattr(hub, "home"):

        raise RuntimeError(
            "MirAIe home is not available"
        )

    if not hub.home.devices:

        raise RuntimeError(
            "No MirAIe devices were found"
        )


# ============================================================================
# DEVICE COMMAND
# ============================================================================

async def execute_device_command(
    device_index,
    method_name,
    *args,
):
    """
    Execute an async command on the MirAIe device.

    This function runs on the same asyncio loop as MQTT.
    """

    if not connected.is_set():

        raise RuntimeError(
            connect_error
            or "MirAIe is not connected"
        )

    devices = hub.home.devices

    if not devices:

        raise RuntimeError(
            "No MirAIe devices available"
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
        "Executing device.%s(%s)",
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
    Call an async MirAIe device method from Flask.
    """

    require_connection()

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

        log.error(
            "Timeout executing device.%s()",
            method_name,
        )

        raise RuntimeError(
            f"MirAIe command '{method_name}' timed out"
        )

    except Exception as exc:

        log.exception(
            "MirAIe command failed: %s",
            method_name,
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
    Require X-Api-Key for AC/control endpoints.

    / and /health remain public.
    """

    if request.path in (
        "/",
        "/health",
    ):
        return None

    if not API_KEY:

        return jsonify({
            "error": "BRIDGE_API_KEY is not configured on Render",
        }), 500

    supplied_key = request.headers.get(
        "X-Api-Key",
    )

    if supplied_key != API_KEY:

        return jsonify({
            "error": "unauthorized",
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

        require_connection()

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
            "error": str(exc),
        }), 503


# ============================================================================
# AC STATUS
# ============================================================================

@app.route(
    "/ac/status",
    methods=["GET"],
)
def device_status():

    try:

        require_connection()

        device = hub.home.devices[0]

        status = getattr(
            device,
            "status",
            None,
        )

        if status is None:

            return jsonify({
                "connected": True,
                "status": None,
                "message": "Device status is not available yet",
            })

        return jsonify({
            "connected": True,
            "status": str(status),
        })

    except Exception as exc:

        log.exception(
            "Device status failed"
        )

        return jsonify({
            "error": str(exc),
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
        )

        if not found:

            return jsonify({
                "error": "turn_on() not found on device",
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
            "error": str(exc),
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
        )

        if not found:

            return jsonify({
                "error": "turn_off() not found on device",
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
            "error": str(exc),
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
            "error": "Temperature must be between 16 and 30°C",
        }), 400

    try:

        found, result = call_device(
            "set_temperature",
            float(value),
        )

        if not found:

            return jsonify({
                "error": "set_temperature() not found on device",
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
            "error": str(exc),
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
                "Invalid mode. Use: "
                "cool, auto, dry, fan, heat"
            ),
        }), 400

    try:

        found, result = call_device(
            "set_hvac_mode",
            modes[mode],
        )

        if not found:

            return jsonify({
                "error": "set_hvac_mode() not found on device",
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
            "error": str(exc),
        }), 500


# ============================================================================
# START MIRAIe
# ============================================================================

def initialize_miraie():
    """
    Start MirAIe after Flask application has been imported.

    This is intentionally protected so importing app.py does not
    repeatedly create multiple asyncio loops.
    """

    global startup_complete

    if startup_complete:
        return

    with startup_lock:

        if startup_complete:
            return

        try:

            start_miraie_connection()

            startup_complete = True

        except Exception as exc:

            log.exception(
                "Could not start MirAIe"
            )


# ============================================================================
# INITIALIZE
# ============================================================================

initialize_miraie()


# ============================================================================
# LOCAL DEVELOPMENT
# ============================================================================

if __name__ == "__main__":

    port = int(
        os.getenv(
            "PORT",
            "5000",
        )
    )

    log.info(
        "Starting Flask on port %s",
        port,
    )

    app.run(
        host="0.0.0.0",
        port=port,
        threaded=True,
    )