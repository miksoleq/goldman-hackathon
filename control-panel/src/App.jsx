import { useCallback, useEffect, useMemo, useState } from "react";
import { Activity, Bell, BookOpen, Grid2X2, Inbox, ListFilter, Plus, RefreshCw, Search, ShieldCheck, BellRing } from "lucide-react";
import RuleBuilder from "./RuleBuilder.jsx";
import { OPERATORS } from "./ruleModel.js";
import { fetchFeed } from "./api.js";

const NAV = [
  ["overview", "Overview", Grid2X2],
  ["rules", "Rules", ListFilter],
  ["messages", "Messages", Inbox],
  ["alerts", "Alerts", Bell],
  ["runbooks", "Runbooks", BookOpen]
];
const FEEDS = ["rules", "messages", "alerts", "runbooks"];

function displayTime(value) {
  if (!value) return "Time unavailable";
  const date = new Date(value);
  return Number.isNaN(date.valueOf()) ? "Time unavailable" :
    new Intl.DateTimeFormat("en-GB", { dateStyle: "medium", timeStyle: "short" }).format(date);
}

function conditionDescription(node) {
  if (!node) return "No condition configured.";
  if (node.all) return node.all.map(conditionDescription).join(" AND ");
  if (node.any) return "(" + node.any.map(conditionDescription).join(" OR ") + ")";
  if (node.not) return "NOT (" + conditionDescription(node.not) + ")";
  const operator = OPERATORS.find(item => item[0] === node.op)?.[1] || node.op;
  const path = node.path?.replace(/^payload\./, "Event data · ").replace(/^meta\./, "Metadata · ") || "Field";
  if (node.op === "exists") return path + (node.value === false ? " is missing" : " is present");
  if (node.op === "jsonSchema") return path + " matches a JSON structure";
  const value = Array.isArray(node.value) ? node.value.join(", ") : typeof node.value === "object" ? JSON.stringify(node.value) : String(node.value);
  return path + " " + operator.toLowerCase() + " “" + value + "”";
}

function Sidebar({ active, onNavigate, ruleCount }) {
  return <aside className="sidebar">
    <a className="brand" href="#" onClick={event => { event.preventDefault(); onNavigate("overview"); }}>
      <span className="brand-mark">GS</span><span>Alerting<span className="brand-sub">CONTROL PANEL</span></span>
    </a>
    <div className="workspace-label">WORKSPACE</div>
    <nav aria-label="Main navigation">
      {NAV.map(([id, label, Icon]) => <button key={id} type="button" className={active === id ? "nav-item active" : "nav-item"}
        onClick={() => onNavigate(id)}>
        <Icon size={16} aria-hidden="true" /><span>{label}</span>
        {id === "rules" && <span className="nav-count">{ruleCount}</span>}
      </button>)}
    </nav>
    <div className="sidebar-bottom"><span className="service-dot" /> Control service <span className="connected">Connected</span></div>
  </aside>;
}

function Stat({ label, value, note, icon }) {
  const Icon = icon;
  return <section className="stat-card"><div className="stat-top"><span>{label}</span><Icon size={16} aria-hidden="true" /></div><strong>{value}</strong><small>{note}</small></section>;
}

function Overview({ feeds, navigate, createRule }) {
  const rules = feeds.rules.items || [];
  const alerts = feeds.alerts.items || [];
  const messages = feeds.messages.items || [];
  const activeRules = rules.filter(rule => rule.enabled).length;
  return <div className="page-content">
    <div className="page-heading"><div><p className="eyebrow">SYSTEM OVERVIEW</p><h1>Good afternoon</h1><p className="subtitle">Manage event rules and monitor alert activity.</p></div>
      <button className="primary-button" onClick={createRule}><Plus size={16} aria-hidden="true" /> Create rule</button></div>
    <div className="stats-grid">
      <Stat label="Active rules" value={activeRules} note={rules.length + " total rules"} icon={ShieldCheck} />
      <Stat label="Messages received" value={messages.length} note="Recent messages" icon={Activity} />
      <Stat label="Alerts generated" value={alerts.length} note="Current inbox" icon={BellRing} />
      <Stat label="Runbooks" value={(feeds.runbooks.items || []).filter(book => book.enabled).length} note="Available for routing" icon={BookOpen} />
    </div>
    <section className="content-section">
      <div className="section-heading"><div><h2>Recent rules</h2><p>Rules currently configured in Control.</p></div><button className="text-button" onClick={() => navigate("rules")}>View all rules <span aria-hidden="true">→</span></button></div>
      {rules.length ? <div className="table-wrap"><table><thead><tr><th>Rule</th><th>Status</th><th>Priority</th><th>Version</th><th>Updated</th></tr></thead>
        <tbody>{rules.slice(0, 5).map(rule => <tr key={rule.id} onClick={() => navigate("rules", rule.id)} tabIndex="0" onKeyDown={event => event.key === "Enter" && navigate("rules", rule.id)}>
          <td><strong>{rule.name}</strong><small>{conditionDescription(rule.definition?.match)}</small></td>
          <td><span className={"status-pill " + (rule.enabled ? "enabled" : "disabled")}>{rule.enabled ? "Enabled" : "Disabled"}</span></td>
          <td>{rule.priority}</td><td>v{rule.version}</td><td>{displayTime(rule.updatedAt)}</td>
        </tr>)}</tbody></table></div> : <Empty title="No rules created yet" text="Create a rule to start matching incoming events." action={createRule} />}
    </section>
  </div>;
}

