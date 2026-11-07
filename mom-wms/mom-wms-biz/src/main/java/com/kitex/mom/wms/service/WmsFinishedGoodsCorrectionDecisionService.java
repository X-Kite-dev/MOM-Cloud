package com.kitex.mom.wms.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionDecisionDTO.Query;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionDecisionDTO.Receipt;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionDecisionDTO.EndCommand;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionDecisionDTO.Outcome;
import com.kitex.mom.wms.dto.WmsCorrectionTerminationDTO.Ended;
import com.kitex.mom.wms.dto.WmsFinishedGoodsReceiptCorrectionCommand;
import com.kitex.mom.wms.repository.WmsFinishedGoodsCorrectionDecisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 成品纠错办理的原结果核对与未执行请求永久结束。
 * 结束与正式办理共用原申请行锁，不修改申请状态、库存或跨服务回写。
 */
@Service
@RequiredArgsConstructor
public class WmsFinishedGoodsCorrectionDecisionService {

	private final WmsFinishedGoodsCorrectionDecisionRepository repository;
	private final PermissionService permissions;

	private record Context(Long tenant, String factory, String actor,
	                       WmsFinishedGoodsCorrectionDecisionRepository.Application application) {
	}

	/**
	 * 兼容既有V1原登记查询；未确认不代表原请求没有执行，完整终态使用outcome。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Receipt lookup(Query query) {
		return registration(query, context(query, false), false);
	}

	/**
	 * 原申请锁内读取V2终态，读取失败不能当作未执行或已结束。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Outcome outcome(Query query) {
		return resolve(query, context(query, true));
	}

	/**
	 * 永久结束完整原办理号；原办理先提交时仅返回原结果。
	 * 同一原人重复结束须保持原文及原因；固定批准号可核对另一复核人的既成事实。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Outcome end(EndCommand command) {
		require(command != null && !text(command.reason()).isEmpty() && text(command.reason()).length() <= 500,
				"请填写500字以内的结束原因");
		var query = command.original();
		var context = context(query, true);
		var prior = resolve(query, context);
		if ("REGISTERED".equals(prior.status())) {
			return prior;
		}
		if (prior.termination() != null) {
			// 固定批准号全申请共用；其他复核人只能查回已有结束，不改写原人及原因。
			boolean sharedApproval = query.action().equals("APPROVE") &&
					!prior.termination().operatorCode().equals(context.actor());
			require(sharedApproval || prior.termination().reason().equals(text(command.reason())),
					"原请求已按其他结束原因登记，请先查询原结果");
			return prior;
		}
		String applicant = repository.applicant(context.tenant(), context.factory(), query.correctionId());
		require(!text(applicant).isEmpty(), "原申请人证据不完整，请保留原文核对");
		require(query.action().equals("WITHDRAW") == applicant.equals(context.actor()),
				"撤回须由原申请人办理，复核须与申请人分离");
		var ended = new Ended(IdWorker.getIdStr(), context.tenant().toString(), context.factory(),
				context.actor(),
				query.warehouseCode(), query.noticeId().toString(), query.requestId(), fingerprint(query),
				payload(query), text(command.reason()),
				WmsCorrectionTerminationService.timestamp(LocalDateTime.now()));
		require(repository.insertEnd(query, ended) == 1, "原办理结束未确认，请保留原文并查询");
		return new Outcome(2, "ENDED", null, ended);
	}

	/**
	 * 先核对原登记，再核对结束凭据；两种事实冲突时拒绝清理本机原文。
	 */
	private Outcome resolve(Query query, Context context) {
		var registration = registration(query, context, true);
		var ended = repository.ended(context.tenant(), context.factory(), query.correctionId(),
				query.requestId());
		require(!"REGISTERED".equals(registration.status()) || ended == null,
				"原办理登记与结束凭据冲突，请保留原文核对");
		if (ended == null) {
			return new Outcome(2, registration.status(), registration, null);
		}
		require(Objects.equals(ended.tenantId(), context.tenant().toString()) &&
						Objects.equals(ended.factoryCode(), context.factory()) &&
						(query.action().equals("APPROVE") || Objects.equals(ended.operatorCode(), context.actor())) &&
						Objects.equals(ended.warehouseCode(), query.warehouseCode()) &&
						Objects.equals(ended.noticeId(), query.noticeId().toString()) &&
						Objects.equals(ended.requestId(), query.requestId()) &&
						Objects.equals(ended.commandFingerprint(), fingerprint(query)) &&
						Objects.equals(ended.requestPayload(), payload(query)),
				"原办理结束凭据与原账号、原申请或正文不一致，请保留原文核对");
		return new Outcome(2, "ENDED", null, ended);
	}

	/**
	 * 组织来自当前身份；仓库取原申请全部冻结明细，停用新作业不阻止原号核对。
	 */
	private Context context(Query query, boolean lock) {
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		var user = SecurityUtils.getUser();
		require(tenant != null && tenant > 0 && !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
				&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && !text(factory).isEmpty()
				&& user != null && Objects.equals(user.getTenantId(), tenant) &&
				!text(user.getUsername()).isEmpty(), "请先登录并选择实际工厂");
		validate(query);
		String permission = query.action()
				.equals("WITHDRAW") ? "wms_bill_add_in_product" : "wms_bill_review_in_product";
		require(permissions.hasPermission(permission) ||
						permissions.hasPermission(permission + "_" + query.warehouseCode()),
				"当前账号无权核对该仓库的原办理结果");
		if (lock) {
			repository.lockApplication(tenant, factory, query.correctionId());
		}
		var found = repository.applications(tenant, factory, query.correctionId());
		require(found.size() == 1, "原红冲申请不存在或范围不一致，请保留原操作核对");
		var row = found.get(0);
		require("0".equals(row.deleted()) && query.noticeId().equals(row.noticeId()) &&
						!text(row.number()).isEmpty(),
				"原申请证据不完整，请保留原操作核对");
		var lines = repository.lines(tenant, factory, row.id());
		// 与申请时冻结行数核对，不能用缺失部分仓库的残余明细认领整单办理结果。
		require(row.lineCount() != null && row.lineCount() > 0 && row.lineCount() <= 10000
						&& lines.size() == row.lineCount() && lines.stream().allMatch(line ->
						"0".equals(line.deleted()) && query.warehouseCode().equals(line.warehouse())),
				"原申请冻结仓库不一致，请从原仓库核对");
		return new Context(tenant, factory, user.getUsername(), row);
	}

