"""ADB-based location injection backends.

Real Android devices do NOT allow arbitrary processes (even via adb) to write
the system GPS. You need ONE of:

  * Emulator (AVD / Genymotion) -> use EmuGeoProvider (adb emu geo fix).
  * A "mock location" app set under Developer Options -> Select mock location
    app, which accepts intents. Then point IntentBroadcastProvider at it.
  * Rooted device -> RootSettingsProvider can poke LocationManager via
    `service call`. (Implementation included as a best-effort example.)
"""
from __future__ import annotations

import shutil
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass


def _run(cmd: list[str], timeout: float = 10.0) -> tuple[int, str, str]:
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return p.returncode, p.stdout.strip(), p.stderr.strip()
    except subprocess.TimeoutExpired:
        return -1, "", f"timeout after {timeout}s: {' '.join(cmd)}"
    except FileNotFoundError:
        return -1, "", f"command not found: {cmd[0]}"


def adb_devices() -> list[str]:
    if shutil.which("adb") is None:
        return []
    # Warm up the adb daemon first; cold start can take >10s.
    _run(["adb", "start-server"], timeout=30.0)
    rc, out, err = _run(["adb", "devices"], timeout=20.0)
    if rc != 0:
        print(f"[device] adb devices failed: {err}")
        return []
    devs = []
    for line in out.splitlines()[1:]:
        line = line.strip()
        if line and "\tdevice" in line:
            devs.append(line.split("\t", 1)[0])
    return devs


@dataclass
class LocationProvider:
    """Base class. Override push()."""
    serial: str | None = None

    def _adb(self, *args: str) -> tuple[int, str, str]:
        cmd = ["adb"]
        if self.serial:
            cmd += ["-s", self.serial]
        cmd += list(args)
        return _run(cmd)

    def push(self, lat: float, lon: float) -> None:
        raise NotImplementedError


class EmuGeoProvider(LocationProvider):
    """For Android emulator. Uses `adb emu geo fix <lon> <lat>`."""

    def push(self, lat: float, lon: float) -> None:
        self._adb("emu", "geo", "fix", f"{lon:.7f}", f"{lat:.7f}")


class IntentBroadcastProvider(LocationProvider):
    """Drive a mock-location app via broadcast intent.

    Configure `action` and optional `component` to match the app you set in
    Developer Options. Defaults match the open-source "SetLocation" pattern:
        action: com.example.setlocation.SET
        extras: lat (float), lon (float)
    Adjust to whatever app you actually installed.
    """

    action: str = "com.gpswalker.SET"
    component: str | None = "com.gpswalker.companion/.LocReceiver"
    extra_lat: str = "lat"
    extra_lon: str = "lon"

    def __init__(self, serial=None, action=None, component=None,
                 extra_lat="lat", extra_lon="lon"):
        super().__init__(serial=serial)
        if action:
            self.action = action
        if component is not None:
            self.component = component
        self.extra_lat = extra_lat
        self.extra_lon = extra_lon

    def push(self, lat: float, lon: float) -> None:
        args = ["shell", "am", "broadcast", "-a", self.action,
                "--ef", self.extra_lat, f"{lat:.7f}",
                "--ef", self.extra_lon, f"{lon:.7f}"]
        if self.component:
            args += ["-n", self.component]
        self._adb(*args)


class HttpProvider(LocationProvider):
    """Push locations over Wi-Fi to the GPSWalker companion app's HTTP server.

    No adb / USB debugging required. `host` is "<ip>:<port>", e.g.
    "192.168.1.37:8080" (read it off the companion app's main screen).
    """

    def __init__(self, host: str):
        super().__init__(serial=None)
        self.host = host.strip().rstrip("/")
        if "://" in self.host:
            self.host = self.host.split("://", 1)[1]
        self.base = f"http://{self.host}"
        self._last_err = 0.0
        self._err_count = 0

    def ping(self, timeout: float = 2.0) -> bool:
        try:
            with urllib.request.urlopen(f"{self.base}/ping", timeout=timeout) as r:
                return r.status == 200
        except Exception as e:
            print(f"[http] ping {self.base} failed: {e}")
            return False

    def push(self, lat: float, lon: float) -> None:
        q = urllib.parse.urlencode({"lat": f"{lat:.7f}", "lon": f"{lon:.7f}"})
        try:
            with urllib.request.urlopen(f"{self.base}/set?{q}", timeout=1.5) as r:
                r.read()
            self._err_count = 0
        except (urllib.error.URLError, OSError) as e:
            # Throttle error spam to once every 3s.
            now = time.time()
            self._err_count += 1
            if now - self._last_err > 3.0:
                print(f"[http] push failed ({self._err_count}x): {e}")
                self._last_err = now
                self._err_count = 0


