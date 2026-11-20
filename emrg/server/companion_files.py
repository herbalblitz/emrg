"""File writing and deletion for a remote companion device.

A port of ``emrg/gateway/files.py::write`` / ``delete`` (reference branch
``feat/cross-platform``) for the companion WSS listener, which has no
gateway. Reads stay daemon-native (``list_files`` / ``read_file``); this
module is the *only* place the companion adds a file effect.

Containment is enforced here, not by the client:

* The client path is workspace-relative (``src/foo.py``) or absolute
  under the bound workspace; anything else is refused. The resolved
  target — symlinks followed — must stay inside the bound workspace root,
  so a symlink inside the workspace pointing outside cannot be used to
  escape it.
* The write goes through :func:`emrg.sandbox.fence.file_refusal`, the
  fence the engine's ``write``/``edit`` tools already answer to, under
  the companion-capped tier (see ``companion_exec.resolve_mode``: a remote
  companion is capped at ``workspace-write`` unless the host opts the
  device into danger).
* The write itself is atomic: temp file in the target's directory, fsync,
  ``os.replace``. Conflict detection is opt-in and content-based
  (``expected_sha256``).

Audit logging follows the daemon's ``companion_local_control`` rule:
operation + session/device id + workspace-relative path, never file
contents.
"""
from __future__ import annotations

import hashlib
import logging
import os
import secrets
from dataclasses import dataclass
from pathlib import Path

from emrg.sandbox.fence import file_refusal
from emrg.sandbox.policy import resolve_policy
from emrg.server.companion_exec import (
    CompanionExecConfig,
    CompanionExecError,
    _session_for,
    resolve_mode,
)
from emrg.tools.base import non_regular_kind

_LOG = logging.getLogger(__name__)

#: File editor ceiling (ported gateway value).
MAX_EDIT_FILE = 2 * 1024 * 1024


