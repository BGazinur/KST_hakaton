PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY,
    email TEXT NOT NULL UNIQUE COLLATE NOCASE,
    password_hash TEXT NOT NULL,
    display_name TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('engineer', 'supervisor', 'admin')),
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sessions (
    token_hash TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires_at);

-- A row represents a physical asset or component and is the root of its digital twin.
CREATE TABLE IF NOT EXISTS equipment (
    id INTEGER PRIMARY KEY,
    parent_id INTEGER REFERENCES equipment(id),
    asset_tag TEXT NOT NULL UNIQUE,
    kind TEXT NOT NULL,
    name TEXT NOT NULL,
    model TEXT,
    serial_number TEXT,
    description TEXT NOT NULL DEFAULT '',
    specifications TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(specifications)),
    installed_on TEXT,
    is_demo INTEGER NOT NULL DEFAULT 0 CHECK (is_demo IN (0, 1)),
    CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX IF NOT EXISTS equipment_parent ON equipment(parent_id);

CREATE TABLE IF NOT EXISTS equipment_identifiers (
    kind TEXT NOT NULL CHECK (kind IN ('qr', 'nameplate')),
    value TEXT NOT NULL COLLATE NOCASE,
    equipment_id INTEGER NOT NULL REFERENCES equipment(id),
    PRIMARY KEY (kind, value)
);

