import { useCallback, useEffect, useState } from "react";

type Pairing = {
  pairing_id: string;
  device_id: string;
  public_key_sha256: string;
  workspace_id: string;
  cwd: string;
  scopes: string[];
  approved?: boolean | null;
  approved_scopes?: string[] | null;
  expires_in: number;
};
type Status = {
  listener_running?: boolean;
  endpoint?: string | null;
  fingerprint_sha256?: string | null;
  active_connections?: number;
};
type Bridge = {
  companionStatus(): Promise<Status>;
  companionPendingPairings(): Promise<{ items?: Pairing[] }>;
  companionDecidePairing(p: { pairingId: string; approved: boolean; scopes?: string[] }): Promise<unknown>;
  companionListenerStart(p: { bindAddress: string; port: number; cwd: string; scopes: string[] }): Promise<{ descriptor?: string; endpoint?: string; fingerprint_sha256?: string }>;
  companionPairingQr(p: { descriptor: string }): Promise<{ data_uri?: string }>;
  companionListenerStop(): Promise<unknown>;
};

type ScopeOption = { id: string; label: string };

const SCOPE_OPTIONS: ScopeOption[] = [
  { id: "status", label: "Read host status" },
  { id: "sessions.list", label: "List sessions" },
  { id: "sessions.manage", label: "Manage sessions" },
  { id: "chat.send", label: "Send chat messages" },
  { id: "tools.monitor", label: "Monitor tool activity" },
  { id: "files.read", label: "Read workspace files" },
  { id: "files.write", label: "Write or delete workspace files" },
  { id: "terminal.exec", label: "Run terminal commands" },
  { id: "sandbox.manage", label: "Manage sandbox settings" },
  { id: "hosts.list", label: "List configured hosts" },
  { id: "memory.read", label: "Read memory" },
  { id: "models.manage", label: "Manage models" },
  { id: "rant.submit", label: "Submit feedback" },
  { id: "evolution.read", label: "Read evolution status" },
  { id: "tasks.manage", label: "Manage scheduled tasks" },
  { id: "skills.manage", label: "Manage skills" },
  { id: "logs.read", label: "Read logs" },
  { id: "audit.read", label: "Read audit records" },
  { id: "devices.manage", label: "Manage paired devices" },
  { id: "settings.manage", label: "Manage settings" },
];

// Preserve the existing host default. The ceiling is not a grant: each
// device still requests scopes and receives only the host-approved subset.
const DEFAULT_HOST_SCOPES = ["status", "sessions.list", "chat.send"];

function emrg(): Bridge | undefined {
  return (window as unknown as { emrg?: Bridge }).emrg;
}

function initialApprovalScopes(pairing: Pairing): string[] {
  const requested = pairing.scopes.filter(scope => SCOPE_OPTIONS.some(option => option.id === scope));
  if (pairing.approved === true) return [...(pairing.approved_scopes ?? requested)];
  // Start with the least-privilege useful grant. The host may explicitly add
  // more requested scopes before approving this one-time transaction.
  return requested.includes("status") ? ["status"] : requested.slice(0, 1);
}

