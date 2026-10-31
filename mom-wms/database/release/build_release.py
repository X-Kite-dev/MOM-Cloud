"""构建固定范围的WMS发布包；本程序不连接数据库、不执行升级。"""

import argparse
import hashlib
import json
import pathlib
import re
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[5]
HERE = pathlib.Path(__file__).resolve().parent
KIND = "WMS_RELEASE_CLOSURE_V1"
SOURCES = [
    ("356", "Code/docs/database/mom-cumulative-upgrade-pg-v4.sql", 356),
    ("359", "Code/docs/database/mom-cumulative-upgrade-pg-v4.sql", 359),
    ("scope", "Code/docs/database/wms-inventory-scope-policy-pg.sql", None),
    ("template", "Code/docs/database/wms-inventory-count-template-pg.sql", None),
    ("schedule", "Code/docs/database/wms-inventory-count-schedule-pg.sql", None),
    ("approval", "Code/docs/database/wms-inventory-adjustment-approval-pg.sql", None),
    ("allocation_cancel", "Code/Mom-Cloud/mom-wms/database/wms-mobile-allocation-safe-cancel-pg.sql", None),
    ("transfer_cancel", "Code/Mom-Cloud/mom-wms/database/wms-mobile-transfer-receiving-pg.sql", None),
    ("organization", "Code/Mom-Cloud/mom-wms/database/wms-organization-transfer-pg.sql", None),
]
BASE_TABLES = (
    "mom_mdm.mdm_warehouse", "mom_wms.wms_live_stock",
    "mom_wms.wms_inside_inventory_plan", "mom_wms.wms_inside_inventory_plan_detail",
    "mom_wms.wms_allocation_bill", "mom_wms.wms_transfer_receipt", "mom_wms.wms_inout_flow",
)
BASE_COLUMNS = {
    "mom_mdm.mdm_warehouse": {"id": "identity", "tenant_id": "identity", "factory_code": "text",
                              "warehouse_code": "text", "status": "boolean", "del_flag": "text"},
    "mom_wms.wms_live_stock": {"id": "identity", "tenant_id": "identity", "factory_code": "text",
                               "warehouse_code": "text", "site_code": "text", "mat_code": "text",
                               "mat_batch": "text", "mat_num": "quantity"},
    "mom_wms.wms_inside_inventory_plan": {"id": "identity", "tenant_id": "identity", "factory_code": "text",
                                          "warehouse_code": "text", "status": "text", "handle_state": "text",
                                          "del_flag": "text"},
    "mom_wms.wms_inside_inventory_plan_detail": {"id": "identity", "tenant_id": "identity", "plan_id": "identity",
                                                 "factory_code": "text", "warehouse_code": "text", "site_code": "text",
                                                 "mat_code": "text", "mat_batch": "text", "current_number": "quantity",
                                                 "inventory_num": "quantity", "status": "text", "pl_status": "text",
                                                 "del_flag": "text"},
    "mom_wms.wms_allocation_bill": {"id": "identity", "tenant_id": "identity", "request_id": "text"},
    "mom_wms.wms_transfer_receipt": {"id": "identity", "tenant_id": "identity", "request_id": "request",
                                     "allocation_bill_id": "identity", "factory_code": "text",
                                     "receiver_code": "text", "request_snapshot": "text", "del_flag": "text"},
    "mom_wms.wms_inout_flow": {"id": "identity", "stock_proof_type": "text"},
}
TYPE_GROUPS = {
    "identity": (["int8"], True),
    "text": (["varchar", "text", "bpchar"], False),
    "request": (["varchar", "text", "bpchar"], True),
    "boolean": (["bool"], False),
    "quantity": (["numeric", "float8", "float4", "int8", "int4", "int2"], False),
}


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def verification_inputs():
    paths = [pathlib.Path(__file__), HERE / "verification/WmsReleaseProbe.java",
             HERE / "verification/baseline.sql", HERE / "verification/run_isolated.py"]
    return {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}


def statements(source):
    """分割顶层语句，保留函数体与引号；不以行文本猜测事务边界。"""
    result = []
    start = index = 0
    state = None
    depth = 0
    while index < len(source):
        pair = source[index:index + 2]
        char = source[index]
        if state == "line":
            if char == "\n":
                state = None
        elif state == "block":
            if pair == "/*":
                depth += 1
                index += 1
            elif pair == "*/":
                depth -= 1
                index += 1
                if depth == 0:
                    state = None
        elif state in ("'", '"'):
            if char == state:
                if index + 1 < len(source) and source[index + 1] == state:
                    index += 1
                else:
                    state = None
        elif state:
            if source.startswith(state, index):
                index += len(state) - 1
                state = None
        elif pair == "--":
            state = "line"
            index += 1
        elif pair == "/*":
            state = "block"
            depth = 1
            index += 1
        elif char in ("'", '"'):
            state = char
        elif char == "$":
            match = re.match(r"\$(?:[A-Za-z_][A-Za-z0-9_]*)?\$", source[index:])
            if match:
                state = match[0]
                index += len(state) - 1
        elif char == ";":
            result.append(source[start:index + 1])
            start = index + 1
        index += 1
    if state and state != "line":
        raise ValueError("SQL引号、函数体或注释未结束")
    if leading(source[start:]):
        raise ValueError("SQL末尾语句未结束")
    return result


