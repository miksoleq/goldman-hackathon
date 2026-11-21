import { AccountMenu } from "../../web-shared/Auth.jsx";
import { useCallback, useEffect, useRef, useState } from "react";
import RunbookActions from "./RunbookActions.jsx";
import { FEED_NAMES, fetchMonitorFeed } from "./api.js";

const EMPTY_MESSAGES = {
  rules: "No rules are available in Control.",
  runbooks: "No runbooks are configured.",
  alerts: "No alerts have been received yet."
};

const SECTIONS = [
  { id: "rules", label: "Rules" },
  { id: "runbooks", label: "Runbooks" },
  { id: "alerts", label: "Alerts" }
];

function formatDate(value) {
  if (!value) return "Time unavailable";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Time unavailable";
  return new Intl.DateTimeFormat("en-GB", {
    dateStyle: "medium",
    timeStyle: "medium",
    timeZone: "UTC"
  }).format(date) + " UTC";
}

function itemId(section, item) {
  if (section === "alerts") return item.alertId;
  return item.id;
}

function severity(item) {
  return String(item.severity || "info").toLowerCase();
}

function itemTitle(section, item) {
  if (section === "alerts") return item.title || "Untitled alert";
  return item.name || (section === "rules" ? "Untitled rule" : "Untitled runbook");
}

function itemMeta(section, item) {
  if (section === "rules") return "Owner: " + (item.createdBy?.username || "System") + " · Version " + item.version + " · Priority " + item.priority;
  if (section === "runbooks") return item.format || "Runbook";
  return [formatDate(item.occurredAt), ...(item.teamIds || []).map(id => "Team " + id)].join(" · ");
}

function itemStatus(section, item) {
  if (section === "rules" || section === "runbooks") return item.enabled ? "Enabled" : "Disabled";
  return severity(item);
}

function Field({ label, children, code = false }) {
  return (
    <>
      <div className="field-label">{label}</div>
      {code
        ? <pre className="payload">{children || "No details available."}</pre>
        : <p>{children || "No details available."}</p>}
    </>
  );
}

function RelatedButton({ label, section, id, onNavigate }) {
  return (
    <button className="text-link" type="button" onClick={() => onNavigate(section, id)}>
      {label} →
    </button>
  );
}

function RelatedRules({ ids, rules, onNavigate }) {
  if (!ids.length) return null;
  return (
    <div className="related-links">
      {ids.map(id => {
        const rule = rules.find(candidate => candidate.id === id);
        return rule
          ? <RelatedButton key={id} label={rule.name} section="rules" id={id} onNavigate={onNavigate} />
          : <span className="item-meta" key={id}>{id}</span>;
      })}
    </div>
  );
}

function RuleDetails({ item, runbooks, onNavigate }) {
  const runbook = runbooks.find(candidate => candidate.id === item.runbookId);
  return (
    <>
      <div className="detail-eyebrow">
        {item.enabled ? "Enabled" : "Disabled"} · Version {item.version}
      </div>
      <h2>{item.name || "Untitled rule"}</h2>
      <p>Owner: {item.createdBy?.username || "System"}</p>
      <p>Priority {item.priority}</p>
      <Field label="Rule definition" code>{item.sourceYaml}</Field>
      {item.runbookId && (
        <>
          <div className="field-label">Runbook</div>
          {runbook
            ? <RelatedButton label={runbook.name} section="runbooks" id={runbook.id} onNavigate={onNavigate} />
            : <p>{item.runbookId}</p>}
        </>
      )}
    </>
  );
}

function RunbookDetails({ item }) {
  return (
    <>
      <div className="detail-eyebrow">
        {item.enabled ? "Enabled" : "Disabled"} · {item.format || "Unknown format"}
      </div>
      <h2>{item.name || "Untitled runbook"}</h2>
      <Field label="Runbook content" code>{item.content}</Field>
      {item.matches && (
        <Field label="Matching conditions" code>
          {JSON.stringify(item.matches, null, 2)}
        </Field>
      )}
    </>
  );
}

