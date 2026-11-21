import { useState } from "react";
import { saveRuleYaml } from "./api.js";

export default function YamlRuleEditor({ rule, onCancel, onSaved }) {
  const [source, setSource] = useState(rule?.sourceYaml ?? "");
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  async function submit(event) {
    event.preventDefault();
    setSaving(true);
    setError("");
    try {
      const result = await saveRuleYaml(source, rule);
      onSaved(result);
    } catch (cause) {
      setError(cause.message || "Could not save the rule.");
    } finally {
      setSaving(false);
    }
  }

  return <form className="rule-builder yaml-editor" onSubmit={submit}>
    <div className="builder-top"><h2>{rule ? "Edit " + rule.name : "Create rule from YAML"}</h2>
      <button type="button" className="text-button" onClick={onCancel} disabled={saving}>Cancel</button></div>
    <label>Rule YAML<textarea rows={19} required spellCheck={false} value={source}
      onChange={event => setSource(event.target.value)} /></label>
    {error && <p role="alert" className="error-banner">{error}</p>}
    <div className="builder-actions"><button className="primary-button" disabled={saving || !source.trim()}>
      {saving ? "Saving…" : rule ? "Save changes" : "Create rule"}</button></div>
  </form>;
}
