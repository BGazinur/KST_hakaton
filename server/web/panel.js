const element = (id) => document.getElementById(id);
const roles = { engineer: "Инженер", supervisor: "Мастер", admin: "Администратор" };
const labels = {
  running: "В работе", stopped: "Остановлено", normal: "Норма", warning: "Предупреждение",
  alarm: "Тревога", unknown: "Нет данных", scheduled: "Запланировано", due: "Срок наступил",
  due_soon: "Срок приближается", pass: "Успешно", fail: "Не пройдена", inconclusive: "Нет заключения",
  no_visible_issue: "Признаков нет", needs_check: "Нужна проверка", possible_defect: "Возможный дефект",
  confirmed_defect: "Дефект подтверждён", skipped: "Пропущено", service: "Обслуживание",
  replacement: "Замена", repair: "Ремонт", qr: "QR", nameplate: "Шильдик",
  passport: "Паспорт", manual: "Руководство", operating_map: "Режимная карта",
  test_protocol: "Протокол", maintenance_regulation: "Регламент ТО",
};
const channels = {
  speed_rpm: ["Обороты", "об/мин"],
  overall_rms_mm_s: ["Общее СКЗ вибрации", "мм/с"],
  amplitude_1x_mm_s: ["Амплитуда 1×", "мм/с"],
  amplitude_2x_mm_s: ["Амплитуда 2×", "мм/с"],
  hf_noise_g: ["ВЧ-шум", "g"],
  pump_bearing_c: ["Температура подшипника насоса", "°C"],
  motor_winding_c: ["Температура обмоток двигателя", "°C"],
};

let token;
let profile;
let selectedId;
let selection = 0;
let assets = [];
const admin = () => profile?.role === "admin";
const canVerify = () => admin() || profile?.role === "supervisor";
const format = (value) => value == null ? "—" : typeof value === "number" ? value.toLocaleString("ru-RU") : value;
const dateLabel = (value) => value ? value.replace("T", " ").replace(/\.\d+/, "").replace(/(Z|\+00:00)$/, " UTC") : "—";
const label = (key) => Object.hasOwn(labels, key) ? labels[key] : format(key);

function node(tag, value, className) {
  const result = document.createElement(tag);
  if (value != null) result.textContent = value;
  if (className) result.className = className;
  return result;
}

function button(value, handler, className = "secondary") {
  const result = node("button", value, className);
  result.type = "button";
  result.addEventListener("click", () => perform(result, handler));
  return result;
}

function notice(id, message, isError = false) {
  const target = element(id);
  target.textContent = message;
  target.hidden = !message;
  target.classList.toggle("error", isError);
}

function signedOut() {
  token = undefined;
  profile = undefined;
  selectedId = undefined;
  assets = [];
  selection += 1;
  document.querySelectorAll("form").forEach((form) => form.reset());
  for (const id of ["equipment-list", "twin-body", "reports", "readings-body", "sample-history-body", "users"]) {
    element(id).replaceChildren();
  }
  element("workspace").hidden = true;
  element("session").hidden = true;
  element("login-view").hidden = false;
}

async function api(path, body) {
  const requestToken = token;
  const headers = requestToken ? { Authorization: `Bearer ${requestToken}` } : {};
  if (body && !(body instanceof FormData)) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(body);
  }
  const response = await fetch(path, { method: body === undefined ? "GET" : "POST", body, headers, cache: "no-store" });
  if (response.ok) return response;
  const { detail } = await response.json().catch(() => ({}));
  if (response.status === 401 && requestToken && requestToken === token) {
    signedOut();
    notice("login-status", "Сессия истекла. Войдите заново.", true);
  }
  const fields = { nameplate: "номер шильдика", email: "почта", password: "пароль", measured_at: "время замера" };
  const errors = Array.isArray(detail) ? detail.map((error) => fields[error.loc.at(-1)]).filter(Boolean) : [];
  const fallback = errors.length ? `Проверьте поля: ${[...new Set(errors)].join(", ")}.` : "Проверьте поля формы.";
  throw new Error(typeof detail === "string" ? detail : response.status === 422 ? fallback : "Сервер не выполнил запрос.");
}

async function json(path, body) {
  return (await api(path, body)).json();
}

async function perform(control, action, status = "panel-status") {
  control.disabled = true;
  notice(status, "");
  try {
    await action();
  } catch (error) {
    notice(status, error instanceof TypeError ? "Нет связи с локальным сервером. Попробуйте ещё раз." : error.message, true);
  } finally {
    control.disabled = false;
  }
}

function submit(id, action, status) {
  const form = element(id);
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    perform(event.submitter ?? form.querySelector("button"), () => action(form), status);
  });
}