def leading(source):
    source = source.strip()
    while source.startswith(("--", "/*")):
        if source.startswith("--"):
            source = source.partition("\n")[2].lstrip()
        else:
            end = source.find("*/")
            if end < 0:
                raise ValueError("SQL注释未结束")
            source = source[end + 2:].lstrip()
    return source


def body(source):
    parts = statements(source)
    if len(parts) < 2 or leading(parts[0]).upper() != "BEGIN;" or leading(parts[-1]).upper() != "COMMIT;":
        raise ValueError("来源必须具有唯一外层BEGIN/COMMIT事务")
    for part in parts[1:-1]:
        if re.match(r"(?:BEGIN|START|COMMIT|END|ROLLBACK|SAVEPOINT|RELEASE|PREPARE)\b", leading(part), re.I):
            raise ValueError("来源存在额外事务控制，拒绝产生非原子发布")
    return "".join(parts[1:-1]).strip() + "\n"


def collect():
    units = []
    for name, relative, section in SOURCES:
        source = (ROOT / relative).read_text(encoding="utf-8")
        if section is not None:
            marker = re.search(rf"(?m)^-- {section}\. ", source)
            if not marker:
                raise ValueError(f"未找到WMS章节{section}")
            end = re.search(r"(?m)^-- (?:下一章节编号：|[0-9]+\. )", source[marker.end():])
            source = source[marker.start():marker.end() + end.start() if end else len(source)]
        content = body(source)
        if re.search(r"\bmom_(?:mes|qms|plan|tpm|iot)\.", content):
            raise ValueError(f"单元{name}越出WMS发布边界")
        units.append({"id": name, "source": relative, "section": section,
                      "sha256": sha(source), "sourceText": source, "body": content})
    return units


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def catalog_sql(units):
    """仅比较本批拥有的对象；前置业务表不与最小夹具全表强比。"""
    source = "\n".join(unit["body"] for unit in units)
    tables = sorted(set(re.findall(r"CREATE TABLE IF NOT EXISTS (mom_wms\.\w+)", source, re.I)))
    functions = sorted(set(re.findall(r"CREATE OR REPLACE FUNCTION (mom_wms\.\w+)\s*\(", source, re.I)))
    indexes = sorted(set(re.findall(r"CREATE (?:UNIQUE )?INDEX IF NOT EXISTS (\w+)", source, re.I)))
    triggers = sorted(set(re.findall(r"CREATE TRIGGER (\w+)\b[^;]*?\bON (mom_(?:wms|mdm)\.\w+)", source, re.I)))
    objects = []
    for table in tables:
        relation = f"to_regclass({literal(table)})"
        query = f"""SELECT jsonb_build_object(
          'kind',c.relkind,'persistence',c.relpersistence,'rls',c.relrowsecurity,'forceRls',c.relforcerowsecurity,
          'comment',obj_description(c.oid,'pg_class'),
          'columns',(SELECT jsonb_agg(jsonb_build_array(a.attname,format_type(a.atttypid,a.atttypmod),a.attnotnull,
            pg_get_expr(d.adbin,d.adrelid)) ORDER BY a.attnum) FROM pg_attribute a LEFT JOIN pg_attrdef d
            ON d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped),
          'constraints',(SELECT jsonb_agg(jsonb_build_array(k.conname,k.contype,k.convalidated,pg_get_constraintdef(k.oid))
            ORDER BY k.conname) FROM pg_constraint k WHERE k.conrelid=c.oid)) FROM pg_class c WHERE c.oid={relation}"""
        objects.append(("table:" + table, query))
    for function in functions:
        schema, name = function.split(".")
        query = f"""SELECT jsonb_agg(jsonb_build_array(pg_get_function_identity_arguments(p.oid),
          pg_get_function_result(p.oid),l.lanname,p.prosecdef,p.provolatile,p.proparallel,p.proconfig,p.prosrc,
          obj_description(p.oid,'pg_proc')) ORDER BY pg_get_function_identity_arguments(p.oid))
          FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace JOIN pg_language l ON l.oid=p.prolang
          WHERE n.nspname={literal(schema)} AND p.proname={literal(name)}"""
        objects.append(("function:" + function, query))
    for index in indexes:
        query = f"""SELECT jsonb_build_array(pg_get_indexdef(i.indexrelid),i.indisunique,i.indisvalid,i.indisready,
          obj_description(i.indexrelid,'pg_class')) FROM pg_index i
          WHERE i.indexrelid=to_regclass({literal('mom_wms.' + index)})"""
        objects.append(("index:" + index, query))
    for name, table in triggers:
        query = f"""SELECT jsonb_build_array(pg_get_triggerdef(t.oid),t.tgenabled,t.tgisinternal)
          FROM pg_trigger t WHERE t.tgrelid=to_regclass({literal(table)}) AND t.tgname={literal(name)}"""
        objects.append(("trigger:" + table + ":" + name, query))
    objects.extend([
        ("prerequisite:count-void-audit-columns-330", """SELECT jsonb_agg(jsonb_build_array(
          a.attname,format_type(a.atttypid,a.atttypmod),a.attnotnull) ORDER BY a.attname)
          FROM pg_attribute a WHERE a.attrelid=to_regclass('mom_wms.wms_inside_inventory_plan')
          AND a.attname IN ('cancel_reason','cancel_request_id','cancel_by','cancel_time') AND NOT a.attisdropped"""),
        ("prerequisite:count-void-audit-constraint-330", """SELECT jsonb_build_array(pg_get_constraintdef(k.oid),
          k.contype,k.convalidated,obj_description(k.oid,'pg_constraint')) FROM pg_constraint k
          WHERE k.conrelid=to_regclass('mom_wms.wms_inside_inventory_plan') AND k.conname='ck_wms_inventory_cancel_330'"""),
        ("prerequisite:count-void-request-index-330", """SELECT jsonb_build_array(pg_get_indexdef(i.indexrelid),
          i.indisunique,i.indisvalid,i.indisready) FROM pg_index i
          WHERE i.indexrelid=to_regclass('mom_wms.uk_wms_inventory_cancel_request_330')"""),
        ("column:mom_wms.wms_inside_inventory_plan.stock_freeze_mode", """SELECT jsonb_build_array(
          format_type(a.atttypid,a.atttypmod),a.attnotnull,pg_get_expr(d.adbin,d.adrelid),col_description(a.attrelid,a.attnum))
          FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
          WHERE a.attrelid=to_regclass('mom_wms.wms_inside_inventory_plan') AND a.attname='stock_freeze_mode' AND NOT a.attisdropped"""),
        ("constraint:mom_wms.ck_wms_count_freeze_mode_359", """SELECT jsonb_build_array(pg_get_constraintdef(k.oid),
          k.convalidated,obj_description(k.oid,'pg_constraint')) FROM pg_constraint k
          WHERE k.conrelid=to_regclass('mom_wms.wms_inside_inventory_plan') AND k.conname='ck_wms_count_freeze_mode_359'"""),
    ])
    rows = ",\n".join(f"({literal(key)}, ({query}))" for key, query in objects)
    return "SELECT jsonb_object_agg(k,v) FROM (VALUES\n" + rows + ") objects(k,v)"


