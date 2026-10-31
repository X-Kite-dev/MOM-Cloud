import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 只对本轮新建loopback实例执行合成数据；不接受云库地址。 */
public final class WmsReleaseProbe {

	private static String url;
	private static Path directory;
	private static Path release;

	public static void main(String[] args) throws Exception {
		if (args.length != 4 || !args[0].matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres")) {
			throw new IllegalArgumentException("Only the isolated loopback instance is allowed");
		}
		directory = Path.of(args[1]).toRealPath();
		release = Path.of(args[2]).toRealPath();
		url = args[0];
		try (var admin = connect()) {
			if (args[3].equals("fresh")) {
				exec(admin, "ALTER DATABASE kitex_mom RENAME TO wms_verified_history");
				exec(admin, "CREATE DATABASE kitex_mom ENCODING 'UTF8'");
			} else if (args[3].equals("bootstrap")) {
				exec(admin, "CREATE DATABASE kitex_mom ENCODING 'UTF8'");
			} else if (args[3].equals("verify")) {
				reject(admin, "wrong-database-preflight-rejected", Files.readString(release.resolve("01-preflight.sql")), "P0001");
			}
		}
		url = url.replace("/postgres", "/kitex_mom");
		try (var db = connect()) {
			if (args[3].equals("bootstrap")) {
				bootstrap(db);
			} else if (args[3].equals("verify")) {
				verifyPackage(db);
			} else if (args[3].equals("fresh")) {
				freshPackage(db);
			} else {
				throw new IllegalArgumentException("Unsupported phase");
			}
		}
		System.out.println("ALL CHECKS PASSED");
	}

	private static Connection connect() throws Exception {
		var db = DriverManager.getConnection(url + "?connectTimeout=5&socketTimeout=20&preferQueryMode=simple", "wms_verify", "");
		if (!Path.of(value(db, "SHOW data_directory")).toRealPath().equals(directory)) {
			db.close();
			throw new IllegalStateException("Unexpected isolated data directory");
		}
		exec(db, "SET statement_timeout='10s'; SET lock_timeout='3s'");
		return db;
	}

	private static void bootstrap(Connection db) throws Exception {
		exec(db, Files.readString(release.resolve("baseline.sql")));
		String before = original(db);
		String sql = Files.readString(release.resolve("02-upgrade.sql"));
		reject(db, "atomic-mid-failure-rollback", sql.replaceAll("(?m)^COMMIT;", "SELECT 1/0;\nCOMMIT;"), "22012");
		assertion("failed-release-no-new-table", value(db, "SELECT to_regclass('mom_wms.wms_inventory_scope_policy') IS NULL").equals("t"));
		assertion("failed-release-original-facts-preserved", original(db).equals(before));
		exec(db, sql);
		assertion("fresh-nine-unit-install", value(db, """
				SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE
				n.nspname='mom_wms' AND c.relkind='r'
				""").equals("19"));
		assertion("successful-release-original-facts-preserved", original(db).equals(before));
		assertion("legacy-count-default-snapshot", value(db,
				"SELECT stock_freeze_mode FROM mom_wms.wms_inside_inventory_plan WHERE id=1").equals("SNAPSHOT"));
		exec(db, sql);
		pass("idempotent-install-rerun");
		String objects = value(db, Files.readString(release.resolve("catalog-query.sql")));
		Files.writeString(release.resolve("schema-objects.json"), objects + "\n");
		String missing = value(db, "SELECT count(*) FROM jsonb_each(("
				+ Files.readString(release.resolve("catalog-query.sql")).replaceFirst(";\\s*$", "") + ")) WHERE value='null'::jsonb");
		assertion("all-owned-object-contracts-present", missing.equals("0"));
		counting(db);
		cancellations(db);
		organization(db);
		competition(db);
		exec(db, sql);
		pass("rerun-with-synthetic-business-history");
	}

	private static String original(Connection db) throws Exception {
		return value(db, """
				SELECT jsonb_build_array((SELECT jsonb_agg(to_jsonb(p)-'stock_freeze_mode' ORDER BY id) FROM
				mom_wms.wms_inside_inventory_plan p WHERE id=1),(SELECT jsonb_agg(to_jsonb(d) ORDER BY id) FROM
				mom_wms.wms_inside_inventory_plan_detail d WHERE id=1),(SELECT jsonb_agg(to_jsonb(s) ORDER BY id)
				FROM mom_wms.wms_live_stock s WHERE id=1))::text
				""");
	}

