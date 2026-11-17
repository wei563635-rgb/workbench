CREATE TABLE IF NOT EXISTS wb_policy_state(id integer PRIMARY KEY CHECK(id=1),revision bigint NOT NULL);
INSERT INTO wb_policy_state(id,revision) VALUES(1,0) ON CONFLICT DO NOTHING;
ALTER TABLE wb_policy_state ADD COLUMN IF NOT EXISTS policy_hash text;
CREATE TABLE IF NOT EXISTS wb_department(id text PRIMARY KEY,personal_limit bigint NOT NULL,pool_limit bigint NOT NULL,concurrency integer NOT NULL);
CREATE TABLE IF NOT EXISTS wb_employee(user_id bigint PRIMARY KEY,external_id text NOT NULL UNIQUE,department_id text,mode text NOT NULL,personal_limit bigint NOT NULL,concurrency integer NOT NULL,enabled boolean NOT NULL,configured boolean NOT NULL);
CREATE TABLE IF NOT EXISTS wb_membership(user_id bigint NOT NULL,external_id text NOT NULL,department_id text,valid_from timestamptz NOT NULL,PRIMARY KEY(user_id,valid_from));
CREATE TABLE IF NOT EXISTS wb_lease(id text PRIMARY KEY,user_id bigint NOT NULL,expires_at timestamptz NOT NULL);
CREATE INDEX IF NOT EXISTS wb_lease_user ON wb_lease(user_id,expires_at);
ALTER TABLE wb_lease ADD COLUMN IF NOT EXISTS client_id text;
ALTER TABLE wb_lease ADD COLUMN IF NOT EXISTS completed boolean NOT NULL DEFAULT false;
ALTER TABLE wb_lease ADD COLUMN IF NOT EXISTS recorded boolean NOT NULL DEFAULT false;
CREATE TABLE IF NOT EXISTS wb_usage(source_id bigint PRIMARY KEY,user_id text NOT NULL,department_id text,request_id text,model text,occurred_at timestamptz NOT NULL,input_tokens bigint NOT NULL,output_tokens bigint NOT NULL,cache_tokens bigint NOT NULL,image_count bigint NOT NULL,duration_ms bigint NOT NULL);
CREATE INDEX IF NOT EXISTS wb_usage_time ON wb_usage(occurred_at,source_id);
CREATE TABLE IF NOT EXISTS wb_audit(client_id text PRIMARY KEY,user_id bigint NOT NULL,created_at timestamptz NOT NULL,status integer NOT NULL,complete boolean NOT NULL,payload bytea NOT NULL);
CREATE TABLE IF NOT EXISTS wb_trace(user_id bigint NOT NULL,request_id text NOT NULL,client_id text NOT NULL,PRIMARY KEY(user_id,request_id));
CREATE INDEX IF NOT EXISTS wb_audit_created ON wb_audit(created_at);
CREATE TABLE IF NOT EXISTS wb_key(key_id bigint PRIMARY KEY,user_id bigint NOT NULL,purpose text NOT NULL);
CREATE TABLE IF NOT EXISTS wb_context(client_id text PRIMARY KEY,channel text NOT NULL,run_id text,tool_key text);
CREATE OR REPLACE FUNCTION wb_capture_usage() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO wb_usage(source_id,user_id,department_id,request_id,model,occurred_at,input_tokens,output_tokens,cache_tokens,image_count,duration_ms)
 SELECT NEW.id,m.external_id,m.department_id,NEW.request_id,NEW.model,NEW.created_at,NEW.input_tokens,NEW.output_tokens,NEW.cache_creation_tokens+NEW.cache_read_tokens,COALESCE(NEW.image_count,0),COALESCE(NEW.duration_ms,0)
 FROM wb_membership m WHERE m.user_id=NEW.user_id AND m.valid_from<=NEW.created_at ORDER BY m.valid_from DESC LIMIT 1
 ON CONFLICT(source_id) DO NOTHING;
 UPDATE wb_lease SET recorded=true WHERE user_id=NEW.user_id AND client_id IN (SELECT client_id FROM wb_trace WHERE user_id=NEW.user_id AND request_id=NEW.request_id);
 DELETE FROM wb_lease WHERE user_id=NEW.user_id AND completed AND recorded;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS wb_usage_capture ON usage_logs;
CREATE TRIGGER wb_usage_capture AFTER INSERT ON usage_logs FOR EACH ROW EXECUTE FUNCTION wb_capture_usage();

-- Independent ingress journal includes calls without native usage IDs. Quota tables stay unchanged.
CREATE SEQUENCE IF NOT EXISTS wb_call_changes;
CREATE TABLE IF NOT EXISTS wb_call(
 id text PRIMARY KEY, change_id bigint NOT NULL DEFAULT nextval('wb_call_changes'), updated_at timestamptz NOT NULL DEFAULT now(),
 logical_id text, channel text NOT NULL DEFAULT 'key', user_id bigint, external_id text, department_id text,
 run_id text, tool_key text, model text, status text NOT NULL DEFAULT 'running', occurred_at timestamptz NOT NULL DEFAULT now(),
 duration_ms bigint, http_status integer, complete boolean NOT NULL DEFAULT false, payload bytea,
 source_usage_id bigint, input_tokens bigint, output_tokens bigint, cache_tokens bigint, image_count bigint);
CREATE INDEX IF NOT EXISTS wb_call_change ON wb_call(updated_at,change_id);
CREATE INDEX IF NOT EXISTS wb_call_user ON wb_call(user_id,id);
CREATE OR REPLACE FUNCTION wb_call_usage() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE wb_call c SET source_usage_id=NEW.id,input_tokens=NEW.input_tokens,output_tokens=NEW.output_tokens,
 cache_tokens=NEW.cache_creation_tokens+NEW.cache_read_tokens,image_count=NEW.image_count,
 updated_at=now(),change_id=nextval('wb_call_changes')
 WHERE c.user_id=NEW.user_id AND c.id IN (SELECT t.client_id FROM wb_trace t WHERE t.user_id=NEW.user_id AND t.request_id=NEW.request_id);
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS wb_call_usage_capture ON usage_logs;
CREATE TRIGGER wb_call_usage_capture AFTER INSERT ON usage_logs FOR EACH ROW EXECUTE FUNCTION wb_call_usage();
CREATE TABLE IF NOT EXISTS wb_call_task(id text PRIMARY KEY,user_id bigint NOT NULL,event_id text NOT NULL,status text NOT NULL,http_status integer,payload bytea);
CREATE INDEX IF NOT EXISTS wb_call_task_event ON wb_call_task(event_id);
CREATE TABLE IF NOT EXISTS wb_call_attempt(id bigserial PRIMARY KEY,event_id text NOT NULL,account_id bigint NOT NULL,http_status integer NOT NULL,transport_error boolean NOT NULL,duration_ms bigint NOT NULL);
CREATE INDEX IF NOT EXISTS wb_call_attempt_event ON wb_call_attempt(event_id,id);
