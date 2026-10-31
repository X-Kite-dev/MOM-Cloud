-- 移动出库永久回执。先在停写发布窗口执行，再发布 WMS 服务与新 APP。
-- 此表无逻辑删除和过期清理；永久保留已确认及已撤销原请求。
BEGIN;

CREATE TABLE IF NOT EXISTS mom_wms.wms_mobile_outbound_receipt (
	tenant_id bigint NOT NULL,
	request_id varchar(64) NOT NULL,
	factory_code varchar(100) NOT NULL,
	operator_code varchar(100) NOT NULL,
	notice_id bigint NOT NULL,
	notice_detail_id bigint NOT NULL,
	source_stock_id bigint NOT NULL,
	warehouse_code varchar(64) NOT NULL,
	bill_type varchar(50) NOT NULL,
	request_payload text NOT NULL,
	request_fingerprint varchar(64) NOT NULL,
	requested_quantity numeric NOT NULL,
	status varchar(20) NOT NULL,
	bill_no varchar(80),
	created_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
	completed_time timestamp,
	cancellation_reason varchar(500),
	PRIMARY KEY (tenant_id, request_id),
	CONSTRAINT ck_wms_mobile_outbound_receipt_identity CHECK (
		tenant_id > 0 AND notice_id > 0 AND notice_detail_id > 0 AND source_stock_id > 0
		AND length(trim(request_id)) BETWEEN 16 AND 64
		AND length(trim(factory_code)) > 0 AND length(trim(operator_code)) > 0
		AND length(trim(warehouse_code)) > 0 AND length(trim(bill_type)) > 0
		AND length(trim(request_payload)) > 0 AND length(request_fingerprint) = 64
		AND requested_quantity > 0
	),
	CONSTRAINT ck_wms_mobile_outbound_receipt_terminal CHECK (
		(status = 'PENDING' AND bill_no IS NULL AND completed_time IS NULL AND cancellation_reason IS NULL)
		OR (status = 'CONFIRMED' AND length(trim(bill_no)) > 0 AND bill_no IS NOT NULL
			AND completed_time IS NOT NULL AND cancellation_reason IS NULL)
		OR (status = 'CANCELLED' AND bill_no IS NULL AND completed_time IS NOT NULL
			AND cancellation_reason IS NOT NULL AND length(trim(cancellation_reason)) > 0)
	)
);

CREATE INDEX IF NOT EXISTS idx_wms_mobile_outbound_receipt_notice
	ON mom_wms.wms_mobile_outbound_receipt (tenant_id, factory_code, notice_id, created_time);

CREATE OR REPLACE FUNCTION mom_wms.guard_mobile_outbound_receipt()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
	IF TG_OP = 'DELETE' OR OLD.status <> 'PENDING' THEN
		RAISE EXCEPTION '移动出库永久回执不得删除或改写终态';
	END IF;
	IF NEW.status NOT IN ('CONFIRMED', 'CANCELLED')
		OR ROW(NEW.tenant_id, NEW.request_id, NEW.factory_code, NEW.operator_code,
			NEW.notice_id, NEW.notice_detail_id, NEW.source_stock_id, NEW.warehouse_code, NEW.bill_type,
			NEW.request_payload, NEW.request_fingerprint, NEW.requested_quantity, NEW.created_time)
		IS DISTINCT FROM ROW(OLD.tenant_id, OLD.request_id, OLD.factory_code, OLD.operator_code,
			OLD.notice_id, OLD.notice_detail_id, OLD.source_stock_id, OLD.warehouse_code, OLD.bill_type,
			OLD.request_payload, OLD.request_fingerprint, OLD.requested_quantity, OLD.created_time) THEN
		RAISE EXCEPTION '移动出库原请求身份与载荷不可变更';
	END IF;
	RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_wms_mobile_outbound_receipt_guard ON mom_wms.wms_mobile_outbound_receipt;
CREATE TRIGGER trg_wms_mobile_outbound_receipt_guard
	BEFORE UPDATE OR DELETE ON mom_wms.wms_mobile_outbound_receipt
	FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_mobile_outbound_receipt();

COMMIT;