	private static void counting(Connection db) throws Exception {
		exec(db, """
				INSERT INTO mom_wms.wms_inside_inventory_plan(id,tenant_id,factory_code,warehouse_code,
				stock_freeze_mode) VALUES(100,1,'F1','W1','FREEZE_COUNT');
				 INSERT INTO mom_wms.wms_inside_inventory_plan_detail(id,tenant_id,factory_code,warehouse_code,
				plan_id,site_code,mat_code,mat_batch,current_number) VALUES(100,1,'F1','W1',100,'S1','M1','B1',10)
				""");
		reject(db, "freeze-matching-stock-insert", "INSERT INTO mom_wms.wms_live_stock VALUES(100,1,'F1','W1','S1','M1','B1',1)", "P0001");
		exec(db, "INSERT INTO mom_wms.wms_live_stock VALUES(101,1,'F1','W1','S1','M1','B2',1)");
		pass("freeze-other-batch-allowed");
		reject(db, "freeze-move-target-covered", "UPDATE mom_wms.wms_live_stock SET mat_batch='B1' WHERE id=101", "P0001");
		reject(db, "freeze-scope-immutable", "UPDATE mom_wms.wms_inside_inventory_plan_detail SET site_code='S2' WHERE id=100", "P0001");
		reject(db, "freeze-not-counted-cannot-finish", "UPDATE mom_wms.wms_inside_inventory_plan SET status='1' WHERE id=100", "P0001");
		reject(db, "freeze-quantity-snapshot-immutable",
				"UPDATE mom_wms.wms_inside_inventory_plan_detail SET current_number=11 WHERE id=100", "P0001");
		reject(db, "freeze-truncate-rejected", "TRUNCATE mom_wms.wms_live_stock", "P0001");
		exec(db, """
				UPDATE mom_wms.wms_inside_inventory_plan_detail SET status='1',inventory_num=10 WHERE id=100;
				 UPDATE mom_wms.wms_inside_inventory_plan SET status='1' WHERE id=100;
				 INSERT INTO mom_wms.wms_live_stock VALUES(100,1,'F1','W1','S1','M1','B1',1)
				""");
		pass("counted-completion-releases-stock");
		reject(db, "finished-freeze-cannot-reopen", "UPDATE mom_wms.wms_inside_inventory_plan SET status='0' WHERE id=100", "P0001");
		exec(db, """
				INSERT INTO mom_wms.wms_inside_inventory_plan(id,tenant_id,factory_code,warehouse_code,
				stock_freeze_mode) VALUES(101,1,'F1','W1','FREEZE_COUNT');
				 INSERT INTO mom_wms.wms_inside_inventory_plan_detail(id,tenant_id,factory_code,warehouse_code,
				plan_id,site_code,mat_code,mat_batch) VALUES(101,1,'F1','W1',101,'S1','M1',NULL)
				""");
		reject(db, "all-batches-freeze-update-source", "UPDATE mom_wms.wms_live_stock SET site_code='S2' WHERE id=101", "P0001");
		reject(db, "all-batches-freeze-delete-source", "DELETE FROM mom_wms.wms_live_stock WHERE id=101", "P0001");
		exec(db, """
				UPDATE mom_wms.wms_inside_inventory_plan SET del_flag='1' WHERE id=101;
				 UPDATE mom_wms.wms_inside_inventory_plan_detail SET del_flag='1' WHERE id=101;
				 UPDATE mom_wms.wms_live_stock SET mat_num=2 WHERE id=101
				""");
		pass("untouched-logical-delete-releases-stock");
		reject(db, "stock-repeatable-read-rejected",
				"BEGIN ISOLATION LEVEL REPEATABLE READ; UPDATE mom_wms.wms_live_stock SET mat_num=3 WHERE id=101; COMMIT", "P0001");
		exec(db, """
				INSERT INTO mom_wms.wms_inside_inventory_plan(id,tenant_id,factory_code,warehouse_code,
				stock_freeze_mode) VALUES(102,1,'F1','W1','FREEZE_COUNT');
				 INSERT INTO mom_wms.wms_inside_inventory_plan_detail(id,tenant_id,factory_code,warehouse_code,
				plan_id,site_code,mat_code,mat_batch,current_number) VALUES(102,1,'F1','W1',102,'S1','M1','B2',2);
				 UPDATE mom_wms.wms_inside_inventory_plan_detail SET status='1',inventory_num=2 WHERE id=102
				""");
		reject(db, "recorded-count-cannot-logically-delete",
				"UPDATE mom_wms.wms_inside_inventory_plan SET del_flag='1' WHERE id=102", "P0001");
		reject(db, "void-requires-existing-audit",
				"UPDATE mom_wms.wms_inside_inventory_plan SET status='2',handle_state='2' WHERE id=102", "23514");
		exec(db, """
				UPDATE mom_wms.wms_inside_inventory_plan SET status='2',handle_state='2',cancel_reason='核对作废',
				cancel_request_id='VOID-102',cancel_by='u',cancel_time=now() WHERE id=102;
				 UPDATE mom_wms.wms_live_stock SET mat_num=3 WHERE id=101
				""");
		pass("audited-void-releases-frozen-stock");
		reject(db, "voided-count-history-cannot-change",
				"UPDATE mom_wms.wms_inside_inventory_plan_detail SET inventory_num=3 WHERE id=102", "P0001");
		reject(db, "scope-policy-invalid-threshold", """
				INSERT INTO mom_wms.wms_inventory_scope_policy(tenant_id,factory_code,warehouse_code,rule_code,
				threshold_days,updated_by) VALUES(1,'F1','W1','NEAR_EXPIRY',0,'u')
				""", "23514");
		exec(db, """
				INSERT INTO mom_wms.wms_inventory_count_template(tenant_id,factory_code,template_code,template_name,
				warehouse_code,rule_code,site_codes_json,stock_freeze_mode,updated_by) VALUES(1,'F1','DAILY','日盘',
				'W1','ALL_POSITIVE','["S1"]','SNAPSHOT','u')
				""");
		reject(db, "schedule-enabled-without-due-rejected", """
				INSERT INTO mom_wms.wms_inventory_count_schedule(tenant_id,factory_code,template_code,enabled,
				interval_days,time_of_day,zone_id,updated_by) VALUES(1,'F1','DAILY',true,1,'08:00','Asia/Shanghai',
				'u')
				""", "23514");
		reject(db, "approval-posted-without-review-rejected", """
				INSERT INTO mom_wms.wms_inventory_adjustment_approval(id,tenant_id,factory_code,plan_id,
				plan_detail_id,warehouse_code,request_id,status,expected_current_number,expected_inventory_num,
				selected_detail_json,reason,applicant_code,submitted_time,posted_time) VALUES(1,1,'F1',100,100,'W1',
				'R1','POSTED',10,11,'{}','差异','u',now(),now())
				""", "23514");
	}