function Empty({ title, text, action, actionLabel = "Create rule" }) {
  return <div className="empty-state"><span className="empty-icon"><Inbox size={19} aria-hidden="true" /></span><h3>{title}</h3><p>{text}</p>
    {action && <button className="secondary-button" onClick={action}>{actionLabel}</button>}</div>;
}

function RuleDetails({ rule, status }) {
  const definition = rule.definition || {};
  return <aside className="detail-panel">
    <div className="detail-eyebrow">RULE · VERSION {rule.version}</div><h2>{rule.name}</h2>
    <span className={"status-pill " + (rule.enabled ? "enabled" : "disabled")}>{rule.enabled ? "Enabled" : "Disabled"}</span>
    <p>Engine · {status ? "v" + status.version + " " + status.state : "Awaiting snapshot"}{status?.reason && " · " + status.reason}</p>
    <section><h3>When this rule matches</h3><p className="condition-text">{conditionDescription(definition.match)}</p></section>
    <section><h3>Alert settings</h3><dl className="detail-list"><div><dt>Severity</dt><dd>{definition.severity || "medium"}</dd></div>
      <div><dt>Priority</dt><dd>{rule.priority}</dd></div><div><dt>Title</dt><dd>{definition.title || rule.name}</dd></div>
      <div><dt>Runbook</dt><dd>{rule.runbookId || "Automatic assignment"}</dd></div>
      <div><dt>Updated</dt><dd>{displayTime(rule.updatedAt)}</dd></div></dl></section>
  </aside>;
}

function RulesPage({ rules, loading, onCreate, selectedId, onSelect }) {
  const [snapshot, setSnapshot] = useState(null);
  const [offline, setOffline] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    let timer;
    async function poll() {
      try {
        const response = await fetch("/api/engine/snapshot", { signal: controller.signal, cache: "no-store" });
        if (!response.ok) throw new Error("Engine unavailable");
        const data = await response.json();
        if (!Array.isArray(data)) throw new Error("Invalid snapshot");
        if (!controller.signal.aborted) { setSnapshot(data); setOffline(false); }
      } catch {
        if (!controller.signal.aborted) { setOffline(true); setSnapshot(null); }
      } finally {
        if (!controller.signal.aborted) timer = setTimeout(poll, 5000);
      }
    }
    void poll();
    return () => { controller.abort(); clearTimeout(timer); };
  }, []);
  const [query, setQuery] = useState("");
  const shown = useMemo(() => rules.filter(rule => rule.name.toLowerCase().includes(query.toLowerCase())), [rules, query]);
  const selected = rules.find(rule => rule.id === selectedId) || shown[0] || null;
  return <div className="page-content">
    <div className="page-heading"><div><p className="eyebrow">CONTROL</p><h1>Rules</h1><p className="subtitle">Create, inspect, and manage event matching rules.</p></div>
      <button className="primary-button" onClick={onCreate}><Plus size={16} aria-hidden="true" /> Create rule</button></div>
    {offline && <p role="status">Engine snapshot unavailable.</p>}
    {snapshot?.filter(item => item.state === "skipped").map(item => <p className="warning-banner" key={item.ruleId}>
      Skipped rule {rules.find(rule => rule.id === item.ruleId)?.name || item.ruleId} · v{item.version} · {item.reason}
    </p>)}
    <div className="rule-workspace">
      <section className="rule-list"><div className="list-heading"><h2>Rule library <span>{rules.length}</span></h2>
        <label className="search-box"><Search size={14} aria-hidden="true" /><input aria-label="Search rules" placeholder="Search rules" value={query} onChange={event => setQuery(event.target.value)} /></label></div>
        {loading ? <p className="loading">Loading rules…</p> : shown.map(rule => <button className={"rule-list-item " + (selected?.id === rule.id ? "selected" : "")} key={rule.id} onClick={() => onSelect(rule.id)}>
          <span className="rule-list-copy"><strong>{rule.name}</strong><small>Priority {rule.priority} · Version {rule.version}</small></span>
          <span className={"status-dot " + (rule.enabled ? "enabled" : "disabled")} aria-label={rule.enabled ? "Enabled" : "Disabled"} />
        </button>)}
        {!loading && shown.length === 0 && <Empty title="No matching rules" text={query ? "Try another search." : "Create your first rule."} action={query ? undefined : onCreate} />}
      </section>
      {selected && <RuleDetails rule={selected} status={snapshot?.find(item => item.ruleId === selected.id)} />}
    </div>
  </div>;
}