class CompanionFileError(ValueError):
    """Safe file refusal with a stable machine-readable ``code``."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class FileWrite:
    path: str
    bytes_written: int
    sha256: str
    created: bool
    mode: str
    line_count: int


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def resolve_under_root(root: Path, raw_path: str) -> Path:
    """Resolve a client path inside the bound workspace root.

    Relative paths are joined to the root; absolute paths must already be
    inside it. The result is fully resolved (symlinks followed) and must
    still be inside the root — a symlink escape is refused, not followed.
    """
    if not isinstance(raw_path, str) or not raw_path.strip():
        raise CompanionFileError("invalid_argument", "path must be a non-empty string")
    candidate = Path(raw_path).expanduser()
    if not candidate.is_absolute():
        candidate = root / candidate
    target = candidate.resolve(strict=False)
    try:
        target.relative_to(root)
    except ValueError:
        raise CompanionFileError(
            "policy_refused", "path is outside the approved workspace") from None
    return target


def _policy_for(*, root: Path, session_id: str | None,
                config: CompanionExecConfig, mode: str | None,
                confirm_danger: bool, device_id: str):
    session = _session_for(session_id, str(root))
    chosen = resolve_mode(session, config, mode,
                          confirm_danger=confirm_danger, device_id=device_id)
    policy = resolve_policy(
        mode=chosen,
        workspace_root=str(root),
        session_id=session.session_id if session is not None else None,
        extra_grants=session.sandbox_grants if session is not None else (),
        withheld_roots=session.sandbox_withheld if session is not None else (),
    )
    return policy


def write(
    raw_path: str,
    content: str,
    *,
    root: str,
    session_id: str | None,
    config: CompanionExecConfig,
    expected_sha256: str | None = None,
    create_parents: bool = False,
    mode: str | None = None,
    confirm_danger: bool = False,
    device_id: str = "",
) -> FileWrite:
    """Write one file under the session's fence, atomically."""
    if not isinstance(content, str):
        raise CompanionFileError("invalid_argument", "content must be a string")
    encoded = content.encode("utf-8")
    if len(encoded) > MAX_EDIT_FILE:
        raise CompanionFileError(
            "invalid_argument",
            f"content is {len(encoded)} bytes; the editor limit is {MAX_EDIT_FILE} bytes",
        )
    workspace = Path(str(root or "")).expanduser()
    if not workspace.is_absolute():
        raise CompanionFileError("invalid_argument", "workspace must be absolute")
    workspace = workspace.resolve()
    # Resolve what a symlink points at before judging it: the fence judges a
    # canonical identity, and writing through a link must not look like
    # writing the link's own name.
    target = resolve_under_root(workspace, raw_path)
    try:
        policy = _policy_for(
            root=workspace, session_id=session_id, config=config, mode=mode,
            confirm_danger=confirm_danger, device_id=device_id)
    except CompanionExecError as exc:
        raise CompanionFileError(exc.code, str(exc)) from None
    _LOG.info(
        "companion file allowed: operation=%r session=%r device=%r path=%r",
        "files.write", session_id, device_id, target.relative_to(workspace).as_posix())
    refusal = file_refusal(str(target), policy)
    if refusal is not None:
        raise CompanionFileError("policy_refused", refusal)

    created = not target.exists()
    if not created:
        if target.is_dir():
            raise CompanionFileError("invalid_argument", "is a directory")
        kind = non_regular_kind(target)
        if kind is not None:
            raise CompanionFileError(
                "invalid_argument",
                f"is {kind}, not a regular file — refusing to replace it",
            )
        if expected_sha256:
            try:
                current = target.read_bytes()
            except OSError as exc:
                raise CompanionFileError("internal_error", f"cannot read file: {exc}") from exc
            if _sha256(current) != str(expected_sha256).lower():
                raise CompanionFileError(
                    "conflict",
                    "the file changed on the host since it was read; reload before saving",
                )

    parent = target.parent
    if not parent.is_dir():
        if not create_parents:
            raise CompanionFileError(
                "not_found", "parent directory does not exist")
        # The fence judged the file, not the directory it is about to create.
        # Creating a directory outside the granted roots is the same widening,
        # so the parent is judged too.
        parent_refusal = file_refusal(str(parent), policy)
        if parent_refusal is not None:
            raise CompanionFileError("policy_refused", parent_refusal)
        try:
            parent.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            raise CompanionFileError("internal_error", f"cannot create directory: {exc}") from exc

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
        raise CompanionFileError("internal_error", f"cannot write file: {exc}") from exc
    finally:
        try:
            tmp.unlink()
        except FileNotFoundError:
            pass

    final = target.stat()
    return FileWrite(
        path=target.relative_to(workspace).as_posix(),
        bytes_written=final.st_size,
        sha256=_sha256(encoded),
        created=created,
        mode=oct(final.st_mode & 0o777),
        line_count=len(content.splitlines()),
    )


def delete(
    raw_path: str,
    *,
    root: str,
    session_id: str | None,
    config: CompanionExecConfig,
    mode: str | None = None,
    confirm_danger: bool = False,
    device_id: str = "",
) -> dict:
    """Delete one file under the same fence a write uses.

    A directory is never removed: the companion editor deletes files, and a
    recursive delete is exactly the operation a stolen token must not have.
    """
    workspace = Path(str(root or "")).expanduser()
    if not workspace.is_absolute():
        raise CompanionFileError("invalid_argument", "workspace must be absolute")
    workspace = workspace.resolve()
    target = resolve_under_root(workspace, raw_path)
    try:
        policy = _policy_for(
            root=workspace, session_id=session_id, config=config, mode=mode,
            confirm_danger=confirm_danger, device_id=device_id)
    except CompanionExecError as exc:
        raise CompanionFileError(exc.code, str(exc)) from None
    _LOG.info(
        "companion file allowed: operation=%r session=%r device=%r path=%r",
        "files.delete", session_id, device_id, target.relative_to(workspace).as_posix())
    refusal = file_refusal(str(target), policy)
    if refusal is not None:
        raise CompanionFileError("policy_refused", refusal)
    if target.is_dir():
        raise CompanionFileError("invalid_argument", "is a directory")
    if not target.exists():
        raise CompanionFileError("not_found", "file not found")
    try:
        target.unlink()
    except OSError as exc:
        raise CompanionFileError("internal_error", f"cannot delete file: {exc}") from exc
    return {"path": target.relative_to(workspace).as_posix(), "deleted": True}
