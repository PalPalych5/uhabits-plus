-- Schemas checked with SELECT * FROM slice/thread_track/thread_state LIMIT 0.
WITH stages AS (
 SELECT s.id,s.name,s.ts,s.dur,t.utid FROM slice s JOIN thread_track t ON t.id=s.track_id
 WHERE s.name GLOB 'habit.resortStage.*' AND s.dur>0
)
SELECT s.name,s.dur/1e6 wall_ms,st.state,st.io_wait,st.blocked_function,
 SUM(MAX(0,MIN(s.ts+s.dur,st.ts+st.dur)-MAX(s.ts,st.ts)))/1e6 state_ms
FROM stages s LEFT JOIN thread_state st ON st.utid=s.utid AND st.ts<s.ts+s.dur AND st.ts+st.dur>s.ts
GROUP BY s.id,st.state,st.io_wait,st.blocked_function ORDER BY s.ts,state_ms DESC;
