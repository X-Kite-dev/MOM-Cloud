-- 先备份并停止全部WMS写入，在独立PG验证后升级；同时更新MES所依赖WMS API，不创建角色授权。
BEGIN;
ALTER TABLE mom_wms.wms_out_notice ADD COLUMN IF NOT EXISTS source_acceptance_revision integer;
DO $$
BEGIN
 IF EXISTS(SELECT 1 FROM mom_wms.wms_out_notice WHERE document_table='mes_picking' AND document_id IS NOT NULL
  AND (tenant_id IS NULL OR tenant_id<=0 OR factory_code IS NULL OR length(trim(factory_code))=0
   OR warehouse_code IS NULL OR length(trim(warehouse_code))=0 OR document_id<=0 OR COALESCE(source_acceptance_revision,1)<=0)) THEN
  RAISE EXCEPTION '存在来源租户工厂仓库或修订不完整的历史领料通知，请先审计归属';
 END IF;
 IF EXISTS(SELECT 1 FROM mom_wms.wms_out_notice WHERE document_table='mes_picking' AND document_id IS NOT NULL
  GROUP BY tenant_id,factory_code,document_id,warehouse_code,COALESCE(source_acceptance_revision,1) HAVING count(*)>1) THEN
  RAISE EXCEPTION '存在同来源同仓同修订历史重复通知，请核对并明确历史修订，不得静默删除合并';
 END IF;
END;
$$;
CREATE UNIQUE INDEX IF NOT EXISTS uk_wms_production_notice_source ON mom_wms.wms_out_notice
 (tenant_id,factory_code,document_id,warehouse_code,COALESCE(source_acceptance_revision,1))
 WHERE document_table='mes_picking' AND document_id IS NOT NULL;
CREATE TABLE IF NOT EXISTS mom_wms.wms_production_notice_source (
 tenant_id bigint NOT NULL,factory_code varchar(100) NOT NULL,document_id bigint NOT NULL,
 current_revision integer NOT NULL,state varchar(20) NOT NULL,updated_time timestamp NOT NULL,
 PRIMARY KEY(tenant_id,factory_code,document_id),CHECK(tenant_id>0 AND document_id>0 AND current_revision>0 AND state IN('ACTIVE','CANCELLED'))
);
CREATE TABLE IF NOT EXISTS mom_wms.wms_production_notice_acceptance (
 id bigint PRIMARY KEY,tenant_id bigint NOT NULL,factory_code varchar(100) NOT NULL,document_id bigint NOT NULL,
 source_revision integer NOT NULL,warehouse_code varchar(100) NOT NULL,notice_id bigint NOT NULL,
 original_request_id varchar(64) NOT NULL,notice_payload text NOT NULL,content_fingerprint varchar(64) NOT NULL,
 receipt_payload text NOT NULL,accepted_time timestamp NOT NULL,
 UNIQUE(tenant_id,factory_code,document_id,source_revision,warehouse_code),UNIQUE(tenant_id,notice_id),UNIQUE(id,tenant_id,factory_code,document_id,source_revision),
 FOREIGN KEY(tenant_id,factory_code,document_id) REFERENCES mom_wms.wms_production_notice_source(tenant_id,factory_code,document_id),
 FOREIGN KEY(notice_id) REFERENCES mom_wms.wms_out_notice(id),CHECK(source_revision>0 AND notice_id>0)
);
CREATE TABLE IF NOT EXISTS mom_wms.wms_production_notice_line (
 acceptance_id bigint NOT NULL,ordinal integer NOT NULL,source_document_detail_id bigint,
 notice_detail_id bigint NOT NULL,material_code varchar(100) NOT NULL,unit varchar(100) NOT NULL,quantity numeric NOT NULL,
 tenant_id bigint NOT NULL,factory_code varchar(100) NOT NULL,document_id bigint NOT NULL,source_revision integer NOT NULL,
 PRIMARY KEY(acceptance_id,ordinal),UNIQUE(notice_detail_id),UNIQUE(tenant_id,factory_code,document_id,source_revision,source_document_detail_id),
 FOREIGN KEY(acceptance_id,tenant_id,factory_code,document_id,source_revision) REFERENCES mom_wms.wms_production_notice_acceptance(id,tenant_id,factory_code,document_id,source_revision),
 FOREIGN KEY(acceptance_id) REFERENCES mom_wms.wms_production_notice_acceptance(id),
 FOREIGN KEY(notice_detail_id) REFERENCES mom_wms.wms_out_notice_detail(id),
 CHECK(ordinal>=0 AND quantity>0 AND (source_document_detail_id IS NULL OR source_document_detail_id>0))
);
CREATE TABLE IF NOT EXISTS mom_wms.wms_production_notice_operation (
 tenant_id bigint NOT NULL,request_id varchar(64) NOT NULL,factory_code varchar(100) NOT NULL,
 document_id bigint NOT NULL,source_revision integer NOT NULL,warehouse_code varchar(100) NOT NULL,
 action varchar(20) NOT NULL,request_payload text NOT NULL,result_payload text NOT NULL,operator_id bigint NOT NULL,
 operator_code varchar(100) NOT NULL,completed_time timestamp NOT NULL,
 PRIMARY KEY(tenant_id,request_id),FOREIGN KEY(tenant_id,factory_code,document_id) REFERENCES mom_wms.wms_production_notice_source(tenant_id,factory_code,document_id),CHECK(length(trim(request_id)) BETWEEN 16 AND 64 AND action IN('ACCEPT','CANCEL') AND operator_id>0 AND document_id>0 AND source_revision>0)
);
CREATE OR REPLACE FUNCTION mom_wms.guard_production_notice_permanent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION '领料受理凭据、来源明细与原请求回执不可改写删除'; END;
$$;
DROP TRIGGER IF EXISTS trg_production_notice_acceptance_immutable ON mom_wms.wms_production_notice_acceptance;
CREATE TRIGGER trg_production_notice_acceptance_immutable BEFORE UPDATE OR DELETE ON mom_wms.wms_production_notice_acceptance
 FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_production_notice_permanent();
