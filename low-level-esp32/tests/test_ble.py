import asyncio
import datetime
import json
import signal
from dataclasses import replace
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

import app
from app import GPSService, Settings
from runtime import BluetoothCleanupError


class Publisher:
    def __init__(self):
        self.messages = []

    def publish(self, payload):
        self.messages.append(payload)

    def health(self):
        return {"last_publish_success": None}


def settings(**kwargs):
    values = dict(scan_seconds=0.002, operation_timeout=0.01, connect_timeout=0.01,
                  cleanup_timeout=0.01, reconnect_delay=0.002, reconnect_max=0.01,
                  health_interval=0.002, startup_delay=0)
    values.update(kwargs)
    return Settings(**values)


class Radio:
    def __init__(self, failure=None, present=True):
        self.failure = failure
        self.present = present
        self.active = self.connections = 0
        self.scanners = []
        self.events = []
        self.device = SimpleNamespace(name=app.BLE_DEVICE_NAME, address="AA:BB:CC:DD:EE:FF")
        self.callback = None

    def scanner(self, **kwargs):
        assert kwargs["bluez"]["adapter"] == "hci0"
        radio = self
        class Scanner:
            running = False
            async def start(self):
                assert radio.active == radio.connections == 0
                radio.active = 1
                self.running = True
                radio.events.append("start")
                if radio.failure == "start_timeout":
                    await asyncio.Future()
                if radio.failure == "start_error":
                    raise RuntimeError("org.bluez.Error.InProgress")
                # Unnamed devices must be harmless.
                kwargs["detection_callback"](SimpleNamespace(name=None), SimpleNamespace(local_name=None))
                if radio.present:
                    kwargs["detection_callback"](radio.device, SimpleNamespace(local_name=None))

            async def stop(self):
                radio.events.append("stop")
                if radio.failure == "stop_timeout":
                    await asyncio.Future()
                if radio.failure == "stop_error":
                    raise RuntimeError("DBus error")
                if self.running:
                    radio.active = 0
                    self.running = False
        result = Scanner()
        self.scanners.append(result)
        return result

    def client(self, device, timeout):
        assert device is self.device
        assert radio_ready(self)
        radio = self
        class Client:
            is_connected = False
            async def connect(self):
                radio.events.append("connect")
                radio.connections = 1
                self.is_connected = True
                if radio.failure == "connect_timeout":
                    await asyncio.Future()
                if radio.failure == "connect_error":
                    raise RuntimeError("DBus error")

            async def start_notify(self, characteristic, callback):
                assert characteristic == app.CHAR_UUID
                radio.events.append("subscribe")
                radio.callback = callback
                if radio.failure == "notify_timeout":
                    await asyncio.Future()
                if radio.failure == "notify_error":
                    raise RuntimeError("DBus error")
                callback(None, b'{"lat": 46.2, "lng": 6.1, "speed": 36}')
                self.is_connected = False

            async def disconnect(self):
                radio.events.append("disconnect")
                if radio.failure == "disconnect_error":
                    raise RuntimeError("DBus error")
                radio.connections = 0
                self.is_connected = False
        return Client()


def radio_ready(radio):
    return radio.active == radio.connections == 0


async def test_device_object_and_connection_loss_preserve_payload():
    radio = Radio()
    publisher = Publisher()
    service = GPSService(settings(), publisher, radio.scanner, radio.client)
    device = await service.find_device()
    assert device is radio.device and radio.active == 0
    await service.session(device)
    assert radio.events == ["start", "stop", "connect", "subscribe", "disconnect"]
    assert radio_ready(radio)
    data = json.loads(publisher.messages[0])
    assert data["gpsType"] == "ESP32" and data["speed"] == 10
    assert data["latitude"] == 46.2
    assert service.last_notification.endswith("+00:00")
    assert service.health()["rabbitmq"]["last_publish_success"] is None


@pytest.mark.parametrize("failure", ["connect_error", "connect_timeout", "notify_error", "notify_timeout"])
async def test_partial_connection_always_disconnects(failure):
    radio = Radio(failure=failure)
    service = GPSService(settings(), Publisher(), radio.scanner, radio.client)
    with pytest.raises((RuntimeError, TimeoutError)):
        await service.session(radio.device)
    assert radio_ready(radio)
    assert service.connection_state == "disconnected"


@pytest.mark.parametrize("failure", ["stop_error", "stop_timeout", "start_timeout"])
async def test_uncertain_scanner_ownership_exits_without_replacement(failure):
    radio = Radio(failure=failure)
    service = GPSService(settings(), Publisher(), radio.scanner, radio.client)
    with pytest.raises(BluetoothCleanupError):
        await service.run()
    assert len(radio.scanners) == 1
    assert service.recovery_level == 2
    assert service.scanner_state == "cleanup_failed"
    if failure == "start_timeout":
        assert radio.events == ["start"]


async def test_repeated_start_errors_are_bounded_and_cleaned():
    radio = Radio(failure="start_error")
    service = GPSService(settings(failure_limit=3), Publisher(), radio.scanner, radio.client)
    with pytest.raises(BluetoothCleanupError):
        await service.run()
    assert len(radio.scanners) == 3
    assert radio.events == ["start", "stop"] * 3
    assert radio_ready(radio)


async def test_disconnect_failure_never_attempts_new_scan():
    radio = Radio(failure="disconnect_error")
    service = GPSService(settings(), Publisher(), radio.scanner, radio.client)
    with pytest.raises(BluetoothCleanupError):
        await service.run()
    assert len(radio.scanners) == 1
    assert service.connection_state == "cleanup_failed"


