"""File reading and editing for the phone.

Reads are the engine's own rule: absolute path, regular file only, bounded size,
UTF-8 text, line windows. A FIFO is refused rather than opened, because opening
one would block the gateway's event loop forever — the same reason the daemon's
``read_file`` handler refuses it.

Writes are the *only* place the gateway adds a file effect, and they go through
:func:`emrg.sandbox.fence.file_refusal`, the fence the engine's ``write``/``edit``
tools already answer to. The target is therefore writable exactly when the
session's tier says it is, with the same protected-daemon-file refusal. The
write itself is atomic: temp file in the target's directory, fsync, ``os.replace``.
A phone that loses its connection halfway through leaves the old file intact.

Conflict detection is opt-in and content-based: a client that read a file can
send the SHA-256 it saw, and a write whose target has changed since is refused
instead of overwriting someone else's edit. There is no mtime race here because
the claim is about the bytes, not the clock.
"""

from __future__ import annotations

import hashlib
import os
import secrets
from dataclasses import dataclass
from pathlib import Path

from emrg.gateway.config import MAX_EDIT_FILE, GatewayConfig
from emrg.gateway.exec import _session_for, resolve_mode
from emrg.gateway.protocol import (
    ERR_CONFLICT,
    ERR_INTERNAL,
    ERR_INVALID_ARGUMENT,
    ERR_NOT_FOUND,
    ERR_POLICY_REFUSED,
    GatewayError,
)
from emrg.sandbox.fence import file_refusal
from emrg.sandbox.policy import resolve_policy
from emrg.tools.base import non_regular_kind


@dataclass(frozen=True)
class FileRead:
    path: str
    content: str
    start_line: int
    line_limit: int | None
    total_lines: int
    truncated: bool
    binary: bool
    size: int
    sha256: str


@dataclass(frozen=True)
class FileWrite:
    path: str
    bytes_written: int
    sha256: str
    created: bool
    mode: str
    line_count: int


def _absolute(raw: str) -> Path:
    if not isinstance(raw, str) or not raw.strip():
        raise GatewayError(ERR_INVALID_ARGUMENT, "path must be a non-empty string")
    path = Path(raw).expanduser()
    if not path.is_absolute():
        raise GatewayError(ERR_INVALID_ARGUMENT, "path must be absolute")
    return path


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _line_window(total: int, start: int, limit: int | None) -> None:
    if start < 1:
        raise GatewayError(ERR_INVALID_ARGUMENT, "start_line must be an integer >= 1")
    if limit is not None and limit < 1:
        raise GatewayError(ERR_INVALID_ARGUMENT, "line_limit must be an integer >= 1")
    del total


def read(
    raw_path: str,
    *,
    start_line: int = 1,
    line_limit: int | None = None,
) -> FileRead:
    """Read a window of one text file.

    ``line_limit=None`` means the whole file, bounded by
    :data:`~emrg.gateway.config.MAX_EDIT_FILE`. The window is a count over the
    file's lines as ``str.splitlines`` sees them, which is the same domain the
    editor displays.
    """
    path = _absolute(raw_path).resolve()
    _line_window(0, start_line, line_limit)
    if not path.exists():
        raise GatewayError(ERR_NOT_FOUND, f"file not found: {path}")
    if path.is_dir():
        raise GatewayError(ERR_INVALID_ARGUMENT, f"is a directory: {path}")
    kind = non_regular_kind(path)
    if kind is not None:
        raise GatewayError(
            ERR_INVALID_ARGUMENT,
            f"{path} is {kind}, not a regular file — opening it would block the gateway",
        )
    size = path.stat().st_size
    if size > MAX_EDIT_FILE:
        raise GatewayError(
            ERR_INVALID_ARGUMENT,
            f"file is {size} bytes; the editor limit is {MAX_EDIT_FILE} bytes",
        )
    try:
        data = path.read_bytes()
    except OSError as exc:
        raise GatewayError(ERR_INTERNAL, f"cannot read {path}: {exc}") from exc
    digest = _sha256(data)
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        return FileRead(
            path=str(path),
            content="",
            start_line=start_line,
            line_limit=line_limit,
            total_lines=0,
            truncated=False,
            binary=True,
            size=size,
            sha256=digest,
        )
    lines = text.splitlines()
    total = len(lines)
    limit = line_limit if line_limit is not None else max(total, 1)
    window = lines[start_line - 1 : start_line - 1 + limit]
    truncated = start_line - 1 + len(window) < total
    return FileRead(
        path=str(path),
        content="\n".join(window),
        start_line=start_line,
        line_limit=line_limit,
        total_lines=total,
        truncated=truncated,
        binary=False,
        size=size,
        sha256=digest,
    )


