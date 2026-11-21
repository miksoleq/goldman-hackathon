import { useEffect, useRef, useState } from "react";
import "./alert-settings.css";

const severities = ["critical", "high", "medium", "low", "info"];

function RecipientPicker({ label, endpoint, value, onChange, onBlur }) {
  const [items, setItems] = useState([]);
  const [query, setQuery] = useState("");
  const [error, setError] = useState("");
  const [open, setOpen] = useState(false);
  const selected = value.split(",").map(item => item.trim()).filter(Boolean);
  useEffect(() => {
    const controller = new AbortController();
    fetch(endpoint, { headers: { Accept: "application/json" }, signal: controller.signal })
      .then(response => {
        if (!response.ok) throw new Error();
        return response.json();
      })
      .then(data => {
        if (!Array.isArray(data)) throw new Error();
        setItems(data.map(item => typeof item === "string" ? item : item.name || item.username).filter(Boolean));
      })
      .catch(error => { if (error.name !== "AbortError") setError("Directory unavailable. Enter an exact name and press Enter."); });
    return () => controller.abort();
  }, [endpoint]);
  const search = query.trim().toLowerCase();
  const options = search.length < 3 ? [] : items
    .filter(name => !selected.includes(name) && name.toLowerCase().includes(search))
    .slice(0, 10);
  function add(name) {
    const next = name.trim();
    if (next && !selected.includes(next)) onChange([...selected, next].join(","));
    setQuery("");
  }
  return <div className="recipient-picker">
    <label>{label}<input value={query} placeholder={"Search " + label.toLowerCase()} onFocus={() => setOpen(true)}
      onBlur={() => { setTimeout(() => setOpen(false), 150); onBlur(); }}
      onChange={event => { setQuery(event.target.value); setOpen(true); }}
      onKeyDown={event => {
        if (event.key === "Escape") setOpen(false);
        if (event.key === "Enter" && (options.length || (error && query.trim()))) {
          event.preventDefault();
          add(options[0] || query);
          setOpen(false);
        }
      }} aria-expanded={open} aria-autocomplete="list" role="combobox" /></label>
    {open && !error && search.length < 3 && <p className="field-help">Type at least 3 characters to see suggestions.</p>}
    {open && !error && search.length >= 3 && options.length === 0 && <p className="field-help">No matching recipients found.</p>}
    {open && options.length > 0 && <ul className="picker-options" role="listbox" aria-label={label + " results"}>
      {options.map(name => <li key={name} role="option" aria-selected="false">
        <button type="button" onMouseDown={event => event.preventDefault()} onClick={() => { add(name); setOpen(false); }}>{name}</button>
      </li>)}
    </ul>}
    {error && <p className="field-help">{error}</p>}
    <div className="chips">{selected.map(name => <span className="chip" key={name}>{name}<button type="button" aria-label={"Remove " + name} onClick={() => onChange(selected.filter(item => item !== name).join(","))}>×</button></span>)}</div>
  </div>;
}

function collectPaths(node) {
  if (node.kind === "group") return node.children.flatMap(collectPaths);
  if (node.kind === "not") return collectPaths(node.child);
  return node.path ? [node.scope + (node.path.startsWith("[") ? "" : ".") + node.path] : [];
}

