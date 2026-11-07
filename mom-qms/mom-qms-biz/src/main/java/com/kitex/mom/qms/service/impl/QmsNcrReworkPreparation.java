package com.kitex.mom.qms.service.impl;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.qms.dto.req.QmsNcrReworkDTO.*;
import com.kitex.mom.wms.dto.WmsNcrReworkDTO.Source;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

import static com.kitex.mom.qms.service.impl.QmsNcrReworkRepository.*;
import static com.kitex.mom.qms.service.impl.QmsNcrReworkSource.*;

/**
 * 原准备号的本地永久裁决；与意图共数据库原号锁，迟到请求不能复活已拒绝原号。
 */
@Service
@RequiredArgsConstructor
public class QmsNcrReworkPreparation {
	private final JdbcTemplate jdbc;
	private final QmsNcrReworkRepository ledger;
	private final QmsNcrReworkSource sources;

	/**
	 * @param intent 已冻结意图，与拒绝二选一 @param refusal 已提交的本地永久拒绝，与意图二选一
	 */
	public record Resolution(Intent intent, NotPrepared refusal) {
	}

	/**
	 * 仅确定的本地前准备业务规则使用；数据库、授权、远程及提交异常不得转换为此类型。
	 */
	public static class LocalRejection extends RuntimeException {
		/**
		 * 原拒绝业务原因码。
		 */
		private final String reasonCode;

		public LocalRejection(String reasonCode, String reason) {
			super(reason);
			this.reasonCode = reasonCode;
		}
	}

	/**
	 * 固定原文只规范一次；非法形状不裁决，避免把无法绑定的请求伪装成已拒绝事实。
	 */
	public Prepare canonical(Prepare input) {
		require(input != null, "独立复核请求不能为空");
		request(input.requestId());
		id(input.proposalId());
		id(input.parentOperationId());
		if (input.completionId() != null) {
			id(input.completionId());
		}
		require("PASS".equals(input.decision()) || "REJECT".equals(input.decision()), "独立复核决定须为PASS/REJECT");
		return new Prepare(input.requestId(), input.proposalId(), input.parentOperationId(), input.completionId(),
				input.decision(), text(input.reviewEvidence()));
	}

	/**
	 * 先于detail锁，与数据库触发器使用同一UTF8十六进制scope原号；不依赖当前进程。
	 */
	public void lock(Scope scope, String request) {
		String key = "QMS_NCR_REWORK_PREPARE_V1:" + scope.tenant() + ":"
				+ HexFormat.of().formatHex(scope.factory().getBytes(StandardCharsets.UTF_8)) + ":" + request;
		jdbc.queryForList("SELECT mom_qms.ncr_rework_prepare_lock_397(?::text)", key);
	}

	/**
	 * 已持原号锁的准备/裁决事务先调用；永久意图与拒绝都按完整规范正文和稳定用户核对。
	 */
	public Resolution replay(long detail, Prepare input, Scope scope) {
		var intent = ledger.intentRequest(scope.tenant(), scope.factory(), input.requestId());
		var refusal = refusal(scope, input.requestId());
		require(intent == null || refusal == null, "同一原号出现意图与本地拒绝冲突，停止办理");
		if (intent != null) {
			var original = intent.command();
			require(intent.detailId() == detail && intent.proposalId() == id(input.proposalId())
							&& intent.parentOperationId() == id(input.parentOperationId()) &&
							intent.reviewerId() == id(scope.actor().userId())
							&& Objects.equals(original.completionId(), input.completionId()) &&
							original.decision().equals(input.decision())
							&& original.reviewEvidence().equals(input.reviewEvidence()),
					"验证原号已用于不同原件、复核人或完整正文");
			return new Resolution(intent, null);
		}
		if (refusal != null) {
			require(refusal.detailId().equals(Long.toString(detail)) && refusal.proposalId().equals(input.proposalId())
							&& refusal.parentOperationId().equals(input.parentOperationId()) &&
							refusal.reviewerId().equals(scope.actor().userId())
							&& refusal.fingerprint().equals(fingerprint(scope, detail, input)),
					"永久未准备原号的原件、人员或完整正文不一致");
			return new Resolution(null, refusal);
		}
		return null;
	}