def preflight(units, contract, require_all):
    expected = json.dumps(contract["objects"], ensure_ascii=False, separators=(",", ":"))
    tag = "$wms_expected_" + sha(expected)[:12] + "$"
    required = ",".join(literal(name) for name in BASE_TABLES)
    missing = "TRUE" if require_all else "FALSE"
    columns = []
    for table, requirements in BASE_COLUMNS.items():
        for column, group in requirements.items():
            allowed, not_null = TYPE_GROUPS[group]
            types = "ARRAY[" + ",".join(literal(name) for name in allowed) + "]::text[]"
            columns.append(f"({literal(table)},{literal(column)},{types},{str(not_null).upper()})")
    required_columns = ",\n".join(columns)
    return f"""DO $wms_release_guard$
DECLARE expected jsonb := {tag}{expected}{tag}::jsonb;
        actual jsonb; item record; relation_name text;
BEGIN
 IF current_database()<>'kitex_mom' OR current_setting('server_encoding')<>'UTF8' THEN
  RAISE EXCEPTION 'WMS发布只允许kitex_mom UTF8数据库';
 END IF;
 FOREACH relation_name IN ARRAY ARRAY[{required}] LOOP
  IF NOT EXISTS(SELECT 1 FROM pg_class WHERE oid=to_regclass(relation_name)
    AND relkind='r' AND relpersistence='p' AND NOT relrowsecurity AND NOT relforcerowsecurity) THEN
   RAISE EXCEPTION 'WMS前置普通表缺失或范围未知：%',relation_name;
  END IF;
 END LOOP;
 FOR item IN SELECT * FROM (VALUES {required_columns})
   required(relation_name,column_name,allowed_types,require_not_null) LOOP
  IF NOT EXISTS(SELECT 1 FROM pg_attribute a JOIN pg_type t ON t.oid=a.atttypid
    WHERE a.attrelid=to_regclass(item.relation_name) AND a.attname=item.column_name
    AND a.attnum>0 AND NOT a.attisdropped AND t.typname=ANY(item.allowed_types)
    AND (NOT item.require_not_null OR a.attnotnull)) THEN
   RAISE EXCEPTION 'WMS前置业务字段缺失、类型或空值约束不兼容：%.%',item.relation_name,item.column_name;
  END IF;
 END LOOP;
 actual := ({catalog_sql(units)});
 FOR item IN SELECT key,value FROM jsonb_each(expected) LOOP
  IF actual->item.key IS NULL OR actual->item.key='null'::jsonb THEN
   IF {missing} OR item.key LIKE 'prerequisite:%' THEN
    RAISE EXCEPTION 'WMS发布或前置对象缺失：%',item.key;
   END IF;
  ELSIF actual->item.key IS DISTINCT FROM item.value THEN
   RAISE EXCEPTION 'WMS发布对象结构不一致：%',item.key
    USING HINT='停止升级并核对差异，不自动覆盖同名未知对象';
  END IF;
 END LOOP;
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid=to_regclass('mom_wms.wms_inout_flow')
    AND conname='ck_wms_inout_flow_stock_proof_type' AND contype='c') THEN
  RAISE EXCEPTION '缺少既有库存来源证据约束';
 END IF;
END $wms_release_guard$;
"""


