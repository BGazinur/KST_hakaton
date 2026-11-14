const W = 412;
const H = 916;
const SERIAL = "KST-042";
const PUMP = "Центробежный насос";
const SEAL = "Уплотнение вала";
const FOUNDATION = "Фундаментная рама";
const SENSOR = "Повышена температура подшипника";
const DEFECT = "Возможная утечка у уплотнения";
const colors = {
  night: "#071317",
  glass: "#112428",
  cyan: "#65F3EB",
  amber: "#FFC063",
  text: "#E6F1F1",
  muted: "#C1D5D6",
  outline: "#567174",
  red: "#9A2525",
};
let font;

function rgb(hex) {
  const value = parseInt(hex.slice(1), 16);
  return { r: (value >> 16) / 255, g: ((value >> 8) & 255) / 255, b: (value & 255) / 255 };
}

function paint(hex, opacity = 1) {
  return { type: "SOLID", color: rgb(hex), opacity };
}

function gradient(top, bottom) {
  return {
    type: "GRADIENT_LINEAR",
    gradientTransform: [[0, 1, 0], [-1, 0, 1]],
    gradientStops: [top, bottom].map((hex, position) => ({ position, color: { ...rgb(hex), a: 1 } })),
  };
}

function place(parent, node, x = 0, y = 0) {
  parent.appendChild(node);
  node.x = x;
  node.y = y;
  return node;
}

function frame(parent, name, width, height, x = 0, y = 0) {
  const node = figma.createFrame();
  node.name = name;
  node.fills = [];
  node.clipsContent = false;
  node.resize(width, height);
  return place(parent, node, x, y);
}

function stack(node, { padding = 0, gap = 8 } = {}) {
  const width = node.width;
  node.layoutMode = "VERTICAL";
  node.primaryAxisSizingMode = "AUTO";
  node.counterAxisSizingMode = "FIXED";
  node.paddingTop = node.paddingRight = node.paddingBottom = node.paddingLeft = padding;
  node.itemSpacing = gap;
  node.resize(width, Math.max(1, node.height));
  return node;
}

function border(node, color = colors.cyan, opacity = 0.34) {
  node.strokes = [paint(color, opacity)];
  node.strokeWeight = 1;
  node.strokeAlign = "INSIDE";
}

function rect(parent, name, x, y, width, height, fill, radius = 0) {
  const node = figma.createRectangle();
  node.name = name;
  node.resize(width, height);
  node.fills = fill;
  node.cornerRadius = radius;
  return place(parent, node, x, y);
}

function text(parent, value, { size = 14, color = colors.muted, bold = false, width, center = false } = {}) {
  const node = figma.createText();
  node.fontName = bold ? font.medium : font.regular;
  node.fontSize = size;
  node.lineHeight = { value: size + (size >= 24 ? 8 : 6), unit: "PIXELS" };
  node.characters = value;
  node.fills = [paint(color)];
  node.textAlignHorizontal = center ? "CENTER" : "LEFT";
  parent.appendChild(node);
  if (parent.layoutMode === "VERTICAL") {
    node.textAutoResize = "HEIGHT";
    node.layoutSizingHorizontal = "FILL";
  } else if (width) {
    node.textAutoResize = "HEIGHT";
    node.resize(width, node.height);
  } else {
    node.textAutoResize = "WIDTH_AND_HEIGHT";
  }
  return node;
}

function title(parent, value, size = 16) {
  return text(parent, value, { size, color: colors.cyan, bold: true });
}

function shape(parent, name, x, y, width, height, body) {
  const node = figma.createNodeFromSvg(`<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">${body}</svg>`);
  node.name = name;
  return place(parent, node, x, y);
}

function panel(parent, name, width, padding = 16, dialog = false) {
  const node = stack(frame(parent, name, width, 1), { padding, gap: dialog ? 12 : 8 });
  node.cornerRadius = 18;
  node.fills = [paint(dialog ? colors.night : colors.glass, dialog ? 1 : 0.93)];
  if (!dialog) border(node);
  return node;
}

