// @vitest-environment jsdom
import React from "react";
import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import RunbookActions from "../../web-shared/RunbookActions.jsx";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

const alert = { alertId: "a123" };
const retry = {
  title: "Retry workflow", params: { workflowId: "WF-42" }, status: "pending_approval",
  stepNo: 2, approvalRequired: true, available: true
};

it("confirms the target before executing and renders the persisted result", async () => {
  const fetch = vi.fn()
    .mockResolvedValueOnce(Response.json({ actions: [retry] }))
    .mockResolvedValueOnce(Response.json({ ...retry, available: false, status: "succeeded",
      result: { response: { status: "running" } } }));
  vi.stubGlobal("fetch", fetch);
  const onRefresh = vi.fn();
  render(<RunbookActions alert={alert} onRefresh={onRefresh} />);
  fireEvent.click(await screen.findByRole("button", { name: "Retry workflow" }));
  expect(screen.getByText("Confirm retry workflow for WF-42?")).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  expect(fetch).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole("button", { name: "Retry workflow" }));
  fireEvent.click(screen.getByRole("button", { name: "Confirm" }));
  await waitFor(() => expect(onRefresh).toHaveBeenCalledOnce());
  expect(fetch.mock.calls[1]).toEqual(["/api/alerts/a123/actions/2/execute", expect.objectContaining({
    method: "POST", body: '{"confirmed":true}'
  })]);
  expect(screen.getByText(/"running"/)).toBeTruthy();
  expect(screen.queryByRole("button")).toBeNull();
});

it("shows why an unavailable step cannot run", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ actions: [
    { ...retry, status: "unavailable", available: false, reason: "Missing parameter: workflowId" }
  ] })));
  render(<RunbookActions alert={alert} onRefresh={vi.fn()} />);
  expect(await screen.findByText(/Missing parameter: workflowId/)).toBeTruthy();
  expect(screen.queryByRole("button")).toBeNull();
});

it("shows automatic steps and their completed results after the monitor refreshes without executing a request", async () => {
  const step = { ...retry, automatic: true, available: false, approvalRequired: false };
  const fetch = vi.fn()
    .mockResolvedValueOnce(Response.json({ actions: [{ ...step, status: "running" }] }))
    .mockResolvedValueOnce(Response.json({ actions: [{ ...step, status: "succeeded", result: { status: "running" } }] }));
  vi.stubGlobal("fetch", fetch);
  const { rerender } = render(<RunbookActions alert={alert} onRefresh={vi.fn()} />);
  expect(await screen.findByText("Automatic · running")).toBeTruthy();
  rerender(<RunbookActions alert={{ ...alert }} onRefresh={vi.fn()} />);
  expect(await screen.findByText("Automatic · succeeded")).toBeTruthy();
  expect(screen.getByText(/"status": "running"/)).toBeTruthy();
  expect(screen.queryByRole("button")).toBeNull();
  expect(fetch.mock.calls.every(([url]) => url === "/api/alerts/a123")).toBe(true);
});

it("shows the Python audit result, streams, elapsed time and approver", async () => {
  const step = { ...retry, kind: "python", title: "Python Script", available: false, status: "succeeded",
    approvedBy: "demo.member", result: { status: "SUCCESS", result: { healthy: true },
      stdout: "checked", stderr: "warning", durationMs: 17 } };
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ actions: [step] })));
  render(<RunbookActions alert={alert} onRefresh={vi.fn()} />);
  expect(await screen.findByText(/17 ms · Approved by demo.member/)).toBeTruthy();
  for (const text of ["Approval Required", "checked", "warning", /"healthy": true/]) {
    expect(screen.getByText(text)).toBeTruthy();
  }
});
