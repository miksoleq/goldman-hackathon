import { useState } from "react";
import { conditionSummary, createGroup, createLeaf, operatorsForType, OPERATORS, toRulePayload, validateDraft } from "./ruleModel.js";
import { createRule } from "./api.js";

const INITIAL_DRAFT = () => ({
  name: "", enabled: true, priority: 100, match: createGroup(), teams: "", users: "",
  severity: "medium", title: "", body: "", runbook: "auto", dedupKeys: "", windowSec: 60,
  stopProcessing: false
});

function ConditionEditor({ node, onChange, onRemove, level = 0 }) {
  if (node.kind === "group") return <fieldset className="condition-group" style={{ "--nesting": level }}>
    <legend>{level === 0 ? "Match conditions" : "Nested group"}</legend>
    <div className="group-toolbar">
      <label>Match
        <select value={node.operator} onChange={event => onChange({ ...node, operator: event.target.value })}>
          <option value="all">All conditions · AND</option>
          <option value="any">Any condition · OR</option>
        </select>
      </label>
      {onRemove && <button className="text-button danger-text" type="button" onClick={onRemove}>Remove group</button>}
    </div>
    <div className="group-children">
      {node.children.map((child, index) => <ConditionEditor key={index} node={child} level={level + 1}
        onChange={updated => onChange({ ...node, children: node.children.map((item, i) => i === index ? updated : item) })}
        onRemove={() => onChange({ ...node, children: node.children.filter((_, i) => i !== index) })} />)}
    </div>
    <div className="condition-actions">
      <button className="secondary-button" type="button" onClick={() => onChange({ ...node, children: [...node.children, createLeaf()] })}>Add condition</button>
      <button className="secondary-button" type="button" onClick={() => onChange({ ...node, children: [...node.children, createGroup()] })}>Add nested group</button>
      <button className="secondary-button" type="button" onClick={() => onChange({ ...node, children: [...node.children, { kind: "not", child: createLeaf() }] })}>Add negated condition</button>
    </div>
  </fieldset>;
  if (node.kind === "not") return <div className="condition-negation">
    <div className="group-toolbar"><strong>Does not match</strong><span className="logic-tag">NOT</span>
      <button className="text-button danger-text" type="button" onClick={onRemove}>Remove</button></div>
    <ConditionEditor node={node.child} level={level + 1} onChange={child => onChange({ ...node, child })} />
  </div>;

  const operator = OPERATORS.find(item => item[0] === node.op) || OPERATORS[1];
  const valueKind = operator[2];
  return <div className="condition-leaf">
    <label>Condition kind<select value={node.op === "jsonSchema" ? "structure" : "field"} onChange={event => {
      if (event.target.value === "structure") onChange({ ...node, scope: "payload", path: "", op: "jsonSchema", ignoreCase: false, example: node.example || "" });
      else onChange({ ...node, scope: "payload", path: "", op: "eq", valueType: "string", value: "", ignoreCase: false });
    }}><option value="field">Field condition</option><option value="structure">JSON structure</option></select></label>
    <div className="condition-fields">
      {valueKind !== "schema" && <>
      <label>Look in
        <select value={node.scope} onChange={event => onChange({ ...node, scope: event.target.value, path: "", valueType: "string", op: "eq", value: "", ignoreCase: false })}>
          <option value="payload">Event data</option>
          <option value="meta">Ingestion metadata</option>
        </select>
      </label>
      <label>Field name
        {node.scope === "meta"
          ? <select required value={node.path} onChange={event => {
            const path = event.target.value;
            const valueType = path === "receivedAt" ? "number" : "string";
            const op = operatorsForType(valueType).some(([candidate]) => candidate === node.op) ? node.op : "eq";
            onChange({ ...node, path, valueType, op, value: "", ignoreCase: false });
          }}>
            <option value="">Select metadata field</option>
            {["source", "adapter", "eventId", "receivedAt", "contentType"].map(field => <option key={field}>{field}</option>)}
          </select>
          : <input required={node.op !== "jsonSchema"} value={node.path} placeholder="amount" onChange={event => onChange({ ...node, path: event.target.value })} />}
      </label>
      {node.scope !== "meta" && <label>Field type<select value={node.valueType} onChange={event => {
        const valueType = event.target.value;
        const allowed = operatorsForType(valueType);
        onChange({ ...node, valueType, op: allowed.some(([op]) => op === node.op) ? node.op : "exists", value: valueType === "boolean" ? "true" : "", ignoreCase: false });
      }}>{["string", "number", "integer", "boolean", "null", "object", "array"].map(type => <option key={type}>{type}</option>)}</select></label>}
      <label>Condition
        <select value={node.op} onChange={event => {
          const next = OPERATORS.find(item => item[0] === event.target.value);
          onChange({ ...node, op: event.target.value, value: "", ignoreCase: false, ...(next[2] === "schema" ? { example: node.example || "" } : {}) });
        }}>
          {operatorsForType(node.valueType).filter(([value]) => value !== "jsonSchema").map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      </>}
      {valueKind === "boolean" && <label>Field is present
        <select value={node.value || "true"} onChange={event => onChange({ ...node, value: event.target.value })}>
          <option value="true">Yes</option><option value="false">No</option>
        </select>
      </label>}
      {valueKind === "number" && <label>Number
        <input required type="number" step={node.op === "gt" || node.op === "gte" || node.op === "lt" || node.op === "lte" ? "any" : "1"}
          value={node.value} onChange={event => onChange({ ...node, value: event.target.value })} />
      </label>}
      {valueKind === "text" && <label>{node.op === "in" || node.op === "nin" ? "Values, comma separated" : "Text value"}
        <input required value={node.value} onChange={event => onChange({ ...node, value: event.target.value })} />
      </label>}
      {(valueKind === "typed" || valueKind === "list") && <>
        <label>{node.op === "in" || node.op === "nin" ? "Values, comma separated" : "Value"}
        {node.valueType === "boolean" && valueKind !== "list" ? <select value={node.value || "true"} onChange={event => onChange({ ...node, value: event.target.value })}><option>true</option><option>false</option></select> : node.valueType === "null" ? <span>null</span> : <input required value={node.value} onChange={event => onChange({ ...node, value: event.target.value })} />}
      </label></>}
      {valueKind === "regex" && <label>RE2 pattern
        <input required maxLength="500" value={node.value} placeholder="^TRD-[0-9]+$"
          onChange={event => onChange({ ...node, value: event.target.value })} />
      </label>}
      {valueKind === "schema" && <div className="schema-builder">
        <p>Paste an example of the entire event JSON. Object fields become required and additional fields are rejected. Array items must have the same structure.</p>
        <label>Example JSON<textarea required rows="8" value={node.example || ""} placeholder={'{"amount": 100, "currency": "USD"}'} onChange={event => onChange({ ...node, example: event.target.value })} /></label>
      </div>}
    </div>
    <div className="leaf-footer">
      {["eq", "ne", "in", "nin", "contains", "startsWith", "endsWith", "regex"].includes(node.op) &&
        <label className="inline-check"><input type="checkbox" checked={Boolean(node.ignoreCase)}
          onChange={event => onChange({ ...node, ignoreCase: event.target.checked })} /> Ignore letter case</label>}
      {onRemove && <button className="text-button danger-text" type="button" onClick={onRemove}>Remove condition</button>}
    </div>
  </div>;
}

export default function RuleBuilder({ runbooks, onCancel, onCreated }) {
  const [draft, setDraft] = useState(INITIAL_DRAFT);
  const [step, setStep] = useState(0);
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);

  function update(values) { setDraft(current => ({ ...current, ...values })); }

  async function submit(event) {
    event.preventDefault();
    setError("");
    if (step === 0) {
      const problem = validateDraft(draft, "trigger");
      if (problem) { setError(problem); return; }
      setStep(1);
      return;
    }
    if (step === 1) { const problem = validateDraft(draft); if (problem) { setError(problem); return; } setStep(2); return; }
    setSaving(true);
    try {
      await createRule(toRulePayload(draft));
      onCreated();
    } catch (cause) {
      setError(cause.message || "Could not reach Control. Check the connection and try again.");
    } finally {
      setSaving(false);
    }
  }

  return <form className="rule-builder" onSubmit={submit} noValidate>
    <div className="builder-top">
      <div><p className="eyebrow">RULE CREATION · STEP {step + 1} OF 3</p>
        <h2>{["Set the trigger", "Configure the alert", "Review and create"][step]}</h2></div>
      <button className="text-button" type="button" onClick={onCancel}>Cancel</button>
    </div>
    {step === 0 && <>
      <label>Rule name<input required maxLength="200" value={draft.name} onChange={event => update({ name: event.target.value })} /></label>
      <ConditionEditor node={draft.match} onChange={match => update({ match })} />
      <p className="form-hint">Event data comes from the submitted JSON. Ingestion metadata is added by Engine and includes source, adapter, event ID, receive time, and content type.</p>
    </>}
    {step === 1 && <div className="form-grid">
      <label>Teams <span className="hint-inline">Enter exact Control team names, separated by commas</span>
        <input value={draft.teams} onChange={event => update({ teams: event.target.value })} placeholder="Market Risk" /></label>
      <label>Users <span className="hint-inline">Enter exact Control usernames, separated by commas</span>
        <input value={draft.users} onChange={event => update({ users: event.target.value })} placeholder="analyst" /></label>
      <label>Severity<select value={draft.severity} onChange={event => update({ severity: event.target.value })}>
        {["low", "medium", "high", "critical"].map(value => <option key={value}>{value}</option>)}</select></label>
      <label>Alert title<input maxLength="500" value={draft.title} onChange={event => update({ title: event.target.value })} placeholder={draft.name || "Defaults to the rule name"} /></label>
      <label className="span-all">Alert message<input maxLength="5000" value={draft.body} onChange={event => update({ body: event.target.value })} placeholder="Optional. Use {{payload.field}} to include event values." /></label>
      <label>Runbook<select value={draft.runbook} onChange={event => update({ runbook: event.target.value })}>
        <option value="auto">Use automatic assignment</option><option value="none">No runbook</option>
        {runbooks.filter(item => item.enabled).map(item => <option key={item.id} value={item.name}>{item.name}</option>)}
      </select></label>
      <label>Priority<input type="number" min="-2147483648" max="2147483647" step="1" value={draft.priority} onChange={event => update({ priority: event.target.value })} /></label>
      <label>Deduplication window (seconds)<input type="number" min="1" max="86400" step="1" value={draft.windowSec} onChange={event => update({ windowSec: event.target.value })} /></label>
      <label>Deduplication fields <span className="hint-inline">Comma separated, optional</span>
        <input value={draft.dedupKeys} onChange={event => update({ dedupKeys: event.target.value })} placeholder="payload.transactionId" /></label>
      <label className="inline-check span-all"><input type="checkbox" checked={draft.enabled} onChange={event => update({ enabled: event.target.checked })} /> Enable this rule after creation</label>
      <label className="inline-check span-all"><input type="checkbox" checked={draft.stopProcessing} onChange={event => update({ stopProcessing: event.target.checked })} /> Stop processing lower-priority rules after a match</label>
    </div>}
    {step === 2 && <div className="review">
      <h3>{draft.name}</h3>
      <p>{draft.enabled ? "Enabled immediately" : "Saved disabled"} · Priority {draft.priority} · {draft.severity} severity</p>
      <p>Recipients: {[...draft.teams.split(","), ...draft.users.split(",")].map(item => item.trim()).filter(Boolean).join(", ") || "None"}</p>
      <p>Runbook: {draft.runbook}</p>
      <p>Match logic · <strong>{conditionSummary(draft.match)}</strong></p>
      <p>Deduplicate identical alerts for {draft.windowSec} seconds{draft.dedupKeys.trim() ? " using " + draft.dedupKeys : ""}.</p>
      {draft.stopProcessing && <p>Stop evaluating lower-priority rules after this match.</p>}
    </div>}
    {error && <div className="error-banner" role="alert">{error}</div>}
    <div className="builder-actions">
      {step > 0 && <button className="secondary-button" type="button" onClick={() => setStep(step - 1)}>Back</button>}
      <button className="primary-button" type="submit" disabled={saving}>{saving ? "Creating…" : step === 2 ? "Create rule" : "Continue"}</button>
    </div>
  </form>;
}
