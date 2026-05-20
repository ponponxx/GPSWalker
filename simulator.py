"""GPS walk simulator with human-like jitter."""
from __future__ import annotations

import math
import random
import threading
import time
from dataclasses import dataclass

EARTH_R = 6_371_000.0  # meters


def haversine_m(lat1, lon1, lat2, lon2) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_R * math.asin(math.sqrt(a))


def offset_meters(lat, lon, dn, de) -> tuple[float, float]:
    """Offset (lat, lon) by dn meters north, de meters east."""
    dlat = dn / EARTH_R
    dlon = de / (EARTH_R * math.cos(math.radians(lat)))
    return lat + math.degrees(dlat), lon + math.degrees(dlon)


def bearing_to(lat1, lon1, lat2, lon2) -> float:
    """Initial bearing in radians from p1 -> p2."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return math.atan2(y, x)


@dataclass
class SimState:
    lat: float = 25.0330
    lon: float = 121.5654  # Taipei 101 default
    speed_kmh: float = 4.5
    running: bool = False
    target_lat: float | None = None
    target_lon: float | None = None
    # joystick vector in [-1, 1], y=+1 is north
    joy_x: float = 0.0
    joy_y: float = 0.0
    mode: str = "idle"  # idle | walk_to | joystick


class GpsSimulator:
    """Runs a fixed-tick loop, pushing positions to the device provider."""

    TICK_HZ = 5  # 5 Hz update

    def __init__(self, provider, on_update=None):
        self.provider = provider
        self.on_update = on_update or (lambda s: None)
        self.state = SimState()
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    # --- public control --------------------------------------------------
    def start(self):
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()

    def set_position(self, lat: float, lon: float):
        with self._lock:
            self.state.lat = lat
            self.state.lon = lon

    def set_speed(self, kmh: float):
        with self._lock:
            self.state.speed_kmh = max(0.1, min(150.0, kmh))

    def set_target(self, lat: float, lon: float):
        with self._lock:
            self.state.target_lat = lat
            self.state.target_lon = lon
            self.state.mode = "walk_to"
            self.state.running = True

    def clear_target(self):
        with self._lock:
            self.state.target_lat = None
            self.state.target_lon = None
            if self.state.mode == "walk_to":
                self.state.mode = "idle"
                self.state.running = False

    def set_joystick(self, x: float, y: float):
        with self._lock:
            self.state.joy_x = max(-1.0, min(1.0, x))
            self.state.joy_y = max(-1.0, min(1.0, y))
            mag = math.hypot(self.state.joy_x, self.state.joy_y)
            if mag > 0.05:
                self.state.mode = "joystick"
                self.state.running = True
            elif self.state.mode == "joystick":
                self.state.running = False
                self.state.mode = "idle"

    def stop_motion(self):
        with self._lock:
            self.state.running = False
            self.state.mode = "idle"
            self.state.target_lat = None
            self.state.target_lon = None
            self.state.joy_x = 0.0
            self.state.joy_y = 0.0

    def snapshot(self) -> dict:
        with self._lock:
            s = self.state
            return {
                "lat": s.lat, "lon": s.lon,
                "speed_kmh": s.speed_kmh,
                "running": s.running, "mode": s.mode,
                "target": ([s.target_lat, s.target_lon]
                           if s.target_lat is not None else None),
            }

    # --- core loop -------------------------------------------------------
    def _loop(self):
        dt = 1.0 / self.TICK_HZ
        last_push = 0.0
        while not self._stop.is_set():
            t0 = time.time()
            self._step(dt)
            # push at full tick rate; provider call is fire-and-forget-ish
            try:
                self.provider.push(self.state.lat, self.state.lon)
            except Exception as e:
                print(f"[provider] push failed: {e}")
            self.on_update(self.snapshot())
            # pace
            elapsed = time.time() - t0
            time.sleep(max(0.0, dt - elapsed))

    def _step(self, dt: float):
        with self._lock:
            s = self.state
            if not s.running:
                return
            # human-walk speed jitter: +/- ~8%
            speed_mps = max(0.0, s.speed_kmh / 3.6 * random.uniform(0.92, 1.08))
            step_m = speed_mps * dt

            if s.mode == "walk_to" and s.target_lat is not None:
                dist = haversine_m(s.lat, s.lon, s.target_lat, s.target_lon)
                if dist <= max(step_m, 1.5):
                    s.lat, s.lon = s.target_lat, s.target_lon
                    s.target_lat = s.target_lon = None
                    s.running = False
                    s.mode = "idle"
                    return
                br = bearing_to(s.lat, s.lon, s.target_lat, s.target_lon)
                # heading jitter: +/- 6 deg
                br += math.radians(random.uniform(-6, 6))
                dn = math.cos(br) * step_m
                de = math.sin(br) * step_m
            elif s.mode == "joystick":
                mag = math.hypot(s.joy_x, s.joy_y)
                if mag < 0.05:
                    return
                jx, jy = s.joy_x / mag, s.joy_y / mag
                # joystick scaled by magnitude (partial tilt = slower)
                step_m *= mag
                br = math.atan2(jx, jy)  # y=north
                br += math.radians(random.uniform(-4, 4))
                dn = math.cos(br) * step_m
                de = math.sin(br) * step_m
            else:
                return

            # positional micro-jitter (~0.4 m) to look like real GPS noise
            dn += random.gauss(0, 0.4)
            de += random.gauss(0, 0.4)
            s.lat, s.lon = offset_meters(s.lat, s.lon, dn, de)