/** Local-only Companion host controls. The listener is opt-in and defaults to loopback. */
export function CompanionPanel() {
  const [status, setStatus] = useState<Status | null>(null);
  const [pairings, setPairings] = useState<Pairing[]>([]);
  const [approvalScopes, setApprovalScopes] = useState<Record<string, string[]>>({});
  const [hostScopes, setHostScopes] = useState<string[]>(DEFAULT_HOST_SCOPES);
  const [bindAddress, setBindAddress] = useState("127.0.0.1");
  const [port, setPort] = useState("18476");
  const [cwd, setCwd] = useState(".");
  const [descriptor, setDescriptor] = useState("");
  const [qrDataUri, setQrDataUri] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [copied, setCopied] = useState(false);
  const portNumber = Number(port);
  const validPort = /^\d+$/.test(port) && portNumber >= 1 && portNumber <= 65535;

  const refresh = useCallback(async () => {
    const api = emrg();
    if (!api) { setError("Companion controls are unavailable in this build."); return; }
    try {
      const [nextStatus, nextPairings] = await Promise.all([
        api.companionStatus(), api.companionPendingPairings(),
      ]);
      const rows = Array.isArray(nextPairings?.items) ? nextPairings.items : [];
      setStatus(nextStatus);
      setPairings(rows);
      setApprovalScopes(current => {
        const next: Record<string, string[]> = {};
        for (const pairing of rows) {
          const selected = pairing.approved === true
            ? (pairing.approved_scopes ?? pairing.scopes)
            : (current[pairing.pairing_id] ?? initialApprovalScopes(pairing));
          next[pairing.pairing_id] = selected.filter(scope => pairing.scopes.includes(scope));
        }
        return next;
      });
      setError("");
    } catch (e) { setError((e as Error).message || "Could not load Companion status."); }
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);

  function toggleHostScope(scope: string, checked: boolean) {
    setHostScopes(current => checked
      ? [...new Set([...current, scope])]
      : current.filter(item => item !== scope));
  }

  function toggleApprovalScope(pairing: Pairing, scope: string, checked: boolean) {
    setApprovalScopes(current => {
      const selected = new Set(current[pairing.pairing_id] ?? initialApprovalScopes(pairing));
      if (checked) selected.add(scope);
      else selected.delete(scope);
      return {
        ...current,
        [pairing.pairing_id]: pairing.scopes.filter(item => selected.has(item)),
      };
    });
  }

  async function start() {
    const api = emrg();
    if (!api || !validPort || !cwd.trim() || hostScopes.length === 0) return;
    setBusy(true); setError(""); setDescriptor(""); setQrDataUri(""); setCopied(false);
    try {
      const scopes = SCOPE_OPTIONS.filter(option => hostScopes.includes(option.id)).map(option => option.id);
      const result = await api.companionListenerStart({
        bindAddress, port: Number(port), cwd, scopes,
      });
      await refresh();
      if (result.descriptor) {
        setDescriptor(result.descriptor);
        try {
          const qr = await api.companionPairingQr({ descriptor: result.descriptor });
          if (typeof qr.data_uri !== "string" || !qr.data_uri.startsWith("data:image/svg+xml;base64,")) {
            throw new Error("The host returned an invalid pairing QR image.");
          }
          setQrDataUri(qr.data_uri);
        } catch (e) {
          setError((e as Error).message || "Could not create pairing QR.");
        }
      }
    } catch (e) { setError((e as Error).message || "Could not start listener."); }
    finally { setBusy(false); }
  }

  async function copyDescriptor() {
    if (!descriptor) return;
    try {
      if (!navigator.clipboard?.writeText) throw new Error("Clipboard access is unavailable in this window.");
      await navigator.clipboard.writeText(descriptor);
      setCopied(true);
      setError("");
    } catch (e) {
      setCopied(false);
      setError((e as Error).message || "Could not copy pairing descriptor.");
    }
  }

  async function stop() {
    const api = emrg();
    if (!api) return;
    setBusy(true); setError(""); setCopied(false);
    try { await api.companionListenerStop(); setDescriptor(""); setQrDataUri(""); await refresh(); }
    catch (e) { setError((e as Error).message || "Could not stop listener."); }
    finally { setBusy(false); }
  }

  async function decide(pairingId: string, approved: boolean) {
    const api = emrg();
    if (!api) return;
    const scopes = approvalScopes[pairingId] ?? [];
    if (approved && scopes.length === 0) return;
    setBusy(true); setError("");
    try {
      await api.companionDecidePairing({
        pairingId, approved, ...(approved ? { scopes } : {}),
      });
      await refresh();
    } catch (e) { setError((e as Error).message || "Could not update pairing."); }
    finally { setBusy(false); }
  }

  return (
    <div className="panel-tab-body" data-settings-body="companion" data-testid="settings-body-companion">
      <div className="settings-group">
        <div className="settings-group-title">Secure device pairing</div>
        <p className="hint">The host stays off until you start it. Loopback is the default; use a trusted private network only when you intend to pair another device.</p>
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "center" }}>
          <label>Bind address <input aria-label="Bind address" value={bindAddress} onChange={e => setBindAddress(e.target.value)} disabled={busy || Boolean(status?.listener_running)} /></label>
          <label>Port <input aria-label="Port" inputMode="numeric" value={port} onChange={e => setPort(e.target.value)} disabled={busy || Boolean(status?.listener_running)} /></label>
          <label>Workspace directory <input aria-label="Workspace directory" value={cwd} onChange={e => setCwd(e.target.value)} disabled={busy || Boolean(status?.listener_running)} /></label>
          {status?.listener_running
            ? <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => void stop()}>Stop host</button>
            : <button type="button" className="btn btn-primary" disabled={busy || !cwd.trim() || !validPort || hostScopes.length === 0} onClick={() => void start()}>Start host and create pairing code</button>}
          <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => void refresh()}>Refresh</button>
        </div>
        <fieldset disabled={busy || Boolean(status?.listener_running)} style={{ marginTop: 12 }}>
          <legend>Scope ceiling for this pairing code</legend>
          <p className="hint">This ceiling applies only to the pairing code created by this start action; it is not an automatic grant. The device must request scopes, and you approve a non-empty subset below.</p>
          <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
            {SCOPE_OPTIONS.map(option => (
              <label key={option.id} style={{ display: "inline-flex", gap: 4, alignItems: "center" }}>
                <input
                  type="checkbox"
                  aria-label={`Host allows ${option.id}`}
                  checked={hostScopes.includes(option.id)}
                  onChange={e => toggleHostScope(option.id, e.target.checked)}
                />
                <span><code>{option.id}</code> — {option.label}</span>
              </label>
            ))}
          </div>
        </fieldset>
        {status && <p className="hint" data-testid="companion-status">{status.listener_running ? `Running at ${status.endpoint || "unknown endpoint"}` : "Host stopped"} · Active connections: {status.active_connections ?? 0}</p>}
        {descriptor && <div><p>Scan this pairing QR code with Android, or import the descriptor manually:</p>{qrDataUri && <img alt="Companion pairing QR code" src={qrDataUri} width={256} height={256} style={{ display: "block", maxWidth: "100%", height: "auto" }} />}<textarea aria-label="Pairing descriptor" readOnly value={descriptor} rows={4} style={{ width: "100%" }} /><button type="button" className="btn btn-ghost" onClick={() => void copyDescriptor()}>Copy pairing descriptor</button>{copied && <span role="status">Copied pairing descriptor.</span>}</div>}
        {error && <p role="alert" className="settings-msg err" data-testid="companion-error">{error}</p>}
      </div>
      <div className="settings-group">
        <div className="settings-group-title">Pending device approvals</div>
        {pairings.length === 0 ? <p className="hint" data-testid="companion-empty">No pending pairing requests.</p> : pairings.map(p => {
          const selected = approvalScopes[p.pairing_id] ?? initialApprovalScopes(p);
          const alreadyDecided = p.approved != null;
          return (
            <div className="task-row" key={p.pairing_id} data-testid="companion-pairing">
              <strong>{p.device_id}</strong>
              <span className="task-hint">Workspace: {p.workspace_id || "—"} · Requested scopes: {p.scopes.join(", ") || "none"} · Expires in {p.expires_in}s</span>
              <code>{p.public_key_sha256}</code>
              <div role="group" aria-label={`Scopes to grant to ${p.device_id}`} style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
                {p.scopes.map(scope => (
                  <label key={scope} style={{ display: "inline-flex", gap: 4, alignItems: "center" }}>
                    <input
                      type="checkbox"
                      aria-label={`Grant ${scope} to ${p.device_id}`}
                      checked={selected.includes(scope)}
                      disabled={busy || alreadyDecided}
                      onChange={e => toggleApprovalScope(p, scope, e.target.checked)}
                    />
                    <code>{scope}</code>
                  </label>
                ))}
              </div>
              {p.approved === true && <span className="task-hint">Approved scopes: {(p.approved_scopes ?? p.scopes).join(", ")}</span>}
              <span className="task-actions">
                <button type="button" className="btn btn-primary" disabled={busy || alreadyDecided || selected.length === 0} onClick={() => void decide(p.pairing_id, true)}>Approve selected scopes</button>
                <button type="button" className="btn btn-ghost" disabled={busy || alreadyDecided} onClick={() => void decide(p.pairing_id, false)}>Reject</button>
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
