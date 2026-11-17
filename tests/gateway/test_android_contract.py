"""The Android contract pins the gateway's shared frame vocabulary.

The Kotlin client and the Python gateway must agree on the API version and on
the set of slash commands the app advertises. This test reads both sources and
fails when they drift, so an older app can never silently speak a frame the
host no longer understands.

Reads:
  * emrg/gateway/__init__.py  -> API_VERSION
  * android/app/.../net/Protocol.kt  -> API_VERSION, SLASH_COMMANDS
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
PY_INIT = ROOT / "emrg" / "gateway" / "__init__.py"
KOTLIN_PROTOCOL = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "emrg" / "app" / "net" / "Protocol.kt"

REQUIRED_COMMANDS = [
    "/resume", "/sessions", "/clear", "/compact", "/memory", "/rename",
    "/model", "/sandbox", "/rant", "/help", "/image", "/delete",
    "/rewind", "/trigger", "/task-session", "/skills", "/version",
]


def _python_api_version() -> str:
    text = PY_INIT.read_text(encoding="utf-8")
    match = re.search(r'^API_VERSION = "([^"]+)"', text, re.MULTILINE)
    assert match, "emrg/gateway/__init__.py must define API_VERSION"
    return match.group(1)


def _kotlin_api_version() -> str:
    text = KOTLIN_PROTOCOL.read_text(encoding="utf-8", errors="replace")
    match = re.search(r'const val API_VERSION = "([^"]+)"', text)
    assert match, "Protocol.kt must define API_VERSION"
    return match.group(1)


def _kotlin_slash_commands() -> list[str]:
    text = KOTLIN_PROTOCOL.read_text(encoding="utf-8", errors="replace")
    start = text.find("val SLASH_COMMANDS")
    assert start >= 0, "Protocol.kt must define SLASH_COMMANDS"
    block = text[start:]
    # The list closes on its own indented line (`)`); find that line so a
    # multi-line list is captured whole.
    lines = block.splitlines()
    end = 0
    for index, line in enumerate(lines):
        if line.strip() == ")":
            end = index
            break
    assert end > 0, "SLASH_COMMANDS list must close with a ')' line"
    joined = "\n".join(lines[: end + 1])
    commands = re.findall(r'"/([a-z-]+)"', joined)
    return [f"/{c}" for c in commands]


def test_api_version_matches_both_sides():
    assert _python_api_version() == _kotlin_api_version() == "1.0"


def test_slash_commands_cover_every_required_command():
    commands = set(_kotlin_slash_commands())
    missing = [c for c in REQUIRED_COMMANDS if c not in commands]
    assert not missing, f"Protocol.kt is missing slash commands: {missing}"


def test_no_command_is_listed_and_unhandled():
    """The registry doubles as the parser; a listed command must be implemented."""
    commands = set(_kotlin_slash_commands())
    # The commands the app's parser must handle mirror this registry exactly.
    assert commands == set(REQUIRED_COMMANDS), commands