	private static void cancellations(Connection db) throws Exception {
		exec(db, """
				INSERT INTO mom_wms.wms_mobile_allocation_cancel_receipt(tenant_id,request_id,factory_code,
				operator_code,warehouse_code,bill_no,request_snapshot,cancellation_reason) VALUES(1,'CANCEL-A','F1',
				'u','W1','A1','{"factory":"F1","sourceWarehouse":"W1","billNo":"A1","quantity":1}','核对')
				""");
		reject(db, "cancelled-allocation-execution-fenced", "INSERT INTO mom_wms.wms_allocation_bill VALUES(10,1,'CANCEL-A')", "P0001");
		reject(db, "allocation-cancellation-immutable",
				"UPDATE mom_wms.wms_mobile_allocation_cancel_receipt SET cancellation_reason='changed'", "P0001");
		exec(db, "INSERT INTO mom_wms.wms_allocation_bill VALUES(11,1,'EXEC-A')");
		reject(db, "allocation-original-id-rebind-fenced",
				"UPDATE mom_wms.wms_allocation_bill SET request_id='OTHER' WHERE id=11", "P0001");
		reject(db, "allocation-original-delete-fenced", "DELETE FROM mom_wms.wms_allocation_bill WHERE id=11", "P0001");
		exec(db, """
				INSERT INTO mom_wms.wms_mobile_transfer_cancel_receipt(tenant_id,request_id,factory_code,
				operator_code,warehouse_code,allocation_bill_id,request_snapshot,cancellation_reason) VALUES(1,
				'CANCEL-T','F1','u','W1',1,'{"billId":"1","final":false,"lines":[{}]}','核对')
				""");
		reject(db, "cancelled-transfer-receive-fenced",
				"INSERT INTO mom_wms.wms_transfer_receipt VALUES(10,1,'CANCEL-T',1,'F1','u','{}','0')", "P0001");
		reject(db, "transfer-cancellation-delete-fenced", "DELETE FROM mom_wms.wms_mobile_transfer_cancel_receipt", "P0001");
		exec(db, "INSERT INTO mom_wms.wms_transfer_receipt VALUES(11,1,'EXEC-T',1,'F1','u','{}','1')");
		reject(db, "deleted-transfer-keeps-permanent-request",
				"INSERT INTO mom_wms.wms_transfer_receipt VALUES(12,1,'EXEC-T',1,'F1','u','{}','0')", "23505");
		reject(db, "transfer-original-payload-frozen",
				"UPDATE mom_wms.wms_transfer_receipt SET request_snapshot='{\"changed\":true}' WHERE id=11", "P0001");
	}

