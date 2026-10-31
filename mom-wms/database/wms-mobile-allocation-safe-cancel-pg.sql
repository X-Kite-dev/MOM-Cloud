-- 即时调拨未执行请求的永久撤销回执。停止全部WMS写入，执行本脚本后再发布WMS和APP。
-- 此表不表示实际调拨，不参与在途/库存统计；不可删除或改写，不作过期清理。
BEGIN;
LOCK TABLE mom_wms.wms_allocation_bill IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE IF NOT EXISTS mom_wms.wms_mobile_allocation_cancel_receipt (
	tenant_id bigint NOT NULL,
	request_id varchar(64) NOT NULL,
	factory_code varchar(100) NOT NULL,
	operator_code varchar(100) NOT NULL,
	warehouse_code varchar(100) NOT NULL,
	bill_no varchar(100) NOT NULL,
	request_snapshot text NOT NULL,
	cancellation_reason varchar(500) NOT NULL,
	completed_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
	PRIMARY KEY (tenant_id, request_id),
	CONSTRAINT ck_wms_mobile_allocation_cancel_identity CHECK (
		tenant_id > 0 AND length(trim(request_id)) BETWEEN 1 AND 64
		AND request_id=trim(request_id) AND length(trim(factory_code)) > 0
		AND length(trim(operator_code)) > 0 AND length(trim(warehouse_code)) > 0
		AND warehouse_code=trim(warehouse_code) AND length(trim(bill_no)) > 0
		AND length(trim(request_snapshot)) > 0 AND length(trim(cancellation_reason)) > 0
	)
);
LOCK TABLE mom_wms.wms_mobile_allocation_cancel_receipt IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
	IF EXISTS (SELECT 1 FROM mom_wms.wms_mobile_allocation_cancel_receipt c
		JOIN mom_wms.wms_allocation_bill b ON b.tenant_id=c.tenant_id AND b.request_id=c.request_id) THEN
		RAISE EXCEPTION '同一调拨原请求同时存在业务单和撤销记录，须先核对历史，升级已停止';
	END IF;
END;
$$;

CREATE OR REPLACE FUNCTION mom_wms.guard_mobile_allocation_cancellation()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE payload jsonb;
BEGIN
	IF TG_OP <> 'INSERT' THEN
		RAISE EXCEPTION '即时调拨永久撤销回执不可改写或删除';
	END IF;
	PERFORM pg_advisory_xact_lock(hashtextextended(CONCAT('WMS_AGENT_TRANSFER:MOBILE_REQUEST:', NEW.tenant_id, ':', NEW.request_id), 0));
	IF EXISTS (SELECT 1 FROM mom_wms.wms_allocation_bill
		WHERE tenant_id=NEW.tenant_id AND request_id=NEW.request_id) THEN
		RAISE EXCEPTION '原调拨已有业务单，不得再封存为未执行撤销';
	END IF;
	payload := NEW.request_snapshot::jsonb;
	IF jsonb_typeof(payload) IS DISTINCT FROM 'object'
		OR payload->>'factory' IS DISTINCT FROM NEW.factory_code
		OR payload->>'sourceWarehouse' IS DISTINCT FROM NEW.warehouse_code
		OR payload->>'billNo' IS DISTINCT FROM NEW.bill_no
		OR jsonb_typeof(payload->'quantity') IS DISTINCT FROM 'number'
		OR COALESCE((payload->>'quantity')::numeric, 0) <= 0 THEN
		RAISE EXCEPTION '即时调拨撤销原文与身份或数量不一致';
	END IF;
	RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION mom_wms.guard_allocation_cancelled_original()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
	IF TG_OP = 'DELETE' THEN
		IF NULLIF(trim(OLD.request_id), '') IS NOT NULL THEN
			RAISE EXCEPTION '调拨原请求业务凭证不可物理删除';
		END IF;
		RETURN OLD;
	END IF;
	IF TG_OP = 'UPDATE' AND NULLIF(trim(OLD.request_id), '') IS NOT NULL
		AND ROW(NEW.tenant_id, NEW.request_id) IS DISTINCT FROM ROW(OLD.tenant_id, OLD.request_id) THEN
		RAISE EXCEPTION '调拨原请求租户与永久请求号不可重绑定';
	END IF;
	IF NULLIF(trim(NEW.request_id), '') IS NOT NULL THEN
		PERFORM pg_advisory_xact_lock(hashtextextended(CONCAT('WMS_AGENT_TRANSFER:MOBILE_REQUEST:', NEW.tenant_id, ':', NEW.request_id), 0));
		IF EXISTS (SELECT 1 FROM mom_wms.wms_mobile_allocation_cancel_receipt
			WHERE tenant_id=NEW.tenant_id AND request_id=NEW.request_id) THEN
			RAISE EXCEPTION '原即时调拨请求已撤销，禁止重放执行';
		END IF;
	END IF;
	RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_mobile_allocation_cancel_immutable ON mom_wms.wms_mobile_allocation_cancel_receipt;
CREATE TRIGGER trg_mobile_allocation_cancel_immutable BEFORE INSERT OR UPDATE OR DELETE
	ON mom_wms.wms_mobile_allocation_cancel_receipt FOR EACH ROW
	EXECUTE FUNCTION mom_wms.guard_mobile_allocation_cancellation();

DROP TRIGGER IF EXISTS trg_allocation_cancel_fence_insert ON mom_wms.wms_allocation_bill;
CREATE TRIGGER trg_allocation_cancel_fence_insert BEFORE INSERT ON mom_wms.wms_allocation_bill
	FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_allocation_cancelled_original();
DROP TRIGGER IF EXISTS trg_allocation_cancel_fence_rebind ON mom_wms.wms_allocation_bill;
CREATE TRIGGER trg_allocation_cancel_fence_rebind BEFORE UPDATE OF tenant_id, request_id ON mom_wms.wms_allocation_bill
	FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_allocation_cancelled_original();
DROP TRIGGER IF EXISTS trg_allocation_cancel_fence_delete ON mom_wms.wms_allocation_bill;
CREATE TRIGGER trg_allocation_cancel_fence_delete BEFORE DELETE ON mom_wms.wms_allocation_bill
	FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_allocation_cancelled_original();

COMMIT;
