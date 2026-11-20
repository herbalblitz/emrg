import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { CompanionPanel } from "./CompanionPanel";

function setup(over: Record<string, unknown> = {}) {
  const api = {
    companionStatus: vi.fn().mockResolvedValue({ listener_running: false, active_connections: 0 }),
    companionPendingPairings: vi.fn().mockResolvedValue([]),
    companionDecidePairing: vi.fn().mockResolvedValue({ ok: true }),
    companionListenerStart: vi.fn().mockResolvedValue({
      endpoint: "wss://127.0.0.1:18476",
      fingerprint_sha256: "a".repeat(64),
      descriptor: '{"version":2}',
    }),
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
    fireEvent.click(screen.getByRole("button", { name: /start host/i }));
    await waitFor(() => expect(api.companionListenerStart).toHaveBeenCalledWith({
      bindAddress: "127.0.0.1", port: 18476, cwd: ".",
    }));
    expect(await screen.findByLabelText("Pairing descriptor")).toBeTruthy();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
    fireEvent.click(screen.getByRole("button", { name: "Copy pairing descriptor" }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('{"version":2}'));
    expect(await screen.findByRole("status")).toHaveTextContent("Copied pairing descriptor.");
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

  it("approves or rejects only through the named pairing method", async () => {
    const pairing = {
      pairing_id: "A".repeat(43), device_id: "android-test",
      public_key_sha256: "b".repeat(64), workspace_id: "workspace",
      cwd: "/workspace", scopes: ["status"], approved: false, expires_in: 60,
    };
    const api = setup({ companionPendingPairings: vi.fn().mockResolvedValue({ items: [pairing] }) });
    await screen.findByTestId("companion-pairing");
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));
    await waitFor(() => expect(api.companionDecidePairing).toHaveBeenCalledWith({
      pairingId: "A".repeat(43), approved: true,
    }));
  });

  it("reports missing preload bridge instead of claiming the host is ready", async () => {
    delete (window as unknown as { emrg?: unknown }).emrg;
    render(<CompanionPanel />);
    expect(await screen.findByRole("alert")).toBeTruthy();
  });
});
