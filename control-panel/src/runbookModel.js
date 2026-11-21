import { parseDocument } from "yaml";

export const formats = ["text", "markdown", "yaml"];

export function newAction(option) {
  const params = Object.fromEntries(option.params.map(key => [key, ""]));
  if (option.name !== "python") return { type: option.name, params };
  return { type: "python", params, approval: "required", code: [
    "def run(ctx):", '    event = ctx["event"]', '    alert = ctx["alert"]',
    '    previous = ctx["previousStepResult"]', '    return {"title": alert["title"]}'
  ].join("\n") };
}

export function actionError(actions) {
  for (const action of actions) {
    if ((action.type === "python" || action.code != null) &&
        (!action.code?.trim() || action.code.length > 65536 || action.code.includes("\0"))) {
      return "Enter a Python script of up to 65,536 characters.";
    }
    if (Object.values(action.params).some(value => !value.trim())) return "Enter a value for each action parameter.";
  }
  return "";
}

export function draftFromBook(book) {
  return {
    name: book?.name ?? "",
    format: book?.format === "url" ? "text" : book?.format ?? "text",
    content: book?.content ?? "",
    actions: book?.actions ?? []
  };
}

export function instructionError(draft, allowPendingActions = false) {
  if (!draft.name.trim() || ["auto", "none"].includes(draft.name.trim())) return "Enter a unique runbook name. The names auto and none are reserved.";
  if (!formats.includes(draft.format)) return "Choose a supported format.";
  if (!draft.content.trim()) return draft.format === "text" && (draft.actions.length || allowPendingActions) ? "" : "Enter instructions.";
  if (draft.format === "yaml") {
    const [issue] = parseDocument(draft.content).errors;
    if (issue) return "Invalid YAML at line " + (issue.linePos?.[0]?.line ?? 1) + ". " + issue.message.split("\n")[0];
  }
  return "";
}

export function bookResource(draft) {
  const { format, content, actions } = draft;
  const spec = !content.trim() && format === "text" && actions.length ? { steps: actions } : { format, content, actions };
  return { kind: "Runbook", name: draft.name.trim(), spec };
}

export function moveAction(actions, from, to) {
  const result = actions.slice();
  result.splice(to, 0, result.splice(from, 1)[0]);
  return result;
}

export function executionMode(action, option) {
  if (!option) return "Mode unavailable";
  if ((action.approval ?? (option.approvalRequired ? "required" : "none")) === "required") return "Approval required";
  return option.autoExecute ? "Automatic" : "Manual";
}
