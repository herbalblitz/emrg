"""EMRG mobile gateway.

A host-side sidecar that lets a paired Android client reach the **unmodified**
EMRG daemon. The gateway is a protocol client, not a second engine: it opens a
WebSocket to ``127.0.0.1:56031`` with the daemon's own token, forwards frames
in both directions, and adds only the operations a phone needs that the daemon
protocol never had (a host command runner, a file editor, config writes, log
tails, device management).

The daemon remains the source of truth for the agent loop, tools, sessions,
memory, scheduler, sandbox and evolution. Nothing in ``emrg/`` outside this
package is modified.

Entry point::

    python -m emrg.gateway serve
    python -m emrg.gateway pair
"""

from __future__ import annotations

#: Version of the *gateway's* public contract. Bumped when a frame changes
#: shape in a way an older Android build cannot read. The engine version is
#: reported separately in ``gw_auth_ok``.
API_VERSION = "1.0"

#: Oldest Android contract this gateway accepts. The Android client sends its
#: own ``api_version`` in ``gw_auth``; a major mismatch is refused with a
#: readable error instead of a half-working screen.
MIN_API_VERSION = "1.0"

__version__ = "0.1.0"