	/**
	 * 仅查询永久拒绝摘要，不能由一次查无宣称未准备；实际写入在独立裁决代理事务。
	 */
	public NotPrepared refusal(Scope scope, String originalRequest) {
		var rows = jdbc.queryForList("""
				SELECT content,detail_id,bad_id,operation_time FROM mom_qms.qms_bad_operation
				WHERE tenant_id=? AND factory_code=? AND request_id=? AND operation_type='REWORK_NOT_PREPARED' AND del_flag='0'
				""", scope.tenant(), scope.factory(), storageKey(scope.factory(), originalRequest));
		if (rows.isEmpty()) {
			return null;
		}
		var row = rows.get(0);
		var value = ledger.decode((String) row.get("content"), NotPrepared.class);
		require(value != null && value.version() == 1 && value.requestId().equals(originalRequest)
				&& value.tenantId().equals(Long.toString(scope.tenant())) && value.factoryCode().equals(scope.factory())
				&& value.detailId().equals(row.get("detail_id").toString()), "永久未准备事实列与原文不一致");
		return value;
	}

	/**
	 * 原准备事务已回滚后另起事务；只有本代理提交确认后才可向Web返回NOT_PREPARED。
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public Resolution adjudicate(long detail, Prepare input, Scope scope, Source authorizedSource,
	                             LocalRejection rejected) {
		lock(scope, input.requestId());
		var c = sources.load(detail, scope, true, false);
		ledger.lockFacts(scope.tenant(), scope.factory(), detail);
		var replay = replay(detail, input, scope);
		if (replay != null) {
			return replay;
		}
		require(authorizedSource != null && authorizedSource.key().detailId().equals(Long.toString(detail))
						&& authorizedSource.key().proposalId().equals(input.proposalId())
						&& authorizedSource.key().parentOperationId().equals(input.parentOperationId()),
				"缺少已受权原仓，不能裁决未准备");
		var value = new NotPrepared(1, input.requestId(), Long.toString(detail), input.proposalId(),
				input.parentOperationId(),
				scope.actor().userId(), Long.toString(scope.tenant()), scope.factory(),
				fingerprint(scope, detail, input),
				authorizedSource.warehouseCode(), rejected.reasonCode, rejected.getMessage(), now().toString());
		String encoded = ledger.encode(value);
		require(encoded.length() <= 2000, "未准备永久拒绝摘要超限，不能截断原事实");
		require(jdbc.update("""
								INSERT INTO mom_qms.qms_bad_operation
								(id,request_id,bad_id,detail_id,ncr_no,operation_type,before_status,after_status,content,
								operator_code,operator_name,operation_time,factory_code,tenant_id,del_flag)
								VALUES (?,?,?,?,?,'REWORK_NOT_PREPARED',?,?,?, ?,?,?,?,?,'0')
								""", cn.hutool.core.util.IdUtil.getSnowflakeNextId(), storageKey(scope.factory(), input.requestId()),
						c.header().getId(),
						detail, c.header().getNcrNo(), c.detail().getStatus(), c.detail().getStatus(), encoded,
						scope.actor().code(),
						scope.actor().name(), java.time.LocalDateTime.parse(value.recordedAt()), scope.factory(),
						scope.tenant()) == 1,
				"未准备永久拒绝未能提交，保留原号核对");
		return new Resolution(null, value);
	}

	/**
	 * 仅调用明确本地业务规则；此异常从准备事务抛出，不能涵盖远端、授权或提交失败。
	 */
	public static void local(boolean valid, String code, String reason) {
		if (!valid) {
			throw new LocalRejection(code, reason);
		}
	}

	/**
	 * 固定有序数组与Web JSON.stringify一致；稳定ID使用文本，不包含token或角色。
	 */
	public String fingerprint(Scope scope, long detail, Prepare input) {
		try {
			var canonical = Arrays.asList("QMS_NCR_REWORK_PREPARE_V1", Long.toString(scope.tenant()), scope.factory(),
					scope.actor().userId(), Long.toString(detail), input.requestId(), input.proposalId(),
					input.parentOperationId(),
					input.completionId(), input.decision(), input.reviewEvidence());
			// JSON.stringify对控制字符使用小写十六进制，专用配置保证两端指纹字节一致，不改共享JSON合同。
			return sha256(JsonMapper.builder().disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE).build()
					.writeValueAsString(canonical));
		} catch (Exception e) {
			throw new CheckedException("无法规范冻结准备正文，保留原号核对");
		}
	}

	/**
	 * 履历原号最长134字符，与其它NCR履历隔离；完整工厂摘要不截断。
	 */
	public static String storageKey(String factory, String originalRequest) {
		return "RWNP:" + sha256(factory) + ":" + originalRequest;
	}

	private static String sha256(String text) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