def upgrade(units, before="", after=""):
    locks = ", ".join(BASE_TABLES)
    blocks = "\n".join(f"-- WMS unit {unit['id']}; {unit['source']}; SHA256 {unit['sha256']}\n{unit['body']}" for unit in units)
    return f"""-- 固定WMS发布单一事务。执行前须备份并停用所有WMS写入，失败须ROLLBACK。
BEGIN;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='5min';
SELECT pg_advisory_xact_lock(hashtextextended('MOM_WMS_RELEASE_CLOSURE_V1',0));
LOCK TABLE {locks} IN SHARE ROW EXCLUSIVE MODE;
{before}
{blocks}
{after}
COMMIT;
"""


def build(output, draft=False, contract_path=None, candidate=False):
    units = collect()
    hashes = {unit["id"]: unit["sha256"] for unit in units}
    status = "DRAFT_NOT_FOR_DEPLOYMENT" if draft else "CANDIDATE_NOT_FOR_DEPLOYMENT" if candidate else "READY"
    manifest = {"kind": KIND, "status": status,
                "units": [{k: unit[k] for k in ("id", "source", "section", "sha256")} for unit in units]}
    files = {}
    if draft:
        files["02-upgrade.sql"] = upgrade(units)
        files["catalog-query.sql"] = catalog_sql(units) + ";\n"
    else:
        path = pathlib.Path(contract_path) if contract_path else HERE / "schema-contract.json"
        if not path.exists():
            raise ValueError("先完成隔离PG演练并生成schema-contract.json，禁止发布未验证草案")
        contract = json.loads(path.read_text(encoding="utf-8"))
        required_phase = "CANDIDATE" if candidate else "VERIFIED"
        if contract.get("verification") != required_phase:
            raise ValueError("契约尚未完成验证，禁止生成可发布包")
        if contract.get("verificationInputs") != verification_inputs():
            raise ValueError("构建或演练程序已变化，请重新完成验证")
        if contract.get("sourceHashes") != hashes or not contract.get("objects") or any(value is None for value in contract["objects"].values()):
            raise ValueError("PG契约缺失或来源已变化，请重新演练")
        files["01-preflight.sql"] = preflight(units, contract, False)
        files["03-verify.sql"] = preflight(units, contract, True)
        files["02-upgrade.sql"] = upgrade(units, files["01-preflight.sql"], files["03-verify.sql"])
    manifest["files"] = {name: sha(value) for name, value in files.items()}
    files["manifest.json"] = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    output = pathlib.Path(output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        # 已发布目录不可变；相同构建只核对，不覆盖任何文件。
        if all((output / name).is_file() and (output / name).read_bytes() == value.encode("utf-8")
               for name, value in files.items()) and {path.name for path in output.iterdir()} == set(files):
            return manifest
        raise ValueError("目标已有不同或不完整产物；必须指定全新目录，保留原包")
    with tempfile.TemporaryDirectory(prefix="wms-release-build-", dir=output.parent) as temp:
        staging = pathlib.Path(temp)
        for name, value in files.items():
            (staging / name).write_text(value, encoding="utf-8")
        # 同文件系统内一次改名，读者只能看到完整目录或目录尚不存在。
        staging.rename(output)
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--draft", action="store_true", help="仅供隔离PG建立契约，禁止用于现场升级")
    args = parser.parse_args()
    result = build(args.output, args.draft)
    print(json.dumps({"status": result["status"], "units": len(result["units"]), "output": str(args.output)}, ensure_ascii=False))
