import React, { useEffect, useState } from "react";
import { useAuth } from "./Auth.jsx";
import { newAlerts } from "./liveAlerts.js";
import { sessionRequest } from "./session.js";
import "./liveAlerts.css";

export function useLiveAlerts() {
  const [items, setItems] = useState(null);
  useEffect(() => {
    const stream = new EventSource("/api/monitor/alerts/live");
    stream.addEventListener("alerts", event => setItems(JSON.parse(event.data)));
    stream.onerror = () => setItems(null);
    return () => stream.close();
  }, []);
  return items;
}

function PersonalAlertNotice() {
  const [notice, setNotice] = useState(null);
  useEffect(() => {
    const controller = new AbortController();
    const stream = new EventSource("/api/me/notifications/stream");
    let previous = null;
    let loading = false;
    let dirty = false;
    let retry;

    // Redis pushes invalidate the durable inbox. Keep at most one read in flight.
    async function refresh() {
      dirty = true;
      if (loading) return;
      loading = true;
      clearTimeout(retry);
      try {
        while (dirty && !controller.signal.aborted) {
          dirty = false;
          const response = await sessionRequest("/api/me/notifications", { cache: "no-store", signal: controller.signal });
          if (!response.ok) throw Error("Could not read notification inbox");
          const { items } = await response.json();
          if (controller.signal.aborted) return;
          const added = newAlerts(previous, items);
          previous = items;
          if (added.length) setNotice(added);
        }
      } catch {
        if (!controller.signal.aborted) retry = setTimeout(refresh, 1000);
      } finally {
        loading = false;
      }
    }

    stream.addEventListener("ready", refresh);
    stream.addEventListener("notifications.changed", refresh);
    return () => { controller.abort(); clearTimeout(retry); stream.close(); };
  }, []);

  if (!notice) return null;
  const [first, ...others] = notice;
  return <aside className="live-alert-notice" role="status" aria-label="New alert">
    <strong>{first.title}</strong><span>{first.severity}{others.length > 0 && " · +" + others.length + " more"}</span>
    <button type="button" aria-label="Dismiss alert notification" onClick={() => setNotice(null)}>×</button>
  </aside>;
}

export function AlertNotification() {
  const { user } = useAuth();
  return user ? <PersonalAlertNotice key={user.id} /> : null;
}
