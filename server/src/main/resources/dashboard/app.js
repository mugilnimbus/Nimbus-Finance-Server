const byId = (id) => document.getElementById(id);
const state = { config: null, account: null, owner: false, accounts: [], inference: null, signupUsernameAvailable: false };

function showNotice(message, error = false) {
  const notice = byId("notice");
  notice.textContent = message;
  notice.classList.toggle("error", error);
  notice.classList.remove("hidden");
  window.clearTimeout(showNotice.timer);
  showNotice.timer = window.setTimeout(() => notice.classList.add("hidden"), 7000);
}

async function request(path, options = {}) {
  const headers = { Accept: "application/json", ...(options.headers || {}) };
  if (options.body && !headers["Content-Type"]) headers["Content-Type"] = "application/json";
  const response = await fetch(path, { ...options, headers, credentials: "same-origin" });
  const text = await response.text();
  const payload = text ? (() => { try { return JSON.parse(text); } catch { return text; } })() : null;
  if (!response.ok) throw new Error(payload?.message || `Request failed (${response.status})`);
  return payload;
}

function normalizedServer() {
  const raw = byId("serverUrl").value.trim().replace(/\/$/, "");
  let url;
  try { url = new URL(raw); } catch { throw new Error("Enter the private HTTPS server address"); }
  if (url.protocol !== "https:" || !url.hostname) throw new Error("The phone server address must use HTTPS");
  return url.origin;
}

function deepLink(invite) {
  const params = new URLSearchParams({ server: normalizedServer() });
  if (invite) params.set("invite", invite);
  return `nimbus://connect?${params.toString()}`;
}

function setQr(image, value) {
  image.src = `/v1/dashboard/qr.svg?value=${encodeURIComponent(value)}`;
}

function updateConnection() {
  try {
    const value = deepLink();
    setQr(byId("serverQr"), value);
    byId("openApp").href = value;
    localStorage.setItem("nimbusServerUrl", normalizedServer());
  } catch {
    byId("serverQr").removeAttribute("src");
    byId("openApp").href = "#";
  }
}

function saveAccount(payload) {
  state.account = payload;
  if (payload) sessionStorage.setItem("nimbusDashboardAccount", JSON.stringify(payload));
  else sessionStorage.removeItem("nimbusDashboardAccount");
  renderAccount();
}

function renderAccount() {
  const signedIn = Boolean(state.account?.user);
  byId("signedOut").classList.toggle("hidden", signedIn);
  byId("signedIn").classList.toggle("hidden", !signedIn);
  if (signedIn) {
    const user = state.account.user;
    byId("accountName").textContent = user.displayName;
    byId("accountUsername").textContent = `@${user.username}`;
    byId("accountAvatar").textContent = (user.displayName || user.username).slice(0, 1).toUpperCase();
  }
}

function renderOwner(unlocked) {
  state.owner = unlocked;
  byId("ownerLocked").classList.toggle("hidden", unlocked);
  byId("ownerUnlocked").classList.toggle("hidden", !unlocked);
  byId("ownerBadge").textContent = unlocked ? "Owner unlocked" : "Locked";
  byId("ownerBadge").classList.toggle("neutral", !unlocked);
}

async function loadOwnerSummary() {
  try {
    const summary = await request("/v1/dashboard/admin/summary");
    renderOwner(true);
    byId("statUsers").textContent = summary.users;
    byId("statDisabled").textContent = summary.disabledUsers;
    byId("statTrashed").textContent = summary.trashedUsers;
    byId("statSessions").textContent = summary.activeSessions;
    byId("statInvites").textContent = summary.availableInvites;
    state.accounts = summary.accounts;
    renderAccounts();
    await loadInferenceState();
  } catch (error) {
    renderOwner(false);
    if (!String(error.message).includes("owner dashboard")) showNotice(error.message, true);
  }
}

