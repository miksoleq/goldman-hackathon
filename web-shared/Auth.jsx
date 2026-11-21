import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import "./auth.css";

const Context = createContext(null);
export function useAuth() { return useContext(Context); }
export const roleLabel = role => role === "TEAM_LEADER" ? "Team Leader" : "Member";

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState("");
  const version = useRef(0);
  const refresh = useCallback(async () => {
    const current = ++version.current;
    try {
      const res = await fetch("/api/auth/me", { credentials: "same-origin", cache: "no-store" });
      if (!res.ok && res.status !== 401) throw new Error();
      const account = res.ok ? await res.json() : null;
      if (current === version.current) { setUser(account); setError(""); }
    } catch {
      if (current === version.current) { setUser(null); setError("Cannot verify your session."); }
    } finally {
      if (current === version.current) setReady(true);
    }
  }, []);
  useEffect(() => {
    const expire = () => { version.current++; setUser(null); setReady(true); };
    void refresh();
    window.addEventListener("focus", refresh);
    window.addEventListener("session-expired", expire);
    return () => {
      version.current++;
      window.removeEventListener("focus", refresh);
      window.removeEventListener("session-expired", expire);
    };
  }, [refresh]);

  async function login(username, password) {
    const res = await fetch("/api/auth/login", { method: "POST", credentials: "same-origin",
      headers: { "Content-Type": "application/json" }, body: JSON.stringify({ username, password }) });
    if (!res.ok) throw new Error(res.status === 401 ? "Incorrect username or password." : "Login is unavailable.");
    const account = await res.json();
    version.current++;
    setUser(account); setReady(true); setError("");
  }

  async function logout() {
    try {
      const res = await fetch("/api/auth/logout", { method: "POST", credentials: "same-origin" });
      if (!res.ok) throw new Error();
      version.current++;
      setUser(null); setError("");
    } catch { setError("Logout failed. Please retry."); }
  }
  return <Context.Provider value={{ user, ready, error, login, logout, refresh }}>{children}</Context.Provider>;
}

export function LoginForm({ onSuccess, title = "Log in" }) {
  const { login } = useAuth();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function submit(name, secret) {
    setBusy(true); setError("");
    try { await login(name, secret); setPassword(""); onSuccess?.(); }
    catch (e) { setError(e.message); }
    finally { setBusy(false); }
  }
  return <form className="account-form" onSubmit={e => { e.preventDefault(); void submit(username, password); }}>
    <h2>{title}</h2>
    <label>Username or email<input required autoComplete="username" value={username} onChange={e => setUsername(e.target.value)} /></label>
    <label>Password<input required type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} /></label>
    {error && <p role="alert">{error}</p>}
    <button disabled={busy} type="submit">Log in</button>
    <fieldset disabled={busy}><legend>Demo login</legend>
      <button type="button" onClick={() => submit("demo.leader", "Runbook123!")}>Demo Team Leader</button>
      <button type="button" onClick={() => submit("demo.member", "Runbook123!")}>Demo Member</button>
    </fieldset>
  </form>;
}

export function AccountMenu() {
  const { user, ready, error, logout } = useAuth();
  const dialog = useRef(null);
  return <div className="account-menu">
    {error && <small role="alert">{error}</small>}
    {user ? <><strong>{user.username}</strong><span className="account-role">{roleLabel(user.role)}</span>
      <button type="button" onClick={logout}>Log out</button></>
      : <button type="button" disabled={!ready} onClick={() => dialog.current.showModal()}>Log in</button>}
    <dialog ref={dialog} aria-label="Account login" className="account-dialog">
      <LoginForm onSuccess={() => dialog.current.close()} />
      <button type="button" onClick={() => dialog.current.close()}>Cancel</button>
    </dialog>
  </div>;
}