function ActivityPage({ type, items }) {
  const [selected, setSelected] = useState(null);
  const current = items.find(item => item.streamId === selected || item.alertId === selected) || items[0];
  const isMessage = type === "messages";
  const title = item => isMessage
    ? item.envelope?.payload?.type || item.envelope?.payload?.eventType || item.envelope?.eventId || "Event message"
    : item.title || "Alert";
  const key = item => isMessage ? item.streamId : item.alertId || item.eventId;
  return <div className="page-content"><div className="page-heading"><div><p className="eyebrow">MONITORING</p><h1>{type[0].toUpperCase() + type.slice(1)}</h1><p className="subtitle">Latest records from the alerting system.</p></div></div>
    <div className="activity-workspace"><div className="activity-list">{items.map(item => <button className="activity-item" key={key(item)} onClick={() => setSelected(key(item))}>
      <strong>{title(item)}</strong><small>{displayTime(isMessage ? item.envelope?.receivedAt : item.occurredAt)}</small></button>)}
      {!items.length && <Empty title={"No " + type + " yet"} text="New records will appear here when available." />}</div>
      {current && <div className="activity-detail"><h2>{title(current)}</h2><pre>{JSON.stringify(isMessage ? current.envelope : current, null, 2)}</pre></div>}
    </div>
  </div>;
}

function RunbooksPage({ items }) {
  return <div className="page-content"><div className="page-heading"><div><p className="eyebrow">CONTROL</p><h1>Runbooks</h1><p className="subtitle">Operational guidance available to alert routes.</p></div></div>
    {items.length ? <div className="runbook-grid">{items.map(book => <article className="runbook-card" key={book.id}><div className="section-heading"><h2>{book.name}</h2><span className={"status-pill " + (book.enabled ? "enabled" : "disabled")}>{book.enabled ? "Enabled" : "Disabled"}</span></div>
      <small>{book.format || "Runbook"} · Updated {displayTime(book.updatedAt)}</small><pre>{book.content}</pre></article>)}</div>
      : <Empty title="No runbooks available" text="Configured runbooks will appear here." />}</div>;
}

export default function App() {
  const [active, setActive] = useState("overview");
  const [feeds, setFeeds] = useState(Object.fromEntries(FEEDS.map(name => [name, { items: [], loading: true, error: "" }])));
  const [creating, setCreating] = useState(false);
  const [selectedRule, setSelectedRule] = useState(null);
  const [notice, setNotice] = useState("");

  const refresh = useCallback(async () => {
    const results = await Promise.all(FEEDS.map(async name => {
      try { return { name, items: await fetchFeed(name), error: "" }; }
      catch (error) { return { name, items: [], error: error.message }; }
    }));
    setFeeds(Object.fromEntries(results.map(result => [result.name, { items: result.items, loading: false, error: result.error }])));
  }, []);
  useEffect(() => { void refresh(); }, [refresh]);
  function navigate(page, id) { setCreating(false); setActive(page); if (id) setSelectedRule(id); }
  function created() {
    setCreating(false);
    setNotice("Rule created and saved in Control.");
    setActive("rules");
    void refresh();
  }

  const rules = feeds.rules.items;
  return <div className="app-shell">
    <Sidebar active={active} onNavigate={navigate} ruleCount={rules.length} />
    <main className="main-area">
      <header className="topbar"><div className="breadcrumb">Alerting <span>/</span> {creating ? "Create rule" : active[0].toUpperCase() + active.slice(1)}</div>
      <div className="topbar-right"><span className="environment"><i /> Demo environment</span><button className="icon-button" aria-label="Refresh data" onClick={refresh}><RefreshCw size={15} aria-hidden="true" /></button></div>
      </header>
      {notice && <div className="success-banner" role="status">{notice}<button aria-label="Dismiss" onClick={() => setNotice("")}>×</button></div>}
      {Object.values(feeds).some(feed => feed.error) && <div className="warning-banner" role="alert">
        Control data could not be loaded. Verify that the backend is running, then <button className="text-button" onClick={refresh}>retry</button>.
      </div>}
      {creating ? <RuleBuilder runbooks={feeds.runbooks.items} onCancel={() => setCreating(false)} onCreated={created} />
        : active === "overview" ? <Overview feeds={feeds} navigate={navigate} createRule={() => setCreating(true)} />
          : active === "rules" ? <RulesPage rules={rules} loading={feeds.rules.loading} onCreate={() => setCreating(true)} selectedId={selectedRule} onSelect={setSelectedRule} />
            : active === "runbooks" ? <RunbooksPage items={feeds.runbooks.items} />
              : <ActivityPage type={active} items={feeds[active]?.items || []} />}
      <footer className="app-footer">Goldman Alerting · Control service</footer>
    </main>
  </div>;
}
