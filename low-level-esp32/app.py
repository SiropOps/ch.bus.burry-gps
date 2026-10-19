#!/usr/bin/env python
"""GPS BLE owner loop. Importing this module performs no I/O."""
import asyncio
import datetime
import json
import logging
import logging.handlers
import math
import os
from pathlib import Path
import re
import signal
import time
from dataclasses import dataclass

from bleak import BleakClient, BleakScanner

from publishing import RabbitPublisher
from runtime import BluetoothCleanupError, bounded

logger = logging.getLogger(__name__)
BLE_DEVICE_NAME = "GPS-NanoESP32"
CHAR_UUID = "00002a56-0000-1000-8000-00805f9b34fb"
GPS_TYPE = "ESP32"
ISO_TIME_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def kmh_to_mps(speed):
    return None if speed is None else float(speed) / 3.6


def decode_notification(data):
    raw = json.loads(data.decode())
    if "time" in raw:
        value = raw["time"]
        try:
            if not isinstance(value, str) or not ISO_TIME_PATTERN.fullmatch(value):
                raise ValueError
            dt = datetime.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
            if dt.year != datetime.datetime.now(datetime.timezone.utc).year:
                raise ValueError
            raw["time"] = dt.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
        except ValueError:
            raw["time"] = None
    # Existing Java/RabbitMQ contract, including speed in m/s.
    return {
        "gpsType": GPS_TYPE, "latitude": raw.get("lat"), "longitude": raw.get("lng"),
        "altitude": raw.get("alt"), "speed": kmh_to_mps(raw.get("speed")),
        "track": raw.get("track"), "ept": raw.get("hdop"), "eps": raw.get("hdop"),
        "epd": raw.get("hdop"), "epx": raw.get("hdop"), "epy": raw.get("hdop"),
        "epv": raw.get("hdop"), "epc": None, "mode": 3, "climb": None,
        "time": raw.get("time"),
    }


@dataclass(frozen=True)
class Settings:
    adapter: str = "hci0"
    scan_seconds: float = 10
    operation_timeout: float = 20
    connect_timeout: float = 30
    cleanup_timeout: float = 10
    reconnect_delay: float = 60
    reconnect_max: float = 900
    failure_limit: int = 5
    process_restart_delay: float = 60
    health_interval: float = 30
    notification_idle: float = 300
    startup_delay: float = 15

    @classmethod
    def from_env(cls, source=None):
        source = os.environ if source is None else source
        values = {"adapter": source.get("BLUETOOTH_ADAPTER", "hci0")}
        for field, env in (
            ("scan_seconds", "BLE_SCAN_SECONDS"),
            ("operation_timeout", "SCANNER_OPERATION_TIMEOUT_SECONDS"),
            ("connect_timeout", "BLE_CLIENT_CONNECT_TIMEOUT_SECONDS"),
            ("cleanup_timeout", "BLE_CLEANUP_TIMEOUT_SECONDS"),
            ("reconnect_delay", "BLE_RECONNECT_DELAY_SECONDS"),
            ("reconnect_max", "BLE_RECONNECT_MAX_SECONDS"),
            ("failure_limit", "BLE_FAILURE_LIMIT"),
            ("process_restart_delay", "BLE_PROCESS_RESTART_DELAY_SECONDS"),
            ("health_interval", "HEALTH_INTERVAL_SECONDS"),
            ("notification_idle", "BLE_NOTIFICATION_IDLE_SECONDS"),
            ("startup_delay", "STARTUP_DELAY_SECONDS"),
        ):
            try:
                value = float(source.get(env, getattr(cls(), field)))
                if not math.isfinite(value) or value < 0 or (value == 0 and field != "startup_delay"):
                    raise ValueError
                if field == "failure_limit":
                    if not value.is_integer():
                        raise ValueError
                    value = int(value)
                values[field] = value
            except (ValueError, TypeError):
                raise ValueError(f"Invalid {env}") from None
        if not re.fullmatch(r"hci\d+", values["adapter"]):
            raise ValueError("Invalid BLUETOOTH_ADAPTER")
        if values["reconnect_max"] < values["reconnect_delay"]:
            raise ValueError("BLE_RECONNECT_MAX_SECONDS must be >= BLE_RECONNECT_DELAY_SECONDS")
        return cls(**values)


