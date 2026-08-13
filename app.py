"""
MirAIe AC Bridge Server
=======================

Flask HTTP bridge for controlling a Panasonic MirAIe AC using
the community `miraie-ac` Python library.

Endpoints
---------
GET  /health
GET  /ac/inspect

POST /ac/on
POST /ac/off
POST /ac/temperature/<int>
POST /ac/mode/<mode>

Authentication
--------------
All endpoints except /health require:

    X-Api-Key: <BRIDGE_API_KEY>

Environment variables
---------------------
MIRAIE_MOBILE
    Mobile number used to log into MirAIe, including +91.

MIRAIE_PASSWORD
    MirAIe account password.

BRIDGE_API_KEY
    Secret key used by the Android application.
"""

import asyncio
import inspect
import logging
import os
import threading

from flask import Flask, jsonify, request
from miraie_ac import MirAIeBroker, MirAIeHub


# ============================================================================
# LOGGING
# ============================================================================

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)

log = logging.getLogger("miraie-bridge")


# ============================================================================
# ENVIRONMENT VARIABLES
# ============================================================================

MOBILE = os.environ.get("MIRAIE_MOBILE")
PASSWORD = os.environ.get("MIRAIE_PASSWORD")
API_KEY = os.environ.get("BRIDGE_API_KEY")

app = Flask(__name__)


# ============================================================================
# GLOBAL MIRAIe OBJECTS
# ============================================================================

loop = asyncio.new_event_loop()

hub = None
broker = None

connected = threading.Event()
connect_error = None


# ============================================================================
# BACKGROUND ASYNCIO LOOP
# ============================================================================

def _run_loop_forever():
    """
    Run the MirAIe asyncio event loop permanently
    in a background thread.
    """
    asyncio.set_event_loop(loop)
    loop.run_forever()


loop_thread = threading.Thread(
    target=_run_loop_forever,
    daemon=True,
    name="miraie-loop"
)

loop_thread.start()


# ============================================================================
# MIRAIe CONNECTION
# ============================================================================

async def _connect():
    """
    Authenticate with MirAIe and establish the MQTT connection.
    """

    global hub
    global broker
    global connect_error

    try:

        if not MOBILE:
            raise RuntimeError(
                "MIRAIE_MOBILE environment variable is not set"
            )

        if not PASSWORD:
            raise RuntimeError(
                "MIRAIE_PASSWORD environment variable is not set"
            )

        log.info("Connecting to MirAIe...")

        broker = MirAIeBroker()
        hub = MirAIeHub()

        await hub.init(
            MOBILE,
            PASSWORD,
            broker
        )

        # Wait until the MQTT client is available.
        while (
            not hasattr(broker, "client")
            or getattr(broker, "client") is None
        ):
            await asyncio.sleep(1)

        connected.set()
        connect_error = None

        log.info(
            "Connected to MirAIe successfully."
        )

        log.info(
            "Devices found: %s",
            hub.home.devices
        )

    except Exception as e:

        connected.clear()
        connect_error = str(e)

        log.exception(
            "Failed to connect to MirAIe"
        )


# Start the connection coroutine.
asyncio.run_coroutine_threadsafe(
    _connect(),
    loop
)


# ============================================================================
# DEVICE COMMAND
# ============================================================================

async def _call_device_method(
    device_index,
    method_name,
    *args
):
    """
    Find and execute a MirAIe device method.

    IMPORTANT:
    The miraie-ac device control methods are synchronous in the
    Python package. We therefore run them in a worker thread so
    they cannot block the MQTT asyncio event loop.

    If a future version returns an awaitable, that is also supported.
    """

    if not connected.is_set():
        raise RuntimeError(
            connect_error
            or "MirAIe is not connected yet"
        )

    if hub is None:
        raise RuntimeError(
            "MirAIe hub is not initialized"
        )

    devices = hub.home.devices

    if not devices:
        raise RuntimeError(
            "No MirAIe devices were found"
        )

    if device_index < 0 or device_index >= len(devices):
        raise RuntimeError(
            f"Device index {device_index} does not exist"
        )

    device = devices[device_index]

    method = getattr(
        device,
        method_name,
        None
    )

    if method is None:
        return False, None

    log.info(
        "Executing device.%s(%s)",
        method_name,
        args
    )

    # ------------------------------------------------------------------------
    # Run the synchronous device method OUTSIDE the MQTT event-loop thread.
    # ------------------------------------------------------------------------

    result = await asyncio.to_thread(
        method,
        *args
    )

    # Some future implementation may return a coroutine/future.
    if inspect.isawaitable(result):
        result = await result

    log.info(
        "device.%s completed successfully",
        method_name
    )

    return True, result


def call_device(
    method_name,
    *args,
    device_index=0,
    timeout=30
):
    """
    Safely execute a MirAIe device command from a Flask request.

    Flask is synchronous, while MirAIe uses asyncio.
    The command is submitted to the dedicated asyncio loop.
    """

    if not connected.is_set():
        raise RuntimeError(
            connect_error
            or "MirAIe is still connecting"
        )

    future = asyncio.run_coroutine_threadsafe(
        _call_device_method(
            device_index,
            method_name,
            *args
        ),
        loop
    )

    try:

        result = future.result(
            timeout=timeout
        )

        return result

    except TimeoutError:

        log.error(
            "Timeout while executing device.%s()",
            method_name
        )

        future.cancel()

        raise RuntimeError(
            f"MirAIe command '{method_name}' timed out "
            f"after {timeout} seconds"
        )

    except Exception:

        log.exception(
            "Error executing device.%s()",
            method_name
        )

        raise