def write(
    raw_path: str,
    content: str,
    *,
    cwd: str,
    session_id: str | None,
    config: GatewayConfig,
    expected_sha256: str | None = None,
    create_parents: bool = False,
    mode: str | None = None,
    confirm_danger: bool = False,
) -> FileWrite:
    """Write one file under the session's fence, atomically."""
    if not isinstance(content, str):
        raise GatewayError(ERR_INVALID_ARGUMENT, "content must be a string")
    encoded = content.encode("utf-8")
    if len(encoded) > MAX_EDIT_FILE:
        raise GatewayError(
            ERR_INVALID_ARGUMENT,
            f"content is {len(encoded)} bytes; the editor limit is {MAX_EDIT_FILE} bytes",
        )
    path = _absolute(raw_path)
    # Resolve what a symlink points at before judging it: the fence judges a
    # canonical identity, and writing through a link must not look like writing
    # the link's own name.
    target = path.resolve(strict=False)

    root = Path(str(cwd or "")).expanduser()
    if not root.is_absolute():
        raise GatewayError(ERR_INVALID_ARGUMENT, "cwd must be absolute")
    root = root.resolve()
    session = _session_for(session_id, str(root))
    chosen = resolve_mode(session, config, mode, confirm_danger=confirm_danger)
    policy = resolve_policy(
        mode=chosen,
        workspace_root=str(root),
        session_id=session.session_id if session is not None else None,
        extra_grants=session.sandbox_grants if session is not None else (),
        withheld_roots=session.sandbox_withheld if session is not None else (),
    )
    refusal = file_refusal(str(target), policy)
    if refusal is not None:
        raise GatewayError(ERR_POLICY_REFUSED, refusal)

    created = not target.exists()
    if not created:
        if target.is_dir():
            raise GatewayError(ERR_INVALID_ARGUMENT, f"is a directory: {target}")
        kind = non_regular_kind(target)
        if kind is not None:
            raise GatewayError(
                ERR_INVALID_ARGUMENT,
                f"{target} is {kind}, not a regular file — refusing to replace it",
            )
        if expected_sha256:
            try:
                current = target.read_bytes()
            except OSError as exc:
                raise GatewayError(ERR_INTERNAL, f"cannot read {target}: {exc}") from exc
            if _sha256(current) != str(expected_sha256).lower():
                raise GatewayError(
                    ERR_CONFLICT,
                    "the file changed on the host since it was read; reload before saving",
                    retryable=True,
                )

    parent = target.parent
    if not parent.is_dir():
        if not create_parents:
            raise GatewayError(
                ERR_NOT_FOUND,
                f"parent directory does not exist: {parent}",
            )
        # The fence judged the file, not the directory it is about to create.
        # Creating a directory outside the granted roots is the same widening,
        # so the parent is judged too.
        parent_refusal = file_refusal(str(parent), policy)
        if parent_refusal is not None:
            raise GatewayError(ERR_POLICY_REFUSED, parent_refusal)
        try:
            parent.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            raise GatewayError(ERR_INTERNAL, f"cannot create {parent}: {exc}") from exc

    original_mode: int | None = None
    if not created:
        try:
            original_mode = os.stat(target).st_mode & 0o777
        except OSError:
            original_mode = None

    tmp = target.with_name(f".{target.name}.{os.getpid()}.{secrets.token_hex(4)}.tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(encoded)
            handle.flush()
            os.fsync(handle.fileno())
        if original_mode is not None:
            try:
                os.chmod(tmp, original_mode)
            except OSError:
                pass
        os.replace(tmp, target)
        # fsync the directory too, so the rename survives a power cut on the
        # filesystems where that matters. Best effort: Windows cannot open a
        # directory this way, and the write is already durable.
        try:
            dir_fd = os.open(parent, os.O_RDONLY)
            try:
                os.fsync(dir_fd)
            finally:
                os.close(dir_fd)
        except OSError:
            pass
    except OSError as exc:
        raise GatewayError(ERR_INTERNAL, f"cannot write {target}: {exc}") from exc
    finally:
        try:
            tmp.unlink()
        except FileNotFoundError:
            pass

    final = target.stat()
    return FileWrite(
        path=str(target),
        bytes_written=final.st_size,
        sha256=_sha256(encoded),
        created=created,
        mode=oct(final.st_mode & 0o777),
        line_count=len(content.splitlines()),
    )


def delete(
    raw_path: str,
    *,
    cwd: str,
    session_id: str | None,
    config: GatewayConfig,
    mode: str | None = None,
    confirm_danger: bool = False,
) -> dict:
    """Delete one file under the same fence a write uses.

    A directory is never removed: the phone's editor deletes files, and a
    recursive delete is exactly the operation a stolen token must not have.
    """
    path = _absolute(raw_path).resolve(strict=False)
    root = Path(str(cwd or "")).expanduser().resolve()
    session = _session_for(session_id, str(root))
    chosen = resolve_mode(session, config, mode, confirm_danger=confirm_danger)
    policy = resolve_policy(
        mode=chosen,
        workspace_root=str(root),
        session_id=session.session_id if session is not None else None,
        extra_grants=session.sandbox_grants if session is not None else (),
        withheld_roots=session.sandbox_withheld if session is not None else (),
    )
    refusal = file_refusal(str(path), policy)
    if refusal is not None:
        raise GatewayError(ERR_POLICY_REFUSED, refusal)
    if path.is_dir():
        raise GatewayError(ERR_INVALID_ARGUMENT, f"is a directory: {path}")
    if not path.exists():
        raise GatewayError(ERR_NOT_FOUND, f"file not found: {path}")
    try:
        path.unlink()
    except OSError as exc:
        raise GatewayError(ERR_INTERNAL, f"cannot delete {path}: {exc}") from exc
    return {"path": str(path), "deleted": True}