function screen(parent, name, camera = true) {
  const node = frame(parent, name, W, H);
  node.cornerRadius = 8;
  node.clipsContent = true;
  const background = rect(node, camera ? "Фон камеры — замените заливку кадром" : "Фон",
    0, 0, W, H, [gradient(camera ? "#0D2225" : "#153338", colors.night)]);
  background.constraints = { horizontal: "STRETCH", vertical: "STRETCH" };
  const time = text(node, "10:24", { color: colors.text, size: 13, bold: true });
  time.name = "Системная строка · время";
  time.x = 16;
  time.y = 13;
  const nav = rect(node, "Системный жест", (W - 112) / 2, H - 17, 112, 4, [paint(colors.text)], 2);
  nav.constraints = { horizontal: "CENTER", vertical: "MAX" };
  return node;
}

function arc(parent) {
  const radius = W * 0.43;
  const diameter = Math.round(radius * 2 + 6);
  const center = diameter / 2;
  const segments = Array.from({ length: 11 }, (_, i) => {
    const start = (35 + i * 10) * Math.PI / 180;
    const end = start + Math.PI / 30;
    const point = (angle) => `${center + radius * Math.cos(angle)} ${center + radius * Math.sin(angle)}`;
    return `<path d="M ${point(start)} A ${radius} ${radius} 0 0 1 ${point(end)}" opacity="${i === 5 ? 0.7 : 0.34}"/>`;
  }).join("");
  shape(parent, "Дуга HUD", (W - diameter) / 2, H * 0.56 - center, diameter, diameter,
    `<g fill="none" stroke="${colors.cyan}" stroke-width="3" stroke-linecap="round">${segments}</g>`);
}

function qrGuide(parent) {
  shape(parent, "Ориентир QR", W / 2 - 96, H * 0.42 - 96, 192, 192,
    `<path d="M2 58 V2 H58 M134 2 H190 V58 M190 134 V190 H134 M58 190 H2 V134" fill="none" stroke="${colors.cyan}" stroke-width="2"/>`);
}

function cameraHud(parent, { qr = false, warning = false } = {}) {
  arc(parent);
  if (qr) qrGuide(parent);
  const badge = panel(parent, "Деморежим", 98, 7);
  badge.cornerRadius = 18;
  badge.x = 16;
  badge.y = 44;
  text(badge, "Деморежим", { size: 12, color: colors.cyan, bold: true, center: true });
  const handle = frame(parent, "Открыть правую панель", 20, 93, W - 20, (H - 93) / 2);
  handle.fills = [paint(colors.glass, 0.93)];
  handle.cornerRadius = 10;
  handle.constraints = { horizontal: "MAX", vertical: "CENTER" };
  border(handle);
  shape(handle, "Шеврон", 6.5, 40.5, 7, 12,
    `<path d="M6 1 1 6l5 5" fill="none" stroke="${colors.cyan}" stroke-width="2" stroke-linecap="round"/>`);
  if (warning) {
    const alert = frame(parent, "Предупреждения по датчикам", 48, 48, W - 60, 42);
    alert.fills = [paint(colors.red)];
    alert.cornerRadius = 9;
    const symbol = text(alert, "!", { size: 24, width: 48, color: "#FFFFFF", center: true });
    symbol.y = 8;
  }
}

function buttons(library) {
  const result = {};
  const styles = [
    { key: "primary", name: "Основная", fill: colors.cyan, ink: colors.night, radius: 10 },
    { key: "outline", name: "Контурная", ink: colors.muted, radius: 24 },
    { key: "text", name: "Текстовая", ink: colors.cyan, radius: 24 },
  ];
  for (const [index, style] of styles.entries()) {
    const component = figma.createComponent();
    component.name = "Кнопка / " + style.name;
    component.resize(292, 44);
    component.cornerRadius = style.radius;
    component.fills = style.fill ? [paint(style.fill)] : [];
    if (style.key === "outline") border(component, colors.outline, 1);
    stack(component, { padding: 12, gap: 0 });
    component.minHeight = 44;
    const label = text(component, "Действие", { color: style.ink, bold: true, center: true });
    const property = component.addComponentProperty("Подпись", "TEXT", "Действие");
    label.componentPropertyReferences = { characters: property };
    place(library, component, 24, 68 + index * 66);
    result[style.key] = { component, property };
  }
  return result;
}

function button(parent, kit, label, style = "primary") {
  const source = kit[style];
  const instance = source.component.createInstance();
  parent.appendChild(instance);
  instance.name = label;
  instance.setProperties({ [source.property]: label });
  instance.layoutSizingHorizontal = "FILL";
  return instance;
}

