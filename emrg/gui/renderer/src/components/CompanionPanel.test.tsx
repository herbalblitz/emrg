import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { CompanionPanel } from "./CompanionPanel";

function setup(over: Record<string, unknown> = {}) {
  const api = {
    companionStatus: vi.fn().mockResolvedValue({ listener_running: false, active_connections: 0 }),
    companionPendingPairings: vi.fn().mockResolvedValue({ items: [] }),
    companionDecidePairing: vi.fn().mockResolvedValue({ ok: true }),
    companionListenerStart: vi.fn().mockResolvedValue({
      endpoint: "wss://127.0.0.1:18476",
      fingerprint_sha256: "a".repeat(64),
      descriptor: '{"version":2}',
    }),
    companionPairingQr: vi.fn().mockResolvedValue({ data_uri: "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=" }),
    companionListenerStop: vi.fn().mockResolvedValue({ running: false }),
    ...over,
  };
  (window as unknown as { emrg?: unknown }).emrg = api;
  render(<CompanionPanel />);
  return api;
}

afterEach(() => {
  cleanup();
  delete (window as unknown as { emrg?: unknown }).emrg;
});

describe("CompanionPanel", () => {
  it("shows the stopped-by-default state and starts only on explicit action", async () => {
    const api = setup();
    await screen.findByTestId("companion-status");
    expect(screen.getByTestId("companion-status").textContent).toContain("Host stopped");
    expect(api.companionListenerStart).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Host allows status")).toBeChecked();
    expect(screen.getByLabelText("Host allows sessions.list")).toBeChecked();
    expect(screen.getByLabelText("Host allows chat.send")).toBeChecked();
    expect(screen.getByLabelText("Host allows terminal.exec")).not.toBeChecked();
    fireEvent.click(screen.getByRole("button", { name: /start host/i }));
    await waitFor(() => expect(api.companionListenerStart).toHaveBeenCalledWith({
      bindAddress: "127.0.0.1", port: 18476, cwd: ".",
      scopes: ["status", "sessions.list", "chat.send"],
    }));
    expect(await screen.findByLabelText("Pairing descriptor")).toBeTruthy();
    expect(await screen.findByAltText("Companion pairing QR code")).toBeTruthy();
    expect(api.companionPairingQr).toHaveBeenCalledWith({ descriptor: '{"version":2}' });
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
    fireEvent.click(screen.getByRole("button", { name: "Copy pairing descriptor" }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('{"version":2}'));
    expect(await screen.findByRole("status")).toHaveTextContent("Copied pairing descriptor.");
    fireEvent.click(screen.getByRole("button", { name: /start host/i }));
    await waitFor(() => expect(api.companionListenerStart).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("passes only the explicitly selected host ceiling to listener startup", async () => {
    const api = setup();
    await screen.findByTestId("companion-status");
    fireEvent.click(screen.getByLabelText("Host allows chat.send"));
    fireEvent.click(screen.getByLabelText("Host allows files.read"));
    fireEvent.click(screen.getByRole("button", { name: /start host/i }));
    await waitFor(() => expect(api.companionListenerStart).toHaveBeenCalledWith({
      bindAddress: "127.0.0.1", port: 18476, cwd: ".",
      scopes: ["status", "sessions.list", "files.read"],
    }));
  });

  it("does not start when the host scope ceiling is empty", async () => {
    const api = setup();
    await screen.findByTestId("companion-status");
    for (const scope of ["status", "sessions.list", "chat.send"]) {
      fireEvent.click(screen.getByLabelText(`Host allows ${scope}`));
    }
    expect((screen.getByRole("button", { name: /start host/i }) as HTMLButtonElement).disabled).toBe(true);
    expect(api.companionListenerStart).not.toHaveBeenCalled();
  });

  it("keeps the manual descriptor available when QR rendering fails", async () => {
    setup({ companionPairingQr: vi.fn().mockRejectedValue(new Error("QR unavailable")) });
    await screen.findByTestId("companion-status");
    fireEvent.click(screen.getByRole("button", { name: /start host/i }));
    expect(await screen.findByLabelText("Pairing descriptor")).toBeTruthy();
    expect(await screen.findByRole("alert")).toHaveTextContent("QR unavailable");
    expect(screen.queryByAltText("Companion pairing QR code")).toBeNull();
  });

  it("surfaces clipboard failures without claiming the descriptor was copied", async () => {
    setup();
    await screen.findByTestId("companion-status");
    fireEvent.click(screen.getByRole("button", { name: /start host/i }));
    await screen.findByLabelText("Pairing descriptor");
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: vi.fn().mockRejectedValue(new Error("Clipboard denied")) },
    });
    fireEvent.click(screen.getByRole("button", { name: "Copy pairing descriptor" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Clipboard denied");
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("rejects ports outside the TCP range before calling the host", async () => {
    const api = setup();
    await screen.findByTestId("companion-status");
    const port = screen.getByLabelText("Port");
    fireEvent.change(port, { target: { value: "65536" } });
    const start = screen.getByRole("button", { name: /start host/i }) as HTMLButtonElement;
    expect(start.disabled).toBe(true);
    fireEvent.click(start);
    expect(api.companionListenerStart).not.toHaveBeenCalled();
  });

  it("approves only the selected non-empty subset of a device request", async () => {
    const pairing = {
      pairing_id: "A".repeat(43), device_id: "android-test",
      public_key_sha256: "b".repeat(64), workspace_id: "workspace",
      cwd: "/workspace", scopes: ["status", "sessions.list", "chat.send"],
      approved: null, approved_scopes: null, expires_in: 60,
    };
    const api = setup({
      companionPendingPairings: vi.fn().mockResolvedValue({ items: [pairing] }),
    });
    await screen.findByTestId("companion-pairing");
    expect(screen.getByLabelText("Grant status to android-test")).toBeChecked();
    expect(screen.getByLabelText("Grant sessions.list to android-test")).not.toBeChecked();
    expect(screen.getByLabelText("Grant chat.send to android-test")).not.toBeChecked();
    fireEvent.click(screen.getByLabelText("Grant sessions.list to android-test"));
    fireEvent.click(screen.getByRole("button", { name: "Approve selected scopes" }));
    await waitFor(() => expect(api.companionDecidePairing).toHaveBeenCalledWith({
      pairingId: "A".repeat(43), approved: true, scopes: ["status", "sessions.list"],
    }));
  });

  it("cannot approve a device with an empty selected scope set", async () => {
    const pairing = {
      pairing_id: "A".repeat(43), device_id: "android-test",
      public_key_sha256: "b".repeat(64), workspace_id: "workspace",
      cwd: "/workspace", scopes: ["status"], expires_in: 60,
    };
    setup({ companionPendingPairings: vi.fn().mockResolvedValue({ items: [pairing] }) });
    await screen.findByTestId("companion-pairing");
    fireEvent.click(screen.getByLabelText("Grant status to android-test"));
    expect((screen.getByRole("button", { name: "Approve selected scopes" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("approves or rejects only through the named pairing method", async () => {
    const pairing = {
      pairing_id: "A".repeat(43), device_id: "android-test",
      public_key_sha256: "b".repeat(64), workspace_id: "workspace",
      cwd: "/workspace", scopes: ["status"], approved: null, expires_in: 60,
    };
    const api = setup({ companionPendingPairings: vi.fn().mockResolvedValue({ items: [pairing] }) });
    await screen.findByTestId("companion-pairing");
    fireEvent.click(screen.getByRole("button", { name: "Approve selected scopes" }));
    await waitFor(() => expect(api.companionDecidePairing).toHaveBeenCalledWith({
      pairingId: "A".repeat(43), approved: true, scopes: ["status"],
    }));
  });

  it("reports missing preload bridge instead of claiming the host is ready", async () => {
    delete (window as unknown as { emrg?: unknown }).emrg;
    render(<CompanionPanel />);
    expect(await screen.findByRole("alert")).toBeTruthy();
  });
});
