import { useState } from "react";

// Minimal test console. It calls ONLY the public MicroCloud API (the same API upstream services
// use) — there is no frontend-specific backend. Today that is just the authenticated /ping smoke
// test; real controls (tenants, customers, accounts, machines, api-keys, billing, audit) get added
// here as the API grows.
//
// TODO: generate a typed client from ../MicroCloud-API.yml (the "generated both ways" pattern),
// and add a token field so /ping's @Guard passes end to end.
export function App() {
  const [token, setToken] = useState("");
  const [result, setResult] = useState<string>("");

  async function ping() {
    setResult("…");
    try {
      const res = await fetch("/microcloud/ping", {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      });
      setResult(`${res.status} ${await res.text()}`);
    } catch (e) {
      setResult(`error: ${String(e)}`);
    }
  }

  return (
    <main style={{ fontFamily: "system-ui, sans-serif", maxWidth: 640, margin: "3rem auto", padding: "0 1rem" }}>
      <h1>MicroCloud — test console</h1>
      <p style={{ color: "#666" }}>
        Skeleton. Calls only the public API. Start with the authenticated <code>/ping</code>.
      </p>
      <label style={{ display: "block", margin: "1rem 0" }}>
        Bearer token (optional for now)
        <input
          value={token}
          onChange={(e) => setToken(e.target.value)}
          placeholder="paste a JWT"
          style={{ display: "block", width: "100%", padding: 8, marginTop: 4 }}
        />
      </label>
      <button onClick={ping} style={{ padding: "8px 16px" }}>
        GET /microcloud/ping
      </button>
      <pre style={{ background: "#f4f4f4", padding: 12, marginTop: 16, whiteSpace: "pre-wrap" }}>{result}</pre>
    </main>
  );
}
