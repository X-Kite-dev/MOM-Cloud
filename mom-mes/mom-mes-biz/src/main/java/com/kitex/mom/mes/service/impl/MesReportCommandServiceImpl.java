package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesReportCommandReceiptDTO;
import com.kitex.mom.mes.dto.MesReportOperatorContextDTO;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.MesReportCommandEntity;
import com.kitex.mom.mes.entity.MesReportRequestCloseEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.enums.MesReportChannelEnum;
import com.kitex.mom.mes.mapper.MesReportCommandMapper;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import com.kitex.mom.mes.service.MesReportCommandService;
import com.kitex.mom.mes.service.MesReportRequestCloseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 报工原正文版本化幂等。
 *
 * <p>只收集公开业务输入，不收集客户端伪造的质量/映射/任务对象。文本保留原值，
 * 数量统一无损十进制表示；渠道和实名身份由服务器给出。先锁请求再锁工单，
 * 任何当前条码解析、许可状态、库存或设备变化都不能改写旧原号的含义。</p>
 */
@Service
@RequiredArgsConstructor
public class MesReportCommandServiceImpl extends ServiceImpl<MesReportCommandMapper, MesReportCommandEntity>
		implements MesReportCommandService {

	private static final String LEGACY_VERSION = "MES_REPORT_COMMAND_V1";
	private static final String VERSION = "MES_REPORT_COMMAND_V2";
	private static final String MATERIAL_VERSION = "MES_REPORT_COMMAND_V3";
	private static final MesPieceMaterialSelectionCodec MATERIAL_INPUT = new MesPieceMaterialSelectionCodec();
	private final MesActualFactoryAccessService factoryAccess;
	private final MesTaskReportMapper reportMapper;
	private final MesReportRequestCloseService requestCloses;
	private final MesIdentifierNodeReadiness nodeReadiness;
	private final MesGoodMaterialPolicyReceiptCollector materialReceipts;

	@Override
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public CapturedCommand capture(MesTaskReportVO command, MesReportChannelEnum channel) {
		return captureInternal(command, channel, false);
	}

	/**
	 * 封存入口复用同一原正文算法和请求锁，不能生成不同格式的“取消命令”。
	 */
	private CapturedCommand captureInternal(MesTaskReportVO command, MesReportChannelEnum channel, boolean closing) {
		String factory = factoryAccess.require("write");
		require(command != null && channel != null, "缺少报工命令或可信入口");
		var actor = SecurityUtils.getUser();
		require(actor.getUsername() != null && !actor.getUsername().isBlank() && actor.getUsername().length() <= 100,
				"实名账号编码缺失或超过审计字段容量");
		Long tenant = TenantContextHolder.getTenantId();
		String request = requestId(command.getRequestId());
		command.setRequestId(request);
		// 必须在选算法之前锁原号并读取永久账，不能用新字段/新算法重新解释已接受V1正文。
		lockRequests(List.of(request));
		MesReportCommandEntity old = baseMapper.findPermanent(tenant, request);
		MesReportRequestCloseEntity closed = requestCloses.findOwn(request);
		require(old == null || closed == null, "原号同时存在成功及封存证据，请人工核查");
		String version = old != null ? old.getCommandVersion() : closed != null ? closed.getCommandVersion() :
				command.getMaterialSelectionJson() == null ? VERSION : MATERIAL_VERSION;
		require(VERSION.equals(version) || LEGACY_VERSION.equals(version) || MATERIAL_VERSION.equals(version),
				"原正文算法版本未知，禁止猜测重放");
		if (MATERIAL_VERSION.equals(version)) {
			require(command.getMaterialSelectionJson() != null, "V3原号必须保留原本件用料全文");
			MATERIAL_INPUT.command(command, channel);
		} else {
			require(command.getMaterialSelectionJson() == null, "旧报工原号不能附加本件用料选择");
		}
		if (LEGACY_VERSION.equals(version)) {
			require(command.getExternalIdentifier() == null && command.getExpectedBindingId() == null
					&& command.getExpectedBindingVersion() == null, "旧V1原号不能附加动态绑定输入后重放");
		}
		if (command.getPassNum() != null) {
			BigDecimal quantity = command.getPassNum().stripTrailingZeros();
			require(quantity.scale() <= 6 && quantity.precision() - quantity.scale() <= 14,
					"报工数量超出20位/6位小数容量，禁止截断或静默舍入");
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("version", version);
		body.put("tenantId", tenant.toString());
		body.put("factoryCode", factory);
		body.put("actorId", actor.getId().toString());
		body.put("actorCode", actor.getUsername());
		body.put("channel", channel.name());
		body.put("requestId", request);
		body.put("arrangeCode", command.getArrangeCode());
		body.put("transferCode", command.getTransferCode());
		body.put("passNum", decimal(command.getPassNum()));
		body.put("correctionPermitNo", command.getCorrectionPermitNo());
		body.put("worksiteCode", command.getWorksiteCode());
		body.put("deviceCode", command.getDeviceCode());
		body.put("operatorCode", command.getOperatorCode());
		body.put("operatorName", command.getOperatorName());
		body.put("sourceEventId", command.getSourceEventId());
		body.put("sourceEventTime", command.getSourceEventTime() == null
				? null : command.getSourceEventTime().toString());
		body.put("manualOverrideReason", command.getManualOverrideReason());
		body.put("wrapRuleCode", command.getWrapRuleCode());
		body.put("wrapCode", command.getWrapCode());
		body.put("capacity", command.getCapacity());
		// 载具上下文只能由同进程协调器设置；HTTP反序列化已将这些字段设为只读。
		body.put("carrierOperationId", command.getCarrierOperationId() == null
				? null : command.getCarrierOperationId().toString());
		body.put("carrierCode", command.getCarrierCode());
		if (!LEGACY_VERSION.equals(version)) {
			body.put("externalIdentifier", command.getExternalIdentifier());
			body.put("expectedBindingId", command.getExpectedBindingId());
			body.put("expectedBindingVersion", command.getExpectedBindingVersion());
		}
		if (MATERIAL_VERSION.equals(version)) {
			body.put("materialSelectionJson", command.getMaterialSelectionJson());
		}
		String snapshot = JSONUtil.toJsonStr(body);
		require(snapshot.length() <= 16000, "报工原正文过长，请核查输入而不是截断重试");
		String fingerprint = hash(version + ":" + snapshot);
		if (closed != null) {
			require(Objects.equals(closed.getCommandSnapshot(), snapshot)
							&& Objects.equals(closed.getRequestFingerprint(), fingerprint)
							&& Objects.equals(closed.getChannel(), channel.name()),
					"封存原号已绑定其他正文或入口，不能重新采集后复用旧号");
			require(closing, "该原号已永久封存为未受理；迟到提交禁止执行，请查封存回执后重新采集");
		}
		if (old != null) {
			require(Objects.equals(old.getFactoryCode(), factory) && Objects.equals(old.getActorId(), actor.getId())
							&& version.equals(old.getCommandVersion()) && Objects.equals(old.getChannel(), channel.name())
							&& Objects.equals(old.getRequestFingerprint(), fingerprint)
							&& Objects.equals(old.getCommandSnapshot(), snapshot),
					"原报工请求号已被不同正文、入口、人员或工厂使用；请核对原回执，不能覆盖或换号重记");
			require("ACCEPTED".equals(old.getStatus()) && old.getReportId() != null, "原报工永久回执异常，请人工核查");
			command.setReportId(old.getReportId());
		} else {
			// 不伪造历史原正文；包含逻辑删除的旧报工同样不能释放请求号。
			require(reportMapper.countHistoricalRequest(tenant, request) == 0,
					"原号属于没有完整原正文的历史报工，禁止按三字段猜测重放；请先核查原业务事实");
		}
		return new CapturedCommand(request, factory, tenant, actor.getId(), actor.getUsername(), channel.name(),
				version, snapshot, fingerprint, old == null ? null : old.getReportId());
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public void lockRequests(Collection<String> requestIds) {
		factoryAccess.require("write");
		require(requestIds != null && !requestIds.isEmpty() && requestIds.size() <= 5000,
				"报工请求锁范围为空或超过完整载具核对上限");
		requestIds.stream().map(this::requestId).distinct().sorted().forEach(request -> {
			String tenant = TenantContextHolder.getTenantId().toString();
			require(Integer.valueOf(1).equals(baseMapper.lockRequest(JSONUtil.toJsonStr(
					List.of("MES_REPORT_REQUEST_LOCK_V1", tenant, request)))), "未取得报工请求业务锁");
			// 新SQL旁路共同字面键；保留旧锁先取，兼容原版本正在结束的请求。
			require(Integer.valueOf(1).equals(baseMapper.lockRequest("MES_REPORT_REQUEST_LOCK_V2:" + tenant + ":"
					+ HexFormat.of().formatHex(request.getBytes(StandardCharsets.UTF_8)))), "未取得原号封存共同锁");
		});
	}

	/**
	 * {@inheritDoc}
	 * 新逐料GOOD在同一事务冻结实际份额子原件；原件收集或必要保存失败时不得留下成功账。
	 */
	@Override
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public void accept(CapturedCommand captured, MesTaskReportEntity report) {
		String factory = factoryAccess.require("write");
		require(captured != null && captured.replayReportId() == null && report != null && report.getId() != null
				&& Objects.equals(captured.tenantId(), TenantContextHolder.getTenantId())
				&& Objects.equals(captured.factoryCode(), factory)
				&& Objects.equals(captured.actorId(), SecurityUtils.getUser().getId())
				&& Objects.equals(captured.requestId(), report.getRequestId())
				&& Objects.equals(captured.factoryCode(), report.getFactoryCode())
				&& Objects.equals(captured.tenantId(), report.getTenantId()), "原正文与实际报工归属不一致");
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("version", captured.version());
		result.put("reportId", report.getId().toString());
		result.put("requestId", report.getRequestId());
		result.put("arrangeCode", report.getArrangeCode());
		result.put("taskCode", report.getTaskCode());
		result.put("canonicalIdentifier", report.getTransferCode());
		result.put("submittedIdentifier", report.getSubmittedIdentifier());
		result.put("reportMode", report.getReportMode());
		result.put("quantity", decimal(report.getReportNum()));
		result.put("acceptedReportStatus", report.getStatus());
		result.put("executionKind", report.getExecutionKind());
		result.put("identifierRouteVersion", report.getIdentifierRouteVersion());
		result.put("reportTime", report.getReportTime().toString());
		if (!LEGACY_VERSION.equals(captured.version())) {
			result.put("identifierBindingId", report.getIdentifierBindingId() == null
					? null : report.getIdentifierBindingId().toString());
			result.put("identifierBindingVersion", report.getIdentifierBindingVersion());
			result.put("identifierNodeAction", report.getIdentifierNodeAction());
			result.put("identifierBindingSnapshot", report.getIdentifierBindingSnapshot());
		}
		// 仅收集本事务已写入的实际分配；失败必须使原GOOD和成功回执一起回滚。
		// 旧统一规则返回null，不增加空字段，不改变原输入算法或旧结果字节。
		var materialReceipt = materialReceipts.capture(report);
		if (materialReceipt != null) {
			result.put("materialExecutionReceipt", materialReceipt);
		}
		LocalDateTime now = LocalDateTime.now();
		MesReportCommandEntity row = new MesReportCommandEntity().setFactoryCode(captured.factoryCode())
				.setRequestId(captured.requestId()).setCommandVersion(captured.version())
				.setRequestFingerprint(captured.fingerprint()).setCommandSnapshot(captured.snapshot())
				.setChannel(captured.channel()).setTaskCode(report.getTaskCode())
				.setArrangeCode(report.getArrangeCode())
				.setReportId(report.getId()).setActorId(captured.actorId()).setActorCode(captured.actorCode())
				.setStatus("ACCEPTED").setResultSnapshot(JSONUtil.toJsonStr(result)).setAcceptedTime(now);
		row.setTenantId(captured.tenantId());
		row.setCreateBy(captured.actorCode());
		row.setCreateTime(now);
		row.setDelFlag("0");
		require(baseMapper.insert(row) == 1, "报工原正文回执保存失败，全部业务事实已回滚");
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public boolean verifyAcceptedReplay(MesTaskReportVO command, MesReportChannelEnum channel, Long reportId) {
		factoryAccess.require("read");
		require(command != null && reportId != null, "已接受重放必须有关联原报工");
		MesReportCommandEntity old = baseMapper.findPermanent(TenantContextHolder.getTenantId(),
				requestId(command.getRequestId()));
		if (old == null) {
			return false;
		}
		// 旧弱网V1摘要未包含重报许可等输入，新成功账必须再核完整正文，不能被外层提前返回绕过。
		CapturedCommand captured = capture(command, channel);
		require(Objects.equals(captured.replayReportId(), reportId), "弱网回执与原正文指向不同报工，请核查原账");
		return true;
	}

	@Override
	@Transactional(readOnly = true)
	public MesReportCommandReceiptDTO receipt(String requestId) {
		String factory = factoryAccess.require("read");
		MesReportCommandEntity old = baseMapper.findPermanent(TenantContextHolder.getTenantId(), requestId(requestId));
		if (old == null) {
			MesReportRequestCloseEntity closed = requestCloses.findOwn(requestId(requestId));
			require(closed != null, "当前工厂下未找到本人原号回执；这不证明未执行，请保留原号原正文");
			return new MesReportCommandReceiptDTO().setRequestId(closed.getRequestId())
					.setAcceptedStatus(closed.getStatus()).setCurrentReportStatus("NOT_EXECUTED")
					.setCommandVersion(closed.getCommandVersion()).setCommandSnapshot(closed.getCommandSnapshot())
					.setRequestFingerprint(closed.getRequestFingerprint()).setCloseReason(closed.getReason());
		}
		require(Objects.equals(old.getFactoryCode(), factory)
						&& Objects.equals(old.getActorId(), SecurityUtils.getUser().getId()),
				"当前工厂下未找到本人报工原号回执");
		MesTaskReportEntity report = reportMapper.selectOne(new LambdaQueryWrapper<MesTaskReportEntity>()
				.eq(MesTaskReportEntity::getTenantId, old.getTenantId())
				.eq(MesTaskReportEntity::getFactoryCode, factory).eq(MesTaskReportEntity::getId, old.getReportId()));
		return new MesReportCommandReceiptDTO().setRequestId(old.getRequestId())
				.setReportId(old.getReportId().toString()).setAcceptedStatus(old.getStatus())
				.setCurrentReportStatus(report == null ? "UNAVAILABLE" : report.getStatus())
				.setResultSnapshot(old.getResultSnapshot()).setCommandVersion(old.getCommandVersion())
				.setCommandSnapshot(old.getCommandSnapshot()).setRequestFingerprint(old.getRequestFingerprint());
	}

	@Override
	@Transactional(readOnly = true)
	public MesReportOperatorContextDTO operatorContext() {
		return operatorContext(true);
	}

	@Override
	@Transactional(readOnly = true)
	public MesReportOperatorContextDTO operatorContext(boolean includeNodeReadiness) {
		String factory = factoryAccess.require("read");
		var actor = SecurityUtils.getUser();
		var result = new MesReportOperatorContextDTO().setTenantId(TenantContextHolder.getTenantId().toString())
				.setFactoryCode(factory).setActorId(actor.getId().toString()).setActorCode(actor.getUsername());
		if (includeNodeReadiness) {
			var readiness = nodeReadiness.deployment();
			result.setNodeExecutionReady(readiness.getDatabaseReady()).setNodeReadiness(readiness);
		}
		return result;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public MesReportCommandReceiptDTO closeUnaccepted(MesTaskReportVO command, MesReportChannelEnum channel,
	                                                  String reason) {
		require(channel == MesReportChannelEnum.STATION || channel == MesReportChannelEnum.MOBILE,
				"当前封存入口只用于实名工站及移动端原号恢复");
		CapturedCommand captured = captureInternal(command, channel, true);
		if (captured.replayReportId() == null) {
			requestCloses.seal(captured, reason);
		}
		// 已成功的只能返回原事实；封存不会冲销成功报工，也不释放原号。
		return receipt(captured.requestId());
	}

	private String requestId(String value) {
		require(value != null, "报工原号不能为空");
		String result = value.trim();
		require(result.length() >= 8 && result.length() <= 64
				&& result.chars().noneMatch(Character::isISOControl), "报工原号必须为8至64位无控制字符文本");
		return result;
	}

	private String decimal(BigDecimal value) {
		return value == null ? null : value.stripTrailingZeros().toPlainString();
	}

	private String hash(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("当前JDK不支持SHA-256", exception);
		}
	}

	/**
	 * 永久账只能通过capture/accept/receipt操作，避免通用CRUD绕过原子受理。
	 */
	@Override
	public MesReportCommandMapper getBaseMapper() {
		throw new CheckedException("报工命令账禁止通用CRUD，请使用领域命令与原号查询");
	}

	@Override
	public boolean saveBatch(Collection<MesReportCommandEntity> rows, int size) {
		throw new CheckedException("报工命令账禁止批量追加");
	}

	@Override
	public boolean saveOrUpdateBatch(Collection<MesReportCommandEntity> rows, int size) {
		throw new CheckedException("报工命令账禁止批量覆盖");
	}

	@Override
	public boolean updateBatchById(Collection<MesReportCommandEntity> rows, int size) {
		throw new CheckedException("报工命令账禁止批量更新");
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