CREATE TABLE IF NOT EXISTS equipment_lines (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS line_equipment (
    line_id INTEGER NOT NULL REFERENCES equipment_lines(id),
    position INTEGER NOT NULL CHECK (position > 0),
    equipment_id INTEGER NOT NULL REFERENCES equipment(id),
    PRIMARY KEY (line_id, position),
    UNIQUE (line_id, equipment_id)
);

CREATE TABLE IF NOT EXISTS documents (
    id INTEGER PRIMARY KEY,
    equipment_id INTEGER NOT NULL REFERENCES equipment(id),
    kind TEXT NOT NULL CHECK (kind IN ('passport', 'manual', 'operating_map', 'test_protocol', 'maintenance_regulation')),
    title TEXT NOT NULL,
    revision TEXT,
    filename TEXT NOT NULL,
    content_type TEXT NOT NULL,
    content BLOB NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS operating_modes (
    id INTEGER PRIMARY KEY,
    equipment_id INTEGER NOT NULL REFERENCES equipment(id),
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    parameters TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(parameters)),
    source TEXT NOT NULL,
    UNIQUE (equipment_id, name)
);

CREATE TABLE IF NOT EXISTS sensors (
    equipment_id INTEGER NOT NULL REFERENCES equipment(id),
    code TEXT NOT NULL,
    component_id INTEGER REFERENCES equipment(id),
    label TEXT NOT NULL,
    unit TEXT NOT NULL,
    nominal_value REAL,
    warning_above REAL,
    alarm_above REAL,
    source TEXT NOT NULL,
    PRIMARY KEY (equipment_id, code),
    CHECK (warning_above IS NULL OR alarm_above IS NULL OR alarm_above >= warning_above)
);

CREATE TABLE IF NOT EXISTS telemetry_samples (
    id INTEGER PRIMARY KEY,
    equipment_id INTEGER NOT NULL REFERENCES equipment(id),
    source TEXT NOT NULL CHECK (source IN ('replay', 'live')),
    measured_at TEXT NOT NULL,
    speed_rpm INTEGER NOT NULL CHECK (speed_rpm >= 0),
    overall_rms_mm_s REAL NOT NULL,
    amplitude_1x_mm_s REAL NOT NULL,
    amplitude_2x_mm_s REAL NOT NULL,
    hf_noise_g REAL NOT NULL,
    vibration_diagnosis TEXT,
    pump_bearing_c REAL NOT NULL,
    motor_winding_c REAL NOT NULL,
    temperature_diagnosis TEXT,
    UNIQUE (equipment_id, source, measured_at),
    UNIQUE (equipment_id, id)
);
CREATE TABLE IF NOT EXISTS current_telemetry (
    equipment_id INTEGER PRIMARY KEY REFERENCES equipment(id),
    sample_id INTEGER NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (equipment_id, sample_id) REFERENCES telemetry_samples(equipment_id, id)
);

CREATE TABLE IF NOT EXISTS operating_state (
    equipment_id INTEGER PRIMARY KEY REFERENCES equipment(id),
    state TEXT NOT NULL CHECK (state IN ('running', 'stopped', 'unknown')),
    run_hours REAL CHECK (run_hours >= 0),
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS defect_types (
    id INTEGER PRIMARY KEY,
    equipment_group TEXT NOT NULL,
    name TEXT NOT NULL,
    visible_signs TEXT NOT NULL,
    UNIQUE (equipment_group, name)
);

CREATE TABLE IF NOT EXISTS inspection_checks (
    id INTEGER PRIMARY KEY,
    equipment_id INTEGER NOT NULL REFERENCES equipment(id),
    component_id INTEGER REFERENCES equipment(id),
    defect_type_id INTEGER REFERENCES defect_types(id),
    position INTEGER NOT NULL CHECK (position > 0),
    title TEXT NOT NULL,
    instructions TEXT NOT NULL,
    acceptance_criteria TEXT NOT NULL,
    recommendation TEXT NOT NULL,
    visual_model TEXT CHECK (visual_model IN ('corrosion')),
    UNIQUE (equipment_id, position),
    UNIQUE (equipment_id, id)
);

CREATE TABLE IF NOT EXISTS maintenance_plans (
    id INTEGER PRIMARY KEY,
    equipment_id INTEGER NOT NULL REFERENCES equipment(id),
    kind TEXT NOT NULL DEFAULT 'service' CHECK (kind IN ('service', 'metrological_verification')),
    sensor_code TEXT,
    name TEXT NOT NULL,
    instructions TEXT NOT NULL,
    calendar_interval_days INTEGER CHECK (calendar_interval_days > 0),
    operating_interval_hours REAL CHECK (operating_interval_hours > 0),
    next_due_on TEXT,
    next_due_hours REAL CHECK (next_due_hours >= 0),
    warning_days INTEGER NOT NULL DEFAULT 0 CHECK (warning_days >= 0),
    warning_hours REAL NOT NULL DEFAULT 0 CHECK (warning_hours >= 0),
    source TEXT NOT NULL,
    FOREIGN KEY (equipment_id, sensor_code) REFERENCES sensors(equipment_id, code),
    CHECK (sensor_code IS NULL OR kind = 'metrological_verification'),
    CHECK (calendar_interval_days IS NOT NULL OR operating_interval_hours IS NOT NULL
        OR next_due_on IS NOT NULL OR next_due_hours IS NOT NULL),
    UNIQUE (equipment_id, id)
);
CREATE INDEX IF NOT EXISTS maintenance_plans_equipment ON maintenance_plans(equipment_id);

CREATE TABLE IF NOT EXISTS maintenance_events (
    id INTEGER PRIMARY KEY,
    equipment_id INTEGER NOT NULL REFERENCES equipment(id),
    plan_id INTEGER,
    performed_by INTEGER REFERENCES users(id),
    performed_at TEXT NOT NULL,
    run_hours REAL CHECK (run_hours >= 0),
    action TEXT NOT NULL CHECK (action IN ('service', 'replacement', 'repair')),
    notes TEXT NOT NULL DEFAULT '',
    FOREIGN KEY (equipment_id, plan_id) REFERENCES maintenance_plans(equipment_id, id)
);
CREATE INDEX IF NOT EXISTS maintenance_events_equipment ON maintenance_events(equipment_id, performed_at);

CREATE TABLE IF NOT EXISTS equipment_tests (
    id INTEGER PRIMARY KEY,
    equipment_id INTEGER NOT NULL REFERENCES equipment(id),
    sensor_code TEXT,
    kind TEXT NOT NULL CHECK (kind IN ('equipment_test', 'metrological_verification')),
    title TEXT NOT NULL,
    performed_at TEXT NOT NULL,
    valid_until TEXT,
    outcome TEXT NOT NULL CHECK (outcome IN ('pass', 'fail', 'inconclusive')),
    details TEXT NOT NULL DEFAULT '',
    protocol_document_id INTEGER REFERENCES documents(id),
    certificate_number TEXT,
    uploaded_by INTEGER REFERENCES users(id),
    FOREIGN KEY (equipment_id, sensor_code) REFERENCES sensors(equipment_id, code)
);
CREATE INDEX IF NOT EXISTS equipment_tests_equipment ON equipment_tests(equipment_id, performed_at);

CREATE TABLE IF NOT EXISTS reports (
    id INTEGER PRIMARY KEY,
    equipment_id INTEGER NOT NULL REFERENCES equipment(id),
    author_id INTEGER NOT NULL REFERENCES users(id),
    created_at TEXT NOT NULL,
    notes TEXT NOT NULL DEFAULT '',
    equipment_snapshot TEXT NOT NULL CHECK (json_valid(equipment_snapshot)),
    telemetry_snapshot TEXT CHECK (telemetry_snapshot IS NULL OR json_valid(telemetry_snapshot)),
    sensors_snapshot TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(sensors_snapshot)),
    client_request_id TEXT,
    UNIQUE (id, equipment_id)
);
CREATE INDEX IF NOT EXISTS reports_equipment ON reports(equipment_id, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS reports_author_request ON reports(author_id, client_request_id);

CREATE TABLE IF NOT EXISTS report_items (
    report_id INTEGER NOT NULL,
    equipment_id INTEGER NOT NULL,
    check_id INTEGER NOT NULL,
    check_snapshot TEXT NOT NULL CHECK (json_valid(check_snapshot)),
    outcome TEXT NOT NULL CHECK (outcome IN (
        'no_visible_issue', 'needs_check', 'possible_defect', 'confirmed_defect', 'inconclusive', 'skipped'
    )),
    observation TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (report_id, check_id),
    FOREIGN KEY (report_id, equipment_id) REFERENCES reports(id, equipment_id) ON DELETE CASCADE,
    FOREIGN KEY (equipment_id, check_id) REFERENCES inspection_checks(equipment_id, id)
);

CREATE TABLE IF NOT EXISTS report_attachments (
    id INTEGER PRIMARY KEY,
    report_id INTEGER NOT NULL REFERENCES reports(id) ON DELETE CASCADE,
    check_id INTEGER,
    kind TEXT NOT NULL CHECK (kind IN ('photo', 'pdf', 'xlsx')),
    filename TEXT NOT NULL,
    content_type TEXT NOT NULL,
    content BLOB NOT NULL,
    FOREIGN KEY (report_id, check_id) REFERENCES report_items(report_id, check_id) ON DELETE CASCADE
);