function AlertDetails({ item, rules, runbooks, onNavigate, onRefresh }) {
  const rule = rules.find(candidate => candidate.id === item.ruleId);
  const runbook = runbooks.find(candidate => candidate.id === item.runbookId);
  const unassigned = !item.runbookId || item.runbookId === "00000000-0000-0000-0000-000000000000";
  const recipients = [
    ...(item.teamIds || []).map(id => "Team · " + id),
    ...(item.userIds || []).map(id => "User · " + id)
  ];

  return (
    <>
      <div className="detail-eyebrow">Alert · {severity(item).toUpperCase()}</div>
      <h2>{item.title || "Untitled alert"}</h2>
      {item.body && <p>{item.body}</p>}
      <div className="field-label">Matching rule</div>
      {rule
        ? <RelatedButton label={rule.name} section="rules" id={rule.id} onNavigate={onNavigate} />
        : <p>{item.ruleId || "Unknown rule"}</p>}
      <div className="field-label">Recipients</div>
      {recipients.length
        ? <ul className="recipient-list">{recipients.map(recipient => <li key={recipient}>{recipient}</li>)}</ul>
        : <p>No recipients listed.</p>}
      <div className="field-label">Runbook</div>
      {runbook
        ? <RelatedButton label={runbook.name} section="runbooks" id={runbook.id} onNavigate={onNavigate} />
        : <p>{unassigned ? "Not assigned" : "Runbook is unavailable."}</p>}
      {item.runbook?.content && <Field label="Instructions" code>{item.runbook.content}</Field>}
      {item.runbook?.actions?.length > 0 && <RunbookActions key={item.alertId} alert={item} onRefresh={onRefresh} />}
    </>
  );
}

function ItemList({ section, items, selectedId, onSelect }) {
  return (
    <div className="list-pane">
      {items.map((item, index) => {
        const id = itemId(section, item);
        const state = itemStatus(section, item);
        return (
          <button
            className="list-item"
            type="button"
            aria-pressed={id === selectedId}
            key={id || index}
            onClick={() => onSelect(section, id)}
          >
            <span className="item-copy">
              <span className="item-title">{itemTitle(section, item)}</span>
              <span className="item-meta">{itemMeta(section, item)}</span>
            </span>
            <span className="item-status" data-severity={section === "alerts" ? severity(item) : undefined}>
              {state}
            </span>
          </button>
        );
      })}
    </div>
  );
}

function EmptyState({ children }) {
  return (
    <div className="empty-state">
      <strong>Nothing to show yet</strong>
      <span>{children}</span>
    </div>
  );
}

