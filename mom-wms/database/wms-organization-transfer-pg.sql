-- 库存组织协同调拨：先停止全部WMS写入并备份，再执行。本脚本不创建角色授权、不改变历史库存公司归属。
BEGIN;
CREATE TABLE IF NOT EXISTS mom_wms.wms_org_transfer_policy (
 id bigint PRIMARY KEY, tenant_id bigint NOT NULL, factory_code varchar(100) NOT NULL,
 warehouse_code varchar(100) NOT NULL, warehouse_name varchar(200) NOT NULL,
 company_code varchar(100) NOT NULL, company_name varchar(200) NOT NULL,
 enabled boolean NOT NULL, cross_company boolean NOT NULL, version integer NOT NULL,
 updated_by varchar(100) NOT NULL, updated_time timestamp NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,factory_code,warehouse_code),
 CHECK(tenant_id>0 AND version>0 AND length(trim(factory_code))>0 AND length(trim(warehouse_code))>0
   AND length(trim(company_code))>0 AND length(trim(company_name))>0)
);
CREATE TABLE IF NOT EXISTS mom_wms.wms_org_transfer_bill (
 id bigint PRIMARY KEY, tenant_id bigint NOT NULL, bill_no varchar(100) NOT NULL,
 source_policy_id bigint NOT NULL, target_policy_id bigint NOT NULL,
 source_factory_code varchar(100) NOT NULL, source_warehouse_code varchar(100) NOT NULL,
 source_warehouse_name varchar(200) NOT NULL, source_company_code varchar(100) NOT NULL, source_company_name varchar(200) NOT NULL,
 target_factory_code varchar(100) NOT NULL, target_warehouse_code varchar(100) NOT NULL,
 target_warehouse_name varchar(200) NOT NULL, target_company_code varchar(100) NOT NULL, target_company_name varchar(200) NOT NULL,
 state varchar(40) NOT NULL, previous_state varchar(40), version integer NOT NULL,
 created_by varchar(100) NOT NULL, confirmed_by varchar(100), created_by_id bigint NOT NULL, confirmed_by_id bigint, agreement_ref varchar(200), remark varchar(1000),
 exception_reason varchar(1000), exception_evidence varchar(1000), created_time timestamp NOT NULL, updated_time timestamp NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,bill_no),
 FOREIGN KEY(tenant_id,source_policy_id) REFERENCES mom_wms.wms_org_transfer_policy(tenant_id,id),
 FOREIGN KEY(tenant_id,target_policy_id) REFERENCES mom_wms.wms_org_transfer_policy(tenant_id,id),
 CHECK(tenant_id>0 AND version>0 AND source_factory_code<>target_factory_code
   AND state IN('PENDING_CONFIRMATION','READY_TO_SHIP','IN_TRANSIT','PARTIALLY_RECEIVED','COMPLETED',
     'CANCELLED','RETURN_PENDING','RETURNED','CLOSED_WITH_RETURN','EXCEPTION_PENDING','CLOSED_WITH_DIFFERENCE'))
);
CREATE TABLE IF NOT EXISTS mom_wms.wms_org_transfer_line (
 id bigint PRIMARY KEY, tenant_id bigint NOT NULL, bill_id bigint NOT NULL, source_stock_id bigint NOT NULL,
 issue_flow_id bigint, quantity numeric NOT NULL, received_quantity numeric NOT NULL DEFAULT 0,
 returned_quantity numeric NOT NULL DEFAULT 0, exception_quantity numeric NOT NULL DEFAULT 0,
 source_snapshot text NOT NULL, issue_snapshot text,
 UNIQUE(tenant_id,bill_id,id), UNIQUE(tenant_id,bill_id,source_stock_id),
 FOREIGN KEY(tenant_id,bill_id) REFERENCES mom_wms.wms_org_transfer_bill(tenant_id,id),
 CHECK(source_stock_id>0 AND quantity>0 AND received_quantity>=0 AND returned_quantity>=0 AND exception_quantity>=0
   AND received_quantity+returned_quantity+exception_quantity<=quantity
   AND ((issue_flow_id IS NULL AND issue_snapshot IS NULL AND received_quantity=0 AND returned_quantity=0 AND exception_quantity=0)
     OR (issue_flow_id>0 AND issue_snapshot IS NOT NULL)))
);
CREATE TABLE IF NOT EXISTS mom_wms.wms_org_transfer_operation (
 tenant_id bigint NOT NULL, request_id varchar(64) NOT NULL, factory_code varchar(100) NOT NULL,
 warehouse_code varchar(100) NOT NULL, operator_code varchar(100) NOT NULL, operator_id bigint NOT NULL, action varchar(40) NOT NULL,
 bill_id bigint, command_payload text NOT NULL, result_payload text NOT NULL,
 status varchar(20) NOT NULL, completed_time timestamp NOT NULL,
 PRIMARY KEY(tenant_id,request_id),
 CHECK(tenant_id>0 AND operator_id>0 AND length(trim(request_id)) BETWEEN 16 AND 64 AND request_id=trim(request_id)
   AND status IN('APPLIED','ABANDONED') AND (status<>'APPLIED' OR bill_id IS NOT NULL)
   AND action IN('CREATE','CONFIRM','SHIP','RECEIVE','CANCEL','REQUEST_RETURN','RETURN',
     'PROPOSE_EXCEPTION','APPROVE_EXCEPTION','REJECT_EXCEPTION'))
);
CREATE TABLE IF NOT EXISTS mom_wms.wms_org_transfer_effect (
 id bigint PRIMARY KEY, tenant_id bigint NOT NULL, bill_id bigint NOT NULL, line_id bigint NOT NULL,
 request_id varchar(64) NOT NULL, factory_code varchar(100) NOT NULL, warehouse_code varchar(100) NOT NULL,
 action varchar(30) NOT NULL, stock_id bigint NOT NULL, flow_id bigint NOT NULL,
 quantity numeric NOT NULL, time timestamp NOT NULL,
 FOREIGN KEY(tenant_id,bill_id,line_id) REFERENCES mom_wms.wms_org_transfer_line(tenant_id,bill_id,id),
 FOREIGN KEY(tenant_id,request_id) REFERENCES mom_wms.wms_org_transfer_operation(tenant_id,request_id),
 UNIQUE(tenant_id,flow_id), CHECK(quantity>0 AND stock_id>0 AND flow_id>0 AND action IN('SHIP','RECEIVE','RETURN'))
);
CREATE INDEX IF NOT EXISTS idx_wms_org_transfer_source ON mom_wms.wms_org_transfer_bill(tenant_id,source_factory_code,source_warehouse_code,state);
CREATE INDEX IF NOT EXISTS idx_wms_org_transfer_target ON mom_wms.wms_org_transfer_bill(tenant_id,target_factory_code,target_warehouse_code,state);
CREATE INDEX IF NOT EXISTS idx_wms_org_transfer_stock ON mom_wms.wms_org_transfer_effect(tenant_id,factory_code,warehouse_code,stock_id);

