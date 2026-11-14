import sqlite3
from contextlib import contextmanager
from datetime import UTC, date, datetime
from typing import Annotated, Literal

from email_validator import EmailNotValidError
from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field, SecretStr, StringConstraints

from .auth import create_user
from .database import DEMO_PUMP, connect, set_current
from .http_auth import User
from .passport import rows

router = APIRouter(prefix="/admin", tags=["Панель управления"])
Text = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=254)]
Instructions = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=4000)]
Number = Annotated[float, Field(allow_inf_nan=False, ge=-1e9, le=1e9)]
Magnitude = Annotated[float, Field(allow_inf_nan=False, ge=0, le=1e9)]
SensorCode = Literal[
    "speed_rpm", "overall_rms_mm_s", "amplitude_1x_mm_s", "amplitude_2x_mm_s",
    "hf_noise_g", "pump_bearing_c", "motor_winding_c",
]


def require_admin(user: User):
    if user["role"] != "admin":
        raise HTTPException(403, "Действие доступно администратору.")


Admin = Annotated[None, Depends(require_admin)]


class NewUser(BaseModel):
    email: Text
    display_name: Text
    password: SecretStr = Field(min_length=12, max_length=128)
    role: Literal["engineer", "supervisor", "admin"]


class NewEquipment(BaseModel):
    name: Text
    kind: Text
    asset_tag: Text
    model: Text | None = None
    serial_number: Text | None = None
    parent_id: Annotated[int, Field(gt=0)] | None = None
    qr: Text | None = None
    nameplate: Annotated[str, StringConstraints(strip_whitespace=True, pattern=r"^[0-9]{8}$")] | None = None
    installed_on: date | None = None
    description: str = Field(default="", max_length=4000)


class NewSensor(BaseModel):
    code: SensorCode
    label: Text
    unit: Text
    source: Text
    nominal_value: Number | None = None
    warning_above: Number | None = None
    alarm_above: Number | None = None


class NewSample(BaseModel):
    measured_at: datetime
    speed_rpm: int = Field(ge=0, le=10_000_000)
    overall_rms_mm_s: Magnitude
    amplitude_1x_mm_s: Magnitude
    amplitude_2x_mm_s: Magnitude
    hf_noise_g: Magnitude
    pump_bearing_c: Number
    motor_winding_c: Number
    vibration_diagnosis: Text | None = None
    temperature_diagnosis: Text | None = None


class NewCheck(BaseModel):
    title: Text
    instructions: Instructions
    acceptance_criteria: Instructions
    recommendation: Instructions


def require_equipment(db, equipment_id):
    if db.execute("SELECT 1 FROM equipment WHERE id = ?", (equipment_id,)).fetchone() is None:
        raise HTTPException(404, "Оборудование не найдено.")


@contextmanager
def write(request, duplicate_message):
    try:
        with connect(request.app.state.db_path) as db:
            yield db
    except sqlite3.IntegrityError as error:
        if error.sqlite_errorcode not in (sqlite3.SQLITE_CONSTRAINT_PRIMARYKEY, sqlite3.SQLITE_CONSTRAINT_UNIQUE):
            raise
        raise HTTPException(409, duplicate_message) from error


@router.get("/users")
def users(request: Request, admin: Admin):
    with connect(request.app.state.db_path) as db:
        return rows(db, "SELECT id, email, display_name, role, active FROM users ORDER BY id", ())


@router.post("/users", status_code=201)
def add_user(data: NewUser, request: Request, admin: Admin):
    with write(request, "Учётная запись с такой почтой уже существует.") as db:
        try:
            user_id = create_user(db, data.email, data.password.get_secret_value(), data.display_name, data.role)
        except EmailNotValidError as error:
            raise HTTPException(422, "Укажите корректный адрес электронной почты.") from error
    return {"id": user_id}


