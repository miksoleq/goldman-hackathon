import { useCallback, useEffect, useMemo, useState } from "react";
import { Bell, BookOpen, Grid2X2, Inbox, ListFilter, Plus, RefreshCw, Search, ShieldCheck, BellRing, Users } from "lucide-react";
import { AccountMenu, LoginForm, useAuth } from "../../web-shared/Auth.jsx";
import RunbookEditor from "./RunbookEditor.jsx";
import TeamsPage from "./TeamsPage.jsx";
import RuleBuilder from "./RuleBuilder.jsx";
import { OPERATORS } from "./ruleModel.js";
import { fetchFeed } from "./api.js";

const NAV = [
  ["overview", "Overview", Grid2X2],
  ["rules", "Rules", ListFilter],
  ["alerts", "Alerts", Bell],
  ["runbooks", "Runbooks", BookOpen],
  ["teams", "Teams", Users]
];
const FEEDS = ["rules", "alerts", "runbooks"];

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
  const { user } = useAuth();
  const rules = feeds.rules.items || [];
  const alerts = feeds.alerts.items || [];
  const activeRules = rules.filter(rule => rule.enabled).length;
  return <div className="page-content">
    <div className="page-heading"><div><p className="eyebrow">SYSTEM OVERVIEW</p><h1>Good afternoon</h1><p className="subtitle">Manage event rules and monitor alert activity.</p></div>
      <button className="primary-button" onClick={createRule}><Plus size={16} aria-hidden="true" /> {user ? "Create rule" : "Log in to create a rule"}</button></div>
    <div className="stats-grid">
      <Stat label="Active rules" value={activeRules} note={rules.length + " total rules"} icon={ShieldCheck} />
      <Stat label="Alerts generated" value={alerts.length} note="Current inbox" icon={BellRing} />
      <Stat label="Runbooks" value={(feeds.runbooks.items || []).filter(book => book.enabled).length} note="Available for routing" icon={BookOpen} />
    </div>
    <section className="content-section">
      <div className="section-heading"><div><h2>Recent rules</h2><p>Rules currently configured in Control.</p></div><button className="text-button" onClick={() => navigate("rules")}>View all rules <span aria-hidden="true">→</span></button></div>
      {rules.length ? <div className="table-wrap"><table><thead><tr><th>Rule</th><th>Owner</th><th>Status</th><th>Priority</th><th>Version</th><th>Updated</th></tr></thead>
        <tbody>{rules.slice(0, 5).map(rule => <tr key={rule.id} onClick={() => navigate("rules", rule.id)} tabIndex="0" onKeyDown={event => event.key === "Enter" && navigate("rules", rule.id)}>
          <td><strong>{rule.name}</strong><small>{conditionDescription(rule.definition?.match)}</small></td>
          <td>{rule.createdBy?.username || "System"}</td>
          <td><span className={"status-pill " + (rule.enabled ? "enabled" : "disabled")}>{rule.enabled ? "Enabled" : "Disabled"}</span></td>
          <td>{rule.priority}</td><td>v{rule.version}</td><td>{displayTime(rule.updatedAt)}</td>
        </tr>)}</tbody></table></div> : <Empty title="No rules created yet" text="Create a rule to start matching incoming events." action={createRule} actionLabel={user ? "Create rule" : "Log in to create a rule"} />}
    </section>
  </div>;
}

function Empty({ title, text, action, actionLabel }) {
  return <div className="empty-state"><span className="empty-icon"><Inbox size={19} aria-hidden="true" /></span><h3>{title}</h3><p>{text}</p>
    {action && <button className="secondary-button" onClick={action}>{actionLabel || "Create rule"}</button>}</div>;
}

function RuleDetails({ rule }) {
  const definition = rule.definition || {};
  return <aside className="detail-panel">
    <div className="detail-eyebrow">RULE · VERSION {rule.version}</div><h2>{rule.name}</h2>
    <span className={"status-pill " + (rule.enabled ? "enabled" : "disabled")}>{rule.enabled ? "Enabled" : "Disabled"}</span>
    <section><h3>When this rule matches</h3><p className="condition-text">{conditionDescription(definition.match)}</p></section>
    <p>Owner: {rule.createdBy?.username || "System"}</p>
    <section><h3>Alert settings</h3><dl className="detail-list"><div><dt>Severity</dt><dd>{definition.severity || "medium"}</dd></div>
      <div><dt>Priority</dt><dd>{rule.priority}</dd></div><div><dt>Title</dt><dd>{definition.title || rule.name}</dd></div>
      <div><dt>Runbook</dt><dd>{rule.runbookId || "Automatic assignment"}</dd></div>
      <div><dt>Updated</dt><dd>{displayTime(rule.updatedAt)}</dd></div></dl></section>
  </aside>;
}