function table(container, headers, values, empty) {
  container.replaceChildren();
  if (!values.length) {
    container.append(node("p", empty, "muted"));
    return;
  }
  const table = node("table");
  const head = table.createTHead().insertRow();
  headers.forEach((title) => {
    const heading = node("th", title);
    heading.scope = "col";
    head.append(heading);
  });
  const body = table.createTBody();
  values.forEach((row) => {
    const line = body.insertRow();
    row.forEach((value) => line.insertCell().append(value instanceof Node ? value : format(value)));
  });
  const wrap = node("div", null, "table-wrap");
  wrap.append(table);
  container.append(wrap);
}

function options(select, values) {
  const before = select.value;
  select.replaceChildren(...values.map(([value, title]) => new Option(title, value)));
  if ([...select.options].some((option) => option.value === before)) select.value = before;
}

async function download(path, filename) {
  const blob = await (await api(path)).blob();
  const url = URL.createObjectURL(blob);
  const link = node("a");
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
}

function renderInventory() {
  element("equipment-count").textContent = assets.length;
  element("equipment-list").replaceChildren(...assets.map((asset) => {
    const item = node("li");
    const control = button(asset.name, () => loadAsset(asset.id), "inventory-item");
    control.setAttribute("aria-current", asset.id === selectedId ? "true" : "false");
    control.append(node("small", asset.asset_tag));
    item.append(control);
    return item;
  }));
  options(element("parent-equipment"), [["", "Без родителя"], ...assets.map((asset) => [asset.id, asset.name])]);
}

async function loadInventory(preferred) {
  assets = await json("/equipment");
  if (!assets.length) {
    renderInventory();
    element("equipment-list").append(node("li", "Оборудование пока не добавлено.", "muted"));
    element("equipment-content").hidden = true;
    return;
  }
  await loadAsset(preferred ?? assets[0].id);
}

async function loadAsset(id) {
  if (selectedId !== id) {
    document.querySelectorAll("#asset-forms form").forEach((form) => form.reset());
    element("equipment-content").hidden = true;
  }
  selectedId = id;
  const turn = ++selection;
  renderInventory();
  const base = `/equipment/${id}`;
  const [passport, reports, history] = await Promise.all([
    json(`${base}/passport`), json(`${base}/reports?limit=20`), json(`${base}/samples`),
  ]);
  if (turn !== selection) return;
  const asset = passport.equipment;
  element("asset-tag").textContent = asset.asset_tag;
  element("asset-name").textContent = asset.name;
  element("asset-details").textContent = [asset.model, asset.serial_number, label(passport.operating_state?.state ?? "unknown")]
    .filter(Boolean).join(" · ");
  renderReadings(passport, history);
  renderTwin(passport);
  renderReports(reports);
  element("verification").hidden = !canVerify() || !passport.sensors.length;
  element("asset-forms").hidden = !admin() && element("verification").hidden;
  options(element("verification-sensor"), passport.sensors.map((sensor) => [sensor.code, sensor.label]));
  const codes = new Set(passport.sensors.map((sensor) => sensor.code));
  const free = Object.entries(channels).filter(([code]) => !codes.has(code));
  element("sensor-form").querySelector("button").disabled = !free.length;
  element("sensor-form").closest("details").hidden = !admin() || !free.length;
  options(element("sensor-type"), free.map(([code, [name]]) => [code, name]));
  setSensorDefaults();
  element("equipment-content").hidden = false;
}

function renderReadings(passport, history) {
  const sample = passport.telemetry;
  const source = element("sample-source");
  source.hidden = !sample;
  source.textContent = sample?.source === "replay" ? "Демонстрация" : "Внесённый замер";
  element("sample-date").textContent = sample ? `Замер ${dateLabel(sample.measured_at)}` : "";
  element("resume-replay").hidden = !admin() || passport.equipment.id !== 1 || sample?.source !== "live";
  const values = new Map(passport.sensors.map((sensor) => [sensor.code, [
    sensor.label, sensor.reading, sensor.unit, sensor.nominal_value, sensor.warning_above, sensor.alarm_above,
    node("span", label(sensor.status), `reading-status ${sensor.status}`),
  ]]));
  if (sample) {
    for (const [code, [name, unit]] of Object.entries(channels)) {
      if (!values.has(code)) values.set(code, [name, sample[code], unit, null, null, null, "Без уставок"]);
    }
  }
  table(element("readings-body"), ["Показатель", "Замер", "Ед.", "Номинал", "Предупр. выше", "Тревога выше", "Статус"],
    [...values.values()], "Для этого оборудования пока нет данных.");
  table(element("sample-history-body"), ["Время", "об/мин", "СКЗ", "1×", "2×", "ВЧ-шум", "Подшипник, °C", "Обмотки, °C"],
    history.map((row) => [dateLabel(row.measured_at), ...Object.keys(channels).map((code) => row[code])]),
    "Внесённых замеров пока нет.");
}