function App() {
  const [activeTab, setActiveTab] = useState("alerts");
  const [selectedIds, setSelectedIds] = useState({});
  const [feeds, setFeeds] = useState(() =>
    Object.fromEntries(FEED_NAMES.map(name => [name, { items: null, error: null }]))
  );
  const [connection, setConnection] = useState("Connecting");
  const [lastUpdated, setLastUpdated] = useState("");
  const [refreshing, setRefreshing] = useState(false);
  const [severityFilter, setSeverityFilter] = useState("all");
  const [query, setQuery] = useState("");
  const refreshingRef = useRef(false);

  const refresh = useCallback(async () => {
    if (refreshingRef.current) return;
    refreshingRef.current = true;
    setRefreshing(true);
    setConnection("Updating");

    const results = await Promise.all(FEED_NAMES.map(async name => {
      try {
        return { name, items: await fetchMonitorFeed(name), error: null };
      } catch {
        return {
          name,
          items: null,
          error: "Could not load " + name + ". Check the service and retry."
        };
      }
    }));

    const unavailable = results.filter(result => result.error).length;
    setFeeds(previous => Object.fromEntries(results.map(result => [
      result.name,
      {
        items: result.items || previous[result.name].items,
        error: result.error
      }
    ])));
    setConnection(unavailable === 0 ? "Live" :
      unavailable === FEED_NAMES.length ? "Disconnected" : "Partial data");
    if (results.some(result => !result.error)) {
      setLastUpdated(new Intl.DateTimeFormat("en-GB", {
        timeStyle: "short",
        timeZone: "UTC"
      }).format(new Date()) + " UTC");
    }
    setRefreshing(false);
    refreshingRef.current = false;
  }, []);

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => {
      if (!document.hidden) void refresh();
    }, 10000);
    const refreshWhenVisible = () => {
      if (!document.hidden) void refresh();
    };
    document.addEventListener("visibilitychange", refreshWhenVisible);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", refreshWhenVisible);
    };
  }, [refresh]);

  function navigate(section, id) {
    if (id) setSelectedIds(previous => ({ ...previous, [section]: id }));
    setActiveTab(section);
  }

  function chooseTab(event, section) {
    if (event.key !== "ArrowRight" && event.key !== "ArrowLeft"
        && event.key !== "Home" && event.key !== "End") return;
    event.preventDefault();
    const currentIndex = SECTIONS.findIndex(candidate => candidate.id === section);
    const nextIndex = event.key === "Home" ? 0 :
      event.key === "End" ? SECTIONS.length - 1 :
        (currentIndex + (event.key === "ArrowRight" ? 1 : SECTIONS.length - 1)) % SECTIONS.length;
    setActiveTab(SECTIONS[nextIndex].id);
    document.getElementById("tab-" + SECTIONS[nextIndex].id)?.focus();
  }

  const feed = feeds[activeTab];
  const sectionItems = feed.items || [];
  const rules = feeds.rules.items || [];
  const items = activeTab === "alerts" ? sectionItems.filter(item => {
    const ruleName = rules.find(rule => rule.id === item.ruleId)?.name || "";
    const searchable = [item.title, item.body, ruleName].filter(Boolean).join(" ").toLowerCase();
    return (severityFilter === "all" || severity(item) === severityFilter) && searchable.includes(query.trim().toLowerCase());
  }) : sectionItems;
  const selected = items.find(item => itemId(activeTab, item) === selectedIds[activeTab]) || items[0];
  const runbooks = feeds.runbooks.items || [];
  const connectionState = {
    Live: "live",
    "Partial data": "partial",
    Disconnected: "disconnected",
    Updating: "updating",
    Connecting: "connecting"
  }[connection];

  function details(item) {
    if (activeTab === "rules") return <RuleDetails item={item} runbooks={runbooks} onNavigate={navigate} />;
    if (activeTab === "runbooks") return <RunbookDetails item={item} />;
    return (
      <AlertDetails
        item={item}
        onRefresh={refresh}
        rules={rules}
        runbooks={runbooks}
        onNavigate={navigate}
      />
    );
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <h1>Event Monitor</h1>
        <div className="header-actions">
          <AccountMenu />
          <div className="connection" data-state={connectionState} role="status" aria-live="polite">
            <span className="connection-mark" aria-hidden="true" />
            <span>{connection}</span>
          </div>
          <button className="refresh-button" type="button" onClick={refresh} disabled={refreshing}>
            {refreshing ? "Refreshing…" : "Refresh"}
          </button>
        </div>
      </header>
      <main>
        <div className="intro">
          <p>Review alerts, their runbooks, and routing rules.</p>
          <p className="updated">{lastUpdated && "Updated " + lastUpdated}</p>
        </div>
        <nav className="section-tabs" role="tablist" aria-label="Monitor sections">
          {SECTIONS.map(section => (
            <button
              id={"tab-" + section.id}
              aria-controls="monitor-panel"
              aria-selected={activeTab === section.id}
              tabIndex={activeTab === section.id ? 0 : -1}
              key={section.id}
              role="tab"
              type="button"
              onClick={() => setActiveTab(section.id)}
              onKeyDown={event => chooseTab(event, section.id)}
            >
              {section.label}
            </button>
          ))}
        </nav>
        <section
          id="monitor-panel"
          className="monitor-panel"
          role="tabpanel"
          aria-labelledby={"tab-" + activeTab}
        >
          {activeTab === "alerts" && <div className="alert-toolbar"><label>Search alerts<input value={query} onChange={event => setQuery(event.target.value)} placeholder="Title, message, or rule" /></label>
            <label>Severity<select value={severityFilter} onChange={event => setSeverityFilter(event.target.value)}><option value="all">All severities</option>{["critical", "high", "medium", "low", "info"].map(value => <option key={value} value={value}>{value[0].toUpperCase() + value.slice(1)}</option>)}</select></label>
            <span>{items.length} of {sectionItems.length} alerts</span></div>}
          {feed.error && (
            <div className="error-state feed-error" role="alert">
              <strong>{activeTab[0].toUpperCase() + activeTab.slice(1)} unavailable</strong>
              <span>{feed.error}</span>
              <button className="text-link" type="button" onClick={refresh}>Retry</button>
            </div>
          )}
          {feed.items === null
            ? feed.error
              ? null
              : <div className="loading-state">Loading {activeTab}…</div>
            : items.length === 0
              ? <EmptyState>{activeTab === "alerts" && sectionItems.length ? "No alerts match these filters." : EMPTY_MESSAGES[activeTab]}</EmptyState>
              : (
                <div className="workspace">
                  <ItemList
                    section={activeTab}
                    items={items}
                    selectedId={selected && itemId(activeTab, selected)}
                    onSelect={(section, id) => setSelectedIds(previous => ({ ...previous, [section]: id }))}
                  />
                  <article className="detail-pane">{details(selected)}</article>
                </div>
              )}
        </section>
      </main>
    </div>
  );
}

export default App;