class GPSService:
    def __init__(self, settings, publisher, scanner_factory=BleakScanner,
                 client_factory=BleakClient, monotonic=time.monotonic, sleep=asyncio.sleep):
        self.settings = settings
        self.publisher = publisher
        self.scanner_factory = scanner_factory
        self.client_factory = client_factory
        self.monotonic = monotonic
        self.sleep = sleep
        self.scanner_state = "stopped"
        self.connection_state = "disconnected"
        self.last_discovery = self.last_notification = None
        self.discovery_at = self.notification_at = None
        self.failures = self.absences = self.invalid_notifications = 0
        self.recoveries = self.recovery_level = 0
        self.notification_timeouts = 0
        self.retry_delay = settings.reconnect_delay
        self.next_health = 0

    async def operation(self, awaitable, timeout):
        return await bounded(awaitable, timeout, restart_delay=self.settings.process_restart_delay)

    def health(self):
        now = self.monotonic()
        return {"timestamp": utc_now(), "adapter": self.settings.adapter,
                "scanner": self.scanner_state, "connection": self.connection_state,
                "last_discovery": self.last_discovery, "last_notification": self.last_notification,
                "discovery_age_seconds": None if self.discovery_at is None else now - self.discovery_at,
                "notification_age_seconds": None if self.notification_at is None else now - self.notification_at,
                "last_gatt_read": None,
                "consecutive_failures": self.failures, "absent_scans": self.absences,
                "invalid_notifications": self.invalid_notifications,
                "notification_timeouts": self.notification_timeouts,
                "recoveries": self.recoveries, "recovery_level": self.recovery_level,
                "retry_delay_seconds": self.retry_delay, "rabbitmq": self.publisher.health()}

    def log_health(self):
        if self.monotonic() >= self.next_health:
            logger.info("GPS health %s", json.dumps(self.health()))
            self.next_health = self.monotonic() + self.settings.health_interval

    async def pause(self, seconds):
        deadline = self.monotonic() + seconds
        while self.monotonic() < deadline:
            self.log_health()
            await self.sleep(min(self.settings.health_interval, deadline - self.monotonic()))

    async def find_device(self):
        found = asyncio.Event()
        target = None

        def on_advertisement(device, advertisement):
            nonlocal target
            self.last_discovery = utc_now()
            self.discovery_at = self.monotonic()
            if BLE_DEVICE_NAME in (device.name or advertisement.local_name or ""):
                target = device
                found.set()

        scanner = self.scanner_factory(detection_callback=on_advertisement,
                                       bluez={"adapter": self.settings.adapter})
        self.scanner_state = "starting"
        uncertain = False
        try:
            try:
                await self.operation(scanner.start(), self.settings.operation_timeout)
            except TimeoutError:
                uncertain = True
                self.scanner_state = "cleanup_failed"
                raise BluetoothCleanupError("Uncertain discovery ownership") from None
            self.scanner_state = "running"
            try:
                await asyncio.wait_for(found.wait(), self.settings.scan_seconds)
            except TimeoutError:
                pass
        finally:
            if not uncertain:
                self.scanner_state = "stopping"
                try:
                    await self.operation(scanner.stop(), self.settings.cleanup_timeout)
                except Exception:
                    self.scanner_state = "cleanup_failed"
                    raise BluetoothCleanupError("Scanner cleanup failed") from None
                self.scanner_state = "stopped"
        return target

    def handle_notify(self, _, data):
        try:
            payload = json.dumps(decode_notification(data))
        except (ValueError, TypeError, AttributeError, OverflowError):
            self.invalid_notifications += 1
            if self.invalid_notifications == 1 or self.invalid_notifications % 100 == 0:
                logger.warning("Invalid GPS notification discarded; count=%s", self.invalid_notifications)
            return
        self.last_notification = utc_now()
        self.notification_at = self.monotonic()
        self.failures = self.absences = 0
        self.recovery_level = 0
        self.retry_delay = self.settings.reconnect_delay
        self.publisher.publish(payload)

    async def session(self, device):
        client = self.client_factory(device, timeout=self.settings.connect_timeout)
        self.connection_state = "connecting"
        try:
            await self.operation(client.connect(), self.settings.connect_timeout)
            await self.operation(client.start_notify(CHAR_UUID, self.handle_notify), self.settings.operation_timeout)
            self.connection_state = "connected"
            connected_at = self.monotonic()
            logger.info("GPS notifications subscribed")
            while client.is_connected:
                self.log_health()
                last_valid = max(connected_at, self.notification_at or connected_at)
                if self.monotonic() - last_valid >= self.settings.notification_idle:
                    self.notification_timeouts += 1
                    logger.warning("GPS notifications silent; reconnecting local client only")
                    break
                await self.sleep(min(1, self.settings.health_interval))
            logger.warning("GPS disconnected; awaiting return")
        finally:
            self.connection_state = "disconnecting"
            try:
                await self.operation(client.disconnect(), self.settings.cleanup_timeout)
            except Exception:
                self.connection_state = "cleanup_failed"
                raise BluetoothCleanupError("GATT cleanup failed") from None
            self.connection_state = "disconnected"

    async def run(self):
        try:
            while True:
                self.log_health()
                try:
                    device = await self.find_device()
                    if device is None:
                        self.absences += 1
                        logger.info("GPS absent; normal waiting state; retry_in_seconds=%s", self.retry_delay)
                    else:
                        await self.session(device)
                except BluetoothCleanupError:
                    self.recovery_level = 2
                    raise
                except Exception as exc:
                    self.failures += 1
                    self.recoveries += 1
                    self.recovery_level = 1
                    logger.warning("GPS BLE operation failed (%s); consecutive_failures=%s recovery_level=1",
                                   type(exc).__name__, self.failures)
                    if self.failures >= self.settings.failure_limit:
                        self.recovery_level = 2
                        raise BluetoothCleanupError("Repeated BLE operation failures") from None
                await self.pause(self.retry_delay)
                self.retry_delay = min(self.retry_delay * 2, self.settings.reconnect_max)
        finally:
            logger.info("GPS final health %s", json.dumps(self.health()))