DROP TRIGGER IF EXISTS trg_production_notice_line_immutable ON mom_wms.wms_production_notice_line;
CREATE TRIGGER trg_production_notice_line_immutable BEFORE UPDATE OR DELETE ON mom_wms.wms_production_notice_line
 FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_production_notice_permanent();
DROP TRIGGER IF EXISTS trg_production_notice_operation_immutable ON mom_wms.wms_production_notice_operation;
CREATE TRIGGER trg_production_notice_operation_immutable BEFORE UPDATE OR DELETE ON mom_wms.wms_production_notice_operation
 FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_production_notice_permanent();
CREATE OR REPLACE FUNCTION mom_wms.guard_accepted_production_notice() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM mom_wms.wms_production_notice_acceptance WHERE notice_id=OLD.id) THEN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION '已受理生产通知必须保留'; END IF;
  IF ROW(NEW.id,NEW.tenant_id,NEW.factory_code,NEW.document_id,NEW.document_table,NEW.warehouse_code,NEW.source_acceptance_revision,NEW.notice_out_num,NEW.bill_type,NEW.del_flag)
   IS DISTINCT FROM ROW(OLD.id,OLD.tenant_id,OLD.factory_code,OLD.document_id,OLD.document_table,OLD.warehouse_code,OLD.source_acceptance_revision,OLD.notice_out_num,OLD.bill_type,OLD.del_flag)
  THEN RAISE EXCEPTION '已受理通知的来源和原始数量不可重定义'; END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_accepted_production_notice ON mom_wms.wms_out_notice;
CREATE TRIGGER trg_accepted_production_notice BEFORE UPDATE OR DELETE ON mom_wms.wms_out_notice
 FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_accepted_production_notice();
CREATE OR REPLACE FUNCTION mom_wms.guard_accepted_production_line() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM mom_wms.wms_production_notice_line WHERE notice_detail_id=OLD.id) THEN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION '已受理生产通知明细必须保留'; END IF;
  IF ROW(NEW.id,NEW.tenant_id,NEW.factory_code,NEW.notice_id,NEW.document_id,NEW.document_table,NEW.warehouse_code,NEW.mat_code,NEW.mat_unit,NEW.notice_out_num,NEW.source_stock_id,NEW.reservation_no,NEW.stock_owner_type,NEW.stock_owner_code,NEW.requirement_mat_code,NEW.requirement_mat_num,NEW.substitute_flag,NEW.substitute_ratio,NEW.del_flag)
   IS DISTINCT FROM ROW(OLD.id,OLD.tenant_id,OLD.factory_code,OLD.notice_id,OLD.document_id,OLD.document_table,OLD.warehouse_code,OLD.mat_code,OLD.mat_unit,OLD.notice_out_num,OLD.source_stock_id,OLD.reservation_no,OLD.stock_owner_type,OLD.stock_owner_code,OLD.requirement_mat_code,OLD.requirement_mat_num,OLD.substitute_flag,OLD.substitute_ratio,OLD.del_flag)
  THEN RAISE EXCEPTION '已受理明细的来源、物料和需求数量不可改写'; END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_accepted_production_line ON mom_wms.wms_out_notice_detail;