function RulesPage({ rules, loading, onCreate, selectedId, onSelect }) {
  const { user } = useAuth();
  const [query, setQuery] = useState("");
  const shown = useMemo(() => rules.filter(rule => rule.name.toLowerCase().includes(query.toLowerCase())), [rules, query]);
  const selected = rules.find(rule => rule.id === selectedId) || shown[0] || null;
  return <div className="page-content">
    <div className="page-heading"><div><p className="eyebrow">CONTROL</p><h1>Rules</h1><p className="subtitle">Create, inspect, and manage event matching rules.</p></div>
      <button className="primary-button" onClick={onCreate}><Plus size={16} aria-hidden="true" /> {user ? "Create rule" : "Log in to create a rule"}</button></div>
    <div className="rule-workspace">
      <section className="rule-list"><div className="list-heading"><h2>Rule library <span>{rules.length}</span></h2>
        <label className="search-box"><Search size={14} aria-hidden="true" /><input aria-label="Search rules" placeholder="Search rules" value={query} onChange={event => setQuery(event.target.value)} /></label></div>
        {loading ? <p className="loading">Loading rules…</p> : shown.map(rule => <button className={"rule-list-item " + (selected?.id === rule.id ? "selected" : "")} key={rule.id} onClick={() => onSelect(rule.id)}>
          <span className="rule-list-copy"><strong>{rule.name}</strong><small>Owner: {rule.createdBy?.username || "System"} · Priority {rule.priority} · Version {rule.version}</small></span>
          <span className={"status-dot " + (rule.enabled ? "enabled" : "disabled")} aria-label={rule.enabled ? "Enabled" : "Disabled"} />
        </button>)}
        {!loading && shown.length === 0 && <Empty title="No matching rules" text={query ? "Try another search." : "Create your first rule."} action={query ? undefined : onCreate} actionLabel={user ? "Create rule" : "Log in to create a rule"} />}
      </section>
      {selected && <RuleDetails rule={selected} />}
    </div>
  </div>;
}

function ActivityPage({ type, items, rules }) {
  const [selected, setSelected] = useState(null);
  const [query, setQuery] = useState("");
  const [severityFilter, setSeverityFilter] = useState("all");
  const [statusFilter, setStatusFilter] = useState("all");
  const filtered = items.filter(item => {
    const ruleName = rules.find(rule => rule.id === item.ruleId)?.name || "";
    const searchText = [item.title, item.body, ruleName].filter(Boolean).join(" ").toLowerCase();
    const attention = (item.actions || []).some(action => ["failed", "unknown", "unavailable", "pending_approval"].includes(action.status));
    return (severityFilter === "all" || String(item.severity || "info").toLowerCase() === severityFilter)
      && (statusFilter === "all" || (statusFilter === "attention") === attention)
      && searchText.includes(query.trim().toLowerCase());
  });
  const current = filtered.find(item => item.alertId === selected) || filtered[0];
  return <div className="page-content"><div className="page-heading"><div><p className="eyebrow">MONITORING</p><h1>Alerts</h1><p className="subtitle">Review recent alerts and their runbook activity.</p></div></div>
    <div className="alert-filters"><label>Search alerts<input aria-label="Search alerts" value={query} onChange={event => setQuery(event.target.value)} placeholder="Title, message, or rule" /></label>
      <label>Severity<select aria-label="Severity filter" value={severityFilter} onChange={event => setSeverityFilter(event.target.value)}><option value="all">All severities</option>{["critical", "high", "medium", "low", "info"].map(value => <option key={value} value={value}>{value[0].toUpperCase() + value.slice(1)}</option>)}</select></label>
      <label>Status<select aria-label="Alert status filter" value={statusFilter} onChange={event => setStatusFilter(event.target.value)}><option value="all">All alerts</option><option value="attention">Needs attention</option><option value="clear">No action issues</option></select></label>
      <span>{filtered.length} of {items.length} alerts</span></div>
    <div className="activity-workspace"><div className="activity-list">{filtered.map(item => {
      const id = item.alertId;
      const severity = String(item.severity || "info").toLowerCase();
      const rule = rules.find(candidate => candidate.id === item.ruleId);
      return <button className="activity-item alert-preview-item" key={id} aria-pressed={(current?.alertId) === id} onClick={() => setSelected(id)}>
        <div className="alert-preview"><div className="preview-content">
          <span className={"preview-severity severity-" + severity}><span className="severity-dot" />{severity}</span>
          <strong>{item.title || "Alert title"}</strong><p>{item.body || "No alert message."}</p>
          <small>{displayTime(item.occurredAt)}{rule ? " · " + rule.name : ""}</small>
        </div></div>
      </button>;
    })}
      {!filtered.length && <Empty title={items.length ? "No matching alerts" : "No alerts yet"} text={items.length ? "Adjust the search or filters." : "New alerts will appear here when available."} />}</div>
      {current && <div className="activity-detail"><div className="alert-detail-header"><div><p className="eyebrow">ALERT · {String(current.severity || "info").toUpperCase()}</p><h2>{current.title || "Alert"}</h2></div><span className="status-pill enabled">{displayTime(current.occurredAt)}</span></div>
        {current.body && <p>{current.body}</p>}<dl className="detail-list"><div><dt>Rule</dt><dd>{rules.find(rule => rule.id === current.ruleId)?.name || current.ruleId || "Unavailable"}</dd></div><div><dt>Recipients</dt><dd>{(current.teamIds || []).length} teams · {(current.userIds || []).length} users</dd></div><div><dt>Runbook</dt><dd>{current.runbook?.name || "Not assigned"}</dd></div></dl>
        {current.runbook?.content && <><h3>Instructions</h3><pre>{current.runbook.content}</pre></>}{current.actions?.length > 0 && <RunbookActions key={current.alertId} alert={current} onRefresh={() => {}} />}</div>}
    </div>
  </div>;
}