async def run_service(settings, publisher):
    async def supervised():
        try:
            await asyncio.sleep(settings.startup_delay)
            await GPSService(settings, publisher).run()
        except Exception as exc:
            logger.error("GPS recovery_level=2 failure=%s; process exit after %s seconds",
                         type(exc).__name__, settings.process_restart_delay)
            await asyncio.sleep(settings.process_restart_delay)
            raise

    loop = asyncio.get_running_loop()
    task = asyncio.create_task(supervised())
    previous = {}
    stopping = False

    def stop(signum, frame):
        nonlocal stopping
        if not stopping:
            stopping = True
            loop.call_soon_threadsafe(task.cancel)

    for signum in (signal.SIGINT, signal.SIGTERM):
        previous[signum] = signal.signal(signum, stop)
    try:
        await task
    except asyncio.CancelledError:
        if not stopping:
            raise
    finally:
        for signum, handler in previous.items():
            signal.signal(signum, handler)


def configure_logging():
    logging.Formatter.converter = time.gmtime
    formatter = logging.Formatter("%(asctime)sZ %(levelname)s %(message)s")
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    console = logging.StreamHandler()
    console.setFormatter(formatter)
    root.addHandler(console)
    filename = os.environ.get("GPS_LOG_FILE", "/app/fail/gps-ESP32.log")
    if filename:
        Path(filename).parent.mkdir(parents=True, exist_ok=True)
        handler = logging.handlers.RotatingFileHandler(filename, maxBytes=2 * 1024 * 1024, backupCount=3)
        handler.setFormatter(formatter)
        root.addHandler(handler)
    logging.getLogger("pika").setLevel(logging.CRITICAL)


def main():
    publisher = None
    try:
        configure_logging()
        settings = Settings.from_env()
        publisher = RabbitPublisher.from_env()
        publisher.start()
        asyncio.run(run_service(settings, publisher))
        return 0
    except Exception as exc:
        logger.error("GPS process stopped (%s)", type(exc).__name__)
        return 1
    finally:
        if publisher is not None:
            publisher.close()


if __name__ == "__main__":
    raise SystemExit(main())
