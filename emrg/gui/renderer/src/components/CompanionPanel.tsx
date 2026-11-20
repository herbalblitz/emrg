import { useCallback, useEffect, useState } from "react";

type Pairing = {
  pairing_id: string;
  device_id: string;
  public_key_sha256: string;
  workspace_id: string;
  cwd: string;
  scopes: string[];
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
  companionDecidePairing(p: { pairingId: string; approved: boolean }): Promise<unknown>;
  companionListenerStart(p: { bindAddress: string; port: number; cwd: string }): Promise<{ descriptor?: string; endpoint?: string; fingerprint_sha256?: string }>;
  companionListenerStop(): Promise<unknown>;
};

function emrg(): Bridge | undefined {
  return (window as unknown as { emrg?: Bridge }).emrg;
}

/** Local-only Companion host controls. The listener is opt-in and defaults to loopback. */
export function CompanionPanel() {
  const [status, setStatus] = useState<Status | null>(null);
  const [pairings, setPairings] = useState<Pairing[]>([]);
  const [bindAddress, setBindAddress] = useState("127.0.0.1");
  const [port, setPort] = useState("18476");
  const [cwd, setCwd] = useState(".");
  const [descriptor, setDescriptor] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const portNumber = Number(port);
  const validPort = /^\d+$/.test(port) && portNumber >= 1 && portNumber <= 65535;

  const refresh = useCallback(async () => {
    const api = emrg();
    if (!api) { setError("Companion controls are unavailable in this build."); return; }
    try {
      const [nextStatus, nextPairings] = await Promise.all([
        api.companionStatus(), api.companionPendingPairings(),
      ]);
      setStatus(nextStatus);
      setPairings(Array.isArray(nextPairings?.items) ? nextPairings.items : []);
      setError("");
    } catch (e) { setError((e as Error).message || "Could not load Companion status."); }
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);

  async function start() {
    const api = emrg();
    if (!api || !validPort || !cwd.trim()) return;
    setBusy(true); setError(""); setDescriptor("");
    try {
      const result = await api.companionListenerStart({
        bindAddress, port: Number(port), cwd,
      });
      if (result.descriptor) setDescriptor(result.descriptor);
      await refresh();
    } catch (e) { setError((e as Error).message || "Could not start listener."); }
    finally { setBusy(false); }
  }

  async function stop() {
    const api = emrg();
    if (!api) return;
    setBusy(true); setError("");
    try { await api.companionListenerStop(); setDescriptor(""); await refresh(); }
    catch (e) { setError((e as Error).message || "Could not stop listener."); }
    finally { setBusy(false); }
  }

  async function decide(pairingId: string, approved: boolean) {
    const api = emrg();
    if (!api) return;
    setBusy(true); setError("");
    try { await api.companionDecidePairing({ pairingId, approved }); await refresh(); }
    catch (e) { setError((e as Error).message || "Could not update pairing."); }
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
            : <button type="button" className="btn btn-primary" disabled={busy || !cwd.trim() || !validPort} onClick={() => void start()}>Start host and create pairing code</button>}
          <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => void refresh()}>Refresh</button>
        </div>
        {status && <p className="hint" data-testid="companion-status">{status.listener_running ? `Running at ${status.endpoint || "unknown endpoint"}` : "Host stopped"} · Active connections: {status.active_connections ?? 0}</p>}
        {descriptor && <div><p>Scan/import this pairing descriptor on Android:</p><textarea aria-label="Pairing descriptor" readOnly value={descriptor} rows={4} style={{ width: "100%" }} /></div>}
        {error && <p role="alert" className="settings-msg err" data-testid="companion-error">{error}</p>}
      </div>
      <div className="settings-group">
        <div className="settings-group-title">Pending device approvals</div>
        {pairings.length === 0 ? <p className="hint" data-testid="companion-empty">No pending pairing requests.</p> : pairings.map(p => (
          <div className="task-row" key={p.pairing_id} data-testid="companion-pairing">
            <strong>{p.device_id}</strong>
            <span className="task-hint">Workspace: {p.workspace_id || "—"} · Scopes: {p.scopes.join(", ") || "none"} · Expires in {p.expires_in}s</span>
            <code>{p.public_key_sha256}</code>
            <span className="task-actions">
              <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void decide(p.pairing_id, true)}>Approve</button>
              <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => void decide(p.pairing_id, false)}>Reject</button>
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
