-- 仅供全新隔离PG的最小前置夹具；不是运行库建表脚本，不含企业真实数据。
-- 不复制运行库的全部历史约束/触发器；现场原有规则仍须单独预检和联合验收。
CREATE SCHEMA mom_wms;
CREATE SCHEMA mom_mdm;

CREATE TABLE mom_mdm.mdm_warehouse (
    id bigint PRIMARY KEY,
    tenant_id bigint NOT NULL,
    factory_code varchar(100) NOT NULL,
    warehouse_code varchar(100) NOT NULL,
    status boolean NOT NULL DEFAULT true,
    del_flag char(1) NOT NULL DEFAULT '0'
);
CREATE TABLE mom_wms.wms_live_stock (
    id bigint PRIMARY KEY,
    tenant_id bigint NOT NULL,
    factory_code varchar(100) NOT NULL,
    warehouse_code varchar(100) NOT NULL,
    site_code varchar(100) NOT NULL,
    mat_code varchar(100) NOT NULL,
    mat_batch varchar(100),
    mat_num double precision NOT NULL
);
CREATE TABLE mom_wms.wms_inside_inventory_plan (
    id bigint PRIMARY KEY,
    tenant_id bigint NOT NULL,
    factory_code varchar(100) NOT NULL,
    warehouse_code varchar(100) NOT NULL,
    status varchar(1) NOT NULL DEFAULT '0',
    handle_state varchar(1) NOT NULL DEFAULT '0',
    del_flag char(1) NOT NULL DEFAULT '0',
    cancel_reason varchar(500),
    cancel_request_id varchar(64),
    cancel_by varchar(64),
    cancel_time timestamp,
    CONSTRAINT ck_wms_inventory_cancel_330 CHECK (
        (status='2' AND handle_state='2' AND nullif(btrim(cancel_reason),'') IS NOT NULL
         AND nullif(btrim(cancel_request_id),'') IS NOT NULL AND nullif(btrim(cancel_by),'') IS NOT NULL
         AND cancel_time IS NOT NULL)
        OR (status IS DISTINCT FROM '2' AND handle_state IS DISTINCT FROM '2'
         AND cancel_reason IS NULL AND cancel_request_id IS NULL AND cancel_by IS NULL AND cancel_time IS NULL)
    )
);
-- 330作废审计的前置契约，按累计升级原定义复现；不在本批重复迁移330。
COMMENT ON CONSTRAINT ck_wms_inventory_cancel_330 ON mom_wms.wms_inside_inventory_plan
    IS 'MOM330:作废状态必须有完整审计';
CREATE UNIQUE INDEX uk_wms_inventory_cancel_request_330
    ON mom_wms.wms_inside_inventory_plan(tenant_id,factory_code,cancel_request_id)
    WHERE cancel_request_id IS NOT NULL;
CREATE TABLE mom_wms.wms_inside_inventory_plan_detail (
    id bigint PRIMARY KEY,
    tenant_id bigint NOT NULL,
    factory_code varchar(100) NOT NULL,
    warehouse_code varchar(100) NOT NULL,
    plan_id bigint NOT NULL REFERENCES mom_wms.wms_inside_inventory_plan(id),
    site_code varchar(100) NOT NULL,
    mat_code varchar(100) NOT NULL,
    mat_batch varchar(100),
    current_number double precision NOT NULL DEFAULT 0,
    inventory_num double precision,
    status varchar(1) NOT NULL DEFAULT '0',
    pl_status varchar(1) NOT NULL DEFAULT '0',
    del_flag char(1) NOT NULL DEFAULT '0'
);
CREATE TABLE mom_wms.wms_allocation_bill (
    id bigint PRIMARY KEY,
    tenant_id bigint NOT NULL,
    request_id varchar(64)
);
CREATE TABLE mom_wms.wms_transfer_receipt (
    id bigint PRIMARY KEY,
    tenant_id bigint NOT NULL,
    request_id varchar(64) NOT NULL,
    allocation_bill_id bigint NOT NULL,
    factory_code varchar(100) NOT NULL,
    receiver_code varchar(100) NOT NULL,
    request_snapshot text NOT NULL,
    del_flag char(1) NOT NULL DEFAULT '0'
);
CREATE TABLE mom_wms.wms_inout_flow (
    id bigint PRIMARY KEY,
    stock_proof_type varchar(50),
    CONSTRAINT ck_wms_inout_flow_stock_proof_type
        CHECK (stock_proof_type IN ('INBOUND', 'OUTBOUND', 'MOBILE_MOVE'))
);

INSERT INTO mom_mdm.mdm_warehouse VALUES
    (1,1,'F1','W1',true,'0'),
    (2,1,'F2','W2',true,'0'),
    (3,1,'F3','W3',false,'0');
INSERT INTO mom_wms.wms_live_stock VALUES (1,1,'F1','W1','OLD','M1','B1',10);
INSERT INTO mom_wms.wms_inside_inventory_plan(id,tenant_id,factory_code,warehouse_code)
    VALUES (1,1,'F1','W1');
INSERT INTO mom_wms.wms_inside_inventory_plan_detail
    (id,tenant_id,factory_code,warehouse_code,plan_id,site_code,mat_code,mat_batch)
    VALUES (1,1,'F1','W1',1,'OLD','M1','B1');
