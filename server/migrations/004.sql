ALTER TABLE inspection_checks ADD COLUMN visual_model TEXT CHECK (visual_model IN ('corrosion'));