class IosProvider(LocationProvider):
    """Inject location into an iPhone (iOS 17+, including iOS 26) via
    pymobiledevice3.

    iOS 17+ needs a RemoteXPC tunnel. Start it FIRST with tunnel.bat
    (must run as Administrator) -- that launches `pymobiledevice3 remote
    tunneld`, a daemon that builds the tunnel and exposes discovered
    devices. This provider then opens a DVT location-simulation channel
    over that tunnel.

    NOTE: this path must be validated on a real device; pymobiledevice3's
    API shifts between versions, so the connect logic below is defensive
    and logs clearly so we can adjust if needed.
    """

    def __init__(self, udid: str | None = None):
        super().__init__(serial=udid)
        self.udid = udid
        self._rsd = None
        self._dvt = None
        self._loc = None
        self.connected = False
        self._last_try = 0.0

    # -- connection -------------------------------------------------------
    def _tunneld_devices(self):
        """Return list of RemoteServiceDiscovery objects from tunneld."""
        from pymobiledevice3.tunneld.api import get_tunneld_devices
        return get_tunneld_devices()

    def _connect(self) -> bool:
        now = time.time()
        if now - self._last_try < 3.0:  # throttle retries
            return False
        self._last_try = now
        try:
            from pymobiledevice3.services.dvt.dvt_secure_socket_proxy import (
                DvtSecureSocketProxyService,
            )
            from pymobiledevice3.services.dvt.instruments.location_simulation import (
                LocationSimulation,
            )
        except Exception as e:
            print(f"[ios] pymobiledevice3 not importable: {e}")
            return False

        try:
            rsds = self._tunneld_devices()
        except Exception as e:
            print(f"[ios] cannot reach tunneld -- is tunnel.bat running "
                  f"(as Administrator)? {e}")
            return False
        if not rsds:
            print("[ios] tunneld sees no device -- iPhone connected, unlocked, "
                  "trusted, and Developer Mode on?")
            return False

        rsd = None
        if self.udid:
            for r in rsds:
                if getattr(r, "udid", None) == self.udid:
                    rsd = r
                    break
        rsd = rsd or rsds[0]

        try:
            dvt = DvtSecureSocketProxyService(rsd)
            dvt.__enter__()  # keep the channel open (no `with` block)
            loc = LocationSimulation(dvt)
            self._rsd, self._dvt, self._loc = rsd, dvt, loc
            self.connected = True
            print(f"[ios] connected: udid={getattr(rsd, 'udid', '?')}")
            return True
        except Exception as e:
            print(f"[ios] failed to open DVT location channel: {e}")
            self._safe_close()
            return False

    def _safe_close(self):
        try:
            if self._loc is not None:
                self._loc.clear()
        except Exception:
            pass
        try:
            if self._dvt is not None:
                self._dvt.__exit__(None, None, None)
        except Exception:
            pass
        self._loc = self._dvt = self._rsd = None

    # -- LocationProvider API --------------------------------------------
    def push(self, lat: float, lon: float) -> None:
        if not self.connected and not self._connect():
            return
        try:
            self._loc.set(lat, lon)
        except Exception as e:
            print(f"[ios] set() failed, will reconnect: {e}")
            self.connected = False
            self._safe_close()

    def close(self):
        self._safe_close()
        self.connected = False


class MultiProvider(LocationProvider):
    """Fan a single location out to several providers at once
    (e.g. one Android + one iPhone)."""

    def __init__(self, providers: list[LocationProvider]):
        super().__init__()
        self.providers = list(providers)

    def push(self, lat: float, lon: float) -> None:
        for p in self.providers:
            try:
                p.push(lat, lon)
            except Exception as e:
                print(f"[multi] {type(p).__name__}.push failed: {e}")

    def close(self):
        for p in self.providers:
            close = getattr(p, "close", None)
            if callable(close):
                try:
                    close()
                except Exception:
                    pass


class StubProvider(LocationProvider):
    """For local UI testing without a device."""

    def push(self, lat: float, lon: float) -> None:
        print(f"[stub] -> {lat:.6f},{lon:.6f}")


def auto_provider(prefer: str = "auto", **kwargs) -> LocationProvider:
    if prefer == "stub":
        return StubProvider()
    if prefer == "http":
        host = kwargs.get("host")
        if not host:
            raise SystemExit("--provider http requires --phone <ip:port>")
        p = HttpProvider(host)
        if p.ping():
            print(f"[device] HTTP provider connected: {p.base}")
        else:
            print(f"[device] WARNING: cannot reach {p.base} yet "
                  f"(check phone IP / same Wi-Fi / app running).")
        return p
    devs = adb_devices()
    serial = devs[0] if devs else None
    if prefer == "emu" or (prefer == "auto" and serial and "emulator" in serial):
        return EmuGeoProvider(serial=serial)
    if prefer == "intent":
        return IntentBroadcastProvider(serial=serial, **kwargs)
    if serial:
        return EmuGeoProvider(serial=serial)
    return StubProvider()
