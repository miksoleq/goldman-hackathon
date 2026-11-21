// @vitest-environment jsdom
import React from "react";
import { cleanup, render, screen, act } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { AlertNotification, useLiveAlerts } from "../../web-shared/LiveAlerts.jsx";

vi.mock("../../web-shared/Auth.jsx", () => ({ useAuth: () => ({ user: { id: "recipient" } }) }));

const streams = new Map();
class MockStream extends EventTarget {
  constructor(url) { super(); streams.set(url, this); }
  close = vi.fn();
  push(name, data) { this.dispatchEvent(new MessageEvent(name, { data: JSON.stringify(data) })); }
}
afterEach(() => { cleanup(); vi.unstubAllGlobals(); streams.clear(); });

it("renders live snapshots, resumes after an outage and closes the subscription", () => {
  vi.stubGlobal("EventSource", MockStream);
  function View() { return <div>{useLiveAlerts()?.[0]?.title ?? "fallback"}</div>; }
  const view = render(<View />);
  const stream = streams.get("/api/monitor/alerts/live");
  act(() => stream.push("alerts", [{ title: "First" }]));
  expect(screen.getByText("First")).toBeTruthy();
  act(() => stream.onerror());
  expect(screen.getByText("fallback")).toBeTruthy();
  act(() => stream.push("alerts", [{ title: "Recovered" }]));
  expect(screen.getByText("Recovered")).toBeTruthy();
  view.unmount();
  expect(stream.close).toHaveBeenCalledOnce();
});

it("announces only new items in the recipient inbox after the baseline", async () => {
  const old = { alertId: "old", title: "Historical" };
  const fresh = { alertId: "new", title: "New payment alert", severity: "high" };
  vi.stubGlobal("EventSource", MockStream);
  const fetch = vi.fn()
    .mockResolvedValueOnce(Response.json({ items: [old] }))
    .mockResolvedValueOnce(Response.json({ items: [fresh, old] }));
  vi.stubGlobal("fetch", fetch);
  render(<AlertNotification />);
  const stream = streams.get("/api/me/notifications/stream");
  await act(async () => stream.push("ready", {}));
  expect(screen.queryByRole("status")).toBeNull();
  await act(async () => stream.push("notifications.changed", {}));
  expect(screen.getByText(fresh.title)).toBeTruthy();
  expect(fetch.mock.calls.every(([url]) => url === "/api/me/notifications")).toBe(true);
});