@router.post("/equipment", status_code=201)
def add_equipment(data: NewEquipment, request: Request, admin: Admin):
    with write(request, "Инвентарный номер, QR-код или номер шильдика уже используется.") as db:
        if data.parent_id is not None:
            require_equipment(db, data.parent_id)
        equipment_id = db.execute(
            """
            INSERT INTO equipment (parent_id, asset_tag, kind, name, model, serial_number, description, installed_on)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (data.parent_id, data.asset_tag, data.kind, data.name, data.model, data.serial_number,
             data.description.strip(), data.installed_on.isoformat() if data.installed_on else None),
        ).lastrowid
        db.executemany(
            "INSERT INTO equipment_identifiers (kind, value, equipment_id) VALUES (?, ?, ?)",
            [(kind, value, equipment_id) for kind, value in (("qr", data.qr), ("nameplate", data.nameplate)) if value],
        )
    return {"id": equipment_id}


@router.post("/equipment/{equipment_id}/sensors", status_code=201)
def add_sensor(equipment_id: int, data: NewSensor, request: Request, admin: Admin):
    if data.alarm_above is not None and data.warning_above is not None and data.alarm_above < data.warning_above:
        raise HTTPException(422, "Порог тревоги должен быть не ниже порога предупреждения.")
    with write(request, "Датчик с таким кодом уже есть в карточке.") as db:
        require_equipment(db, equipment_id)
        db.execute(
            """
            INSERT INTO sensors (equipment_id, code, label, unit, nominal_value, warning_above, alarm_above, source)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (equipment_id, data.code, data.label, data.unit, data.nominal_value, data.warning_above,
             data.alarm_above, data.source),
        )
    return {"code": data.code}


@router.post("/equipment/{equipment_id}/samples", status_code=201)
def add_sample(equipment_id: int, data: NewSample, request: Request, admin: Admin):
    if data.measured_at.utcoffset() is None:
        raise HTTPException(422, "Время замера должно содержать часовой пояс.")
    measured_at = data.measured_at.astimezone(UTC)
    with write(request, "Замер с таким временем уже есть в карточке.") as db:
        db.execute("BEGIN IMMEDIATE")
        require_equipment(db, equipment_id)
        sample_id = db.execute(
            """
            INSERT INTO telemetry_samples (
                equipment_id, source, measured_at, speed_rpm, overall_rms_mm_s, amplitude_1x_mm_s,
                amplitude_2x_mm_s, hf_noise_g, vibration_diagnosis, pump_bearing_c, motor_winding_c,
                temperature_diagnosis
            ) VALUES (?, 'live', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (equipment_id, measured_at.isoformat(), data.speed_rpm, data.overall_rms_mm_s, data.amplitude_1x_mm_s,
             data.amplitude_2x_mm_s, data.hf_noise_g, data.vibration_diagnosis, data.pump_bearing_c,
             data.motor_winding_c, data.temperature_diagnosis),
        ).lastrowid
        latest = db.execute(
            """
            SELECT id FROM telemetry_samples WHERE equipment_id = ? AND source = 'live'
            ORDER BY measured_at DESC LIMIT 1
            """,
            (equipment_id,),
        ).fetchone()
        set_current(db, equipment_id, latest["id"])
    return {"id": sample_id}


@router.post("/equipment/{equipment_id}/resume-replay", status_code=204)
def resume_replay(equipment_id: int, request: Request, admin: Admin):
    if equipment_id != DEMO_PUMP:
        raise HTTPException(404, "Для этого оборудования нет демонстрационного потока.")
    with connect(request.app.state.db_path) as db:
        db.execute("BEGIN IMMEDIATE")
        sample = db.execute(
            "SELECT id FROM telemetry_samples WHERE equipment_id = ? AND source = 'replay' ORDER BY measured_at LIMIT 1",
            (equipment_id,),
        ).fetchone()
        if sample is None:
            raise HTTPException(404, "Демонстрационные замеры не найдены.")
        set_current(db, equipment_id, sample["id"])


@router.post("/equipment/{equipment_id}/checks", status_code=201)
def add_check(equipment_id: int, data: NewCheck, request: Request, admin: Admin):
    with connect(request.app.state.db_path) as db:
        db.execute("BEGIN IMMEDIATE")
        require_equipment(db, equipment_id)
        position = db.execute(
            "SELECT COALESCE(MAX(position), 0) + 1 FROM inspection_checks WHERE equipment_id = ?",
            (equipment_id,),
        ).fetchone()[0]
        check_id = db.execute(
            """
            INSERT INTO inspection_checks (equipment_id, position, title, instructions, acceptance_criteria, recommendation)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (equipment_id, position, data.title, data.instructions, data.acceptance_criteria, data.recommendation),
        ).lastrowid
    return {"id": check_id}
