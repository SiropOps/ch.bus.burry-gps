import asyncio
import json
import logging
import threading
import subprocess
import sys
from types import SimpleNamespace

import pytest
import pika

from app import GPSService, Settings
from publishing import RabbitPublisher


async def until(predicate):
    async with asyncio.timeout(10):
        while not predicate():
            await asyncio.sleep(0.001)


async def test_eight_hour_rabbit_outage_keeps_ble_session_and_notifications(caplog):
    """Real worker + owner loop, virtual broker backoffs, 1 Hz GPS for 8 h."""
    caplog.set_level(logging.INFO, logger="app")
    loop = asyncio.get_running_loop()
    now = 0
    notifications = attempts = 0
    scanner_starts = scanner_stops = 0
    callback = None
    device = SimpleNamespace(name="GPS-NanoESP32", address="AA:BB:CC:DD:EE:FF")

    class ClockStop:
        stopped = False
        delays = []
        def is_set(self):
            return self.stopped
        def set(self):
            self.stopped = True
        def wait(self, seconds):
            self.delays.append(seconds)
            asyncio.run_coroutine_threadsafe(produce(seconds), loop).result(timeout=5)
            if now >= 8 * 3600:
                self.set()

    async def produce(seconds):
        nonlocal now, notifications
        for _ in range(min(int(seconds), 8 * 3600 - now)):
            now += 1
            callback(None, json.dumps({"lat": notifications, "speed": 36}).encode())
            notifications += 1
            await asyncio.sleep(0)

    class Scanner:
        def __init__(self, **kwargs):
            self.detect = kwargs["detection_callback"]
        async def start(self):
            nonlocal scanner_starts
            scanner_starts += 1
            self.detect(device, SimpleNamespace(local_name=device.name))
        async def stop(self):
            nonlocal scanner_stops
            scanner_stops += 1

    class Client:
        is_connected = True
        async def connect(self):
            pass
        async def start_notify(self, characteristic, notify):
            nonlocal callback
            callback = notify
        async def disconnect(self):
            self.is_connected = False

    def unavailable(parameters):
        nonlocal attempts
        attempts += 1
        raise OSError("broker unavailable")

    publisher = RabbitPublisher(None, unavailable)
    publisher.stopping = ClockStop()
    service = GPSService(Settings(startup_delay=0), publisher, Scanner,
                         lambda *a, **kw: Client(), monotonic=lambda: now)
    task = asyncio.create_task(service.run())
    try:
        await until(lambda: callback is not None)
        publisher.start()
        await until(lambda: not publisher.thread.is_alive())
        assert now == notifications == 28800
        assert 480 < attempts < 500
        assert publisher.stopping.delays[:7] == [1, 2, 4, 8, 16, 32, 60]
        assert max(publisher.stopping.delays) == 60
        assert len(publisher.pending) == 128
        assert publisher.dropped == 28800 - 128
        assert json.loads(publisher.pending[0])["latitude"] == 28800 - 128
        assert json.loads(publisher.pending[-1])["latitude"] == 28799
        service.log_health()
        health = [json.loads(record.message.split("GPS health ")[1]) for record in caplog.records
                  if record.message.startswith("GPS health ")]
        assert max(item["rabbitmq"]["dropped"] for item in health) > 0
        assert scanner_starts == scanner_stops == 1
        assert service.connection_state == "connected"
        assert service.failures == service.recovery_level == service.notification_timeouts == 0
    finally:
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        publisher.close()
    assert service.connection_state == "disconnected"


@pytest.mark.parametrize("error", [TimeoutError, pika.exceptions.ConnectionBlockedTimeout,
                                  pika.exceptions.NackError, pika.exceptions.UnroutableError])
