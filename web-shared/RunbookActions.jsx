import { useEffect, useRef, useState } from "react";
import { executeAction, fetchAlertDetails } from "./alertActions.js";

export default function RunbookActions({ alert, onRefresh }) {
  const [steps, setSteps] = useState(null);
  const [confirming, setConfirming] = useState(null);
  const [pending, setPending] = useState(null);
  const [error, setError] = useState("");
  const generation = useRef(0);

  useEffect(() => {
    const request = new AbortController();
    const current = ++generation.current;
    let timer;
    async function refresh() {
      try {
        const detail = await fetchAlertDetails(alert.alertId, request.signal);
        if (request.signal.aborted || current !== generation.current) return;
        setSteps(detail.actions);
        setError("");
        const next = detail.actions.find(step => !["succeeded", "skipped"].includes(step.status));
        if (next?.automatic && ["ready", "running"].includes(next.status)) timer = setTimeout(refresh, 1000);
      } catch (e) {
        if (!request.signal.aborted && current === generation.current) setError(e.message);
      }
    }
    void refresh();
    return () => { clearTimeout(timer); request.abort(); };
  }, [alert]);

  async function run(step, confirmed) {
    setPending(step.stepNo);
    setConfirming(null);
    setError("");
    try {
      const result = await executeAction(alert.alertId, step.stepNo, confirmed);
      generation.current++;
      setSteps(previous => previous.map(item => item.stepNo === step.stepNo ? result : item));
      void onRefresh();
    } catch (e) {
      setError(e.message);
    } finally {
      setPending(null);
    }
  }

  return <section aria-label="Runbook actions">
    <h3>Runbook actions</h3>
    {error && <p role="alert">{error}</p>}
    {!steps && !error && <p>Loading actions…</p>}
    {steps?.map(step => <div className="runbook-action" key={step.stepNo}>
      <strong>{step.title}</strong>
      {step.kind === "python" && step.approvalRequired && <span className="status-pill">Approval Required</span>}
      <p>{Object.entries(step.params).map(([name, value]) => name + " = " + (value || "—")).join(", ")}</p>
      <p role="status">{step.automatic && "Automatic · "}{pending === step.stepNo ? "Running…" : step.status.replaceAll("_", " ")}
        {step.reason && " · " + step.reason}</p>
      {step.result && (step.kind === "python" ? <div className="python-result">
        <p>{step.result.status}{step.result.durationMs != null && " · " + step.result.durationMs + " ms"}
          {step.approvedBy && " · Approved by " + step.approvedBy}</p>
        {Object.hasOwn(step.result, "result") && <><strong>Result</strong><pre>{JSON.stringify(step.result.result, null, 2)}</pre></>}
        {step.result.stdout && <><strong>stdout</strong><pre>{step.result.stdout}</pre></>}
        {step.result.stderr && <><strong>stderr</strong><pre>{step.result.stderr}</pre></>}
        {step.result.error && <p>{String(step.result.error)}</p>}
      </div> : <pre className="payload">{JSON.stringify(step.result, null, 2)}</pre>)}
      {!step.automatic && step.available && (confirming === step.stepNo
        ? <div>
            <p>Confirm {step.title.toLowerCase()}{Object.values(step.params).length ? " for " + Object.values(step.params).join(", ") : ""}?</p>
            <button type="button" disabled={pending !== null} onClick={() => run(step, true)}>Confirm</button>
            {" "}<button type="button" onClick={() => setConfirming(null)}>Cancel</button>
          </div>
        : <button type="button" disabled={pending !== null}
            onClick={() => step.approvalRequired ? setConfirming(step.stepNo) : run(step, false)}>
            {step.title}
          </button>)}
    </div>)}
  </section>;
}
