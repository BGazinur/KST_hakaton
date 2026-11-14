ALTER TABLE inspection_checks ADD COLUMN visual_hint TEXT CHECK (visual_hint IN (
    'shaft_leak', 'coupling_guard', 'motor_cable', 'motor_fins', 'motor_ground', 'motor_discoloration'
));
