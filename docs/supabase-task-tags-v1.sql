-- PR9 preparation only. Do not deploy/enable against an existing account without
-- coordinated client migration and two-client acceptance. This script never
-- advertises write support: the v1 sync_log endpoint can ACK unknown entities.
BEGIN;

CREATE TABLE IF NOT EXISTS public.task_tag_protocol (
    singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
    enabled boolean NOT NULL DEFAULT false,
    protocol_epoch integer NOT NULL DEFAULT 1 CHECK(protocol_epoch = 1)
);
INSERT INTO public.task_tag_protocol(singleton, enabled) VALUES(true, false)
ON CONFLICT(singleton) DO NOTHING;

CREATE TABLE IF NOT EXISTS public.task_tags_v1 (
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    tag_id text NOT NULL,
    name text NOT NULL CHECK(length(trim(name)) BETWEEN 1 AND 80),
    normalized_name text NOT NULL,
    updated_at bigint NOT NULL,
    deleted_at bigint,
    PRIMARY KEY(user_id,tag_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS task_tags_v1_active_names
ON public.task_tags_v1(user_id,normalized_name) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS public.task_tag_links_v1 (
    user_id uuid NOT NULL,
    task_id text NOT NULL,
    tag_id text NOT NULL,
    updated_at bigint NOT NULL,
    deleted_at bigint,
    PRIMARY KEY(user_id,task_id,tag_id),
    FOREIGN KEY(user_id,tag_id) REFERENCES public.task_tags_v1(user_id,tag_id) ON DELETE CASCADE
);

ALTER TABLE public.task_tags_v1 ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.task_tag_links_v1 ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.task_tag_protocol ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS task_tags_v1_read ON public.task_tags_v1;
CREATE POLICY task_tags_v1_read ON public.task_tags_v1 FOR SELECT USING(auth.uid()=user_id);
DROP POLICY IF EXISTS task_tag_links_v1_read ON public.task_tag_links_v1;
CREATE POLICY task_tag_links_v1_read ON public.task_tag_links_v1 FOR SELECT USING(auth.uid()=user_id);

-- Reject unsupported tag events in the OLD endpoint instead of silently ACKing
-- them. Existing habit/task entity projection is unchanged.
CREATE OR REPLACE FUNCTION public.reject_unnegotiated_task_tag_event()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.entity_type IN ('task_tag','task_tag_links') THEN
        RAISE EXCEPTION 'task_tags_v1 requires the versioned tag RPC; legacy endpoint is not supported';
    END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS task_tag_protocol_guard ON public.sync_log;
CREATE TRIGGER task_tag_protocol_guard BEFORE INSERT ON public.sync_log
FOR EACH ROW EXECUTE FUNCTION public.reject_unnegotiated_task_tag_event();

-- Deliberately no client write grants or INSERT/UPDATE/DELETE policies. Enabling
-- requires an atomic versioned RPC with op_uuid deduplication, per-user cursors,
-- tag/assignment tombstones, missing-parent replay, and task-delete handling.
-- The current Android client preserves pending events in SQLite TaskTagOutbox
-- and reports task_tags_server_contract_required; it does not call these tables.
COMMIT;