	private static void organization(Connection db) throws Exception {
		exec(db, """
				INSERT INTO mom_wms.wms_org_transfer_policy VALUES(1,1,'F1','W1','仓1','C1','公司1',true,true,1,'u',
				now()),(2,1,'F2','W2','仓2','C2','公司2',true,true,1,'v',now())
				""");
		reject(db, "organization-inactive-warehouse-rejected",
				"INSERT INTO mom_wms.wms_org_transfer_policy VALUES(3,1,'F3','W3','仓3','C3','公司3',true,true,1,'u',now())", "P0001");
		reject(db, "organization-company-binding-frozen",
				"UPDATE mom_wms.wms_org_transfer_policy SET company_code='OTHER',version=2 WHERE id=1", "P0001");
		exec(db, """
				INSERT INTO mom_wms.wms_org_transfer_bill(id,tenant_id,bill_no,source_policy_id,target_policy_id,
				source_factory_code,source_warehouse_code,source_warehouse_name,source_company_code,
				source_company_name,target_factory_code,target_warehouse_code,target_warehouse_name,
				target_company_code,target_company_name,state,version,created_by,created_by_id,created_time,
				updated_time) VALUES(1,1,'ORG1',1,2,'F1','W1','仓1','C1','公司1','F2','W2','仓2','C2','公司2',
				'PENDING_CONFIRMATION',1,'u',1,now(),now());
				 INSERT INTO mom_wms.wms_org_transfer_line(id,tenant_id,bill_id,source_stock_id,quantity,
				source_snapshot) VALUES(1,1,1,1,10,'{}')
				""");
		reject(db, "organization-version-cannot-skip", "UPDATE mom_wms.wms_org_transfer_bill SET version=3 WHERE id=1", "P0001");
		reject(db, "organization-unissued-line-cannot-receive",
				"UPDATE mom_wms.wms_org_transfer_line SET received_quantity=1 WHERE id=1", "23514");
		exec(db, "UPDATE mom_wms.wms_org_transfer_line SET issue_flow_id=1,issue_snapshot='{}',received_quantity=4 WHERE id=1");
		reject(db, "organization-quantity-conservation",
				"UPDATE mom_wms.wms_org_transfer_line SET returned_quantity=7 WHERE id=1", "23514");
		reject(db, "organization-confirmed-quantity-monotonic",
				"UPDATE mom_wms.wms_org_transfer_line SET received_quantity=3 WHERE id=1", "P0001");
		exec(db, """
				INSERT INTO mom_wms.wms_org_transfer_operation VALUES(1,'ORG-RECEIVE-00001','F2','W2','v',2,
				'RECEIVE',1,'{}','{}','APPLIED',now());
				 INSERT INTO mom_wms.wms_org_transfer_effect VALUES(1,1,1,1,'ORG-RECEIVE-00001','F2','W2','RECEIVE',
				1,1,4,now())
				""");
		reject(db, "organization-receipt-immutable",
				"UPDATE mom_wms.wms_org_transfer_operation SET result_payload='{\"changed\":true}'", "P0001");
		reject(db, "organization-effect-immutable", "DELETE FROM mom_wms.wms_org_transfer_effect", "P0001");
		exec(db, "UPDATE mom_mdm.mdm_warehouse SET status=false WHERE id=1");
		assertion("warehouse-disable-preserves-and-disables-policy", value(db,
				"SELECT (NOT enabled AND version=2)::text FROM mom_wms.wms_org_transfer_policy WHERE id=1").equals("true"));
		reject(db, "warehouse-history-prevents-delete", "DELETE FROM mom_mdm.mdm_warehouse WHERE id=1", "P0001");
		exec(db, "INSERT INTO mom_wms.wms_inout_flow VALUES(1,'INBOUND'),(2,'ORGANIZATION_TRANSFER')");
		reject(db, "original-proof-rule-still-rejects-unknown", "INSERT INTO mom_wms.wms_inout_flow VALUES(3,'UNKNOWN')", "23514");
	}

