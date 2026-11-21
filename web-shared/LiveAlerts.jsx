import React, { useEffect, useState } from "react";
import { newAlerts } from "./liveAlerts.js";

export function useLiveAlerts() {
  const [items, setItems] = useState(null);
  const [notice, setNotice] = useState(null);
  useEffect(() => {
    let previous = null;
    const stream = new EventSource("/api/monitor/alerts/live");
    const receive = event => {
      const snapshot = JSON.parse(event.data);
      const added = newAlerts(previous, snapshot);
      previous = snapshot;
      setItems(snapshot);
      if (added.length) setNotice(added);
    };
    stream.addEventListener("alerts", receive);
    stream.onerror = () => setItems(null);
    return () => stream.close();
  }, []);
  return { items, notice, dismiss: () => setNotice(null) };
}

export function AlertNotification({ live }) {
  if (!live.notice) return null;
  const [first, ...others] = live.notice;
  return <aside className="live-alert-notice" role="status" aria-label="New alert">
    <strong>{first.title}</strong><span>{first.severity}{others.length > 0 && " · +" + others.length + " more"}</span>
    <button type="button" aria-label="Dismiss alert notification" onClick={live.dismiss}>×</button>
  </aside>;
}
