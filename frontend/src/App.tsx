// The MicroCloud test console. Two roles share one console shell:
//   - admin  (super-admin JWT from password login): platform setup — tenants, Proxmox clusters,
//             placements, networks, machine types, zones, templates.
//   - tenant (an opaque tenant secret used directly as the Bearer): self-service — customers, fund
//             accounts, machines, and read-only catalog.
// Everything is driven through the same public /microcloud API a real upstream would use.

import { useState } from "react";
import { request, superadminLogin } from "./api";
import { ResourcePanel } from "./ui";
import "./styles.css";

type Session = { role: "admin" | "tenant"; token: string };

const STORE_KEY = "microcloud.session";

function loadSession(): Session | null {
  try {
    return JSON.parse(localStorage.getItem(STORE_KEY) || "null");
  } catch {
    return null;
  }
}

export function App() {
  const [session, setSession] = useState<Session | null>(loadSession);

  function login(s: Session) {
    localStorage.setItem(STORE_KEY, JSON.stringify(s));
    setSession(s);
  }
  function logout() {
    localStorage.removeItem(STORE_KEY);
    setSession(null);
  }

  if (!session) return <Login onLogin={login} />;
  return <Console session={session} onLogout={logout} />;
}

function Login({ onLogin }: { onLogin: (s: Session) => void }) {
  const [tab, setTab] = useState<"admin" | "tenant">("admin");
  const [password, setPassword] = useState("");
  const [secret, setSecret] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  async function adminLogin() {
    setBusy(true);
    setErr("");
    try {
      const token = await superadminLogin(password);
      onLogin({ role: "admin", token });
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function tenantLogin() {
    setBusy(true);
    setErr("");
    try {
      // Validate the secret with a cheap authenticated call before entering.
      await request("GET", "/customer", { token: secret });
      onLogin({ role: "tenant", token: secret });
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login">
      <h1>MicroCloud</h1>
      <p className="hint">test console</p>
      <div className="tabs">
        <button className={tab === "admin" ? "on" : ""} onClick={() => setTab("admin")}>
          Super-admin
        </button>
        <button className={tab === "tenant" ? "on" : ""} onClick={() => setTab("tenant")}>
          Tenant
        </button>
      </div>
      {tab === "admin" ? (
        <div className="form">
          <label>
            <span>Operator password</span>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && adminLogin()}
            />
          </label>
          <button onClick={adminLogin} disabled={busy}>
            Log in
          </button>
        </div>
      ) : (
        <div className="form">
          <label>
            <span>Tenant secret</span>
            <input
              value={secret}
              onChange={(e) => setSecret(e.target.value)}
              placeholder="the plaintext secret minted for the tenant"
              onKeyDown={(e) => e.key === "Enter" && tenantLogin()}
            />
          </label>
          <button onClick={tenantLogin} disabled={busy}>
            Enter
          </button>
        </div>
      )}
      {err && <div className="msg err">{err}</div>}
    </main>
  );
}

function Console({ session, onLogout }: { session: Session; onLogout: () => void }) {
  const nav = session.role === "admin" ? adminNav(session.token) : tenantNav(session.token);
  const [active, setActive] = useState(nav[0].id);
  const current = nav.find((n) => n.id === active) ?? nav[0];

  return (
    <div className="shell">
      <aside>
        <div className="brand">
          MicroCloud
          <span className="role">{session.role}</span>
        </div>
        <nav>
          {nav.map((n) => (
            <button key={n.id} className={n.id === active ? "on" : ""} onClick={() => setActive(n.id)}>
              {n.label}
            </button>
          ))}
        </nav>
        <button className="logout" onClick={onLogout}>
          Log out
        </button>
      </aside>
      <main className="content">{current.render()}</main>
    </div>
  );
}

// ---- helpers to keep the nav configs terse ----

const t = (token: string) => ({ token });
const list = (token: string, path: string) => () => request("GET", path, t(token));
const create = (token: string, path: string) => (body: any) => request("POST", path, { token, body });
const del = (token: string, path: string) => (row: any) => request("DELETE", `${path}/${row.id}`, t(token));

type NavItem = { id: string; label: string; render: () => any };

function adminNav(token: string): NavItem[] {
  return [
    {
      id: "tenants",
      label: "Tenants",
      render: () => (
        <ResourcePanel
          title="Tenants"
          description="Upstream deployments. Mint a secret, then log in on the Tenant tab with it."
          columns={[
            { key: "id", label: "id" },
            { key: "name", label: "name" },
            { key: "status", label: "status" },
          ]}
          load={list(token, "/tenant")}
          createFields={[{ name: "name", label: "name" }]}
          onCreate={create(token, "/tenant")}
          actions={[
            {
              label: "mint secret",
              run: async (row) => {
                const label = window.prompt("secret label (optional)") ?? "";
                return request("POST", `/tenant/${row.id}/secret`, { token, body: label ? { label } : {} });
              },
            },
            { label: "secrets", run: (row) => request("GET", `/tenant/${row.id}/secret`, t(token)) },
            { label: "delete", confirm: "Delete this tenant?", run: del(token, "/tenant") },
          ]}
        />
      ),
    },
    {
      id: "proxmox",
      label: "Proxmox clusters",
      render: () => (
        <ResourcePanel
          title="Proxmox clusters"
          description="Provider credentials. The token secret is write-only (never returned). 'inventory' reads the cluster live."
          columns={[
            { key: "id", label: "id" },
            { key: "name", label: "name" },
            { key: "apiUrl", label: "apiUrl" },
            { key: "tokenId", label: "tokenId" },
            { key: "verifyTls", label: "tls" },
            { key: "status", label: "status" },
          ]}
          load={list(token, "/machine/proxmox")}
          createFields={[
            { name: "name", label: "name" },
            { name: "apiUrl", label: "apiUrl", placeholder: "https://pve:8006" },
            { name: "tokenId", label: "tokenId", placeholder: "user@pve!name" },
            { name: "tokenSecret", label: "tokenSecret" },
            { name: "verifyTls", label: "verify TLS", type: "checkbox", optional: true },
          ]}
          onCreate={create(token, "/machine/proxmox")}
          actions={[
            { label: "inventory", run: (row) => request("GET", `/machine/proxmox/${row.id}/inventory`, t(token)) },
            { label: "delete", confirm: "Delete this cluster?", run: del(token, "/machine/proxmox") },
          ]}
        />
      ),
    },
    {
      id: "placements",
      label: "Placements",
      render: () => (
        <ResourcePanel
          title="Placements"
          description="A landing coordinate: kind (proxmox/lxc | proxmox/vm) + cluster + node + pool + storage."
          columns={[
            { key: "id", label: "id" },
            { key: "kind", label: "kind" },
            { key: "name", label: "name" },
            { key: "clusterId", label: "cluster" },
            { key: "node", label: "node" },
            { key: "pool", label: "pool" },
            { key: "storage", label: "storage" },
            { key: "status", label: "status" },
          ]}
          load={list(token, "/machine/placement")}
          createFields={[
            { name: "kind", label: "kind", placeholder: "proxmox/lxc" },
            { name: "name", label: "name" },
            { name: "clusterId", label: "clusterId", type: "number" },
            { name: "node", label: "node" },
            { name: "pool", label: "pool" },
            { name: "storage", label: "storage (rootfs / disk)", placeholder: "local-lvm" },
          ]}
          onCreate={create(token, "/machine/placement")}
          actions={[{ label: "delete", confirm: "Delete?", run: del(token, "/machine/placement") }]}
        />
      ),
    },
    {
      id: "networks",
      label: "Networks",
      render: () => (
        <ResourcePanel
          title="Networks"
          description="An IPv4 range bound to a placement; machines draw a private IP from it."
          columns={[
            { key: "id", label: "id" },
            { key: "placementId", label: "placement" },
            { key: "startIp", label: "start" },
            { key: "endIp", label: "end" },
            { key: "gateway", label: "gateway" },
            { key: "prefixLength", label: "prefix" },
            { key: "bridge", label: "bridge" },
            { key: "allocatedCount", label: "used" },
            { key: "totalCount", label: "total" },
          ]}
          load={list(token, "/machine/network")}
          createFields={[
            { name: "name", label: "name", optional: true },
            { name: "placementId", label: "placementId", type: "number" },
            { name: "startIp", label: "startIp" },
            { name: "endIp", label: "endIp" },
            { name: "gateway", label: "gateway" },
            { name: "prefixLength", label: "prefixLength", type: "number", placeholder: "20" },
            { name: "bridge", label: "bridge", placeholder: "vmbr0" },
          ]}
          onCreate={create(token, "/machine/network")}
          actions={[{ label: "delete", confirm: "Delete?", run: del(token, "/machine/network") }]}
        />
      ),
    },
    {
      id: "types",
      label: "Machine types",
      render: () => (
        <ResourcePanel
          title="Machine types"
          description="Performance class + allowed spec ranges, backed by placements (comma-separated ids)."
          columns={[
            { key: "id", label: "id" },
            { key: "name", label: "name" },
            { key: "placementIds", label: "placements", render: (v) => (v || []).join(",") },
            { key: "coresMin", label: "coresMin" },
            { key: "coresMax", label: "coresMax" },
            { key: "memoryMbMin", label: "memMin" },
            { key: "memoryMbMax", label: "memMax" },
            { key: "diskGbMin", label: "diskMin" },
            { key: "diskGbMax", label: "diskMax" },
            { key: "status", label: "status" },
          ]}
          load={list(token, "/machine/type")}
          createFields={[
            { name: "name", label: "name" },
            { name: "description", label: "description", optional: true },
            { name: "placementIds", label: "placementIds", type: "ids", placeholder: "1,2" },
            { name: "coresMin", label: "coresMin", type: "number" },
            { name: "coresMax", label: "coresMax", type: "number" },
            { name: "memoryMbMin", label: "memoryMbMin", type: "number" },
            { name: "memoryMbMax", label: "memoryMbMax", type: "number" },
            { name: "diskGbMin", label: "diskGbMin", type: "number" },
            { name: "diskGbMax", label: "diskGbMax", type: "number" },
          ]}
          onCreate={create(token, "/machine/type")}
          actions={[{ label: "delete", confirm: "Delete?", run: del(token, "/machine/type") }]}
        />
      ),
    },
    {
      id: "zones",
      label: "Zones",
      render: () => (
        <ResourcePanel
          title="Zones"
          description="A locality partition of placements (comma-separated ids)."
          columns={[
            { key: "id", label: "id" },
            { key: "name", label: "name" },
            { key: "placementIds", label: "placements", render: (v) => (v || []).join(",") },
            { key: "status", label: "status" },
          ]}
          load={list(token, "/machine/zone")}
          createFields={[
            { name: "name", label: "name" },
            { name: "description", label: "description", optional: true },
            { name: "placementIds", label: "placementIds", type: "ids", placeholder: "1,2" },
          ]}
          onCreate={create(token, "/machine/zone")}
          actions={[{ label: "delete", confirm: "Delete?", run: del(token, "/machine/zone") }]}
        />
      ),
    },
    {
      id: "templates",
      label: "Templates",
      render: () => (
        <ResourcePanel
          title="Templates"
          description="Catalog images (seeded from config). 'upload' pushes the image onto a placement's storage (async); 'uploads' shows per-placement status."
          columns={[
            { key: "id", label: "id" },
            { key: "name", label: "name" },
            { key: "kind", label: "kind" },
            { key: "status", label: "status" },
          ]}
          load={list(token, "/machine/template")}
          actions={[
            {
              label: "upload",
              run: (row) => {
                const placementId = window.prompt("upload to placement id");
                if (!placementId) throw new Error("cancelled");
                return request("POST", `/machine/template/${row.id}/upload`, {
                  token,
                  body: { placementId: Number(placementId) },
                });
              },
            },
            { label: "uploads", run: (row) => request("GET", `/machine/template/${row.id}/upload`, t(token)) },
          ]}
        />
      ),
    },
    {
      id: "offerings",
      label: "Offerings",
      render: () => (
        <ResourcePanel
          title="Offerings"
          description="A (machine type, zone, template) triple granted to a tenant — the only catalog that tenant sees."
          columns={[
            { key: "id", label: "id" },
            { key: "tenantId", label: "tenant" },
            { key: "machineTypeName", label: "type" },
            { key: "zoneName", label: "zone" },
            { key: "templateName", label: "template" },
            { key: "status", label: "status" },
          ]}
          load={list(token, "/machine/offering")}
          createFields={[
            { name: "tenantId", label: "tenantId", type: "number" },
            { name: "machineTypeId", label: "machineTypeId", type: "number" },
            { name: "zoneId", label: "zoneId", type: "number" },
            { name: "templateId", label: "templateId", type: "number" },
          ]}
          onCreate={create(token, "/machine/offering")}
          actions={[
            { label: "disable", run: (row) => request("PATCH", `/machine/offering/${row.id}`, { token, body: { status: "disabled" } }) },
            { label: "enable", run: (row) => request("PATCH", `/machine/offering/${row.id}`, { token, body: { status: "active" } }) },
            { label: "delete", confirm: "Delete?", run: del(token, "/machine/offering") },
          ]}
        />
      ),
    },
  ];
}

function tenantNav(token: string): NavItem[] {
  return [
    {
      id: "customers",
      label: "Customers",
      render: () => (
        <ResourcePanel
          title="Customers"
          description="Your end-users (one per upstream user, keyed by externalRef)."
          columns={[
            { key: "id", label: "id" },
            { key: "externalRef", label: "externalRef" },
            { key: "status", label: "status" },
          ]}
          load={list(token, "/customer")}
          createFields={[{ name: "externalRef", label: "externalRef" }]}
          onCreate={create(token, "/customer")}
          actions={[{ label: "delete", confirm: "Delete?", run: del(token, "/customer") }]}
        />
      ),
    },
    {
      id: "accounts",
      label: "Accounts",
      render: () => (
        <ResourcePanel
          title="Fund accounts"
          description="A customer's balance. 'topup' adds funds; 'ledger' shows every change."
          columns={[
            { key: "id", label: "id" },
            { key: "customerId", label: "customer" },
            { key: "name", label: "name" },
            { key: "balance", label: "balance" },
          ]}
          load={list(token, "/account")}
          createFields={[
            { name: "customerId", label: "customerId", type: "number" },
            { name: "name", label: "name" },
          ]}
          onCreate={create(token, "/account")}
          actions={[
            {
              label: "topup",
              run: (row) => {
                const amount = window.prompt("amount to add");
                if (!amount) throw new Error("cancelled");
                const remark = window.prompt("remark (optional)") ?? undefined;
                return request("POST", `/account/${row.id}/topup`, {
                  token,
                  body: { amount: Number(amount), remark },
                });
              },
            },
            { label: "ledger", run: (row) => request("GET", `/account/${row.id}/ledger`, t(token)) },
          ]}
        />
      ),
    },
    {
      id: "machines",
      label: "Machines",
      render: () => (
        <ResourcePanel
          title="Machines"
          description="Provision a private-IP LXC. Every op is async — poll (refresh) for status."
          columns={[
            { key: "id", label: "id" },
            { key: "customerId", label: "customer" },
            { key: "accountId", label: "acct·compute" },
            { key: "newapiAccountId", label: "acct·newapi" },
            { key: "ccproxyAccountId", label: "acct·ccproxy" },
            { key: "typeId", label: "type" },
            { key: "templateId", label: "template" },
            { key: "cores", label: "cores" },
            { key: "memoryMb", label: "mem" },
            { key: "diskGb", label: "disk" },
            { key: "ip", label: "ip" },
            { key: "status", label: "status" },
            { key: "aiMode", label: "ai" },
            { key: "aiStatus", label: "ai status" },
          ]}
          load={list(token, "/machine")}
          createFields={[
            { name: "customerId", label: "customerId", type: "number" },
            { name: "accountId", label: "accountId (compute)", type: "number" },
            { name: "newapiAccountId", label: "newapiAccountId (blank = compute)", type: "number", optional: true },
            { name: "ccproxyAccountId", label: "ccproxyAccountId (blank = compute)", type: "number", optional: true },
            { name: "hostname", label: "hostname" },
            { name: "offeringId", label: "offeringId", type: "number" },
            { name: "cores", label: "cores", type: "number" },
            { name: "memoryMb", label: "memoryMb", type: "number" },
            { name: "diskGb", label: "diskGb", type: "number" },
            { name: "user", label: "login user" },
            { name: "sshPubkey", label: "sshPubkey", optional: true },
          ]}
          onCreate={create(token, "/machine")}
          actions={[
            { label: "start", run: (row) => request("POST", `/machine/${row.id}/start`, t(token)) },
            { label: "shutdown", run: (row) => request("POST", `/machine/${row.id}/shutdown`, t(token)) },
            { label: "stop (force)", confirm: "HARD-stop (pull the plug, no FS flush)? Prefer shutdown.", run: (row) => request("POST", `/machine/${row.id}/stop`, t(token)) },
            // AI switch (super-admin): newapi relay <-> ccproxy subscription. Needs a super-admin
            // token; a human completes the ccproxy OAuth out of band, then aiStatus lands 'ready'.
            {
              label: "→ccproxy",
              confirm: "Switch this machine's Claude Code to a ccproxy subscription login?",
              run: (row) => request("POST", `/machine/${row.id}/ai/ccproxy`, t(token)),
            },
            {
              label: "→newapi",
              confirm: "Switch this machine's Claude Code back to the newapi relay?",
              run: (row) => request("POST", `/machine/${row.id}/ai/newapi`, t(token)),
            },
            { label: "delete", confirm: "Destroy this machine?", run: del(token, "/machine") },
          ]}
        />
      ),
    },
    {
      id: "offerings",
      label: "Offerings",
      render: () => (
        <ResourcePanel
          title="Offerings"
          description="What you may provision: each row is a (machine type, zone, template) with the type's allowed spec ranges. Use its id as offeringId when creating a machine."
          columns={[
            { key: "id", label: "id" },
            { key: "machineTypeName", label: "type" },
            { key: "coresMin", label: "coresMin" },
            { key: "coresMax", label: "coresMax" },
            { key: "memoryMbMin", label: "memMin" },
            { key: "memoryMbMax", label: "memMax" },
            { key: "diskGbMin", label: "diskMin" },
            { key: "diskGbMax", label: "diskMax" },
            { key: "zoneName", label: "zone" },
            { key: "templateName", label: "template" },
            { key: "status", label: "status" },
          ]}
          load={list(token, "/machine/offering")}
        />
      ),
    },
  ];
}