CREATE TRIGGER trg_accepted_production_line BEFORE UPDATE OR DELETE ON mom_wms.wms_out_notice_detail
 FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_accepted_production_line();

-- 取消未知来源时也必须永久保留源标记；不能因没有通知外键就被清理掉。
CREATE OR REPLACE FUNCTION mom_wms.guard_production_notice_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION '领料来源修订与取消标记不可删除'; END IF;
 IF ROW(NEW.tenant_id,NEW.factory_code,NEW.document_id) IS DISTINCT FROM ROW(OLD.tenant_id,OLD.factory_code,OLD.document_id)
  OR NEW.current_revision NOT IN(OLD.current_revision,OLD.current_revision+1) THEN RAISE EXCEPTION '领料来源身份不可变，修订须连续'; END IF;
 IF NEW.current_revision=OLD.current_revision AND OLD.state='CANCELLED' AND NEW.state<>'CANCELLED'
  THEN RAISE EXCEPTION '已取消修订不能恢复为有效来源'; END IF;
 IF NEW.current_revision>OLD.current_revision AND OLD.state<>'CANCELLED' AND NOT EXISTS(
  SELECT 1 FROM mom_wms.wms_out_notice n WHERE n.tenant_id=OLD.tenant_id AND n.factory_code=OLD.factory_code
   AND n.document_table='mes_picking' AND n.document_id=OLD.document_id AND COALESCE(n.source_acceptance_revision,1)=OLD.current_revision)
  THEN RAISE EXCEPTION '空的有效修订尚未形成取消证据'; END IF;
 IF NEW.current_revision>OLD.current_revision OR NEW.state='CANCELLED' THEN
  IF EXISTS(SELECT 1 FROM mom_wms.wms_out_notice n WHERE n.tenant_id=OLD.tenant_id AND n.factory_code=OLD.factory_code
   AND n.document_table='mes_picking' AND n.document_id=OLD.document_id AND COALESCE(n.source_acceptance_revision,1)=OLD.current_revision
   AND (n.state IS DISTINCT FROM '3' OR n.del_flag IS DISTINCT FROM '0' OR n.all_out_num IS NULL OR n.all_out_num<>0)) THEN RAISE EXCEPTION '旧修订存在未撤回或已出库通知'; END IF;
  IF EXISTS(SELECT 1 FROM mom_wms.wms_out_bill b JOIN mom_wms.wms_out_notice n ON n.id=b.notice_id
   AND n.tenant_id=b.tenant_id AND n.factory_code=b.factory_code WHERE n.tenant_id=OLD.tenant_id AND n.factory_code=OLD.factory_code
   AND n.document_table='mes_picking' AND n.document_id=OLD.document_id AND COALESCE(n.source_acceptance_revision,1)=OLD.current_revision
   AND b.del_flag='0') THEN RAISE EXCEPTION '旧修订仍有关联出库单'; END IF;
 END IF;
 RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_production_notice_source ON mom_wms.wms_production_notice_source;
CREATE TRIGGER trg_production_notice_source BEFORE UPDATE OR DELETE ON mom_wms.wms_production_notice_source
 FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_production_notice_source();

CREATE OR REPLACE FUNCTION mom_wms.guard_production_notice_acceptance_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM mom_wms.wms_out_notice n WHERE n.id=NEW.notice_id AND n.tenant_id=NEW.tenant_id
  AND n.factory_code=NEW.factory_code AND n.document_id=NEW.document_id AND n.document_table='mes_picking'
  AND n.source_acceptance_revision=NEW.source_revision AND n.warehouse_code=NEW.warehouse_code
  AND n.bill_type='produce' AND n.del_flag='0' AND n.state='0') THEN RAISE EXCEPTION '受理凭据必须绑定本作用域实际新建领料通知'; END IF;
 RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_production_notice_acceptance_binding ON mom_wms.wms_production_notice_acceptance;
CREATE TRIGGER trg_production_notice_acceptance_binding BEFORE INSERT ON mom_wms.wms_production_notice_acceptance
 FOR EACH ROW EXECUTE FUNCTION mom_wms.guard_production_notice_acceptance_binding();
COMMIT;

