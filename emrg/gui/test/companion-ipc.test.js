"use strict";
/** Security contract checks for the narrow Companion Electron IPC surface. */
const { test } = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

const MAIN = fs.readFileSync(path.join(__dirname, "..", "main.js"), "utf8");
const PRELOAD = fs.readFileSync(path.join(__dirname, "..", "preload.js"), "utf8");

test("Companion IPC is restricted to the primary top-level local renderer", () => {
  const start = MAIN.indexOf("function assertCompanionRenderer(event)");
  const end = MAIN.indexOf('ipcMain.handle("emrg:listTasks"', start);
  assert.ok(start >= 0 && end > start, "Companion IPC guard/handlers must exist");
  const block = MAIN.slice(start, end);
  assert.match(block, /event\.sender !== win\.webContents/);
  assert.match(block, /frame\.isMainFrame/);
  assert.match(block, /frame !== win\.webContents\.mainFrame/);
  assert.match(block, /expectedUrl\.startsWith\("file:\/\/"\)/);
  for (const channel of [
    "emrg:companionStatus",
    "emrg:companionPendingPairings",
    "emrg:companionDecidePairing",
    "emrg:companionPairingQr",
    "emrg:companionListenerStart",
    "emrg:companionListenerStop",
  ]) {
    const at = block.indexOf(`ipcMain.handle("${channel}"`);
    assert.ok(at >= 0, `missing fixed handler ${channel}`);
    const next = block.indexOf("ipcMain.handle(", at + 1);
    const handler = block.slice(at, next < 0 ? block.length : next);
    assert.match(handler, /assertCompanionRenderer\(event\)/, `${channel} must validate sender`);
  }
});

test("Companion preload has named methods only, no generic IPC bridge", () => {
  assert.match(PRELOAD, /companionStatus:\s*\(\) => ipcRenderer\.invoke\("emrg:companionStatus"\)/);
  assert.match(PRELOAD, /companionPendingPairings:\s*\(\) => ipcRenderer\.invoke\("emrg:companionPendingPairings"\)/);
  assert.match(PRELOAD, /companionDecidePairing:\s*\(payload\) => ipcRenderer\.invoke\("emrg:companionDecidePairing", payload\)/);
  assert.match(PRELOAD, /companionPairingQr:\s*\(payload\) => ipcRenderer\.invoke\("emrg:companionPairingQr", payload\)/);
  assert.match(PRELOAD, /companionListenerStart:\s*\(payload\) => ipcRenderer\.invoke\("emrg:companionListenerStart", payload\)/);
  assert.match(PRELOAD, /companionListenerStop:\s*\(\) => ipcRenderer\.invoke\("emrg:companionListenerStop"\)/);
  assert.doesNotMatch(PRELOAD, /invoke:\s*\([^)]*channel/);
});

test("Companion IPC forwards only bounded explicit host and device scope selections", () => {
  const start = MAIN.indexOf('ipcMain.handle("emrg:companionDecidePairing"');
  const end = MAIN.indexOf('ipcMain.handle("emrg:listTasks"', start);
  const block = MAIN.slice(start, end);
  assert.match(block, /Object\.keys\(payload\)\.some\(key => !\["pairingId", "approved", "scopes"\]\.includes\(key\)\)/);
  assert.match(block, /Object\.keys\(payload\)\.some\(key => !\["bindAddress", "port", "cwd", "scopes"\]\.includes\(key\)\)/);
  assert.match(block, /payload\.scopes\.length > 20/);
  assert.match(block, /new Set\(payload\.scopes\)\.size !== payload\.scopes\.length/);
  assert.match(block, /decision\.scopes = payload\.scopes/);
  assert.match(block, /request\.scopes = payload\.scopes/);
  assert.match(block, /companionLocalControl\("pairing\.decide", decision\)/);
  assert.match(block, /companionLocalControl\("listener\.start", request\)/);
});