	private static void competition(Connection db) throws Exception {
		try (var owner = connect()) {
			exec(owner, "BEGIN; SELECT pg_advisory_xact_lock(hashtextextended('WMS_INVENTORY_FREEZE|1|F1|W1',0))");
			reject(db, "freeze-building-competition-fails-closed", "UPDATE mom_wms.wms_live_stock SET mat_num=4 WHERE id=101", "P0001");
			exec(owner, "ROLLBACK");
			exec(db, "UPDATE mom_wms.wms_live_stock SET mat_num=4 WHERE id=101");
			pass("freeze-lock-released-at-transaction-end");
		}
		race(db, "allocation-execution-first",
				"INSERT INTO mom_wms.wms_allocation_bill VALUES(40,1,'RACE-A')",
				allocationCancel("RACE-A"));
		race(db, "allocation-cancellation-first", allocationCancel("RACE-B"),
				"INSERT INTO mom_wms.wms_allocation_bill VALUES(41,1,'RACE-B')");
		race(db, "transfer-execution-first",
				"INSERT INTO mom_wms.wms_transfer_receipt VALUES(40,1,'RACE-T',1,'F1','u','{}','0')",
				transferCancel("RACE-T"));
		race(db, "transfer-cancellation-first", transferCancel("RACE-U"),
				"INSERT INTO mom_wms.wms_transfer_receipt VALUES(41,1,'RACE-U',1,'F1','u','{}','0')");
	}

	private static String allocationCancel(String request) {
		return "INSERT INTO mom_wms.wms_mobile_allocation_cancel_receipt"
				+ "(tenant_id,request_id,factory_code,operator_code,warehouse_code,bill_no,request_snapshot,cancellation_reason)"
				+ " VALUES(1,'" + request + "','F1','u','W1','A2',"
				+ "'{\"factory\":\"F1\",\"sourceWarehouse\":\"W1\",\"billNo\":\"A2\",\"quantity\":1}','核对')";
	}

	private static String transferCancel(String request) {
		return "INSERT INTO mom_wms.wms_mobile_transfer_cancel_receipt"
				+ "(tenant_id,request_id,factory_code,operator_code,warehouse_code,allocation_bill_id,request_snapshot,cancellation_reason)"
				+ " VALUES(1,'" + request + "','F1','u','W1',1,"
				+ "'{\"billId\":\"1\",\"final\":false,\"lines\":[{}]}','核对')";
	}

	private static void race(Connection db, String name, String winner, String loser) throws Exception {
		try (var owner = connect()) {
			exec(owner, "BEGIN; " + winner);
			var waitingPid = new CompletableFuture<String>();
			var competing = CompletableFuture.supplyAsync(() -> {
				try (var second = connect()) {
					waitingPid.complete(value(second, "SELECT pg_backend_pid()"));
					exec(second, loser);
					return "APPLIED";
				} catch (SQLException error) {
					return error.getSQLState();
				} catch (Exception error) {
					throw new RuntimeException(error);
				}
			});
			String pid = waitingPid.get(5, TimeUnit.SECONDS);
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			boolean waiting = false;
			while (System.nanoTime() < deadline) {
				waiting = value(db, "SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory'"
						+ " AND NOT granted AND pid=" + pid + ")").equals("t");
				if (waiting) {
					break;
				}
				Thread.sleep(20);
			}
			assertion(name + "-actually-waits-for-lock", waiting && !competing.isDone());
			exec(owner, "COMMIT");
			assertion(name + "-one-winner", competing.get(5, TimeUnit.SECONDS).equals("P0001"));
		}
	}