function RunbooksPage({ items, open }) {
  const { user } = useAuth();
  return <div className="page-content"><div className="page-heading"><div><p className="eyebrow">CONTROL</p><h1>Runbooks</h1><p className="subtitle">Operational guidance available to alert routes.</p></div><button className="primary-button" onClick={() => open("new")}>{user ? "Create runbook" : "Log in to create a runbook"}</button></div>
    {items.length ? <div className="runbook-grid">{items.map(book => <article className="runbook-card" key={book.id}><div className="section-heading"><h2>{book.name}</h2><span className={"status-pill " + (book.enabled ? "enabled" : "disabled")}>{book.enabled ? "Enabled" : "Disabled"}</span></div>
      <small>{book.format || "Runbook"} · Updated {displayTime(book.updatedAt)}</small><pre>{book.content}</pre><button className="text-button edit-runbook" aria-label={"Edit " + book.name} onClick={() => open(book.id)}>Edit</button></article>)}</div>
      : <Empty title="No runbooks available" text="Configured runbooks will appear here." />}</div>;
}

export default function App() {
  const [active, setActive] = useState("overview");
  const [feeds, setFeeds] = useState(Object.fromEntries(FEEDS.map(name => [name, { items: [], loading: true, error: "" }])));
  const { user, ready } = useAuth();
  const creationRoute = () => ["#/rules/new", "#/login?next=rules/new"].includes(window.location.hash)
    || window.location.pathname === "/rules/new";
  const [creating, updateCreating] = useState(creationRoute);
  useEffect(() => {
    const onRoute = () => updateCreating(creationRoute());
    window.addEventListener("hashchange", onRoute);
    window.addEventListener("popstate", onRoute);
    return () => { window.removeEventListener("hashchange", onRoute); window.removeEventListener("popstate", onRoute); };
  }, []);
  useEffect(() => {
    if (!ready || !creating) return;
    const target = user ? "/#/rules/new" : "/#/login?next=rules/new";
    if (window.location.pathname + window.location.hash !== target) window.history.replaceState(null, "", target);
  }, [ready, creating, user]);
  function setCreating(open) {
    if (creationRoute() !== open) window.history.pushState(null, "", open ? "/#/rules/new" : "/");
    updateCreating(open);
  }
  const [bookEditor, setBookEditor] = useState(null);
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
  function navigate(page, id) { setBookEditor(null); setCreating(false); setActive(page); if (id) setSelectedRule(id); }
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
      <div className="topbar-right"><AccountMenu /><span className="environment"><i /> Demo environment</span><button className="icon-button" aria-label="Refresh data" onClick={refresh}><RefreshCw size={15} aria-hidden="true" /></button></div>
      </header>
      {notice && <div className="success-banner" role="status">{notice}<button aria-label="Dismiss" onClick={() => setNotice("")}>×</button></div>}
      {Object.values(feeds).some(feed => feed.error) && <div className="warning-banner" role="alert">
        Control data could not be loaded. Verify that the backend is running, then <button className="text-button" onClick={refresh}>retry</button>.
      </div>}
      {(creating || bookEditor || active === "teams") && !ready ? <p className="page-content">Checking session…</p>
        : (creating || bookEditor || active === "teams") && !user ? <section className="page-content"><LoginForm title={creating ? "Log in to create a rule" : bookEditor ? "Log in to edit runbooks" : "Log in to view teams"} /></section>
        : creating ? <RuleBuilder runbooks={feeds.runbooks.items} onCancel={() => setCreating(false)} onCreated={created} />
        : bookEditor ? <RunbookEditor key={bookEditor} id={bookEditor === "new" ? null : bookEditor} onCancel={() => setBookEditor(null)}
          onSaved={book => { setNotice("Saved runbook “" + book.name + "”."); setBookEditor(null); void refresh(); }} />
        : active === "teams" ? <TeamsPage key={user.id + user.role} />
        : active === "overview" ? <Overview feeds={feeds} navigate={navigate} createRule={() => setCreating(true)} />
          : active === "rules" ? <RulesPage rules={rules} loading={feeds.rules.loading} onCreate={() => setCreating(true)} selectedId={selectedRule} onSelect={setSelectedRule} />
            : active === "runbooks" ? <RunbooksPage items={feeds.runbooks.items} open={setBookEditor} />
              : <ActivityPage type={active} items={feeds[active]?.items || []} rules={rules} />}
    </main>
  </div>;
}
