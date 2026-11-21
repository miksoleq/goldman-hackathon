import { useCallback, useEffect, useState } from "react";
import { useAuth } from "../../web-shared/Auth.jsx";
import { sessionRequest } from "../../web-shared/session.js";

async function directory(path, signal) {
  const res = await sessionRequest("/api/" + path + "/directory", { signal, cache: "no-store" });
  if (!res.ok) throw new Error("Could not load the team directory.");
  return res.json();
}

export default function TeamsPage() {
  const { user, refresh } = useAuth();
  const leader = user.role === "TEAM_LEADER";
  const [teams, setTeams] = useState(null);
  const [people, setPeople] = useState([]);
  const [selected, setSelected] = useState("");
  const [search, setSearch] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const load = useCallback(async signal => {
    const [list, candidates] = await Promise.all([
      directory("teams", signal), leader ? directory("users", signal) : Promise.resolve([])
    ]);
    if (signal?.aborted) return;
    setTeams(list); setPeople(candidates);
  }, [leader]);
  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal).catch(e => { if (!controller.signal.aborted) setError(e.message); });
    return () => controller.abort();
  }, [load]);
  const team = leader ? teams?.[0] : teams?.find(item => item.id === selected) ?? teams?.[0];

  async function change(person, add) {
    setBusy(true); setError(""); setNotice("");
    try {
      const path = "/api/teams/" + team.id + "/members";
      const res = await sessionRequest(add ? path : path + "/" + person.id, add ? {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ userId: person.id })
      } : { method: "DELETE" });
      if (res.status === 403) { await refresh(); throw new Error("You cannot change this team membership."); }
      if (!res.ok) throw new Error("Could not update this team.");
      await load();
      await refresh();
      setSearch(""); setNotice(person.username + (add ? " added to " : " removed from ") + team.name + ".");
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  }
  const available = people.filter(person => !team?.members.some(member => member.id === person.id)
    && person.username.toLowerCase().includes(search.trim().toLowerCase()));

  return <section className="page-content"><h1>Teams</h1>
    {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    {!teams && !error && <p>Loading teams…</p>}
    {teams?.length === 0 && <p>{leader ? "No team assigned." : "No teams yet."}</p>}
    {team && <div className="team-directory">
      {!leader && <label>Team<select value={team.id} disabled={busy} onChange={e => { setSelected(e.target.value); setSearch(""); setNotice(""); }}>
        {teams.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
      </select></label>}
      <h2>Members of {team.name}</h2>
      {team.members.length === 0 && <p>No members assigned.</p>}
      <ul aria-label="Team members">{team.members.map(person => <li key={person.id}>{person.username}
        {leader && !person.teamLeader && <button type="button" className="text-button" disabled={busy} aria-label={"Remove " + person.username}
          onClick={() => change(person, false)}>Remove</button>}</li>)}</ul>
      {leader && <><label>Find a user to assign<input type="search" value={search} onChange={e => setSearch(e.target.value)} /></label>
        {search.trim() && (available.length ? <ul aria-label="Matching users">{available.slice(0, 30).map(person => <li key={person.id}>
          {person.username}<button type="button" className="secondary-button" disabled={busy} aria-label={"Assign " + person.username}
            onClick={() => change(person, true)}>Assign</button></li>)}</ul> : <p>No matching users available.</p>)}</>}
    </div>}
  </section>;
}