function section(title, children) {
  if (!children.length) return null;
  const details = node("details");
  details.append(node("summary", title), ...children);
  return details;
}

function renderTwin(passport) {
  const { equipment: asset } = passport;
  const nodes = [];
  if (asset.description) nodes.push(node("p", asset.description, "preserve"));
  const facts = [
    asset.installed_on && `Установлено ${asset.installed_on}`,
    passport.operating_state?.run_hours != null && `${format(passport.operating_state.run_hours)} моточасов`,
    ...passport.identifiers.map((item) => `${label(item.kind)} · ${item.value}`),
  ].filter(Boolean);
  if (facts.length) nodes.push(node("p", facts.join("  ·  "), "muted"));
  const block = (title, children) => {
    const row = section(title, children);
    if (row) nodes.push(row);
  };
  block("Характеристики", Object.entries(asset.specifications ?? {}).map(([key, value]) =>
    node("p", `${key} · ${typeof value === "object" ? JSON.stringify(value) : format(value)}`)));
  block("Узлы", passport.components.map((item) => button(`${item.name} · ${item.asset_tag}`, () => loadAsset(item.id))));
  block("Линии", passport.lines.map((line) =>
    node("p", `${line.name} · ${line.equipment.map((item) => item.name).join(" → ")}`)));
  block("Режимы работы", passport.operating_modes.map((mode) => node("p", `${mode.name} · ${mode.description}`)));
  block("Планы и история ТО", [
    ...passport.maintenance.map((item) => node("p", `${item.name} · ${item.message ?? label(item.status)}`)),
    ...passport.maintenance_history.map((item) => node("p", `${dateLabel(item.performed_at)} · ${label(item.action)} · ${item.notes}`)),
  ]);
  block("Испытания и поверки", passport.tests.map((test) => {
    const row = node("div", null, "entry");
    const parts = [test.title, test.performed_at, label(test.outcome),
      test.valid_until && `до ${test.valid_until}`, test.certificate_number, test.details].filter(Boolean);
    row.append(node("p", parts.join(" · ")));
    if (test.protocol_document_id) {
      const metadata = passport.documents.find((item) => item.id === test.protocol_document_id);
      row.append(button("Скачать протокол", () => download(`/documents/${test.protocol_document_id}`, metadata?.filename ?? "protocol.pdf")));
    }
    return row;
  }));
  block("Документы", passport.documents.map((item) => {
    const row = node("div", null, "entry");
    row.append(node("p", [item.title, label(item.kind), item.revision].filter(Boolean).join(" · ")),
      button("Скачать", () => download(`/documents/${item.id}`, item.filename)));
    return row;
  }));
  block("Шаги осмотра", passport.checks.map((item) => {
    const row = node("div", null, "entry");
    row.append(node("strong", `${item.position}. ${item.title}`), node("p", item.instructions),
      node("p", `Критерий · ${item.acceptance_criteria}`), node("p", `Рекомендация · ${item.recommendation}`));
    return row;
  }));
  if (!nodes.length) nodes.push(node("p", "Дополнительные сведения пока не добавлены.", "muted"));
  element("twin-body").replaceChildren(...nodes);
}

function renderReports(reports) {
  const entries = reports.map((report) => {
    const content = node("div", null, "report-details");
    const actions = node("div", null, "actions");
    for (const type of ["pdf", "xlsx"]) {
      actions.append(button(type === "pdf" ? "Скачать PDF" : "Скачать Excel",
        () => download(`/reports/${report.id}/export/${type}`, `inspection-${report.id}.${type}`)));
    }
    const load = button("Показать результаты", async () => {
      const data = await json(`/reports/${report.id}`);
      table(content, ["Проверка", "Результат", "Наблюдение"],
        data.items.map((item) => [item.check_snapshot.title, label(item.outcome), item.observation || "—"]),
        "В отчёте нет результатов.");
      content.prepend(node("p", `Автор · ${data.author_name}`, "muted"));
      for (const attachment of data.attachments) {
        content.append(button(attachment.filename,
          () => download(`/reports/${report.id}/attachments/${attachment.id}`, attachment.filename)));
      }
    });
    content.append(load);
    return section(`Отчёт №${report.id} · ${dateLabel(report.created_at)}`, [
      ...(report.notes ? [node("p", report.notes, "preserve")] : []), actions, content,
    ]);
  });
  element("reports").replaceChildren(...(entries.length ? entries : [node("p", "Отчётов пока нет.", "muted")]));
}

