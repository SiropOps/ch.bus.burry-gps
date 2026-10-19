"""One thread owns all Pika objects; BLE callbacks only enqueue telemetry."""
from collections import deque
from datetime import datetime, timezone
import logging
import os
import threading
import time

import pika

logger = logging.getLogger(__name__)


class DeadlineConnection(pika.BlockingConnection):
    """Bound AMQP RPCs even when a peer sends heartbeats but withholds replies.

    Pika 1.3.2 has no public RPC/confirmation deadline. Its public call_later
    callbacks are deferred during BlockingChannel RPCs, so use the underlying
    I/O timer and Pika's own stream termination/cleanup path. Keep this small
    adapter covered by real-Pika tests when upgrading the pinned dependency.
    All access, including termination, stays in the owning RabbitMQ thread.
    """
    operation_timeout = 10
    stop_poll_interval = 0.2

    def __init__(self, parameters, stopping):
        self.stopping = stopping
        super().__init__(parameters)

    def _flush_output(self, *waiters):
        loop = self._impl.ioloop
        deadline = time.monotonic() + self.operation_timeout
        timer = None

        def check():
            nonlocal timer
            remaining = deadline - time.monotonic()
            if self.stopping.is_set():
                self._impl._terminate_stream(InterruptedError("RabbitMQ worker stopping"))
            elif remaining <= 0:
                self._impl._terminate_stream(TimeoutError("RabbitMQ operation deadline exceeded"))
            else:
                timer = loop.call_later(min(self.stop_poll_interval, remaining), check)

        timer = loop.call_later(min(self.stop_poll_interval, self.operation_timeout), check)
        try:
            return super()._flush_output(*waiters)
        finally:
            loop.remove_timeout(timer)


class RabbitPublisher:
    def __init__(self, parameters, connection_factory=None, max_pending=128):
        self.parameters = parameters
        self.max_pending = max_pending
        self.pending = deque()
        self.lock = threading.Lock()
        self.stopping = threading.Event()
        self.connection_factory = connection_factory or (lambda params: DeadlineConnection(params, self.stopping))
        self.thread = None
        self.connected = False
        self.failures = self.dropped = 0
        self.last_success = self.last_success_at = None

    @classmethod
    def from_env(cls):
        credentials = pika.PlainCredentials(os.environ["spring.rabbitmq.username"],
                                            os.environ["spring.rabbitmq.password"])
        parameters = pika.ConnectionParameters(
            host=os.environ["spring.rabbitmq.host"], port=int(os.environ["spring.rabbitmq.port"]),
            virtual_host="/", credentials=credentials, heartbeat=30,
            socket_timeout=5, stack_timeout=10, blocked_connection_timeout=5,
            connection_attempts=1)
        return cls(parameters)

    def publish(self, payload):
        with self.lock:
            if len(self.pending) >= self.max_pending:
                self.pending.popleft()
                self.dropped += 1
            self.pending.append(payload)

    def health(self):
        with self.lock:
            return {"connected": self.connected, "pending": len(self.pending), "dropped": self.dropped,
                    "consecutive_failures": self.failures, "last_publish_success": self.last_success,
                    "publish_age_seconds": None if self.last_success_at is None else time.monotonic() - self.last_success_at}

    def start(self):
        self.thread = threading.Thread(target=self.run, name="rabbit-publisher", daemon=True)
        self.thread.start()

    def run(self):
        delay = 1
        while not self.stopping.is_set():
            connection = None
            try:
                connection = self.connection_factory(self.parameters)
                channel = connection.channel()
                channel.queue_declare(queue="gps", durable=True)
                channel.confirm_delivery()
                with self.lock:
                    self.connected = True
                while not self.stopping.is_set():
                    with self.lock:
                        payload = self.pending[0] if self.pending else None
                    if payload is None:
                        connection.process_data_events(time_limit=0.2)
                        self.stopping.wait(0.05)
                        continue
                    channel.basic_publish(exchange="", routing_key="gps", mandatory=True,
                                          properties=pika.BasicProperties(content_type="application/json"),
                                          body=payload)
                    with self.lock:
                        if self.pending and self.pending[0] is payload:
                            self.pending.popleft()
                        self.last_success = datetime.now(timezone.utc).isoformat()
                        self.last_success_at = time.monotonic()
                        self.failures = 0
                    delay = 1
            except Exception as exc:
                with self.lock:
                    self.failures += 1
                logger.warning("RabbitMQ unavailable (%s); retry_in_seconds=%s", type(exc).__name__, delay)
            finally:
                with self.lock:
                    self.connected = False
                if connection is not None:
                    try:
                        connection.close()
                    except Exception:
                        pass
            self.stopping.wait(delay)
            delay = min(delay * 2, 60)

    def close(self):
        self.stopping.set()
        if self.thread is not None:
            self.thread.join(timeout=15)
            if self.thread.is_alive():
                logger.error("RabbitMQ worker did not stop before deadline; process will release socket")