function field(parent, label, multiline = false) {
  const node = stack(frame(parent, label, 240, 1), { padding: 14 });
  node.minHeight = multiline ? 76 : 54;
  node.cornerRadius = 4;
  node.layoutSizingHorizontal = "FILL";
  border(node, colors.outline, 1);
  text(node, label, { size: 16 });
  return node;
}

function bottomPanel(parent) {
  const node = panel(parent, "Нижняя карточка", W - 48);
  node.x = 24;
  return node;
}

function finishBottom(node) {
  node.y = H - 35 - node.height;
  node.constraints = { horizontal: "STRETCH", vertical: "MAX" };
}

function centered(node) {
  node.x = (node.parent.width - node.width) / 2;
  node.y = (node.parent.height - node.height) / 2;
  node.constraints = { horizontal: "CENTER", vertical: "CENTER" };
}

function contentRow(parent, label) {
  const row = panel(parent, label, 240, 12);
  row.cornerRadius = 14;
  row.layoutSizingHorizontal = "FILL";
  title(row, label + " ›", 14);
}

function marker(parent) {
  const mark = frame(parent, "Пример AR-метки", 225, 65, 153, 450);
  shape(mark, "Указатель", 0, 0, 62, 61,
    `<circle cx="15" cy="15" r="12" fill="${colors.amber}" fill-opacity=".22" stroke="${colors.amber}"/>
     <path d="M60 51 15 15l6 13M15 15l14 2" stroke="${colors.amber}" stroke-width="2" fill="none"/>`);
  const label = panel(mark, "Подпись метки", 172, 8);
  label.x = 53;
  label.y = 40;
  label.cornerRadius = 8;
  border(label, colors.amber, 1);
  text(label, "Метка осмотра", { color: colors.text, bold: true });
}

function login(parent, kit) {
  const node = screen(parent, "Вход", false);
  const form = panel(node, "Форма входа", W - 48, 24);
  form.itemSpacing = 12;
  const image = figma.createImage(figma.base64Decode(KST_LOGO_PNG));
  const logo = rect(form, "КСТ Энерго Инжиниринг", 0, 0, 292, 146, [
    { type: "IMAGE", imageHash: image.hash, scaleMode: "FIT" },
  ]);
  form.counterAxisAlignItems = "CENTER";
  logo.layoutSizingHorizontal = "FIXED";
  text(form, "Вход в аккаунт", { color: colors.text, size: 28, bold: true });
  text(form, "Для деморежима введите произвольный логин и пароль. Используйте вымышленные данные.");
  field(form, "Логин или почта");
  field(form, "Пароль");
  const signIn = button(form, kit, "Войти");
  centered(form);
  return { node, signIn };
}

function dialog(parent, name, kit, content) {
  const node = screen(parent, name);
  cameraHud(node);
  rect(node, "Затемнение", 0, 0, W, H, [paint("#000000", 0.58)]);
  const body = panel(node, name, 328, 20, true);
  title(body, name, 24);
  content(body);
  const close = button(body, kit, "Закрыть", "text");
  centered(body);
  return { node, close };
}

function section(name, x, y, width, height) {
  const node = figma.createSection();
  node.name = name;
  node.fills = [paint("#19292D")];
  node.resizeWithoutConstraints(width, height);
  node.x = x;
  node.y = y;
  return node;
}

function layout(screens, columns) {
  screens.forEach((node, index) => {
    node.x = 32 + (index % columns) * (W + 48);
    node.y = 44 + Math.floor(index / columns) * (H + 56);
  });
}

async function loadFonts() {
  const available = await figma.listAvailableFontsAsync();
  for (const family of ["Roboto", "Inter"]) {
    const regular = available.find(({ fontName }) => fontName.family === family && fontName.style === "Regular");
    if (!regular) continue;
    const medium = available.find(({ fontName }) => fontName.family === family && fontName.style === "Medium") || regular;
    await Promise.all([figma.loadFontAsync(regular.fontName), figma.loadFontAsync(medium.fontName)]);
    return { regular: regular.fontName, medium: medium.fontName };
  }
  throw new Error("В Figma недоступен шрифт Roboto или Inter.");
}

