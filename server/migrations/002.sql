ALTER TABLE equipment_tests ADD COLUMN certificate_number TEXT;
ALTER TABLE equipment_tests ADD COLUMN uploaded_by INTEGER REFERENCES users(id);
ALTER TABLE reports ADD COLUMN sensors_snapshot TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(sensors_snapshot));
