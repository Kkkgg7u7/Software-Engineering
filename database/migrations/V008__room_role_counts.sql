-- Run once on existing MySQL databases. NULL preserves the original preset.
ALTER TABLE rooms ADD COLUMN role_counts JSON NULL;