	/**
	 * 按原命令及唯一履历核对实际办理人；V2固定批准号可由同仓复核人读取。
	 * 其他动作和V1仍绑定原办理人，不用当前库存推断执行结果。
	 */
	private Receipt registration(Query query, Context context, boolean sharedApproval) {
		var tenant = context.tenant();
		var factory = context.factory();
		var row = context.application();
		var operations = repository.operations(tenant, factory, row.id(), query.requestId());
		require(operations.size() <= 1, "原办理履历不唯一，请联系管理员核对");
		if (operations.isEmpty()) {
			return receipt(query, tenant, factory, context.actor(), row.number(), null, null, null, null);
		}
		var operation = operations.get(0);
		boolean approval = query.action().equals("APPROVE");
		String expectedType = approval && "STALE".equals(operation.type()) ? "STALE"
				: approval ? "APPROVE_EXECUTE" : query.action();
		String after = switch (expectedType) {
			case "WITHDRAW" -> "WITHDRAWN";
			case "REJECT" -> "REJECTED";
			case "STALE" -> "STALE";
			default -> "EXECUTED";
		};
		String fingerprint = fingerprint(query);
		// V2固定批准号由申请唯一决定，授权同仓复核人可核对原事实；V1及其他动作仍绑定原人。
		require("0".equals(operation.deleted()) && !text(operation.operator()).isEmpty() &&
						(sharedApproval && approval || Objects.equals(context.actor(), operation.operator()))
						&& fingerprint.equals(operation.fingerprint()) && expectedType.equals(operation.type())
						&& "SUBMITTED".equals(operation.before()) && after.equals(operation.after())
						&& after.equals(row.status()) && operation.time() != null,
				"原办理人、正文或永久履历不一致，请保留原操作核对");
		return receipt(query, tenant, factory, operation.operator(), row.number(), operation.time().toString(),
				fingerprint, operation.type(), after);
	}

	private void validate(Query query) {
		require(query != null && Set.of("APPROVE", "WITHDRAW", "REJECT").contains(text(query.action()))
				&& query.action().equals(text(query.action()))
				&& query.correctionId() != null && query.correctionId() > 0 && query.noticeId() != null &&
				query.noticeId() > 0
				&& !text(query.warehouseCode()).isEmpty() &&
				query.warehouseCode().equals(query.warehouseCode().trim())
				&& query.warehouseCode().length() <= 64 && !query.warehouseCode().matches(".*[?*].*")
				&& !text(query.requestId()).isEmpty() && query.requestId().length() <= 100
				&& query.requestId().equals(query.requestId().trim()) && !text(query.reason()).isEmpty()
				&& query.reason().length() <= 500, "请保留完整的原动作、申请、仓库及办理正文");
		if (query.action().equals("APPROVE")) {
			require(query.requestId().equals("APPROVE:" + query.correctionId()) &&
							query.reason().equals("批准执行"),
					"批准操作必须使用原申请的固定办理号");
		}
	}

	private String fingerprint(Query query) {
		if (query.action().equals("APPROVE")) {
			return hash("APPROVE|" + query.correctionId());
		}
		var command = new WmsFinishedGoodsReceiptCorrectionCommand();
		command.setRequestId(query.requestId());
		if (query.action().equals("WITHDRAW")) {
			command.setReason(query.reason().trim());
		} else {
			command.setRejectReason(query.reason().trim());
		}
		return hash(JSONUtil.toJsonStr(command));
	}

	private Receipt receipt(Query query, Long tenant, String factory, String actor, String number, String time,
	                        String fingerprint, String operation, String after) {
		return new Receipt(1, operation == null ? "NOT_CONFIRMED" : "REGISTERED", query.requestId(),
				tenant.toString(),
				factory, actor, query.warehouseCode(), query.noticeId().toString(),
				query.correctionId().toString(), number,
				time, null, fingerprint, operation == null ? null : payload(query), query.action(), operation,
				operation == null ? null : "SUBMITTED", after);
	}

	private String payload(Query query) {
		var data = new LinkedHashMap<String, Object>();
		data.put("requestId", query.requestId());
		data.put("warehouseCode", query.warehouseCode());
		data.put("noticeId", query.noticeId().toString());
		data.put("reason", query.reason().trim());
		data.put("qmsDecisionReversalId", null);
		data.put("qmsDecisionAuthorizationCode", null);
		data.put("stockSelections", List.of());
		data.put("action", query.action());
		data.put("correctionId", query.correctionId().toString());
		try {
			return new ObjectMapper().writeValueAsString(data);
		} catch (JsonProcessingException exception) {
			throw new CheckedException("原办理回执无法编码，请保留原操作");
		}
	}

	private String hash(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private String text(String value) {
		return value == null ? "" : value.trim();
	}

	private void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}
}