export default function AlertSettings({ draft, update, runbooks, showRecipientError }) {
  const [touched, setTouched] = useState(false);
  const [keyInput, setKeyInput] = useState("");
  const [activeField, setActiveField] = useState("title");
  const titleRef = useRef(null);
  const bodyRef = useRef(null);
  const fields = draft.dedupKeys.split(",").map(value => value.trim()).filter(Boolean);
  const variables = [...new Set([...collectPaths(draft.match), "payload.service", "payload.error.code"])].slice(0, 6);
  const missing = !draft.teams.trim() && !draft.users.trim();
  function addKey(value) {
    const next = value.trim();
    if (next && !fields.includes(next)) update({ dedupKeys: [...fields, next].join(",") });
    setKeyInput("");
  }
  function insertVariable(path) {
    const ref = activeField === "title" ? titleRef : bodyRef;
    const input = ref.current;
    const variable = "{{" + path + "}}";
    const start = input?.selectionStart ?? draft[activeField].length;
    const end = input?.selectionEnd ?? start;
    update({ [activeField]: draft[activeField].slice(0, start) + variable + draft[activeField].slice(end) });
    requestAnimationFrame(() => { input?.focus(); input?.setSelectionRange(start + variable.length, start + variable.length); });
  }
  return <div className="alert-settings">
    <section className="settings-section">
      <div className="settings-heading"><h3>Recipients</h3><p>Choose who receives the alert.</p></div>
      <div className="settings-grid">
        <RecipientPicker label="Teams" endpoint="/api/teams" value={draft.teams} onChange={teams => update({ teams })} onBlur={() => setTouched(true)} />
        <RecipientPicker label="Users" endpoint="/api/users" value={draft.users} onChange={users => update({ users })} onBlur={() => setTouched(true)} />
      </div>
      {missing && (touched || showRecipientError) && <p className="field-error" role="alert">Add at least one team or user recipient.</p>}
    </section>
    <section className="settings-section">
      <div className="settings-heading"><h3>Alert content &amp; appearance</h3><p>Write the alert recipients will see.</p></div>
      <div className="settings-grid">
        <label>Severity<span className={"severity-select severity-" + draft.severity}><span className="severity-dot" /><select value={draft.severity} onChange={event => update({ severity: event.target.value })}>
          {severities.map(value => <option key={value} value={value}>{value[0].toUpperCase() + value.slice(1)}</option>)}</select></span></label>
        <label>Alert title<input ref={titleRef} maxLength="500" value={draft.title} onFocus={() => setActiveField("title")} onChange={event => update({ title: event.target.value })} placeholder={draft.name} />
          <span className="field-help">Supports variables such as {"{{payload.field}}"}.</span></label>
        <label className="span-all">Alert message<textarea ref={bodyRef} rows="4" maxLength="5000" value={draft.body}
          onFocus={() => setActiveField("body")} onChange={event => update({ body: event.target.value })} placeholder="Describe what happened and what needs attention." /></label>
      </div>
      <div className="variable-helper"><span>Insert into {activeField === "title" ? "title" : "message"}</span><div className="chips">
        {variables.map(path => <button key={path} type="button" className="variable-chip" onMouseDown={event => event.preventDefault()} onClick={() => insertVariable(path)}>{"{{" + path + "}}"}</button>)}
      </div></div>
      <div className="alert-preview"><span className="preview-label">ALERT PREVIEW</span><div className="preview-content">
        <span className={"preview-severity severity-" + draft.severity}><span className="severity-dot" />{draft.severity}</span>
        <strong>{draft.title || draft.name || "Alert title"}</strong><p>{draft.body || "Your alert message will appear here."}</p>
        <small>Template variables are shown as placeholders until an event is received.</small>
      </div></div>
    </section>
    <section className="settings-section">
      <div className="settings-heading"><h3>Response</h3><p>Choose guidance associated with this rule.</p></div>
      <label>Runbook<select value={draft.runbook} onChange={event => update({ runbook: event.target.value })}>
        <option value="auto">Use automatic assignment</option><option value="none">No runbook</option>
        {runbooks.filter(item => item.enabled).map(item => <option key={item.id} value={item.name}>{item.name}</option>)}
      </select><span className="field-help">Attach step-by-step instructions or automated actions shown when this alert fires.</span></label>
      <p className="field-help">New alerts include the instructions and actions from the assigned runbook.</p>
    </section>
    <details className="settings-section advanced-settings"><summary>Noise control &amp; advanced settings</summary>
      <div className="settings-heading"><h3>Deduplication</h3><p>Configure how duplicate events are identified within a time window.</p>
        <p>The first match opens the window. Repeated matches increase the alert count until the window expires.</p></div>
      <div className="settings-grid settings-align">
        <label>Deduplication window (seconds)<input type="number" min="1" max="86400" step="1" value={draft.windowSec} onChange={event => update({ windowSec: event.target.value })} />
          <span className="field-help">Time window from 1 to 86,400 seconds.</span></label>
        <div><label>Deduplication fields<input value={keyInput} onChange={event => setKeyInput(event.target.value)}
          onBlur={() => { if (keyInput.trim()) addKey(keyInput); }}
          onKeyDown={event => { if (event.key === "Enter" || event.key === ",") { event.preventDefault(); addKey(keyInput); } }} placeholder="Add a field and press Enter" />
          <span className="field-help">Optional. Fields used to identify duplicate events (e.g. payload.transactionId).</span></label>
          <div className="chips">{fields.map(field => <span className="chip" key={field}>{field}<button type="button" aria-label={"Remove " + field} onClick={() => update({ dedupKeys: fields.filter(item => item !== field).join(",") })}>×</button></span>)}</div>
        </div>
      </div>
      <label>Priority<input type="number" min="-2147483648" max="2147483647" step="1" value={draft.priority} onChange={event => update({ priority: event.target.value })} />
        <span className="field-help">Lower numbers come first. Ties are sorted by rule name.</span></label>
      <label className="inline-check"><input type="checkbox" checked={draft.enabled} onChange={event => update({ enabled: event.target.checked })} /> Enable this rule after creation</label>
      <label className="inline-check"><input type="checkbox" checked={draft.stopProcessing} onChange={event => update({ stopProcessing: event.target.checked })} /> Skip later rules when this rule matches</label>
    </details>
  </div>;
}