	private static void verifyPackage(Connection db) throws Exception {
		String before = original(db);
		exec(db, Files.readString(release.resolve("01-preflight.sql")));
		pass("installed-preflight-matches-contract");
		exec(db, Files.readString(release.resolve("02-upgrade.sql")));
		exec(db, Files.readString(release.resolve("03-verify.sql")));
		assertion("final-package-preserves-existing-facts", original(db).equals(before));
		reject(db, "unknown-same-name-column-stops-preflight",
				"BEGIN; ALTER TABLE mom_wms.wms_inventory_scope_policy ADD COLUMN unexpected text; "
				+ Files.readString(release.resolve("01-preflight.sql")), "P0001");
		reject(db, "unknown-function-security-stops-preflight",
				"BEGIN; ALTER FUNCTION mom_wms.guard_mobile_allocation_cancellation() SECURITY DEFINER; "
				+ Files.readString(release.resolve("01-preflight.sql")), "P0001");
		reject(db, "disabled-owned-trigger-stops-preflight",
				"BEGIN; ALTER TABLE mom_wms.wms_allocation_bill DISABLE TRIGGER trg_allocation_cancel_fence_insert; "
				+ Files.readString(release.resolve("01-preflight.sql")), "P0001");
		reject(db, "missing-object-stops-post-verification", "BEGIN; DROP TABLE mom_wms.wms_inventory_scope_policy; "
				+ Files.readString(release.resolve("03-verify.sql")), "P0001");
		reject(db, "missing-count-void-audit-stops-preflight",
				"BEGIN; ALTER TABLE mom_wms.wms_inside_inventory_plan DROP CONSTRAINT ck_wms_inventory_cancel_330; "
				+ Files.readString(release.resolve("01-preflight.sql")), "P0001");
		exec(db, Files.readString(release.resolve("03-verify.sql")));
		pass("preflight-rejection-preserves-known-schema");
		reject(db, "missing-warehouse-status-stops-preflight",
				"BEGIN; ALTER TABLE mom_mdm.mdm_warehouse DROP COLUMN status; "
				+ Files.readString(release.resolve("01-preflight.sql")), "P0001");
		reject(db, "wrong-warehouse-status-type-stops-preflight",
				"""
				BEGIN;
				ALTER TABLE mom_mdm.mdm_warehouse ALTER COLUMN status DROP DEFAULT;
				ALTER TABLE mom_mdm.mdm_warehouse ALTER COLUMN status TYPE text USING status::text;
				""" + Files.readString(release.resolve("01-preflight.sql")), "P0001");
		reject(db, "missing-inventory-material-stops-preflight",
				"BEGIN; ALTER TABLE mom_wms.wms_live_stock DROP COLUMN mat_code; "
				+ Files.readString(release.resolve("01-preflight.sql")), "P0001");
		reject(db, "wrong-inventory-quantity-type-stops-preflight",
				"BEGIN; ALTER TABLE mom_wms.wms_live_stock ALTER COLUMN mat_num TYPE text USING mat_num::text; "
				+ Files.readString(release.resolve("01-preflight.sql")), "P0001");
	}

	private static void freshPackage(Connection db) throws Exception {
		exec(db, Files.readString(release.getParent().resolve("baseline.sql")));
		String before = original(db);
		exec(db, Files.readString(release.resolve("01-preflight.sql")));
		pass("fresh-target-preflight-allows-missing-owned-objects");
		String sql = Files.readString(release.resolve("02-upgrade.sql"));
		reject(db, "guarded-package-failure-rolls-back", sql.replaceAll("(?m)^COMMIT;", "SELECT 1/0;\nCOMMIT;"), "22012");
		assertion("guarded-package-failure-preserves-facts", original(db).equals(before));
		assertion("guarded-package-failure-preserves-schema", value(db,
				"SELECT to_regclass('mom_wms.wms_inventory_scope_policy') IS NULL").equals("t"));
		exec(db, sql);
		exec(db, Files.readString(release.resolve("03-verify.sql")));
		assertion("guarded-package-fresh-install-preserves-facts", original(db).equals(before));
	}

	private static void exec(Connection db, String sql) throws SQLException {
		try (var statement = db.createStatement()) {
			statement.execute(sql);
		}
	}

	private static String value(Connection db, String sql) throws SQLException {
		try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
			if (!result.next()) {
				throw new IllegalStateException("Expected one result row");
			}
			return result.getString(1);
		}
	}

	private static void reject(Connection db, String name, String sql, String expectedState) throws SQLException {
		try {
			exec(db, sql);
		} catch (SQLException error) {
			exec(db, "ROLLBACK");
			if (!expectedState.equals(error.getSQLState())) {
				throw new IllegalStateException(name + ": expected SQLSTATE " + expectedState + ", got " + error.getSQLState(), error);
			}
			pass(name);
			return;
		}
		throw new IllegalStateException(name + ": invalid operation was accepted");
	}

	private static void assertion(String name, boolean valid) {
		if (!valid) {
			throw new IllegalStateException(name);
		}
		pass(name);
	}

	private static void pass(String name) {
		System.out.println("PASS " + name);
	}
}
