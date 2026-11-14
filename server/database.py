import csv
import json
import os
import sqlite3
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DEFAULT_DB = ROOT / "data" / "inspection.db"
REPLAY_ASSET = ROOT.parent / "app" / "src" / "main" / "assets" / "telemetry" / "pump-1.tsv"
DEMO_PUMP = 1


def db_path():
    return Path(os.environ.get("INSPECTION_DB", DEFAULT_DB))


def utc_now():
    return datetime.now(UTC).isoformat(timespec="milliseconds")


@contextmanager
def connect(path):
    connection = sqlite3.connect(path, timeout=10)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    try:
        with connection:
            yield connection
    finally:
        connection.close()


def initialize(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with connect(path) as db:
        db.execute("PRAGMA journal_mode = WAL")
        db.execute("BEGIN IMMEDIATE")
        version = db.execute("PRAGMA user_version").fetchone()[0]
        if version == 4:
            return
        if version == 0:
            execute_sql(db, ROOT / "schema.sql")
            seed(db)
        elif version not in (1, 2, 3):
            raise RuntimeError(f"Unsupported database schema version: {version}")
        if version == 1:
            execute_sql(db, ROOT / "migrations" / "002.sql")
        if version in (1, 2):
            execute_sql(db, ROOT / "migrations" / "003.sql")
        if version in (1, 2, 3):
            execute_sql(db, ROOT / "migrations" / "004.sql")
        if db.execute("SELECT 1 FROM equipment WHERE id = ? AND is_demo = 1", (DEMO_PUMP,)).fetchone():
            seed_cv(db)
        db.execute("PRAGMA user_version = 4")


def execute_sql(db, path):
    # executescript would commit the transaction before applying the schema.
    statement = ""
    for line in path.read_text(encoding="utf-8").splitlines(keepends=True):
        statement += line
        if sqlite3.complete_statement(statement):
            db.execute(statement)
            statement = ""


def seed(db):
    db.executemany(
        """
        INSERT INTO equipment (id, parent_id, asset_tag, kind, name, description, is_demo)
        VALUES (?, ?, ?, ?, ?, ?, 1)
        """,
        [
            (1, None, "DEMO-PUMP-001", "centrifugal_pump", "Центробежный насос №1",
             "Демонстрационный агрегат для осмотра по вибрации и температуре."),
            (2, None, "DEMO-VLV-001", "gate_valve", "Входная задвижка", "Перед насосом."),
            (3, None, "DEMO-CV-001", "check_valve", "Обратный клапан", "После насоса."),
            (4, None, "DEMO-VLV-002", "gate_valve", "Выходная задвижка", "После обратного клапана."),
            (5, 1, "DEMO-BRG-001", "bearing", "Подшипниковый узел насоса", "Точка контроля температуры."),
            (6, 1, "DEMO-MOTOR-001", "motor", "Электродвигатель", "Точка контроля температуры обмоток."),
            (7, 1, "DEMO-COUPLING-001", "coupling", "Муфта", "Соединение насоса и привода."),
        ],
    )
    db.execute(
        "INSERT INTO equipment_lines (id, name, description) VALUES (1, ?, ?)",
        ("Демонстрационная насосная линия", "Элементы расположены по направлению потока."),
    )
    db.executemany(
        "INSERT INTO line_equipment VALUES (1, ?, ?)",
        enumerate((2, 1, 3, 4), start=1),
    )
    db.execute("INSERT INTO equipment_identifiers VALUES ('qr', 'DEMO-PUMP-001', 1)")
    db.execute(
        "INSERT INTO operating_modes (equipment_id, name, description, parameters, source) VALUES (1, ?, ?, ?, ?)",
        ("Демонстрационный рабочий режим", "Воспроизведение записанных замеров насоса.",
         '{"reference_speed_rpm": 2950}', "Данные хакатона, ориентир для демонстрации"),
    )
    db.executemany(
        """
        INSERT INTO sensors (
            equipment_id, code, component_id, label, unit, nominal_value,
            warning_above, alarm_above, source
        ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        [
            ("speed_rpm", None, "Обороты", "об/мин", None, None, None, "dbLoop"),
            ("overall_rms_mm_s", None, "СКЗ общее", "мм/с", None, None, None, "dbLoop"),
            ("amplitude_1x_mm_s", None, "Амплитуда 1×", "мм/с", None, 2.8, 4.5, "dbLoop"),
            ("amplitude_2x_mm_s", 7, "Амплитуда 2×", "мм/с", None, 1.8, 3.0, "dbLoop"),
            ("hf_noise_g", 5, "ВЧ шум", "g", None, 2.5, None, "dbLoop"),
            ("pump_bearing_c", 5, "Температура подшипника насоса", "°C", 50, 75, 90, "dbLoop"),
            ("motor_winding_c", 6, "Температура обмоток электродвигателя", "°C", 65, 90, 110, "dbLoop"),
        ],
    )
    with REPLAY_ASSET.open(encoding="utf-8", newline="") as stream:
        db.executemany(
            """
            INSERT INTO telemetry_samples (
                equipment_id, source, measured_at, speed_rpm, overall_rms_mm_s,
                amplitude_1x_mm_s, amplitude_2x_mm_s, hf_noise_g, vibration_diagnosis,
                pump_bearing_c, motor_winding_c, temperature_diagnosis
            ) VALUES (1, 'replay', :measured_at, :speed_rpm, :overall_rms_mm_s,
                :amplitude_1x_mm_s, :amplitude_2x_mm_s, :hf_noise_g, :vibration_diagnosis,
                :pump_bearing_c, :motor_winding_c, :temperature_diagnosis)
            """,
            csv.DictReader(stream, delimiter="\t"),
        )
    db.executemany(
        """
        INSERT INTO defect_types (equipment_group, name, visible_signs)
        VALUES (:equipment_group, :name, :visible_signs)
        """,
        json.loads((ROOT / "defect_types.json").read_text(encoding="utf-8")),
    )
    db.executemany(
        """
        INSERT INTO inspection_checks (
            equipment_id, component_id, position, title, instructions, acceptance_criteria, recommendation
        ) VALUES (1, ?, ?, ?, ?, ?, ?)
        """,
        [
            (None, 1, "Возможная утечка в зоне вала",
             "Наведите камеру на видимую часть корпуса рядом с валом.",
             "Зона видна целиком. Отметьте капли или влажный участок, если они видны.",
             "Передайте наблюдение для проверки уплотнения по регламенту."),
            (7, 2, "Защитный кожух муфты",
             "Поместите кожух и место его крепления в кадр.",
             "На пригодном кадре виден кожух и доступные для осмотра места крепления.",
             "Зафиксируйте замечание и передайте его ответственному за обслуживание."),
        ],
    )
    db.execute(
        "INSERT INTO operating_state (equipment_id, state, run_hours, source) VALUES (1, 'running', 3950, 'demo')"
    )
    db.execute(
        """
        INSERT INTO maintenance_plans (
            equipment_id, name, instructions, operating_interval_hours, next_due_hours, warning_hours, source
        ) VALUES (1, ?, ?, 4000, 4000, 100, 'demo')
        """,
        ("Демонстрационное плановое ТО", "Проверить регламент, назначить обслуживание и зафиксировать работы."),
    )
    first = db.execute(
        """
        SELECT id FROM telemetry_samples WHERE equipment_id = ? AND source = 'replay'
        ORDER BY measured_at LIMIT 1
        """,
        (DEMO_PUMP,),
    ).fetchone()
    if first is None:
        raise RuntimeError("The replay asset contains no measurements")
    set_current(db, DEMO_PUMP, first["id"])


def seed_cv(db):
    db.executemany(
        "INSERT OR IGNORE INTO equipment_identifiers (kind, value, equipment_id) VALUES (?, '00123456', ?)",
        [("qr", DEMO_PUMP), ("nameplate", DEMO_PUMP)],
    )
    db.execute(
        """
        INSERT INTO inspection_checks (
            equipment_id, position, title, instructions, acceptance_criteria, recommendation, visual_model
        )
        SELECT ?, COALESCE(MAX(position), 0) + 1, ?, ?, ?, ?, 'corrosion'
        FROM inspection_checks WHERE equipment_id = ?
        HAVING COUNT(CASE WHEN visual_model = 'corrosion' THEN 1 END) = 0
        """,
        (
            DEMO_PUMP, "Коррозия рамы насоса",
            "Приблизьтесь и наведите камеру на металлическую раму насоса.",
            "Осмотрите раму. Оцените подсвеченные участки и подтвердите результат вручную.",
            "Зафиксируйте участок и передайте наблюдение для проверки по регламенту.",
            DEMO_PUMP,
        ),
    )


def set_current(db, equipment_id, sample_id):
    db.execute(
        """
        INSERT INTO current_telemetry (equipment_id, sample_id, updated_at) VALUES (?, ?, ?)
        ON CONFLICT (equipment_id) DO UPDATE SET
            sample_id = excluded.sample_id, updated_at = excluded.updated_at
        """,
        (equipment_id, sample_id, utc_now()),
    )


def current_telemetry(db, equipment_id):
    current = db.execute(
        """
        SELECT s.*, c.updated_at FROM current_telemetry c
        JOIN telemetry_samples s ON s.equipment_id = c.equipment_id AND s.id = c.sample_id
        WHERE c.equipment_id = ?
        """,
        (equipment_id,),
    ).fetchone()
    return dict(current) if current else None