function tab(users) {
  element("equipment-view").hidden = users;
  element("users-view").hidden = !users;
  element("show-equipment").setAttribute("aria-pressed", String(!users));
  element("show-users").setAttribute("aria-pressed", String(users));
}

async function loadUsers() {
  const result = await json("/admin/users");
  if (!admin()) return;
  table(element("users"), ["Имя", "Почта", "Роль", "Статус"],
    result.map((user) => [user.display_name, user.email, roles[user.role], user.active ? "Активен" : "Отключён"]),
    "Учётных записей пока нет.");
}

function jsonForm(form, numbers = []) {
  const entries = [...new FormData(form)].filter(([, value]) => value.trim() !== "");
  return Object.fromEntries(entries.map(([key, value]) => [key, numbers.includes(key) ? Number(value) : value]));
}

submit("login-form", async (form) => {
  const { access_token: accessToken } = await json("/auth/login", Object.fromEntries(new FormData(form)));
  token = accessToken;
  form.reset();
  profile = await json("/auth/me");
  element("profile").textContent = `${profile.display_name} · ${roles[profile.role]}`;
  element("login-view").hidden = true;
  element("workspace").hidden = false;
  element("session").hidden = false;
  element("show-users").hidden = !admin();
  element("add-equipment").hidden = !admin();
  document.querySelectorAll("[data-admin]").forEach((item) => { item.hidden = !admin(); });
  notice("panel-status", "");
  tab(false);
  await perform(element("show-equipment"), loadInventory);
}, "login-status");

element("logout").addEventListener("click", async () => {
  try { await api("/auth/logout", {}); } catch { /* Clear the browser session even if the server is unreachable. */ }
  signedOut();
});
element("show-equipment").addEventListener("click", () => {
  tab(false);
  notice("panel-status", "");
  if (!assets.length) perform(element("show-equipment"), loadInventory);
});
element("show-users").addEventListener("click", () => perform(element("show-users"), async () => {
  if (!admin()) return;
  tab(true);
  await loadUsers();
}));
element("refresh").addEventListener("click", () => perform(element("refresh"), () => loadAsset(selectedId)));
element("resume-replay").addEventListener("click", () => perform(element("resume-replay"), async () => {
  const id = selectedId;
  await api(`/admin/equipment/${id}/resume-replay`, {});
  if (selectedId === id) await loadAsset(id);
  notice("panel-status", "Воспроизведение демонстрации возобновлено.");
}));

submit("equipment-form", async (form) => {
  const data = jsonForm(form, ["parent_id"]);
  const { id } = await json("/admin/equipment", data);
  form.reset();
  await loadInventory(data.parent_id ?? id);
  notice("panel-status", `Оборудование добавлено · №${id}`);
});
submit("user-form", async (form) => {
  await json("/admin/users", Object.fromEntries(new FormData(form)));
  form.reset();
  await loadUsers();
  notice("panel-status", "Пользователь создан.");
});

function setSensorDefaults() {
  const form = element("sensor-form");
  const channel = channels[element("sensor-type").value];
  if (channel) [form.elements.label.value, form.elements.unit.value] = channel;
}
element("sensor-type").addEventListener("change", setSensorDefaults);

function addToAsset(formId, path, prepare, message) {
  submit(formId, async (form) => {
    const id = selectedId;
    await api(path(id), prepare(form));
    form.reset();
    if (selectedId === id) await loadAsset(id);
    notice("panel-status", message);
  });
}

addToAsset("sample-form", (id) => `/admin/equipment/${id}/samples`, (form) => {
  const data = jsonForm(form, Object.keys(channels));
  data.measured_at = new Date(data.measured_at).toISOString();
  return data;
}, "Замер сохранён.");
addToAsset("sensor-form", (id) => `/admin/equipment/${id}/sensors`,
  (form) => jsonForm(form, ["nominal_value", "warning_above", "alarm_above"]), "Датчик добавлен.");
addToAsset("check-form", (id) => `/admin/equipment/${id}/checks`, jsonForm, "Шаг осмотра добавлен.");
addToAsset("document-form", (id) => `/equipment/${id}/documents`, (form) => new FormData(form), "Документ добавлен.");
addToAsset("verification-form", (id) => `/equipment/${id}/verifications`, (form) => {
  const data = new FormData(form);
  for (const [key, value] of [...data]) {
    if (value === "" || (value instanceof File && !value.name)) data.delete(key);
  }
  return data;
}, "Поверка сохранена.");