async def test_absent_scan_releases_scanner():
    radio = Radio(present=False)
    service = GPSService(settings(), Publisher(), radio.scanner, radio.client)
    assert await service.find_device() is None
    assert radio_ready(radio)
    assert service.last_discovery is not None


async def test_esp32_absent_for_eight_hours_does_not_exit():
    now = 0
    attempts = []
    async def sleep(seconds):
        nonlocal now
        now += seconds
        await asyncio.sleep(0)
    service = GPSService(Settings(), Publisher(), monotonic=lambda: now, sleep=sleep)
    async def absent():
        attempts.append(now)
        if now >= 8 * 3600:
            raise asyncio.CancelledError
        return None
    service.find_device = absent
    with pytest.raises(asyncio.CancelledError):
        await service.run()
    assert attempts[:5] == [0, 60, 180, 420, 900]
    assert 30 < len(attempts) < 40
    assert service.retry_delay == 900
    assert service.failures == 0 and service.recovery_level == 0
    assert service.absences > 30


async def test_notification_confirms_recovery_after_errors():
    service = GPSService(settings(), Publisher())
    scans = 0
    async def find():
        nonlocal scans
        scans += 1
        if scans <= 2:
            raise RuntimeError("DBus error")
        if scans == 3:
            return object()
        raise asyncio.CancelledError
    async def session(device):
        assert service.failures == 2
        service.handle_notify(None, b'{"speed": 0}')
    service.find_device = find
    service.session = session
    with pytest.raises(asyncio.CancelledError):
        await service.run()
    assert service.failures == 0 and service.recovery_level == 0
    assert service.last_notification is not None


async def test_cancel_during_connect_cleans_all_tasks():
    radio = Radio(failure="connect_timeout")
    service = GPSService(settings(connect_timeout=30), Publisher(), radio.scanner, radio.client)
    before = set(asyncio.all_tasks())
    task = asyncio.create_task(service.session(radio.device))
    while "connect" not in radio.events:
        await asyncio.sleep(0)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert radio_ready(radio)
    assert set(asyncio.all_tasks()) == before


async def test_connected_but_silent_client_is_disconnected_without_process_escalation():
    now = 0
    async def sleep(seconds):
        nonlocal now
        now += seconds
    client = SimpleNamespace(is_connected=True, connect=AsyncMock(), start_notify=AsyncMock(),
                             disconnect=AsyncMock())
    service = GPSService(settings(notification_idle=300, health_interval=30), Publisher(),
                         client_factory=lambda *args, **kwargs: client,
                         monotonic=lambda: now, sleep=sleep)
    await service.session(object())
    client.disconnect.assert_awaited_once()
    assert now >= 300 and service.notification_timeouts == 1
    assert service.failures == service.recovery_level == 0


async def test_sigterm_during_discovery_cleans_owner(monkeypatch):
    radio = Radio(present=False)
    service = GPSService(settings(scan_seconds=30), Publisher(), radio.scanner, radio.client)
    callbacks = {}
    def register(signum, handler):
        previous = callbacks.get(signum)
        callbacks[signum] = handler
        return previous
    monkeypatch.setattr(app.signal, "signal", register)
    monkeypatch.setattr(app, "GPSService", lambda *args: service)
    task = asyncio.create_task(app.run_service(settings(), service.publisher))
    while service.scanner_state != "running":
        await asyncio.sleep(0)
    callbacks[signal.SIGTERM](signal.SIGTERM, None)
    await task
    assert radio_ready(radio)


def test_invalid_payload_does_not_exit_or_log_raw_data(caplog):
    publisher = Publisher()
    service = GPSService(settings(), publisher)
    for data in [b'private-password', b'[]', b'{"speed":"private-password"}', b'\xff']:
        service.handle_notify(None, data)
    assert service.invalid_notifications == 4
    assert not publisher.messages
    assert "private-password" not in caplog.text
    service.handle_notify(None, b'{"speed":36}')
    assert len(publisher.messages) == 1


def test_rabbit_contract_and_time_conversion():
    year = datetime.datetime.now(datetime.timezone.utc).year
    data = app.decode_notification(json.dumps({"lat": 1, "lng": 2, "alt": 3, "speed": 36,
                                               "track": 4, "hdop": 5,
                                               "time": f"{year}-01-02T03:04:05Z"}).encode())
    assert data == {"gpsType": "ESP32", "latitude": 1, "longitude": 2, "altitude": 3,
                    "speed": 10, "track": 4, "ept": 5, "eps": 5, "epd": 5, "epx": 5,
                    "epy": 5, "epv": 5, "epc": None, "mode": 3, "climb": None,
                    "time": f"{year}-01-02T03:04:05.000Z"}


@pytest.mark.parametrize("value", [None, "bad", "2019-01-01T00:00:00Z", 123])
def test_invalid_time_is_null(value):
    assert app.decode_notification(json.dumps({"time": value}).encode())["time"] is None


@pytest.mark.parametrize("env", [{"BLE_FAILURE_LIMIT": "1.5"}, {"BLE_SCAN_SECONDS": "nan"},
                                  {"BLUETOOTH_ADAPTER": "hci0;bad"},
                                  {"BLE_RECONNECT_MAX_SECONDS": "1"},
                                  {"BLE_PROCESS_RESTART_DELAY_SECONDS": "0"}])
def test_invalid_configuration_rejected(env):
    with pytest.raises(ValueError):
        Settings.from_env(env)
