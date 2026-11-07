package com.kitex.mom.wms.service.rework;

import com.fasterxml.jackson.databind.ObjectMapper;
import cn.hutool.core.util.IdUtil;
import com.kitex.mom.wms.dto.WmsNcrReworkDTO.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

import static com.kitex.mom.wms.service.rework.WmsNcrReworkRules.*;

/**
 * 返工永久结果账；所有列显式写入，不依赖BaseEntity自动填充或跨域写表。
 * 原号锁在原仓锁、parent行锁之前，永久终态只INSERT，不UPDATE/DELETE。
 */
@Repository
@RequiredArgsConstructor
public class WmsNcrReworkRepository {
	private final JdbcTemplate db;
	private final ObjectMapper json;

	/**
	 * 目录或保护未就绪明确失败，查询异常永不伪装为NOT_FOUND。
	 */
	public void ready() {
		try {
			require(Boolean.TRUE.equals(
							db.queryForObject("SELECT mom_wms.wms_ncr_rework_ready_v1()", Boolean.class)),
					"NCR返工数据库保护未就绪");
		} catch (org.springframework.dao.DataAccessException e) {
			throw new com.kitex.common.core.exception.CheckedException("NCR返工数据库章节未安装或保护不完整");
		}
	}

	/**
	 * 同scope原号apply与cancel共享事务锁，未受理取消也成为永久事实。
	 */
	public void lock(Command c) {
		Key k = c.source().key();
		db.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?,0))", Integer.class,
				"WMS_NCR_REWORK:" + k.tenantId() + ":" + k.factoryCode() + ":" + c.requestId());
		if (k.binding() != null) {
			// 与395保持完全相同的实际原仓锁键，禁止以新锁域绕过旧特殊收发。
			db.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?,0))", Integer.class,
					"WMS_SPECIAL_WAREHOUSE:" + k.tenantId() + ":" + k.factoryCode() + ":" +
							c.source().warehouseCode());
		}
	}

	/**
	 * 权威未见只指原组织原号SELECT零行，连接、解析或列正文冲突一律抛错。
	 */
	public Receipt find(Command c) {
		Key k = c.source().key();
		List<Receipt> rows = db.query(
				"SELECT id,tenant_id,factory_code,request_id,parent_operation_id,warehouse_code,"
						+ "status,command_json,receipt_json,recorded_at FROM mom_wms.wms_ncr_rework_result "
						+ "WHERE tenant_id=? AND factory_code=? AND request_id=?", (r, n) -> {
					Command original = decode(r.getString("command_json"), Command.class);
					Receipt receipt = decode(r.getString("receipt_json"), Receipt.class);
					command(original);
					validateReceipt(receipt);
					require(original.equals(receipt.command()) &&
									original.source().key().tenantId().equals(r.getString("tenant_id"))
									&& original.source().key().factoryCode().equals(r.getString("factory_code"))
									&& original.requestId().equals(r.getString("request_id"))
									&& original.source().key().parentOperationId()
									.equals(r.getString("parent_operation_id"))
									&& original.source().warehouseCode().equals(r.getString("warehouse_code"))
									&& receipt.resultId().equals(r.getString("id")) &&
									receipt.status().equals(r.getString("status"))
									&& LocalDateTime.parse(receipt.recordedAt())
									.equals(r.getObject("recorded_at", LocalDateTime.class)),
							"返工永久结果列与原文不一致");
					return receipt;
				}, id(k.tenantId()), k.factoryCode(), c.requestId());
		require(rows.size() <= 1, "返工原号存在重复永久结果");
		if (rows.isEmpty()) {
			return null;
		}
		Receipt receipt = rows.get(0);
		require(c.equals(receipt.command()), "返工原号已被不同完整命令占用");
		return receipt;
	}

	/**
	 * 完整校验永久终态、原人及逐件前后状态，不把截断JSON或空回包当作权威成功。
	 */
	private void validateReceipt(Receipt receipt) {
		require(receipt != null && receipt.command() != null && receipt.changes() != null,
				"永久返工回执不完整");
		id(receipt.resultId());
		actor(receipt.recordedBy());
		require(Set.of("APPLIED", "CANCELLED").contains(receipt.status())
						&& receipt.command().reviewer().userId().equals(receipt.recordedBy().userId()),
				"永久返工回执终态或落账人员错误");
		LocalDateTime at;
		try {
			at = LocalDateTime.parse(receipt.recordedAt());
		} catch (RuntimeException e) {
			throw new com.kitex.common.core.exception.CheckedException("永久返工回执时间缺失或无效");
		}
		require(at.getNano() % 1000 == 0, "永久返工回执时间精度无效");
		if ("CANCELLED".equals(receipt.status())) {
			require(receipt.changes().isEmpty(), "取消回执不得包含库存变化");
			return;
		}
		var pieces = receipt.command().source().pieces();
		require(receipt.changes().size() == pieces.size(), "永久返工回执逐件集合不完整");
		boolean pass = "PASS".equals(receipt.command().decision());
		for (int i = 0; i < pieces.size(); i++) {
			var p = pieces.get(i);
			var c = receipt.changes().get(i);
			require(c != null && p.stockId().equals(c.stockId()) && p.quantity().equals(c.quantity())
							&& "1".equals(c.beforeStatus()) && "REWORK".equals(c.beforeQuality())
							&& "UN_QUALIFIED".equals(c.beforeResult()) && (pass ? "0" : "1").equals(c.afterStatus())
							&& (pass ? "QUALIFIED" : "HOLD").equals(c.afterQuality())
							&& (pass ? "RELEASE" : "UN_QUALIFIED").equals(c.afterResult()),
					"永久返工逐件状态或数量不匹配");
		}
	}

	/**
	 * parent行锁内排除另一个原号已经生效，取消历史不占用生效唯一位。
	 */
	public void requireUnapplied(Key k) {
		Long count = db.queryForObject("SELECT count(*) FROM mom_wms.wms_ncr_rework_result "
						+ "WHERE tenant_id=? AND factory_code=? AND parent_operation_id=? AND status='APPLIED'",
				Long.class,
				id(k.tenantId()), k.factoryCode(), id(k.parentOperationId()));
		require(count != null && count == 0, "该原返工操作已经由另一决定生效");
	}

	/**
	 * 在旧库存仍被锁定时插入结果证明原件，随后同事务更新库存，延迟约束核最终精确终态。
	 */
	public Receipt insert(Command c, String status, Actor actor, LocalDateTime at, List<Change> changes) {
		Key k = c.source().key();
		Receipt receipt = new Receipt(c, status, IdUtil.getSnowflakeNextIdStr(), actor, at.toString(),
				List.copyOf(changes));
		require(db.update("INSERT INTO mom_wms.wms_ncr_rework_result "
						+
						"(id,tenant_id,factory_code,request_id,parent_operation_id,warehouse_code,status,command_json,receipt_json,recorded_at) "
						+ "VALUES (?,?,?,?,?,?,?,?,?,?)", id(receipt.resultId()), id(k.tenantId()), k.factoryCode(),
				c.requestId(), id(k.parentOperationId()), c.source().warehouseCode(), status,
				encode(c), encode(receipt), Timestamp.valueOf(at)) == 1, "返工永久回执保存失败");
		return Objects.requireNonNull(find(c));
	}

	/**
	 * 显式序列化冻结原文，令牌与权限集合从不进入对象合同。
	 */
	public String encode(Object value) {
		try {
			return json.writeValueAsString(value);
		} catch (Exception e) {
			throw new com.kitex.common.core.exception.CheckedException("返工永久原文无法编码");
		}
	}

	/**
	 * 读取原record，损坏/缺失JSON明确失败，不补空业务或成功事实。
	 */
	public <T> T decode(String value, Class<T> type) {
		try {
			return json.readValue(value, type);
		} catch (Exception e) {
			throw new com.kitex.common.core.exception.CheckedException("返工永久原文无法完整读取");
		}
	}
}
