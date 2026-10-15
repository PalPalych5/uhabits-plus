-- Direct children avoid expensive broad interval self-joins.
SELECT c.name,COUNT(*) count,SUM(c.dur)/1e6 inclusive_ms,MAX(c.dur)/1e6 max_ms
FROM slice c WHERE c.parent_id IN (
 SELECT id FROM slice WHERE name GLOB 'habit.resortStage.*' AND dur>0
) AND c.dur>=0 GROUP BY c.name ORDER BY inclusive_ms DESC LIMIT 60;
