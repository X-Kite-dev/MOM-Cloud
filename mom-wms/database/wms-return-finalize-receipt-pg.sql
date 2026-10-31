-- 生产退料结束收货永久原请求。停写、备份并预演后，先执行脚本再发布 WMS 与 APP。
BEGIN;

CREATE TABLE IF NOT EXISTS mom_wms.wms_return_finalize_receipt (
	tenant_id bigint NOT NULL,
	request_id varchar(64) NOT NULL,
	factory_code varchar(100) NOT NULL,
	operator_code varchar(100) NOT NULL,
	notice_id bigint NOT NULL,
	warehouse_code varchar(64) NOT NULL,
	request_payload text NOT NULL,
	request_fingerprint varchar(64) NOT NULL,
	status varchar(20) NOT NULL,
	result_payload text,
	created_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
	completed_time timestamp,
	cancellation_reason varchar(500),
	PRIMARY KEY (tenant_id, request_id),
	CONSTRAINT ck_wms_return_finalize_identity CHECK (
		tenant_id > 0 AND notice_id > 0 AND length(trim(request_id)) BETWEEN 16 AND 64
		AND length(trim(factory_code)) > 0 AND length(trim(operator_code)) > 0
		AND length(trim(warehouse_code)) > 0 AND length(trim(request_payload)) > 0
		AND length(request_fingerprint) = 64
	),
	CONSTRAINT ck_wms_return_finalize_terminal CHECK (
		(status = 'PENDING' AND result_payload IS NULL AND completed_time IS NULL AND cancellation_reason IS NULL)
		OR (status = 'CONFIRMED' AND result_payload IS NOT NULL AND length(trim(result_payload)) > 0
			AND completed_time IS NOT NULL AND cancellation_reason IS NULL)
		OR (status = 'CANCELLED' AND result_payload IS NULL AND completed_time IS NOT NULL
			AND cancellation_reason IS NOT NULL AND length(trim(cancellation_reason)) > 0)
	)
);

CREATE INDEX IF NOT EXISTS idx_wms_return_finalize_notice
	ON mom_wms.wms_return_finalize_receipt (tenant_id, factory_code, notice_id, created_time);

CREATE OR REPLACE FUNCTION mom_wms.guard_return_finalize_receipt()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
	IF TG_OP = 'DELETE' OR OLD.status <> 'PENDING' THEN
		RAISE EXCEPTION '生产退料终结永久回执不得删除或改写终态';
	END IF;
	IF NEW.status NOT IN ('CONFIRMED', 'CANCELLED')
		OR ROW(NEW.tenant_id, NEW.request_id, NEW.factory_code, NEW.operator_code,
			NEW.notice_id, NEW.warehouse_code, NEW.request_payload, NEW.request_fingerprint, NEW.created_time)
		IS DISTINCT FROM ROW(OLD.tenant_id, OLD.request_id, OLD.factory_code, OLD.operator_code,
			OLD.notice_id, OLD.warehouse_code, OLD.request_payload, OLD.request_fingerprint, OLD.created_time) THEN
		RAISE EXCEPTION '生产退料终结原请求身份与内容不可变更';
	END IF;
	RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_wms_return_finalize_receipt_guard ON mom_wms.wms_return_finalize_receipt;
CREATE TRIGGER trg_wms_return_finalize_receipt_guard
	BEFORE UPDATE OR DELETE ON mom_wms.wms_return_finalize_receipt
	FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_return_finalize_receipt();

COMMIT;