async function loadInferenceState(reload = false) {
  if (!state.owner) return;
  try {
    state.inference = await request(`/v1/dashboard/admin/inference${reload ? "?reload=1" : ""}`);
    byId("inferenceUnavailable").classList.add("hidden");
    renderInference();
    scheduleInferenceRefresh();
  } catch {
    state.inference = null;
    byId("inferenceUnavailable").classList.remove("hidden");
    byId("modelList").innerHTML = '<p class="muted">Inference service unavailable.</p>';
  }
}

function scheduleInferenceRefresh() {
  window.clearTimeout(loadInferenceState.timer);
  if (state.owner && state.inference?.models?.some((model) => ["downloading", "loading", "downloaded"].includes(model.status))) {
    loadInferenceState.timer = window.setTimeout(() => loadInferenceState(true), 2000);
  }
}

function renderInference() {
  const snapshot = state.inference;
  if (!snapshot) return;
  const settings = snapshot.settings;
  const form = byId("inferenceSettings");
  Object.entries(settings).forEach(([name, value]) => {
    const field = form.elements.namedItem(name);
    if (!field || name === "activeModel") return;
    if (field.type === "checkbox") field.checked = Boolean(value);
    else field.value = value;
  });
  const models = snapshot.models || [];
  byId("modelList").innerHTML = models.length ? models.map((model) => {
    const active = model.id === settings.activeModel;
    const running = ["loaded", "loading", "sleeping"].includes(model.status);
    const busy = ["loading", "downloading", "downloaded"].includes(model.status);
    return `<div class="model-row">
      <div class="model-identity"><strong>${escapeHtml(model.id)}</strong><span>${escapeHtml(model.source)} · ${escapeHtml(model.status)} · ${(model.inputModalities || ["text"]).map(escapeHtml).join(" + ")}</span></div>
      ${active ? '<span class="status-pill active">Active</span>' : `<button class="account-action restore" type="button" data-model-action="ACTIVATE" data-model="${escapeHtml(model.id)}" ${busy ? "disabled" : ""}>Activate</button>`}
      <button class="account-action" type="button" data-model-action="${running ? "UNLOAD" : "LOAD"}" data-model="${escapeHtml(model.id)}" ${busy ? "disabled" : ""}>${running ? "Unload" : "Load"}</button>
      ${model.removable && !active ? `<button class="account-action delete" type="button" data-model-action="REMOVE" data-model="${escapeHtml(model.id)}" ${busy ? "disabled" : ""}>Remove</button>` : ""}
    </div>`;
  }).join("") : '<p class="muted">No model is installed. Download one to begin.</p>';
}

async function runModelAction(model, action) {
  if (action === "REMOVE" && !window.confirm(`Remove ${model} from server storage?`)) return;
  try {
    state.inference = await request("/v1/dashboard/admin/inference/model-action", { method: "POST", body: JSON.stringify({ model, action }) });
    renderInference();
    scheduleInferenceRefresh();
    showNotice(action === "ACTIVATE" ? "Model selected and loading" : `Model action started: ${action.toLowerCase()}`);
  } catch (error) { showNotice(error.message, true); }
}

function renderAccounts() {
  const query = byId("accountSearch").value.trim().toLowerCase();
  const accounts = state.accounts.filter((account) => `${account.displayName} ${account.username}`.toLowerCase().includes(query));
  byId("accountList").innerHTML = accounts.length ? accounts.map((account) => {
    const enabled = account.status === "ACTIVE";
    const trashed = account.status === "TRASHED";
    const statusLabel = trashed ? "Trash" : enabled ? "Active" : "Disabled";
    const purgeText = trashed && account.purgeAfter ? ` · purges ${new Date(account.purgeAfter).toLocaleDateString()}` : "";
    return `<div class="account-row ${enabled ? "" : "disabled"}">
      <div class="account-identity"><strong>${escapeHtml(account.displayName)}</strong><span>@${escapeHtml(account.username)} · joined ${new Date(account.createdAt).toLocaleDateString()}</span></div>
      <span class="status-pill ${enabled ? "active" : "disabled"}">${statusLabel}${purgeText}</span>
      <span class="session-count">${account.activeSessions} session${account.activeSessions === 1 ? "" : "s"}</span>
      <div class="account-actions">
        <button class="account-action ${enabled ? "danger" : "restore"}" type="button" data-action="status" data-username="${escapeHtml(account.username)}" data-enabled="${enabled ? "false" : "true"}">${enabled ? "Disable" : "Restore"}</button>
        <button class="account-action delete" type="button" data-action="delete" data-username="${escapeHtml(account.username)}">${trashed ? "Delete now" : "Delete"}</button>
      </div>
    </div>`;
  }).join("") : `<p class="muted">${query ? "No matching accounts." : "No accounts yet."}</p>`;
}

