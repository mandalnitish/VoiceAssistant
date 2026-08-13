"""
MirAIe AC Bridge Server
=======================

Flask HTTP bridge for controlling a Panasonic MirAIe AC using
the community `miraie-ac` Python library.

Endpoints
---------
GET  /
GET  /health
GET  /ac/inspect
GET  /ac/status

POST /ac/on
POST /ac/off
POST /ac/temperature/<int>
POST /ac/mode/<mode>

Authentication
--------------
All endpoints except / and /health require:

    X-Api-Key: <BRIDGE_API_KEY>

Environment variables
---------------------
MIRAIE_MOBILE
    MirAIe registered mobile number, including +91.

MIRAIE_PASSWORD
    MirAIe account password.

BRIDGE_API_KEY
    Secret key shared with the Android application.
"""

import asyncio
import logging
import os
import threading

from flask import Flask, jsonify, request

from miraie_ac import MirAIeBroker, MirAIeHub
from miraie_ac.enums import HVACMode


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


# ============================================================================
# FLASK
# ============================================================================

app = Flask(__name__)


# ============================================================================
# MIRAIe GLOBAL STATE
# ============================================================================

loop = asyncio.new_event_loop()

hub = None
broker = None

connected = threading.Event()

connect_error = None


# ============================================================================
# BACKGROUND ASYNCIO LOOP
# ============================================================================

def run_async_loop():
    """
    Keep the asyncio event loop running permanently.

    MirAIe uses asyncio + MQTT, while Flask is synchronous.
    """

    asyncio.set_event_loop(loop)

    log.info("MirAIe asyncio loop started")

    loop.run_forever()


loop_thread = threading.Thread(
    target=run_async_loop,
    daemon=True,
    name="miraie-async-loop"
)

loop_thread.start()


# ============================================================================
# MIRAIe CONNECTION
# ============================================================================

async def connect_miraie():
    """
    Authenticate with MirAIe and initialize the MQTT broker.

    The miraie-ac library itself starts the MQTT broker connection
    as a background asyncio task.
    """

    global hub
    global broker
    global connect_error

    try:

        if not MOBILE:
            raise RuntimeError(
                "MIRAIE_MOBILE environment variable is not configured"
            )

        if not PASSWORD:
            raise RuntimeError(
                "MIRAIE_PASSWORD environment variable is not configured"
            )

        log.info("Connecting to MirAIe...")

        # Create broker.
        broker = MirAIeBroker()

        # Create hub inside the asyncio loop.
        hub = MirAIeHub()

        # Authenticate and initialize.
        await hub.init(
            MOBILE,
            PASSWORD,
            broker
        )

        # Wait until the MQTT client has been created.
        while (
            not hasattr(broker, "client")
            or getattr(broker, "client", None) is None
        ):
            await asyncio.sleep(0.5)

        connected.set()
        connect_error = None

        log.info(
            "Connected to MirAIe successfully."
        )

        try:
            log.info(
                "Devices found: %s",
                hub.home.devices
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


# Start MirAIe connection.
connect_future = asyncio.run_coroutine_threadsafe(
    connect_miraie(),
    loop
)


# ============================================================================
# WAIT FOR CONNECTION
# ============================================================================

def require_connection():
    """
    Verify that the MirAIe connection is ready.
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
            "MirAIe home information is not available"
        )

    if not hub.home.devices:
        raise RuntimeError(
            "No MirAIe devices were found"
        )


# ============================================================================
# ASYNC DEVICE COMMAND
# ============================================================================

async def async_device_command(
    device_index,
    method_name,
    *args
):
    """
    Execute an async method on the MirAIe device.

    Important:
        miraie-ac 1.1.1 defines device commands such as
        turn_on(), turn_off(), set_temperature() and
        set_hvac_mode() as async methods.

    Therefore they must execute on the same asyncio event loop
    that owns the MQTT client.
    """

    require_connection()

    devices = hub.home.devices

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

    # miraie-ac device methods are async.
    result = method(*args)

    if asyncio.iscoroutine(result):

        result = await result

    log.info(
        "device.%s completed successfully",
        method_name
    )

    return True, result


# ============================================================================
# SYNCHRONOUS FLASK -> ASYNCIO BRIDGE
# ============================================================================

def call_device(
    method_name,
    *args,
    device_index=0,
    timeout=20
):
    """
    Submit an async MirAIe command to the dedicated asyncio loop.

    Flask is synchronous, so the HTTP request waits for the
    asyncio command to finish.
    """

    require_connection()

    future = asyncio.run_coroutine_threadsafe(
        async_device_command(
            device_index,
            method_name,
            *args
        ),
        loop
    )

    try:

        return future.result(
            timeout=timeout
        )

    except TimeoutError:

        future.cancel()

        log.error(
            "Timeout executing device.%s()",
            method_name
        )

        raise RuntimeError(
            f"MirAIe command '{method_name}' timed out after "
            f"{timeout} seconds"
        )

    except Exception as exc:

        log.exception(
            "Error executing device.%s()",
            method_name
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
    Protect AC/control endpoints using X-Api-Key.
    """

    # Public endpoints.
    if request.path in (
        "/",
        "/health"
    ):
        return None

    # API key must exist on Render.
    if not API_KEY:

        return jsonify({
            "error": "BRIDGE_API_KEY is not configured on server"
        }), 500

    # Check API key.
    request_key = request.headers.get(
        "X-Api-Key"
    )

    if request_key != API_KEY:

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

    is_connected = connected.is_set()

    return jsonify({
        "status": "ok",
        "connected": is_connected,
        "error": connect_error
    })


# ============================================================================
# INSPECT
# ============================================================================

@app.route(
    "/ac/inspect",
    methods=["GET"]
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
            "members": members
        })

    except Exception as exc:

        log.exception(
            "Device inspection failed"
        )

        return jsonify({
            "error": str(exc)
        }), 503


