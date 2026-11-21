import { useEffect, useRef, useState } from "react";
import { executeAction, fetchAlertDetails } from "./api.js";

export default function RunbookActions({ alert, onRefresh }) {
  const [steps, setSteps] = useState(null);
  const [confirming, setConfirming] = useState(null);
  const [pending, setPending] = useState(null);
  const [error, setError] = useState("");
  const generation = useRef(0);

  useEffect(() => {
    const request = new AbortController();
    const current = ++generation.current;
    fetchAlertDetails(alert.alertId, request.signal).then(detail => {
      if (current === generation.current) {
        setSteps(detail.actions);
        setError("");
      }
    }).catch(e => {
      if (!request.signal.aborted && current === generation.current) setError(e.message);
    });
    return () => request.abort();
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
      <p>{Object.entries(step.params).map(([name, value]) => name + " = " + (value || "—")).join(", ")}</p>
      <p role="status">{step.automatic && "Automatic · "}{pending === step.stepNo ? "Running…" : step.status.replaceAll("_", " ")}
        {step.reason && " · " + step.reason}</p>
      {step.result && <pre className="payload">{JSON.stringify(step.result, null, 2)}</pre>}
      {!step.automatic && step.available && (confirming === step.stepNo
        ? <div>
            <p>Confirm {step.title.toLowerCase()} for {Object.values(step.params).join(", ")}?</p>
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
