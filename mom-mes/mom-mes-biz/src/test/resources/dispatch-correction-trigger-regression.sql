-- PostgreSQL-only regression, run after V4 323 in a disposable verification database/session.
-- Calls the installed production trigger function; no production business rows are inserted.
-- Before the procedural IF fix, CASE preparation references the absent correction_request_id
-- field and fails even though the request/probe branch only needs id.
BEGIN;
CREATE TEMP TABLE mom_dispatch_correction_request_probe (id bigint) ON COMMIT DROP;
CREATE CONSTRAINT TRIGGER mom_dispatch_correction_request_probe_commit
 AFTER INSERT ON mom_dispatch_correction_request_probe
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
 EXECUTE FUNCTION mom_mes.mom_check_dispatch_correction_commit_v1();
INSERT INTO pg_temp.mom_dispatch_correction_request_probe(id) VALUES(NULL);
SET CONSTRAINTS mom_dispatch_correction_request_probe_commit IMMEDIATE;
ROLLBACK;
