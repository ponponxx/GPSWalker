"""GPSWalker: Flask + SocketIO server tying UI <-> simulator <-> device(s)."""
from __future__ import annotations

import argparse
import os

from flask import Flask, render_template
from flask_socketio import SocketIO, emit

import device
from simulator import GpsSimulator


def build_provider(args, ap) -> device.LocationProvider:
    """Compose the location provider(s) from CLI args.

    Android side comes from --provider; --ios adds an iPhone. If both are
    present they are wrapped in a MultiProvider so one walk drives both.
    """
    providers: list[device.LocationProvider] = []

    if args.provider != "none":
        kwargs = {}
        if args.provider == "intent":
            kwargs = dict(action=args.intent_action,
                          component=args.intent_component)
        elif args.provider == "http":
            if not args.phone:
                ap.error("--provider http requires --phone <ip:port>")
            kwargs = dict(host=args.phone)
        providers.append(device.auto_provider(prefer=args.provider, **kwargs))

    if args.ios:
        providers.append(device.IosProvider(udid=args.ios_udid))

    if not providers:
        ap.error("nothing to drive: pick a --provider and/or --ios")

    if len(providers) == 1:
        return providers[0]
    return device.MultiProvider(providers)


def make_app(provider: device.LocationProvider):
    app = Flask(__name__, template_folder="templates", static_folder="static")
    app.config["SECRET_KEY"] = "gpswalker"
    sio = SocketIO(app, cors_allowed_origins="*", async_mode="threading")

    if isinstance(provider, device.MultiProvider):
        names = ", ".join(type(p).__name__ for p in provider.providers)
        print(f"[device] MultiProvider -> [{names}]")
    else:
        print(f"[device] using provider: {type(provider).__name__}")

    sim = GpsSimulator(
        provider,
        on_update=lambda s: sio.emit("state", s),
        on_complete=lambda: sio.emit("completed", sim.snapshot()),
    )
    sim.start()

    @app.route("/")
    def index():
        return render_template(
            "index.html",
            initial=sim.snapshot(),
            provider=type(provider).__name__,
            devices=device.adb_devices(),
        )

    @sio.on("connect")
    def _on_connect():
        emit("state", sim.snapshot())

    @sio.on("set_speed")
    def _on_speed(data):
        try:
            sim.set_speed(float(data.get("kmh", 4.5)))
        except (TypeError, ValueError):
            pass

    @sio.on("set_position")
    def _on_set_pos(data):
        sim.set_position(float(data["lat"]), float(data["lon"]))

    @sio.on("set_target")
    def _on_target(data):
        sim.set_target(float(data["lat"]), float(data["lon"]))

    @sio.on("clear_target")
    def _on_clear():
        sim.clear_target()

    @sio.on("set_route")
    def _on_route(data):
        pts = data.get("points") or []
        sim.set_route(pts)

    @sio.on("clear_route")
    def _on_clear_route():
        sim.clear_route()

    @sio.on("joystick")
    def _on_joy(data):
        sim.set_joystick(float(data.get("x", 0.0)), float(data.get("y", 0.0)))

    @sio.on("stop")
    def _on_stop():
        sim.stop_motion()

    return app, sio


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--provider", default="auto",
                    choices=["auto", "http", "emu", "intent", "stub", "none"],
                    help="Android backend. 'http' = Wi-Fi to companion app. "
                         "'none' = no Android (iPhone only). Default: auto.")
    ap.add_argument("--phone", default=None,
                    help="Android phone <ip:port> for --provider http, "
                         "e.g. 192.168.1.37:8080.")
    ap.add_argument("--ios", action="store_true",
                    help="Also drive an iPhone via pymobiledevice3 "
                         "(needs tunnel.bat running as Administrator).")
    ap.add_argument("--ios-udid", default=None,
                    help="Target a specific iPhone by UDID (optional).")
    ap.add_argument("--intent-action", default="com.gpswalker.SET")
    ap.add_argument("--intent-component",
                    default="com.gpswalker.companion/.LocReceiver",
                    help="pkg/.Receiver of mock-location app (optional).")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=int(os.getenv("PORT", "5000")))
    args = ap.parse_args()

    provider = build_provider(args, ap)
    app, sio = make_app(provider)
    print(f"[server] http://{args.host}:{args.port}")
    sio.run(app, host=args.host, port=args.port, allow_unsafe_werkzeug=True)


if __name__ == "__main__":
    main()
