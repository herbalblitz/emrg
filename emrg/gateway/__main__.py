"""``emrg-gateway`` — host-side companion for the EMRG Android app.

Commands::

    emrg-gateway serve                 run the TLS gateway (foreground)
    emrg-gateway pair                  mint a one-shot pairing code
    emrg-gateway devices              list paired devices
    emrg-gateway revoke <device-id>   revoke one device
    emrg-gateway fingerprint          print the certificate pin
    emrg-gateway rotate-cert          replace the certificate (re-pair every phone)
    emrg-gateway status               daemon/gateway liveness and paths

``pair`` and ``devices`` deliberately talk to the same files the running server
reads, so the CLI and the daemon-side gateway need no second control channel.
"""

from __future__ import annotations

import argparse
import json
import logging
import logging.handlers
import os
import sys
import time
from pathlib import Path

from emrg.config import ensure_config, logs_dir
from emrg.gateway import API_VERSION, __version__
from emrg.gateway.audit import AuditLog
from emrg.gateway.config import gateway_paths, load_config
from emrg.gateway.identity import DeviceStore
from emrg.gateway.protocol import GatewayError
from emrg.gateway.server import GatewayServer
from emrg.gateway.tls import (
    GatewayTlsError,
    _local_names,
    certificate_fingerprint,
    ensure_certificate,
)


def _setup_logging(verbose: bool) -> None:
    paths = gateway_paths()
    root = logging.getLogger("emrg.gateway")
    root.setLevel(logging.DEBUG if verbose else logging.INFO)
    root.propagate = False
    if root.handlers:
        return
    stream = logging.StreamHandler(sys.stderr)
    stream.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s", "%H:%M:%S"))
    root.addHandler(stream)
    try:
        file_handler = logging.handlers.RotatingFileHandler(
            paths.root / "gateway.log",
            maxBytes=5 * 1024 * 1024,
            backupCount=3,
            encoding="utf-8",
        )
        file_handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s"))
        root.addHandler(file_handler)
    except OSError:
        pass


def _addresses() -> list[str]:
    import socket

    try:
        hostname = socket.gethostname()
    except OSError:
        hostname = "emrg-host"
    return [name for name in _local_names(hostname) if name not in {"127.0.0.1", "::1"}]


def cmd_serve(args) -> int:
    config = load_config()
    if args.host:
        config.host = args.host
    if args.port:
        config.port = int(args.port)
    _setup_logging(args.verbose)
    ensure_config()
    paths = gateway_paths()
    info = ensure_certificate(paths.cert, paths.key)
    print(f"EMRG gateway {__version__} (API {API_VERSION})")
    print(f"  listening:    wss://{config.host}:{config.port}")
    print(f"  certificate:  {info.fingerprint}")
    print(f"  state:        {paths.root}")
    print(f"  daemon log:   {logs_dir() / 'emrgd.log'}")
    print("  pair a phone: emrg-gateway pair")
    print("Ctrl+C stops the gateway; a running turn keeps going until it finishes.")
    server = GatewayServer(config, paths)
    try:
        import asyncio

        asyncio.run(server.serve())
    except KeyboardInterrupt:
        return 0
    except GatewayTlsError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    except OSError as exc:
        print(f"error: cannot listen on {config.host}:{config.port}: {exc}", file=sys.stderr)
        return 2
    return 0


def cmd_pair(args) -> int:
    config = load_config()
    paths = gateway_paths()
    ensure_config()
    try:
        info = ensure_certificate(paths.cert, paths.key)
    except GatewayTlsError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    store = DeviceStore(paths.devices, paths.pairing, max_devices=config.max_devices)
    issued = store.issue_pairing_code(ttl_seconds=args.ttl)
    addresses = _addresses()
    host = config.public_host or (addresses[0] if addresses else "127.0.0.1")
    payload = {
        "type": "emrg-pair",
        "version": 1,
        "api_version": API_VERSION,
        "host": host,
        "port": config.port,
        "code": issued["code"],
        "fingerprint": info.fingerprint,
        "expires_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(issued["expires_at"])),
        "addresses": addresses,
    }
    if args.json:
        print(json.dumps(payload, indent=2, ensure_ascii=False))
        return 0
    print("Pair this phone with the host")
    print()
    print(f"  code:        {issued['code']}   (one use, {issued['ttl_seconds']} seconds)")
    print(f"  host:        {host}")
    print(f"  port:        {config.port}")
    print(f"  fingerprint: {info.fingerprint}")
    if len(addresses) > 1:
        print(f"  also:        {', '.join(addresses[1:])}")
    print()
    print("On the phone: Pair host -> enter the code. The fingerprint is shown for")
    print("comparison before the token is stored in Android Keystore.")
    return 0