async function setAccountEnabled(username, enabled) {
  if (!enabled && !window.confirm(`Disable @${username}? Their phones will be signed out, but shared history will be kept.`)) return;
  try {
    const summary = await request(`/v1/dashboard/admin/users/${encodeURIComponent(username)}/status`, { method: "POST", body: JSON.stringify({ enabled }) });
    state.accounts = summary.accounts;
    byId("statUsers").textContent = summary.users;
    byId("statDisabled").textContent = summary.disabledUsers;
    byId("statTrashed").textContent = summary.trashedUsers;
    byId("statSessions").textContent = summary.activeSessions;
    renderAccounts();
    showNotice(enabled ? `@${username} restored` : `@${username} disabled and signed out`);
  } catch (error) { showNotice(error.message, true); }
}

async function deleteAccountPermanently(username) {
  const confirmation = window.prompt(`Permanently delete @${username}? This cannot be undone. Type the username to confirm:`);
  if (confirmation === null) return;
  if (confirmation.toLowerCase() !== username.toLowerCase()) { showNotice("Username confirmation did not match", true); return; }
  try {
    const summary = await request(`/v1/dashboard/admin/users/${encodeURIComponent(username)}`, { method: "DELETE", body: JSON.stringify({ confirmation }) });
    state.accounts = summary.accounts;
    byId("statUsers").textContent = summary.users;
    byId("statDisabled").textContent = summary.disabledUsers;
    byId("statTrashed").textContent = summary.trashedUsers;
    byId("statSessions").textContent = summary.activeSessions;
    renderAccounts();
    showNotice(`@${username} permanently deleted`);
  } catch (error) { showNotice(error.message, true); }
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"]/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[char]));
}

document.querySelectorAll(".tab").forEach((tab) => tab.addEventListener("click", () => {
  document.querySelectorAll(".tab").forEach((item) => item.classList.toggle("active", item === tab));
  document.querySelectorAll(".panel").forEach((panel) => panel.classList.toggle("active", panel.id === tab.dataset.panel));
}));

byId("serverUrl").addEventListener("input", updateConnection);
byId("accountSearch").addEventListener("input", renderAccounts);
byId("accountList").addEventListener("click", (event) => {
  const button = event.target.closest(".account-action");
  if (!button) return;
  if (button.dataset.action === "delete") deleteAccountPermanently(button.dataset.username);
  else setAccountEnabled(button.dataset.username, button.dataset.enabled === "true");
});
byId("modelList").addEventListener("click", (event) => {
  const button = event.target.closest("[data-model-action]");
  if (button) runModelAction(button.dataset.model, button.dataset.modelAction);
});

byId("downloadModel").addEventListener("submit", async (event) => {
  event.preventDefault();
  const model = String(new FormData(event.currentTarget).get("model")).trim();
  try {
    state.inference = await request("/v1/dashboard/admin/inference/download", { method: "POST", body: JSON.stringify({ model }) });
    renderInference(); scheduleInferenceRefresh(); showNotice("Model download started");
  } catch (error) { showNotice(error.message, true); }
});

