-- 标准在途接收未执行原请求的永久取消围栏。先停全部WMS实例，再执行并发布新WMS与移动端。
-- 不调整库存，不增加角色/菜单授权；已执行接收仍以wms_transfer_receipt为权威凭证。
BEGIN;
LOCK TABLE mom_wms.wms_transfer_receipt IN SHARE ROW EXCLUSIVE MODE;
CREATE TABLE IF NOT EXISTS mom_wms.wms_mobile_transfer_cancel_receipt (
	tenant_id bigint NOT NULL,
	request_id varchar(64) NOT NULL,
	factory_code varchar(100) NOT NULL,
	operator_code varchar(100) NOT NULL,
	warehouse_code varchar(100) NOT NULL,
	allocation_bill_id bigint NOT NULL,
	request_snapshot text NOT NULL,
	cancellation_reason varchar(500) NOT NULL,
	completed_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
	PRIMARY KEY(tenant_id,request_id),
	CONSTRAINT ck_wms_mobile_transfer_cancel CHECK (
		tenant_id > 0 AND allocation_bill_id > 0 AND length(trim(request_id)) BETWEEN 1 AND 64
		AND request_id=trim(request_id) AND length(trim(factory_code)) > 0 AND length(trim(operator_code)) > 0
		AND length(trim(warehouse_code)) > 0 AND warehouse_code=trim(warehouse_code)
		AND length(trim(request_snapshot)) > 0 AND length(trim(cancellation_reason)) > 0
	)
);
LOCK TABLE mom_wms.wms_mobile_transfer_cancel_receipt IN SHARE ROW EXCLUSIVE MODE;
DO $$
BEGIN
	IF EXISTS (SELECT 1 FROM mom_wms.wms_transfer_receipt GROUP BY tenant_id,request_id HAVING count(*)>1) THEN
		RAISE EXCEPTION '接收原号存在含删除历史的重复记录，须先核对历史，升级停止';
	END IF;
	IF EXISTS (SELECT 1 FROM mom_wms.wms_mobile_transfer_cancel_receipt c
		JOIN mom_wms.wms_transfer_receipt r ON r.tenant_id=c.tenant_id AND r.request_id=c.request_id) THEN
		RAISE EXCEPTION '接收原号同时存在接收和取消记录，须先核对历史，升级停止';
	END IF;
END;
$$;
-- 含逻辑删除历史仍占用永久原号，旧客户端不能以删除记录为由重复入库。
CREATE UNIQUE INDEX IF NOT EXISTS uk_wms_transfer_receipt_request_permanent
	ON mom_wms.wms_transfer_receipt(tenant_id,request_id);
CREATE OR REPLACE FUNCTION mom_wms.guard_mobile_transfer_cancel()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE payload jsonb;
BEGIN
	IF TG_OP <> 'INSERT' THEN
		RAISE EXCEPTION '接收永久取消回执不可改写或删除';
	END IF;
	PERFORM pg_advisory_xact_lock(hashtextextended(CONCAT('WMS_TRANSFER_RECEIPT:',NEW.tenant_id,':',NEW.request_id),0));
	IF EXISTS (SELECT 1 FROM mom_wms.wms_transfer_receipt WHERE tenant_id=NEW.tenant_id AND request_id=NEW.request_id) THEN
		RAISE EXCEPTION '原接收已存在业务凭证，不可取消为未执行';
	END IF;
	payload := NEW.request_snapshot::jsonb;
	IF jsonb_typeof(payload) IS DISTINCT FROM 'object'
		OR payload->>'billId' IS DISTINCT FROM NEW.allocation_bill_id::text
		OR jsonb_typeof(payload->'final') IS DISTINCT FROM 'boolean'
		OR jsonb_typeof(payload->'lines') IS DISTINCT FROM 'array' THEN
		RAISE EXCEPTION '接收取消回执原文与单据不一致';
	END IF;
	IF jsonb_array_length(payload->'lines') NOT BETWEEN 1 AND 500 THEN
		RAISE EXCEPTION '接收取消明细数量不合法';
	END IF;
	RETURN NEW;
END;
$$;
CREATE OR REPLACE FUNCTION mom_wms.guard_transfer_receipt_cancelled_original()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
	IF TG_OP = 'DELETE' THEN
		RAISE EXCEPTION '接收原始业务凭证不可物理删除';
	END IF;
	IF TG_OP = 'UPDATE' AND ROW(NEW.tenant_id,NEW.request_id,NEW.allocation_bill_id,NEW.factory_code,NEW.receiver_code,NEW.request_snapshot)
		IS DISTINCT FROM ROW(OLD.tenant_id,OLD.request_id,OLD.allocation_bill_id,OLD.factory_code,OLD.receiver_code,OLD.request_snapshot) THEN
		RAISE EXCEPTION '接收原始身份、单据、原号和原文不可重绑定';
	END IF;
	PERFORM pg_advisory_xact_lock(hashtextextended(CONCAT('WMS_TRANSFER_RECEIPT:',NEW.tenant_id,':',NEW.request_id),0));
	IF EXISTS (SELECT 1 FROM mom_wms.wms_mobile_transfer_cancel_receipt WHERE tenant_id=NEW.tenant_id AND request_id=NEW.request_id) THEN
		RAISE EXCEPTION '原接收请求已取消，禁止重放入库';
	END IF;
	RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_mobile_transfer_cancel_immutable ON mom_wms.wms_mobile_transfer_cancel_receipt;
CREATE TRIGGER trg_mobile_transfer_cancel_immutable BEFORE INSERT OR UPDATE OR DELETE
	ON mom_wms.wms_mobile_transfer_cancel_receipt FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_mobile_transfer_cancel();
DROP TRIGGER IF EXISTS trg_transfer_receipt_cancel_insert ON mom_wms.wms_transfer_receipt;
CREATE TRIGGER trg_transfer_receipt_cancel_insert BEFORE INSERT ON mom_wms.wms_transfer_receipt
	FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_transfer_receipt_cancelled_original();
DROP TRIGGER IF EXISTS trg_transfer_receipt_cancel_rebind ON mom_wms.wms_transfer_receipt;
CREATE TRIGGER trg_transfer_receipt_cancel_rebind BEFORE UPDATE OF tenant_id,request_id,allocation_bill_id,factory_code,receiver_code,request_snapshot
	ON mom_wms.wms_transfer_receipt FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_transfer_receipt_cancelled_original();
DROP TRIGGER IF EXISTS trg_transfer_receipt_cancel_delete ON mom_wms.wms_transfer_receipt;
CREATE TRIGGER trg_transfer_receipt_cancel_delete BEFORE DELETE ON mom_wms.wms_transfer_receipt
	FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_transfer_receipt_cancelled_original();
COMMIT;