CREATE OR REPLACE FUNCTION mom_wms.guard_org_transfer_policy() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION '库存组织绑定不可删除，只能停用'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended(CONCAT('WMS_ORG_TRANSFER:',NEW.tenant_id,':POLICY:',NEW.factory_code),0));
 IF NEW.enabled THEN
  PERFORM 1 FROM mom_mdm.mdm_warehouse WHERE tenant_id=NEW.tenant_id AND factory_code=NEW.factory_code
   AND warehouse_code=NEW.warehouse_code AND status=true AND del_flag='0' FOR SHARE;
  IF NOT FOUND THEN RAISE EXCEPTION '合作库存组织只能发布启用的实际仓库'; END IF;
 END IF;
 IF TG_OP='UPDATE' AND ROW(NEW.tenant_id,NEW.factory_code,NEW.warehouse_code,NEW.company_code)
   IS DISTINCT FROM ROW(OLD.tenant_id,OLD.factory_code,OLD.warehouse_code,OLD.company_code) THEN
   RAISE EXCEPTION '库存组织公司、工厂和仓库绑定不可重定义';
 END IF;
 IF EXISTS(SELECT 1 FROM mom_wms.wms_org_transfer_policy p WHERE p.tenant_id=NEW.tenant_id
   AND p.factory_code=NEW.factory_code AND p.company_code<>NEW.company_code) THEN
   RAISE EXCEPTION '同一工厂的库存组织公司必须一致';
 END IF;
 RETURN NEW;