async function build() {
  font = await loadFonts();
  const view = figma.viewport.center;
  const startX = figma.currentPage.children.reduce((right, node) => {
    const box = node.absoluteBoundingBox;
    return box ? Math.max(right, box.x + box.width + 80) : right;
  }, view.x);
  const gridWidth = 32 * 2 + 4 * W + 3 * 48;
  const rowsHeight = 44 + 3 * H + 2 * 56 + 32;
  const sections = [];
  try {
    const flow = section("KST Pump · Осмотр", startX, view.y, gridWidth, rowsHeight);
    sections.push(flow);
    const more = section("KST Pump · Меню и диалоги", startX + gridWidth + 80, view.y, 32 * 2 + 2 * W + 48, rowsHeight);
    sections.push(more);
    const library = section("KST Pump · Компоненты", startX + gridWidth + more.width + 160, view.y, 340, 296);
    sections.push(library);
    const kit = buttons(library);
    const screens = new Map();
    const links = [];
    const go = (node, destination, trigger = "ON_CLICK") => links.push({ node, destination, trigger });
    const action = (panel, label, destination, style) => {
      go(button(panel, kit, label, style), destination);
    };
    const stage = (name, content, options) => {
      const node = screen(flow, name);
      screens.set(name, node);
      cameraHud(node, options);
      const bottom = bottomPanel(node);
      content(bottom);
      finishBottom(bottom);
      return node;
    };

    const { node: auth, signIn } = login(flow, kit);
    go(signIn, "QR-код");

    stage("QR-код", (card) => {
      title(card, "Наведите камеру на QR-код оборудования");
      action(card, "Использовать демо-QR", "Превью оборудования");
      action(card, "Выбрать вручную", "Ручной выбор", "text");
      action(card, "Проверить AR-метку", "Ручная AR-метка", "text");
    }, { qr: true });

    stage("Ручной выбор", (card) => {
      title(card, "Выберите оборудование");
      action(card, PUMP + " · " + SERIAL, "Превью оборудования", "outline");
      action(card, "Вернуться к QR-коду", "QR-код", "text");
    });

    stage("Превью оборудования", (card) => {
      title(card, PUMP);
      text(card, "Серийный номер " + SERIAL);
      action(card, "Открыть карточку", "Карточка оборудования", "outline");
      action(card, "Начать проверку", "Анализ показаний");
    });

    stage("Анализ показаний", (card) => {
      const status = frame(card, "Загрузка", 240, 20);
      status.layoutMode = "HORIZONTAL";
      status.layoutSizingHorizontal = "FILL";
      status.itemSpacing = 10;
      status.counterAxisAlignItems = "CENTER";
      shape(status, "Индикатор", 0, 0, 18, 18,
        `<path d="M9 2a7 7 0 1 1-7 7" stroke="${colors.cyan}" stroke-width="2" fill="none" stroke-linecap="round"/>`);
      text(status, "Анализ показаний…");
    }, {});

    stage("Предупреждение по датчику", (card) => {
      title(card, "Анализ показаний завершён");
      action(card, "Продолжить проверку", "Наведение на насос");
    }, { warning: true });

    stage("Наведение на насос", (card) => {
      title(card, "Поместите оборудование в кадр");
      action(card, "Показать демораспознавание насоса", "Осмотр узла");
    }, { warning: true });

    stage("Осмотр узла", (card) => {
      title(card, "1 из 2", 12);
      title(card, SEAL);
      text(card, "Наведите камеру на корпус в зоне вала.");
      action(card, "Показать деморезультат проверки", "Результат узла");
      action(card, "Пропустить узел", "Следующий узел", "outline");
    }, { warning: true });

    stage("Результат узла", (card) => {
      title(card, "1 из 2", 12);
      title(card, SEAL);
      text(card, "Обнаружены признаки возможного дефекта", { color: colors.amber });
      text(card, "На корпусе рядом с валом виден влажный участок.");
      text(card, "Осмотрите уплотнение и зафиксируйте результат.");
      action(card, "Далее", "Следующий узел");
    }, { warning: true });

    stage("Следующий узел", (card) => {
      title(card, "2 из 2", 12);
      title(card, FOUNDATION);
      text(card, "Наведите камеру на раму и анкерные болты.");
      action(card, "Показать деморезультат проверки", "Результат без замечаний");
      action(card, "Пропустить узел", "Отчёт", "outline");
    }, { warning: true });

    stage("Отчёт", (card) => {
      title(card, "Итог проверки");
      text(card, "Узлы · 2\nПредупреждения · 2\nПропущено · 1");
      field(card, "Заметка инженера", true);
      action(card, "Отправить отчёт", "Отчёт отправлен");
    }, { warning: true });

    stage("Отчёт отправлен", (card) => {
      title(card, "Итог проверки");
      text(card, "Узлы · 2\nПредупреждения · 2\nПропущено · 1");
      title(card, "Отчёт отправлен", 14);
      action(card, "Новая проверка", "QR-код");
    }, { warning: true });

    const supplemental = [];
    const addDialog = (name, content) => {
      const { node, close } = dialog(more, name, kit, content);
      go(close, "BACK");
      screens.set(name, node);
      supplemental.push(node);
    };

    addDialog("Карточка оборудования", (card) => {
      title(card, PUMP);
      text(card, "Серийный номер " + SERIAL);
      title(card, "Паспортные характеристики");
      text(card, "Модель · КСТ 65–40");
      title(card, "Показания и уставки");
      text(card, "Температура подшипника · 78 °C · уставка 75 °C");
      title(card, "Узлы и комплектующие");
      contentRow(card, SEAL);
      contentRow(card, FOUNDATION);
      title(card, "Неисправности предыдущих осмотров");
      contentRow(card, SENSOR);
    });

    addDialog("Текущие неисправности", (card) => {
      title(card, SENSOR);
      text(card, "По данным датчиков", { color: colors.amber });
      text(card, "Показание датчика превышает уставку.");
      text(card, "Температура подшипника · 78 °C · уставка 75 °C");
      title(card, "Рекомендация", 14);
      text(card, "Сверьте показание с контрольным измерением.");
      contentRow(card, DEFECT);
    });

    addDialog("История проверок", (card) => {
      contentRow(card, "14 нояб. 2026 г. · 10:24");
      title(card, PUMP);
      text(card, SEAL + " · Обнаружены признаки возможного дефекта");
      text(card, FOUNDATION + " · Узел пропущен");
      contentRow(card, "13 нояб. 2025 г. · 10:00");
    });

    addDialog("Аккаунт", (card) => {
      text(card, "ФИО · Алексей Иванов");
      text(card, "Должность · Инженер по эксплуатации");
      text(card, "Логин или почта · engineer@example.test");
      action(card, "Выйти", "Вход");
    });
    screens.set("Вход", auth);

    const drawer = screen(more, "Правое меню");
    screens.set(drawer.name, drawer);
    supplemental.unshift(drawer);
    cameraHud(drawer, { warning: true });
    rect(drawer, "Затемнение", 0, 0, W, H, [paint("#000000", 0.62)]);
    const side = frame(drawer, "Правая панель", 320, H - 56, W - 320, 26);
    side.fills = [paint(colors.night)];
    side.cornerRadius = 18;
    const menu = stack(frame(side, "Пункты меню", 288, 1, 16, 16));
    action(menu, "Закрыть", "BACK", "text");
    for (const name of ["Карточка оборудования", "Текущие неисправности", "История проверок"]) {
      action(menu, name, name, "text");
    }
    const account = stack(frame(side, "Нижний пункт", 288, 1, 16, side.height - 70));
    rect(account, "Разделитель", 0, 0, 288, 1, [paint(colors.outline)]);
    action(account, "Аккаунт", "Аккаунт", "text");

    const manual = screen(more, "Ручная AR-метка");
    screens.set(manual.name, manual);
    supplemental.push(manual);
    cameraHud(manual);
    marker(manual);
    const manualCard = bottomPanel(manual);
    title(manualCard, "Проверить AR-метку");
    text(manualCard, "Смените ракурс. Стрелка должна указывать на ту же точку детали.");
    button(manualCard, kit, "Убрать метку", "outline");
    action(manualCard, "Вернуться к осмотру", "QR-код", "text");
    finishBottom(manualCard);

    const clear = screen(more, "Результат без замечаний");
    screens.set(clear.name, clear);
    supplemental.push(clear);
    cameraHud(clear, { warning: true });
    const clearCard = bottomPanel(clear);
    title(clearCard, "2 из 2", 12);
    title(clearCard, FOUNDATION);
    text(clearCard, "Видимых проблем не обнаружено");
    action(clearCard, "Далее", "Отчёт");
    finishBottom(clearCard);

    for (const node of [...flow.children, manual, clear]) {
      const handle = node.children.find((child) => child.name === "Открыть правую панель");
      const alert = node.children.find((child) => child.name === "Предупреждения по датчикам");
      if (handle) go(handle, drawer.name);
      if (alert) go(alert, "Текущие неисправности");
    }
    go(screens.get("Анализ показаний"), "Предупреждение по датчику", "AFTER_TIMEOUT");

    layout(flow.children, 4);
    layout(supplemental, 2);
    more.resizeWithoutConstraints(more.width, 44 + 4 * H + 3 * 56 + 32);
    for (const { node, destination, trigger } of links) {
      const actions = destination === "BACK" ? [{ type: "BACK" }] : [{
        type: "NODE",
        destinationId: screens.get(destination).id,
        navigation: "NAVIGATE",
        transition: null,
        preserveScrollPosition: false,
      }];
      await node.setReactionsAsync([{
        trigger: trigger === "AFTER_TIMEOUT" ? { type: trigger, timeout: 650 } : { type: trigger },
        actions,
      }]);
    }
    figma.currentPage.flowStartingPoints = [
      ...figma.currentPage.flowStartingPoints,
      { nodeId: auth.id, name: "KST Pump · Демоосмотр" },
    ];
    figma.currentPage.selection = [auth];
    figma.viewport.scrollAndZoomIntoView([auth]);
    figma.closePlugin(`KST Pump: создано экранов — ${screens.size}.`);
  } catch (error) {
    for (const node of sections) node.remove();
    throw error;
  }
}

