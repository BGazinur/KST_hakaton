import os
from functools import lru_cache
from io import BytesIO
from pathlib import Path
from xml.sax.saxutils import escape

from openpyxl import Workbook
from openpyxl.cell.cell import ILLEGAL_CHARACTERS_RE
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

EXPORT_TYPES = {
    "pdf": "application/pdf",
    "xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
}
OUTCOMES = {
    "no_visible_issue": "Видимых дефектов нет",
    "needs_check": "Требуется проверка",
    "possible_defect": "Возможный дефект",
    "confirmed_defect": "Дефект подтверждён",
    "inconclusive": "Недостаточно данных",
    "skipped": "Пропущено",
}
SENSOR_STATUSES = {
    "normal": "Норма",
    "warning": "Предупреждение",
    "alarm": "Тревога",
    "unknown": "Нет показания или уставки",
}
TELEMETRY_LABELS = {
    "speed_rpm": ("Обороты", "об/мин"),
    "overall_rms_mm_s": ("СКЗ общее", "мм/с"),
    "amplitude_1x_mm_s": ("Амплитуда 1×", "мм/с"),
    "amplitude_2x_mm_s": ("Амплитуда 2×", "мм/с"),
    "hf_noise_g": ("ВЧ шум", "g"),
    "pump_bearing_c": ("Температура подшипника насоса", "°C"),
    "motor_winding_c": ("Температура обмоток", "°C"),
}


def report_sections(report):
    equipment = report["equipment_snapshot"]
    sample = report["telemetry_snapshot"] or {}
    source = {"replay": "Воспроизведение", "live": "Внесённый замер"}.get(sample.get("source"))
    yield "Сведения", ("Поле", "Значение"), [
        ("Отчёт", f"№{report['id']}"),
        ("Оборудование", equipment["name"]),
        ("Инвентарный номер", equipment["asset_tag"]),
        ("Модель", equipment.get("model")),
        ("Серийный номер", equipment.get("serial_number")),
        ("Автор", report["author_name"]),
        ("Создан", report["created_at"]),
        ("Время замера", sample.get("measured_at")),
        ("Источник замера", source),
        ("Диагноз по вибрации из записи", sample.get("vibration_diagnosis")),
        ("Диагноз по температуре из записи", sample.get("temperature_diagnosis")),
        ("Заметки", report["notes"]),
    ]
    sensor_rows = [
        (sensor["label"], sensor["reading"], sensor["unit"], sensor["nominal_value"],
         sensor["warning_above"], sensor["alarm_above"], SENSOR_STATUSES[sensor["status"]])
        for sensor in report["sensors_snapshot"]
    ]
    if not sensor_rows and sample:
        # Older reports have the measurements but never stored the historical limits.
        sensor_rows = [
            (label, sample[code], unit, None, None, None, None)
            for code, (label, unit) in TELEMETRY_LABELS.items() if code in sample
        ]
    if sensor_rows:
        yield "Показания", ("Показатель", "Замер", "Ед.", "Номинал", "Предупр. >", "Тревога >", "Статус"), sensor_rows

    yield "Шаги осмотра", ("Шаг", "Проверка", "Результат", "Наблюдение", "Рекомендация"), [
        (item["check_snapshot"]["position"], item["check_snapshot"]["title"], OUTCOMES[item["outcome"]],
         item["observation"], item["check_snapshot"]["recommendation"])
        for item in report["items"]
    ]
    if report["attachments"]:
        yield "Вложения", ("ID", "Шаг ID", "Файл"), [
            (item["id"], item["check_id"], item["filename"]) for item in report["attachments"]
        ]


@lru_cache(maxsize=1)
def pdf_font():
    windows = Path(os.environ.get("WINDIR", "C:/Windows"))
    configured = os.environ.get("INSPECTION_PDF_FONT")
    candidates = [Path(configured)] if configured else [
        windows / "Fonts" / "arial.ttf",
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
        Path("/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf"),
        Path("/System/Library/Fonts/Supplemental/Arial.ttf"),
    ]
    for path in candidates:
        if path.is_file():
            name = "Inspection"
            pdfmetrics.registerFont(TTFont(name, str(path)))
            return name
    raise FileNotFoundError("Set INSPECTION_PDF_FONT to a TrueType font with Cyrillic support")


def clean(value):
    return ILLEGAL_CHARACTERS_RE.sub("", "" if value is None else str(value))


def make_pdf(report):
    buffer = BytesIO()
    font = pdf_font()
    body = ParagraphStyle("Body", fontName=font, fontSize=10, leading=14, spaceAfter=5, splitLongWords=True)
    heading = ParagraphStyle(
        "Heading", parent=body, fontSize=13, leading=18, spaceBefore=12, textColor=colors.HexColor("#24465A"),
        keepWithNext=True,
    )
    title = ParagraphStyle("Title", parent=heading, fontSize=21, leading=27, spaceBefore=0)
    cell_style = ParagraphStyle("Cell", parent=body, fontSize=8.5, leading=11)

    def paragraph(value, style=body):
        return Paragraph(escape(clean(value)).replace("\n", "<br/>"), style)

    content = [paragraph(f"Отчёт об осмотре №{report['id']}", title)]
    for name, headers, records in report_sections(report):
        content.append(paragraph(name, heading))
        if name == "Показания":
            table_rows = [headers, *records]
            content.append(Table(
                [[paragraph(value, cell_style) for value in row] for row in table_rows],
                colWidths=(115, 44, 33, 52, 59, 53, 88),
                repeatRows=1,
                style=TableStyle([
                    ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#E9F0F3")),
                    ("LINEBELOW", (0, 0), (-1, 0), 0.5, colors.HexColor("#B7C6CC")),
                    ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ]),
            ))
            continue
        for record in records:
            if name == "Сведения":
                label, value = record
                if value is not None and value != "":
                    content.append(paragraph(f"{label} — {value}"))
            else:
                fields = [f"{label} — {value}" for label, value in zip(headers, record) if value not in (None, "")]
                content.append(paragraph("\n".join(fields)))
                content.append(Spacer(1, 4))
    SimpleDocTemplate(buffer, pagesize=A4, title=f"Отчёт об осмотре №{report['id']}").build(content)
    return buffer.getvalue()


def make_xlsx(report):
    book = Workbook()
    for index, (name, headers, records) in enumerate(report_sections(report)):
        sheet = book.active if index == 0 else book.create_sheet()
        sheet.title = name
        sheet.append(headers)
        for cell in sheet[1]:
            cell.fill = PatternFill("solid", fgColor="24465A")
            cell.font = Font(bold=True, color="FFFFFF")
        for row_number, record in enumerate(records, start=2):
            for column, value in enumerate(record, start=1):
                cell = sheet.cell(row=row_number, column=column)
                if isinstance(value, str):
                    cell.value = clean(value)
                    cell.data_type = "s"
                else:
                    cell.value = value
                cell.alignment = Alignment(vertical="top", wrap_text=isinstance(value, str))
        for column, header in enumerate(headers, start=1):
            widths = [len(str(record[column - 1] or "")) for record in records]
            sheet.column_dimensions[get_column_letter(column)].width = min(60, max([12, len(header) + 3, *widths]))
        sheet.freeze_panes = "A2"
    output = BytesIO()
    book.save(output)
    return output.getvalue()