END;
$$;
CREATE OR REPLACE FUNCTION mom_wms.guard_org_transfer_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '组织调拨永久回执与实物效果不可改写或删除';
END;
$$;
CREATE OR REPLACE FUNCTION mom_wms.guard_org_transfer_bill() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION '组织调拨业务凭证不可物理删除'; END IF;
 IF ROW(NEW.tenant_id,NEW.id,NEW.bill_no,NEW.source_policy_id,NEW.target_policy_id,NEW.source_factory_code,
   NEW.source_warehouse_code,NEW.source_company_code,NEW.target_factory_code,NEW.target_warehouse_code,NEW.target_company_code,NEW.created_by,NEW.created_by_id)
 IS DISTINCT FROM ROW(OLD.tenant_id,OLD.id,OLD.bill_no,OLD.source_policy_id,OLD.target_policy_id,OLD.source_factory_code,
   OLD.source_warehouse_code,OLD.source_company_code,OLD.target_factory_code,OLD.target_warehouse_code,OLD.target_company_code,OLD.created_by,OLD.created_by_id)
 OR NEW.version<>OLD.version+1 THEN RAISE EXCEPTION '组织调拨身份不可重绑定，版本须连续推进'; END IF;
 RETURN NEW;
END;
$$;
CREATE OR REPLACE FUNCTION mom_wms.guard_org_transfer_line() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION '组织调拨原始行不可删除'; END IF;
 IF ROW(NEW.tenant_id,NEW.id,NEW.bill_id,NEW.source_stock_id,NEW.quantity,NEW.source_snapshot)
 IS DISTINCT FROM ROW(OLD.tenant_id,OLD.id,OLD.bill_id,OLD.source_stock_id,OLD.quantity,OLD.source_snapshot)
 OR NEW.received_quantity<OLD.received_quantity OR NEW.returned_quantity<OLD.returned_quantity OR NEW.exception_quantity<OLD.exception_quantity
 OR (OLD.issue_flow_id IS NOT NULL AND ROW(NEW.issue_flow_id,NEW.issue_snapshot) IS DISTINCT FROM ROW(OLD.issue_flow_id,OLD.issue_snapshot)) THEN
   RAISE EXCEPTION '组织调拨来源不可改写，已确认数量不能减少';
 END IF;
 RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_org_transfer_policy ON mom_wms.wms_org_transfer_policy;
CREATE TRIGGER trg_org_transfer_policy BEFORE UPDATE OR DELETE OR INSERT ON mom_wms.wms_org_transfer_policy
 FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_org_transfer_policy();
DROP TRIGGER IF EXISTS trg_org_transfer_operation_immutable ON mom_wms.wms_org_transfer_operation;
CREATE TRIGGER trg_org_transfer_operation_immutable BEFORE UPDATE OR DELETE ON mom_wms.wms_org_transfer_operation
 FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_org_transfer_immutable();
DROP TRIGGER IF EXISTS trg_org_transfer_effect_immutable ON mom_wms.wms_org_transfer_effect;
CREATE TRIGGER trg_org_transfer_effect_immutable BEFORE UPDATE OR DELETE ON mom_wms.wms_org_transfer_effect
 FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_org_transfer_immutable();