byId("inferenceSettings").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = new FormData(event.currentTarget);
  const previous = state.inference?.settings || {};
  const payload = {
    ...previous,
    contextTokens: Number(form.get("contextTokens")), maxOutputTokens: Number(form.get("maxOutputTokens")), reasoningEffort: form.get("reasoningEffort"),
    temperature: Number(form.get("temperature")), topP: Number(form.get("topP")), topK: Number(form.get("topK")), seed: Number(form.get("seed")),
    parallelSlots: Number(form.get("parallelSlots")), threads: Number(form.get("threads")), batchThreads: Number(form.get("batchThreads")),
    batchSize: Number(form.get("batchSize")), microBatchSize: Number(form.get("microBatchSize")), gpuLayers: String(form.get("gpuLayers")).trim().toLowerCase(),
    splitMode: form.get("splitMode"), tensorSplit: String(form.get("tensorSplit")).trim(), mainGpu: Number(form.get("mainGpu")),
    fitToMemory: form.has("fitToMemory"), fitTargetMiB: Number(form.get("fitTargetMiB")), flashAttention: form.get("flashAttention"),
    cacheTypeK: form.get("cacheTypeK"), cacheTypeV: form.get("cacheTypeV")
  };
  try {
    state.inference = await request("/v1/dashboard/admin/inference/settings", { method: "POST", body: JSON.stringify(payload) });
    renderInference(); scheduleInferenceRefresh(); showNotice("Runtime profile saved; active model is reloading");
  } catch (error) { showNotice(error.message, true); }
});

byId("restoreInference").addEventListener("click", async () => {
  if (!window.confirm("Restore the server inference defaults and reload the active model?")) return;
  try {
    state.inference = await request("/v1/dashboard/admin/inference/restore", { method: "POST" });
    renderInference(); scheduleInferenceRefresh(); showNotice("Inference defaults restored");
  } catch (error) { showNotice(error.message, true); }
});
byId("refreshInference").addEventListener("click", () => loadInferenceState(true));

let usernameTimer;
byId("signupUsername").addEventListener("input", () => {
  window.clearTimeout(usernameTimer);
  state.signupUsernameAvailable = false;
  const username = byId("signupUsername").value.trim();
  const hint = byId("usernameAvailability");
  hint.className = "field-hint";
  hint.textContent = "Checking username…";
  if (!/^[A-Za-z0-9._-]{3,64}$/.test(username)) {
    hint.textContent = "Use 3-64 letters, numbers, dots, underscores, or hyphens";
    hint.classList.add("error");
    return;
  }
  usernameTimer = window.setTimeout(async () => {
    try {
      const result = await request(`/v1/auth/username-availability?username=${encodeURIComponent(username)}`);
      if (byId("signupUsername").value.trim() !== username) return;
      state.signupUsernameAvailable = result.available;
      hint.textContent = result.message;
      hint.classList.add(result.available ? "success" : "error");
    } catch (error) { hint.textContent = error.message; hint.classList.add("error"); }
  }, 400);
});
byId("copyServer").addEventListener("click", async () => {
  try { await navigator.clipboard.writeText(normalizedServer()); showNotice("Server address copied"); }
  catch (error) { showNotice(error.message, true); }
});

byId("signupPanel").addEventListener("submit", async (event) => {
  event.preventDefault();
  const formElement = event.currentTarget;
  const form = new FormData(formElement);
  try {
    normalizedServer();
    if (!state.signupUsernameAvailable) throw new Error("Choose an available username first");
    const payload = await request("/v1/auth/register", { method: "POST", body: JSON.stringify({
      inviteCode: form.get("inviteCode"), username: form.get("username"), displayName: form.get("displayName"), password: form.get("password"), deviceName: "Dashboard browser"
    }) });
    saveAccount(payload); formElement.reset(); state.signupUsernameAvailable = false; byId("usernameAvailability").textContent = "Your unique sign-in ID"; showNotice("Account created. Scan the connection QR to sign in on the phone.");
    await loadOwnerSummary();
  } catch (error) { showNotice(error.message, true); }
});