// Same PNG as app/src/main/res/drawable-nodpi/kst_energy_logo.png, embedded for offline import.
const KST_LOGO_PNG = "iVBORw0KGgoAAAANSUhEUgAAAlgAAAEsCAYAAAAfPc2WAACol0lEQVR4Ae3AA6AkWZbG8f937o3IzKdyS2Oubdu2bdu2bdu2bWmMnpZKr54yMyLu+Xa3anqmhztr1a+efNcfNFddddVVV1111VVX/UchuOqqq6666qqrrrrqPxLBVVddddVVV1111VX/kQiuuuqqq6666qqrrvqPRHDVVVddddVVV1111X8kgquuuuqqq6666qqr/iMRXHXVVVddddVVV131H4ngqquuuuqqq6666qr/SARXXXXVVVddddVVV/1HIrjqqquuuuqqq6666j8SwVVXXXXVVVddddVV/5EIrrrqqquuuuqqq676j0Rw1VVXXXXVVVddddV/JIKrrrrqqquuuuqqq/4jEVx11VVXXXXVVVdd9R+J4Kqrrrrqqquuuuqq/0gEV1111VVXXXXVVVf9RyK46qqrrrrqqquuuuo/EsFVV1111VVXXXXVVf+RCK666qqrrrrqqquu+o9EcNVVV1111VVXXXXVfySCq6666qqrrrrqqqv+IxFcddVVV1111VVXXfUfieCqq6666qqrrrrqqv9IBFddddVVV1111VVX/UciuOqqq6666qqrrrrqPxLBVVddddVVV1111VX/kQiuuuqqq6666qqrrvqPRHDVVVddddVVV1111X8kgquuuuqqq6666qqr/iMRXHXVVVddddVVV131H4ngqquuuuqqq6666qr/SARXXXXVVVddddVVV/1HIrjqqquuuuqqq6666j8SwVVXXXXVVVddddVV/5EIrrrqqquuuuqqq676j0Rw1VVXXXXVVVddddV/JIKrrrrqqquuuuqqq/4jEVx11VVXXXXVVVdd9R+J4Kqrrrrqqquuuuqq/0gEV1111VVXXXXVVVf9RyK46qqrrrrqqquuuuo/EsFVV1111VVXXXXVVf+RCK666qqrrrrqqquu+o9EcNVVV1111VVXXXXVfySCq6666qqrrrrqqqv+IxFcddVVV1111VVXXfUfieCqq6666qqrrrrqqv9IBFddddVVV1111VVX/UciuOqqq6666qqrrrrqPxLBVVddddVVV1111VX/kQiuuuqqq6666qqrrvqPRHDVVVddddVVV1111X8kgquuuuqqq6666qqr/iMRXHXVVVddddVVV131H4ngqquuuuqqq6666qr/SARXXXXVVVddddVVV/1HIrjqqquuuuqqq6666j8SwVVXXXXVVVddddVV/5EIrrrqqquuuuqqq676j0Rw1VVXXXXVVVddddV/JIKrrrrqqquuuuqqq/4jEVx11VVXXXXVVVdd9R+J4Kqrrrrqqquuuuqq/0gEV1111VVXXXXVVVf9RyK46qqrrrrqqquuuuo/EsFVV1111VVXXXXVVf+RCK666qqrrrrqqquu+o9EcNVVV1111VVXXXXVfySCq6666qqrrrrqqqv+IxFcddVVV1111VVXXfUfieCqq6666qqrrrrqqv9IBFddddVVV1111VVX/UciuOqqq6666qqrrrrqPxLBVVddddVVV1111VX/kQiuuuqqq6666qqrrvqPRHDVVVddddVVV1111X8kgquuuuqqq6666qqr/iMRXHXVVVddddVVV131H4ngqquuuuqqq6666qr/SARXXXXVVVddddVVV/1HIrjqqquuuuqqq6666j8SwVVXXXXVVVddddVV/5EIrrrqqquuuuqqq676j0Rw1VVXXXXVVVddddV/JIKrrrrqqquuuuqqq/4jEVx11VVXXXXVVVdd9R+J4Kqrrrrqqquuuuqq/0gEV1111VVXXXXVVVf9RyK46qqrrrrqqquuuuo/EsFVV1111VVXXXXVVf+RCK666qqrrrrqqquu+o9EcNVVV1111VVXXXXVfySCq6666qqrrrrqqqv+IxFcddVVV1111VVXXfUfieCqq6666qqrrrrqqv9IBFddddVVV1111VVX/UciuOqqq6666qqrrrrqPxLBVVddddVVV1111VX/kQiuuuqqq6666qqrrvqPRHDVVVddddVVV1111X8kgquuuuqqq6666qqr/iMRXHXVVVddddVVV131H4ngqquuuuqqq6666qr/SARXXfVClBTkDFyAxIKwqBZp/kUWWGCBBRZYYIHFVf/LZVSsxDEwBQwJkihqpFegIDFEY7QJJzUnWvQkJhqEk1ZHILEbMYpQhxMKIgzhJA2OGU4oCaIHiwloJVAJSkJpBmCUQYE8ohKMAiuJCFBgNwhhgQWJSYwFFhCiYNJBGiIC0dOcRJkoyVVXXXXVC0LlqqteiGk24EyqC5qCBBxiCDNgOotnE8+tWrwwibnqfy4HDyDCPIcyjQhTSo8oNMAN1pqR/QbzYQ0lsE1XAk/J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o3mD0QM7RaxizmzaJehJF1wrkSZiQbdaManS18Y4jVTm0HVkm+gMWXvKNLJJY4Ugl8y6BbluZK3Mp4mhr5QQkQk5UF0wQoBiE2mgTDBUKA6iTZhGSRHFJBNTWRDq6aY1lkBBeiBaIVUoeUDTNlFg5QZ1RkxJ2kQtZCYRQWZSVLBNukO1kZHUtWndBgWI6YCpbjC0NXVWma03mDxSamUogqg4KyUnolRWMVKGSiuVfoRZCcY6UVtjyg53lYwZhYlYDbhu4MlMZaRlpZWOmSciZjQnJXp679OAUibWsUGvibHNiBqMsUJTj2SuuuoFQCff9QfNVVddddVVV1111VX/UQiuuuqqq6666qqrrvqPRHDVVVddddVVV1111X8kgquuuuqqq6666qqr/iMRXHXVVVddddVVV131H4ngqquuuuqqq6666qr/SARXXXXVVVddddVVV/1HIrjqqquuuuqqq6666j8SwVVXXXXVVVddddVV/5EIrrrqqquuuuqqq676j0Rw1VVXXXXVVVddddV/JIKrrrrqqquuuuqqq/4jEVx11VVXXXXVVVdd9R+J4Kqrrrrqqquuuuqq/0gEV1111VVXXXXVVVf9RyK46qqrrrrqqquuuuo/EsFVV1111VVXXXXVVf+RCK666qqrrrrqqquu+o9EcNVVV1111VVXXXXVfySCq6666qqrrrrqqqv+IxFcddVVV1111VVXXfUfieCqq6666qqrrrrqqv9IBFddddVVV1111VVX/UciuOqqq6666qqrrrrqPxLBVVddddVVV1111VX/kQiuuuqqq6666qqrrvqPRHDVVVddddVVV1111X8kgquuuuqqq6666qqr/iMRXHXVVVddddVVV131H4ngqquuuuqqq6666qr/SARXXXXVVVddddVVV/1HIrjqqquuuuqqq6666j8SwVVXXXXVVVddddVV/5EIrrrqqquuuuqqq676j0Rw1VVXXXXVVVddddV/JIKrrrrqqquuuuqqq/4jEVx11VVXXXXVVVdd9R+J4Kqrrrrqqquuuuqq/0gEV1111VVXXXXVVVf9RyK46qqrrrrqqquuuuo/EsFVV1111VVXXXXVVf+RCK666qqrrrrqqquu+o9EcNVVV1111VVXXXXVfySCq6666qqrrrrqqqv+IxFcddVVV1111VVXXfUfieCqq6666qqrrrrqqv9IBFddddVVV1111VVX/UciuOqqq6666qqrrrrqPxLBVVddddVVV1111VX/kQiuuuqqq6666qqrrvqPRHDVVVddddVVV1111X8kgquuuuqqq6666qqr/iMRXHXVVVddddVVV131H4ngqquuuuqqq6666qr/SARXXXXVVVddddVVV/1HIrjqqquuuuqqq6666j8SwVVXXXXVVVddddVV/5EIrrrqqquuuuqqq676j0Rw1VVXXXXVVVddddV/JIKrrrrqqquuuuqqq/4jEVx11VVXXXXVVVdd9R+J4Kqrrrrqqquuuuqq/0gEV1111VVXXXXVVVf9RyK46qqrrrrqqquuuuo/EsFVV1111VVXXXXVVf+RCK666qqrrrrqqquu+o9EcNVVV1111VVXXXXVfySCq6666qqrrrrqqqv+IxFcddVVV1111VVXXfUfieCqq6666qqrrrrqqv9IBFddddVVV1111VVX/UciuOqqq6666qqrrrrqPxLBVVddddVVV1111VX/kQiuuuqqq6666qqrrvqPRHDVVVddddVVV1111X8kgquuuuqqq6666qqr/iMRXHXVVVddddVVV131H4ngqquuuuqqq6666qr/SARXXXXVVVddddVVV/1HIrjqqquuuuqqq6666j8SwVVXXXXVVVddddVV/5EIrrrqqquuuuqqq676j0Rw1VVXXXXVVVddddV/JIKrrrrqqquuuuqqq/4jEVx11VVXXXXVVVdd9R+J4Kqrrrrqqquuuuqq/0gEV1111VVXXXXVVVf9RyK46qqrrrrqqquuuuo/EsFVV1111VVXXXXVVf+RCK666qqrrrrqqquu+o9EcNVVV1111VVXXXXVfySCq6666qqrrrrqqqv+IxFcddVVV1111VVXXfUfieCqq6666qqrrrrqqv9IBFddddVVV1111VVX/UciuOqqq6666qqrrrrqPxLBVVddddVVV1111VX/kQiuuuqqq6666qqrrvqPRHDVVVddddVVV1111X8kgquuuuqqq6666qqr/iMRXHXVVVddddVVV131H4ngqquuuuqqq6666qr/SARXXXXVVVddddVVV/1HIrjqqquuuuqqq6666j8SwVVXXXXVVVddddVV/5EIrrrqqquuuuqqq676j0Rw1VVXXXXVVVddddV/JIKrrrrqqquuuuqqq/4j8Y9tdbvH48eovwAAAABJRU5ErkJggg==";

build().catch((error) => {
  console.error(error);
  figma.closePlugin("Не удалось собрать макеты KST Pump: " + error.message);
});