# ============================================================================
# AUTHENTICATION
# ============================================================================

@app.before_request
def check_auth():
    """
    Protect every endpoint except /health.
    """

    # Health check is public.
    if request.path == "/health":
        return None

    # API key must be configured.
    if not API_KEY:

        return jsonify({
            "error": "BRIDGE_API_KEY is not configured on the server"
        }), 500

    # Check API key.
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
    methods=["GET"]
)
def root():

    return jsonify({
        "service": "MirAIe AC Bridge",
        "status": "running",
        "health": "/health"
    })


# ============================================================================
# HEALTH
# ============================================================================

@app.route(
    "/health",
    methods=["GET"]
)
def health():

    return jsonify({
        "status": "ok",
        "connected": connected.is_set(),
        "error": connect_error
    })


# ============================================================================
# INSPECT DEVICE
# ============================================================================

@app.route(
    "/ac/inspect",
    methods=["GET"]
)
def inspect_device():

    if not connected.is_set():

        return jsonify({
            "error": (
                connect_error
                or "MirAIe is not connected yet"
            )
        }), 503

    try:

        device = hub.home.devices[0]

        members = sorted(
            member
            for member in dir(device)
            if not member.startswith("_")
        )

        return jsonify({
            "device_repr": repr(device),
            "members": members
        })

    except Exception as e:

        log.exception(
            "Device inspection failed"
        )

        return jsonify({
            "error": str(e)
        }), 500


# ============================================================================
# TURN ON
# ============================================================================

@app.route(
    "/ac/on",
    methods=["POST"]
)
def turn_on():

    try:

        found, result = call_device(
            "turn_on"
        )

        if not found:

            return jsonify({
                "error": (
                    "turn_on() was not found "
                    "on the MirAIe device"
                )
            }), 500

        return jsonify({
            "status": "on",
            "method_used": "turn_on",
            "result": str(result)
        })

    except Exception as e:

        log.exception(
            "AC ON command failed"
        )

        return jsonify({
            "error": str(e)
        }), 500


# ============================================================================
# TURN OFF
# ============================================================================

@app.route(
    "/ac/off",
    methods=["POST"]
)
def turn_off():

    try:

        found, result = call_device(
            "turn_off"
        )

        if not found:

            return jsonify({
                "error": (
                    "turn_off() was not found "
                    "on the MirAIe device"
                )
            }), 500

        return jsonify({
            "status": "off",
            "method_used": "turn_off",
            "result": str(result)
        })

    except Exception as e:

        log.exception(
            "AC OFF command failed"
        )

        return jsonify({
            "error": str(e)
        }), 500


# ============================================================================
# SET TEMPERATURE
# ============================================================================

@app.route(
    "/ac/temperature/<int:value>",
    methods=["POST"]
)
def set_temperature(value):

    # Reasonable Panasonic AC temperature range.
    if value < 16 or value > 30:

        return jsonify({
            "error": (
                "Temperature must be between "
                "16 and 30 degrees Celsius"
            )
        }), 400

    try:

        found, result = call_device(
            "set_temperature",
            float(value)
        )

        if not found:

            return jsonify({
                "error": (
                    "set_temperature() was not found "
                    "on the MirAIe device"
                )
            }), 500

        return jsonify({
            "status": "ok",
            "method_used": "set_temperature",
            "temperature": value,
            "result": str(result)
        })

    except Exception as e:

        log.exception(
            "Temperature command failed"
        )

        return jsonify({
            "error": str(e)
        }), 500


# ============================================================================
# SET HVAC MODE
# ============================================================================

@app.route(
    "/ac/mode/<mode>",
    methods=["POST"]
)
def set_mode(mode):

    mode = mode.lower().strip()

    allowed_modes = {
        "cool",
        "dry",
        "heat",
        "auto",
        "fan"
    }

    if mode not in allowed_modes:

        return jsonify({
            "error": (
                "Invalid mode. Allowed modes are: "
                "cool, dry, heat, auto, fan"
            )
        }), 400

    try:

        found, result = call_device(
            "set_hvac_mode",
            mode
        )

        if not found:

            return jsonify({
                "error": (
                    "set_hvac_mode() was not found "
                    "on the MirAIe device"
                )
            }), 500

        return jsonify({
            "status": "ok",
            "method_used": "set_hvac_mode",
            "mode": mode,
            "result": str(result)
        })

    except Exception as e:

        log.exception(
            "HVAC mode command failed"
        )

        return jsonify({
            "error": str(e)
        }), 500


# ============================================================================
# APPLICATION START
# ============================================================================

if __name__ == "__main__":

    port = int(
        os.environ.get(
            "PORT",
            5000
        )
    )

    app.run(
        host="0.0.0.0",
        port=port,
        threaded=True
    )