# ============================================================================
# DEVICE STATUS
# ============================================================================

@app.route(
    "/ac/status",
    methods=["GET"]
)
def device_status():

    try:

        require_connection()

        device = hub.home.devices[0]

        status = getattr(
            device,
            "status",
            None
        )

        if status is None:

            return jsonify({
                "connected": True,
                "status": None,
                "message": "Device status is not available yet"
            })

        return jsonify({
            "connected": True,
            "status": {
                "is_online": getattr(
                    status,
                    "is_online",
                    None
                ),
                "temperature": getattr(
                    status,
                    "temperature",
                    None
                ),
                "room_temperature": getattr(
                    status,
                    "room_temperature",
                    None
                ),
                "power_mode": str(
                    getattr(
                        status,
                        "power_mode",
                        None
                    )
                ),
                "fan_mode": str(
                    getattr(
                        status,
                        "fan_mode",
                        None
                    )
                ),
                "hvac_mode": str(
                    getattr(
                        status,
                        "hvac_mode",
                        None
                    )
                ),
                "v_swing_mode": str(
                    getattr(
                        status,
                        "v_swing_mode",
                        None
                    )
                ),
                "h_swing_mode": str(
                    getattr(
                        status,
                        "h_swing_mode",
                        None
                    )
                )
            }
        })

    except Exception as exc:

        log.exception(
            "Device status request failed"
        )

        return jsonify({
            "error": str(exc)
        }), 503


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
                "error": "turn_on() not found on device"
            }), 500

        return jsonify({
            "status": "on",
            "method_used": "turn_on",
            "result": str(result)
        })

    except Exception as exc:

        log.exception(
            "AC ON command failed"
        )

        return jsonify({
            "error": str(exc)
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
                "error": "turn_off() not found on device"
            }), 500

        return jsonify({
            "status": "off",
            "method_used": "turn_off",
            "result": str(result)
        })

    except Exception as exc:

        log.exception(
            "AC OFF command failed"
        )

        return jsonify({
            "error": str(exc)
        }), 500


# ============================================================================
# SET TEMPERATURE
# ============================================================================

@app.route(
    "/ac/temperature/<int:value>",
    methods=["POST"]
)
def set_temperature(value):

    # Normal AC temperature range.
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
                    "set_temperature() not found "
                    "on device"
                )
            }), 500

        return jsonify({
            "status": "ok",
            "method_used": "set_temperature",
            "temperature": value,
            "result": str(result)
        })

    except Exception as exc:

        log.exception(
            "Temperature command failed"
        )

        return jsonify({
            "error": str(exc)
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

    mode_map = {
        "cool": HVACMode.COOL,
        "auto": HVACMode.AUTO,
        "dry": HVACMode.DRY,
        "fan": HVACMode.FAN,
        "heat": HVACMode.HEAT
    }

    if mode not in mode_map:

        return jsonify({
            "error": (
                "Invalid mode. Allowed modes: "
                "cool, auto, dry, fan, heat"
            )
        }), 400

    try:

        found, result = call_device(
            "set_hvac_mode",
            mode_map[mode]
        )

        if not found:

            return jsonify({
                "error": (
                    "set_hvac_mode() not found "
                    "on device"
                )
            }), 500

        return jsonify({
            "status": "ok",
            "method_used": "set_hvac_mode",
            "mode": mode,
            "result": str(result)
        })

    except Exception as exc:

        log.exception(
            "HVAC mode command failed"
        )

        return jsonify({
            "error": str(exc)
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

    log.info(
        "Starting Flask server on port %s",
        port
    )

    app.run(
        host="0.0.0.0",
        port=port,
        threaded=True
    )