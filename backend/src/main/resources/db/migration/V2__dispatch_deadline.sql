-- Vans must leave by a fixed time so hot food reaches children on time.
-- Like the cutoff, the deadline is stored on each order (data, not a timer),
-- so "late" is a query and the rule can change without touching old orders.
ALTER TABLE meal_order ADD COLUMN dispatch_by TIMESTAMPTZ;

-- Backfill existing rows with the defaults (06:30 breakfast, 11:00 lunch, India time).
UPDATE meal_order
   SET dispatch_by = (service_date + CASE meal_slot WHEN 'BREAKFAST' THEN TIME '06:30' ELSE TIME '11:00' END)
                     AT TIME ZONE 'Asia/Kolkata';

ALTER TABLE meal_order ALTER COLUMN dispatch_by SET NOT NULL;