DROP TRIGGER IF EXISTS trg_org_transfer_bill_identity ON mom_wms.wms_org_transfer_bill;
CREATE TRIGGER trg_org_transfer_bill_identity BEFORE UPDATE OR DELETE ON mom_wms.wms_org_transfer_bill
 FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_org_transfer_bill();
DROP TRIGGER IF EXISTS trg_org_transfer_line_identity ON mom_wms.wms_org_transfer_line;
CREATE TRIGGER trg_org_transfer_line_identity BEFORE UPDATE OR DELETE ON mom_wms.wms_org_transfer_line
 FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_org_transfer_line();

-- 保留部署库既有证据类型约束，只为本次组织调拨增加一种合法类型。
DO $$
DECLARE original text;
BEGIN
 SELECT pg_get_expr(conbin,conrelid) INTO original FROM pg_constraint
  WHERE conrelid='mom_wms.wms_inout_flow'::regclass AND conname='ck_wms_inout_flow_stock_proof_type';
 IF original IS NULL THEN RAISE EXCEPTION '缺少既有库存证据约束，请先核对基础升级'; END IF;
 IF position('ORGANIZATION_TRANSFER' in original)=0 THEN
  ALTER TABLE mom_wms.wms_inout_flow DROP CONSTRAINT ck_wms_inout_flow_stock_proof_type;
  EXECUTE format('ALTER TABLE mom_wms.wms_inout_flow ADD CONSTRAINT ck_wms_inout_flow_stock_proof_type CHECK ((%s) OR stock_proof_type=%L) NOT VALID',original,'ORGANIZATION_TRANSFER');
 END IF;
END;
$$;

-- 仓库停用撤下合作目录；历史组织绑定保留，公司归属不随仓库配置删除而被重标。
CREATE OR REPLACE FUNCTION mom_wms.org_transfer_warehouse_lifecycle() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND NEW.status=false AND OLD.status IS DISTINCT FROM NEW.status THEN
  UPDATE mom_wms.wms_org_transfer_policy SET enabled=false,version=version+1,updated_time=CURRENT_TIMESTAMP
   WHERE tenant_id=NEW.tenant_id AND factory_code=NEW.factory_code AND warehouse_code=NEW.warehouse_code AND enabled=true;
 END IF;
 IF TG_OP='DELETE' OR (TG_OP='UPDATE' AND (NEW.del_flag='1' OR ROW(NEW.tenant_id,NEW.factory_code,NEW.warehouse_code) IS DISTINCT FROM ROW(OLD.tenant_id,OLD.factory_code,OLD.warehouse_code))) THEN
  IF EXISTS(SELECT 1 FROM mom_wms.wms_org_transfer_bill WHERE tenant_id=OLD.tenant_id AND
    ((source_factory_code=OLD.factory_code AND source_warehouse_code=OLD.warehouse_code)
      OR (target_factory_code=OLD.factory_code AND target_warehouse_code=OLD.warehouse_code))) THEN
   RAISE EXCEPTION '仓库存在组织调拨历史，不可删除或重绑定';
  END IF;
  IF EXISTS(SELECT 1 FROM mom_wms.wms_org_transfer_policy WHERE tenant_id=OLD.tenant_id AND factory_code=OLD.factory_code
    AND warehouse_code=OLD.warehouse_code AND enabled=true) THEN RAISE EXCEPTION '先停用合作库存组织再删除仓库'; END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_org_transfer_warehouse_lifecycle ON mom_mdm.mdm_warehouse;
CREATE TRIGGER trg_org_transfer_warehouse_lifecycle BEFORE UPDATE OR DELETE ON mom_mdm.mdm_warehouse
 FOR EACH ROW EXECUTE FUNCTION mom_wms.org_transfer_warehouse_lifecycle();
COMMIT;