byId("signinPanel").addEventListener("submit", async (event) => {
  event.preventDefault();
  const formElement = event.currentTarget;
  const form = new FormData(formElement);
  try {
    normalizedServer();
    const payload = await request("/v1/auth/login", { method: "POST", body: JSON.stringify({ username: form.get("username"), password: form.get("password"), deviceName: "Dashboard browser" }) });
    saveAccount(payload); formElement.reset(); showNotice("Signed in to the dashboard");
  } catch (error) { showNotice(error.message, true); }
});

byId("browserSignOut").addEventListener("click", async () => {
  const token = state.account?.accessToken;
  if (token) await request("/v1/auth/logout", { method: "POST", headers: { Authorization: `Bearer ${token}` } }).catch(() => {});
  saveAccount(null); showNotice("Signed out of the dashboard");
});

byId("ownerLogin").addEventListener("submit", async (event) => {
  event.preventDefault();
  const formElement = event.currentTarget;
  const key = new FormData(formElement).get("adminKey");
  try {
    await request("/v1/dashboard/admin/login", { method: "POST", body: JSON.stringify({ adminKey: key }) });
    formElement.reset(); await loadOwnerSummary(); showNotice("Owner dashboard unlocked");
  } catch (error) { showNotice(error.message, true); }
});

byId("createInvite").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = new FormData(event.currentTarget);
  try {
    const invitation = await request("/v1/dashboard/admin/registration-invites", { method: "POST", body: JSON.stringify({ serverUrl: normalizedServer(), expiresInHours: Number(form.get("expiresInHours")), maxUses: 1 }) });
    byId("inviteResult").classList.remove("empty");
    byId("inviteResult").innerHTML = `<div class="invite-result-content"><img id="inviteQr" class="qr" alt="One-use Nimbus account enrollment QR"><div><p class="step">ONE-USE INVITATION</p><p class="code">${escapeHtml(invitation.code)}</p><p class="muted">Expires ${new Date(invitation.expiresAt).toLocaleString()}</p><button id="copyInvite" class="button secondary" type="button">Copy invitation</button></div></div>`;
    setQr(byId("inviteQr"), invitation.deepLink);
    byId("copyInvite").addEventListener("click", async () => { await navigator.clipboard.writeText(invitation.code); showNotice("Invitation copied"); });
    await loadOwnerSummary(); showNotice("Enrollment QR created");
  } catch (error) { showNotice(error.message, true); }
});

byId("refreshOwner").addEventListener("click", loadOwnerSummary);
byId("ownerSignOut").addEventListener("click", async () => {
  await request("/v1/dashboard/admin/logout", { method: "POST" }).catch(() => {}); window.clearTimeout(loadInferenceState.timer); renderOwner(false); showNotice("Owner dashboard locked");
});

async function start() {
  try {
    state.config = await request("/v1/dashboard/config");
    byId("buildVersion").textContent = `v${state.config.version}`;
    byId("serverState").textContent = "Server ready";
    byId("serverState").parentElement.classList.add("ready");
    const saved = localStorage.getItem("nimbusServerUrl");
    const inferred = location.protocol === "https:" ? location.origin : "";
    byId("serverUrl").value = state.config.serverUrl || saved || inferred;
    updateConnection();
  } catch (error) {
    byId("serverState").textContent = "Server unavailable"; showNotice(error.message, true);
  }

  try { saveAccount(JSON.parse(sessionStorage.getItem("nimbusDashboardAccount"))); } catch { saveAccount(null); }

  const fragment = new URLSearchParams(location.hash.replace(/^#/, ""));
  const launchToken = fragment.get("owner");
  if (launchToken) {
    history.replaceState(null, "", location.pathname + location.search);
    try { await request("/v1/dashboard/admin/redeem", { method: "POST", body: JSON.stringify({ token: launchToken }) }); }
    catch (error) { showNotice(error.message, true); }
  }
  await loadOwnerSummary();
}

start();
