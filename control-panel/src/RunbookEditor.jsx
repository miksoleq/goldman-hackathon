import { useEffect, useRef, useState } from "react";
import Markdown from "react-markdown";
import { loadRunbookEditor, saveRunbook } from "./api.js";
import { bookResource, draftFromBook, executionMode, formats, instructionError, moveAction, newAction, actionError } from "./runbookModel.js";

const steps = ["Instructions", "Actions", "Review & save"];

function ActionFields({ actions, types, onChange }) {
  const [type, setType] = useState("");
  function add() {
    const option = types.find(item => item.name === type);
    onChange([...actions, newAction(option)]);
    setType("");
  }
  function edit(index, patch) {
    onChange(actions.map((action, i) => i === index ? { ...action, ...patch } : action));
  }
  function updateParam(index, key, value) {
    onChange(actions.map((action, i) => i === index ? { ...action, params: { ...action.params, [key]: value } } : action));
  }
  return <>
    {actions.map((action, index) => {
      const option = types.find(item => item.name === action.type);
      return <fieldset className="runbook-step" key={index}><legend>{index + 1}. {option?.title || action.type}</legend>
        <p>{executionMode(action, option)}</p>
        {Object.entries(action.params).map(([key, value]) => <label key={key}>{key}
          <input required value={value} onChange={event => updateParam(index, key, event.target.value)} />
        </label>)}
        {(action.type === "python" || action.code != null) && <>
          <label>Python code<textarea className="python-source" rows="7" spellCheck={false} maxLength={65536}
            value={action.code ?? ""} onChange={e => edit(index, { code: e.target.value })} /></label>
          <label>Execution<select value={action.approval ?? (option?.approvalRequired === false ? "none" : "required")}
            onChange={e => edit(index, { approval: e.target.value })}>
            <option value="required">Require approval</option><option value="none">Run automatically on alert</option>
          </select></label>
        </>}
        <div className="condition-actions">
          <button className="secondary-button" type="button" disabled={index === 0} onClick={() => onChange(moveAction(actions, index, index - 1))}>Move up</button>
          <button className="secondary-button" type="button" disabled={index === actions.length - 1} onClick={() => onChange(moveAction(actions, index, index + 1))}>Move down</button>
          <button className="text-button danger-text" type="button" onClick={() => onChange(actions.filter((_, i) => i !== index))}>Remove</button>
        </div>
      </fieldset>;
    })}
    <div className="action-picker"><label>Action type<select value={type} onChange={event => setType(event.target.value)}>
      <option value="">Choose an action</option>
      {types.filter(item => item.selectable).map(item => <option value={item.name} key={item.name}>{item.title}</option>)}
    </select></label><button className="secondary-button" type="button" disabled={!type} onClick={add}>Add action</button></div>
  </>;
}

function InstructionFields({ draft, update }) {
  return <>
      <div className="form-grid"><label>Runbook name<input required value={draft.name} onChange={e => update({ name: e.target.value })} /></label>
        <label>Format<select value={draft.format} onChange={e => update({ format: e.target.value })}>
          {formats.map(format => <option key={format}>{format}</option>)}</select></label></div>
      <div className="instruction-content"><label htmlFor="runbook-content">Instructions</label><textarea id="runbook-content" spellCheck={draft.format !== "yaml"} rows="10" value={draft.content}
        onChange={e => update({ content: e.target.value })} /></div>
  </>;
}

export function InlineRunbookFields({ draft, update }) {
  const [types, setTypes] = useState([]);
  const [error, setError] = useState("");
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setError("");
    loadRunbookEditor(null, controller.signal)
      .then(data => { if (!controller.signal.aborted) setTypes(data.types); })
      .catch(cause => { if (!controller.signal.aborted) setError(cause.message); });
    return () => controller.abort();
  }, [attempt]);
  return <fieldset className="runbook-step"><legend>New runbook</legend>
    <InstructionFields draft={draft} update={update} />
    {error && <p role="alert">{error} <button type="button" onClick={() => setAttempt(attempt + 1)}>Retry</button></p>}
    <ActionFields actions={draft.actions} types={types} onChange={actions => update({ actions })} />
  </fieldset>;
}

function RunbookForm({ data, onCancel, onSaved }) {
  const [draft, setDraft] = useState(() => draftFromBook(data.book));
  const [step, setStep] = useState(0);
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const heading = useRef(null);
  useEffect(() => { heading.current?.focus(); }, [step]);
  function update(fields) {
    setError("");
    setDraft(current => ({ ...current, ...fields }));
  }
  async function submit(event) {
    event.preventDefault();
    if (saving) return;
    const issue = instructionError(draft, step === 0);
    if (issue) { setStep(0); setError(issue); return; }
    if (step > 0 && actionError(draft.actions)) {
      setStep(1); setError(actionError(draft.actions)); return;
    }
    if (step < 2) { setError(""); setStep(step + 1); return; }
    setSaving(true);
    try {
      const book = await saveRunbook(data.book?.id, data.etag, bookResource(draft));
      onSaved(book);
    } catch (cause) {
      if (cause.message.includes("name already exists") || cause.message.includes("YAML")) setStep(0);
      setError(cause.message);
    } finally { setSaving(false); }
  }
  return <form className="rule-builder runbook-editor" onSubmit={submit}>
    <div className="builder-top"><div><p className="eyebrow">{data.book ? "EDIT RUNBOOK" : "CREATE RUNBOOK"} · STEP {step + 1} OF 3</p>
      <h2 tabIndex="-1" ref={heading}>{steps[step]}</h2></div>
      <button className="text-button" type="button" onClick={onCancel} disabled={saving}>Cancel</button></div>
    {step === 0 && <InstructionFields draft={draft} update={update} />}
    {step === 1 && <ActionFields actions={draft.actions} types={data.types} onChange={actions => update({ actions })} />}
    {step === 2 && <section className="review"><h3>{draft.name}</h3><p>{draft.format}</p>
      {draft.format === "markdown" ? <Markdown skipHtml>{draft.content}</Markdown> : <pre>{draft.content}</pre>}
      <h3>Actions</h3>{draft.actions.length ? <ol>{draft.actions.map((action, i) => {
        const option = data.types.find(item => item.name === action.type);
        return <li key={i}><strong>{option?.title || action.type}</strong> · {executionMode(action, option)}
          {Object.entries(action.params).map(([key, value]) => <p key={key}>{key} = <code>{value}</code></p>)}</li>;
      })}</ol> : <p>No actions</p>}</section>}
    {error && <div className="error-banner" role="alert">{error}</div>}
    <div className="builder-actions">{step > 0 && <button className="secondary-button" type="button" disabled={saving}
      onClick={() => { setError(""); setStep(step - 1); }}>Back</button>}
      <button className="primary-button" disabled={saving}>{saving ? "Saving…" : step < 2 ? "Continue" : data.book ? "Save changes" : "Create runbook"}</button>
    </div>
  </form>;
}

export default function RunbookEditor({ id, onCancel, onSaved }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setError("");
    loadRunbookEditor(id, controller.signal).then(result => { if (!controller.signal.aborted) setData(result); })
      .catch(cause => { if (!controller.signal.aborted) setError(cause.message); });
    return () => controller.abort();
  }, [id, attempt]);
  if (data) return <RunbookForm data={data} onCancel={onCancel} onSaved={onSaved} />;
  return <section className="page-content">{error ? <div role="alert">{error} <button onClick={() => setAttempt(attempt + 1)}>Retry</button></div>
    : <p role="status">Loading editor…</p>}<button className="text-button" onClick={onCancel}>Cancel</button></section>;
}