def cmd_devices(args) -> int:
    config = load_config()
    paths = gateway_paths()
    store = DeviceStore(paths.devices, paths.pairing, max_devices=config.max_devices)
    devices = store.public_devices()
    if args.json:
        print(json.dumps({"devices": devices, "max_devices": config.max_devices}, indent=2))
        return 0
    if not devices:
        print("No paired devices.")
        return 0
    print(f"{'id':<18} {'name':<24} {'created':<21} {'last seen':<21} scopes")
    for device in devices:
        print(
            f"{device['id']:<18} {device['name'][:24]:<24} "
            f"{device['created'][:19]:<21} {device['last_seen'][:19]:<21} "
            f"{','.join(device['scopes'])}"
        )
    return 0


def cmd_revoke(args) -> int:
    config = load_config()
    paths = gateway_paths()
    store = DeviceStore(paths.devices, paths.pairing, max_devices=config.max_devices)
    removed = store.revoke(args.device_id)
    if removed:
        AuditLog(paths.audit).append("device.revoke", decision="allow",
                                     detail={"target": args.device_id, "source": "cli"})
        print(f"Revoked {args.device_id}.")
        return 0
    print(f"No device with id {args.device_id!r}.", file=sys.stderr)
    return 1


def cmd_fingerprint(args) -> int:
    paths = gateway_paths()
    try:
        ensure_certificate(paths.cert, paths.key)
        print(certificate_fingerprint(paths.cert))
    except (GatewayTlsError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return 0


def cmd_rotate(args) -> int:
    paths = gateway_paths()
    try:
        info = ensure_certificate(paths.cert, paths.key, rotate=True)
    except (GatewayTlsError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    AuditLog(paths.audit).append("cert.rotate", decision="allow", detail={"fingerprint": info.fingerprint})
    print(f"New certificate fingerprint: {info.fingerprint}")
    print("Every paired phone must pair again: the stored pin no longer matches.")
    return 0


def cmd_status(args) -> int:
    from emrg.connect import is_server_running_sync

    config = load_config()
    paths = gateway_paths()
    daemon = is_server_running_sync()
    print(f"gateway version: {__version__} (API {API_VERSION})")
    print(f"state directory: {paths.root}")
    print(f"config:          {paths.config}")
    print(f"certificate:     {paths.cert}")
    print(f"daemon:          {'listening on 127.0.0.1:56031' if daemon else 'not running'}")
    print(f"gateway bind:    {config.host}:{config.port}")
    try:
        print(f"fingerprint:     {certificate_fingerprint(paths.cert)}")
    except Exception:
        print("fingerprint:     certificate not created yet")
    store = DeviceStore(paths.devices, paths.pairing, max_devices=config.max_devices)
    print(f"devices:         {len(store.devices())} paired")
    return 0 if daemon else 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="emrg-gateway",
        description="EMRG host gateway for the Android app (TLS + device pairing).",
    )
    parser.add_argument("--version", action="version", version=f"emrg-gateway {__version__}")
    sub = parser.add_subparsers(dest="command", required=True)

    serve_p = sub.add_parser("serve", help="run the gateway in the foreground")
    serve_p.add_argument("--host", help="bind address (default from config.json)")
    serve_p.add_argument("--port", type=int, help="bind port (default 56032)")
    serve_p.add_argument("--verbose", action="store_true")
    serve_p.set_defaults(func=cmd_serve)

    pair_p = sub.add_parser("pair", help="mint a one-shot pairing code")
    pair_p.add_argument("--ttl", type=float, default=300.0, help="seconds the code stays valid")
    pair_p.add_argument("--json", action="store_true", help="machine-readable output")
    pair_p.set_defaults(func=cmd_pair)

    devices_p = sub.add_parser("devices", help="list paired devices")
    devices_p.add_argument("--json", action="store_true")
    devices_p.set_defaults(func=cmd_devices)

    revoke_p = sub.add_parser("revoke", help="revoke one device")
    revoke_p.add_argument("device_id")
    revoke_p.set_defaults(func=cmd_revoke)

    sub.add_parser("fingerprint", help="print the certificate pin").set_defaults(func=cmd_fingerprint)
    sub.add_parser("rotate-cert", help="replace the certificate").set_defaults(func=cmd_rotate)
    sub.add_parser("status", help="show gateway state").set_defaults(func=cmd_status)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return int(args.func(args) or 0)
    except GatewayError as exc:
        print(f"error [{exc.code}]: {exc.message}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