def test_failed_publication_reconnects_and_retries_without_changing_payload(error):
    calls = []
    connections = []
    publisher = None

    class Connection:
        closed = False
        def channel(self):
            return self
        def queue_declare(self, **kwargs):
            pass
        def confirm_delivery(self):
            pass
        def basic_publish(self, **kwargs):
            calls.append(kwargs["body"])
            if len(calls) <= 3:
                raise error([]) if error in (pika.exceptions.NackError, pika.exceptions.UnroutableError) else error()
            publisher.stopping.set()
        def close(self):
            self.closed = True

    def factory(parameters):
        result = Connection()
        connections.append(result)
        return result

    class Stop:
        stopped = False
        delays = []
        def is_set(self):
            return self.stopped
        def set(self):
            self.stopped = True
        def wait(self, seconds):
            self.delays.append(seconds)

    publisher = RabbitPublisher(None, factory)
    publisher.stopping = Stop()
    publisher.publish('{"gpsType":"ESP32","speed":10}')
    publisher.run()
    assert len(connections) == 4 and all(connection.closed for connection in connections)
    assert len(set(calls)) == 1
    assert publisher.stopping.delays[:3] == [1, 2, 4]
    assert publisher.last_success is not None and publisher.failures == 0
    assert not publisher.pending


async def test_notifications_and_health_do_not_wait_for_blocked_publication():
    entered, release = threading.Event(), threading.Event()
    owners = []
    class Connection:
        def channel(self):
            owners.append(threading.get_ident())
            return self
        def queue_declare(self, **kwargs):
            owners.append(threading.get_ident())
        def confirm_delivery(self):
            owners.append(threading.get_ident())
        def basic_publish(self, **kwargs):
            owners.append(threading.get_ident())
            entered.set()
            assert release.wait(timeout=5)
            raise TimeoutError()
        def close(self):
            owners.append(threading.get_ident())

    publisher = RabbitPublisher(None, lambda _: Connection())
    service = GPSService(Settings(), publisher)
    publisher.publish("initial")
    publisher.start()
    try:
        await until(entered.is_set)
        async with asyncio.timeout(2):
            for i in range(2000):
                service.handle_notify(None, b'{"speed":36}')
                if i % 10 == 0:
                    service.health()
                    await asyncio.sleep(0)
        assert publisher.health()["pending"] == 128
        assert publisher.dropped == 2001 - 128
        assert service.failures == 0 and service.last_notification is not None
    finally:
        release.set()
        publisher.close()
    assert len(set(owners)) == 1 and owners[0] != threading.get_ident()
    assert not publisher.thread.is_alive()


def test_close_join_is_bounded_even_if_a_network_dependency_never_returns(monkeypatch, caplog):
    entered, release = threading.Event(), threading.Event()
    def stuck(parameters):
        entered.set()
        release.wait(timeout=5)
        raise OSError()
    publisher = RabbitPublisher(None, stuck)
    publisher.start()
    try:
        assert entered.wait(timeout=1)
        real_join = publisher.thread.join
        deadlines = []
        def short_join(timeout):
            deadlines.append(timeout)
            real_join(timeout=0.01)
        monkeypatch.setattr(publisher.thread, "join", short_join)
        publisher.close()
        assert deadlines == [15]
        assert publisher.thread.daemon
        assert "did not stop before deadline" in caplog.text
    finally:
        release.set()
        real_join(timeout=1)
    assert not publisher.thread.is_alive()


def test_process_exits_even_if_network_worker_remains_stuck_after_close():
    program = '''
import threading
from publishing import RabbitPublisher
entered = threading.Event()
def stuck(parameters):
    entered.set()
    threading.Event().wait()
publisher = RabbitPublisher(None, stuck)
publisher.start()
assert entered.wait(timeout=1)
join = publisher.thread.join
def accelerated_join(timeout):
    assert timeout == 15
    join(timeout=.01)
publisher.thread.join = accelerated_join
publisher.close()
assert publisher.thread.is_alive()
print("main exited")
'''
    result = subprocess.run([sys.executable, "-c", program], capture_output=True,
                            text=True, timeout=5)
    assert result.returncode == 0
    assert "main exited" in result.stdout
    assert "did not stop before deadline" in result.stderr
