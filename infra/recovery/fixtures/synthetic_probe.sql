-- Synthetic probe template for a conducted OPS-04 drill. NEVER run in tests.
-- An operator adapts this to the pinned schema. When passed to psql with
-- -A -t -F '|', it must emit one row per relation and metric:
--   <relation>|row_count|<integer>
--   <relation>|checksum|<lowercase hex digest>
-- It must not print patient data, credentials or payload bytes.
SELECT 'core_chart' AS relation, 'row_count' AS metric, 0::text AS value
WHERE false;
