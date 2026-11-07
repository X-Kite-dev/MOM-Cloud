package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.barcode.api.feign.RemoteBarcodeService;
import com.kitex.mom.common.barcode.dto.FrozenBarcodeDefinition;
import com.kitex.mom.common.barcode.dto.req.BarcodeDefinitionFreezeReq;
import com.kitex.mom.common.barcode.utils.FrozenBarcodeCodec;
import com.kitex.mom.mdm.feign.RemoteEmployeeService;
import com.kitex.mom.mdm.feign.RemoteFactoryModelService;
import com.kitex.mom.mes.api.feign.RemotePackagingStationPermissionService;
import com.kitex.mom.mes.dto.MesPackagingDTO;
import com.kitex.mom.mes.dto.MesTaskReportResult;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.MesPackagingAdoptionEntity;
import com.kitex.mom.mes.entity.MesPackagingSealEntity;
import com.kitex.mom.mes.entity.MesPackagingSealMemberEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesWrapEntity;
import com.kitex.mom.mes.entity.MesWrapRecordEntity;
import com.kitex.mom.mes.entity.MesWrapRuleEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.mapper.MesPackagingAdoptionMapper;
import com.kitex.mom.mes.mapper.MesPackagingSealMapper;
import com.kitex.mom.mes.mapper.MesPackagingSealMemberMapper;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import com.kitex.mom.mes.mapper.MesWrapMapper;
import com.kitex.mom.mes.mapper.MesWrapRecordMapper;
import com.kitex.mom.mes.mapper.MesWrapRuleMapper;
import com.kitex.mom.wms.api.feign.RemoteContainerBarcodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 标准包装执行编排：冻结采用、同派工初封、永久原号回读及当前工作区。
 *
 * <p>封箱只建立已有GOOD产出的包装关系，不再次记产、扣料、完工或执行WMS入库。
 * 成功和未执行封存均保留原号；当前拆箱状态单列，不能重写当时成功的结果。</p>
 */
@Service
@RequiredArgsConstructor
public class MesPackagingExecutionService {

	private static final String ADOPTION_VERSION = "MES_PACKAGING_ADOPTION_V1";
	private static final String COMMAND_VERSION = "MES_PACKAGING_SEAL_V1";
	private static final String RECEIPT_VERSION = "MES_PACKAGING_SEAL_RECEIPT_V1";
	private final MesActualFactoryAccessService access;
	private final MesExecutionTaskLock taskLock;
	private final MesPackagingAdoptionMapper adoptions;
	private final MesPackagingSealMapper seals;
	private final MesPackagingSealMemberMapper members;
	private final MesTaskArrangeMapper arrangements;
	private final MesTaskMapper tasks;
	private final MesTaskReportMapper reports;
	private final MesWrapRuleMapper rules;
	private final MesWrapMapper wraps;
	private final MesWrapRecordMapper records;
	private final RemoteBarcodeService barcodes;
	private final RemoteFactoryModelService factoryModels;
	private final RemotePackagingStationPermissionService stationPermissions;
	private final RemoteEmployeeService employees;
	private final RemoteContainerBarcodeService containerBarcodes;
	private final PermissionService permissions;

	/**
	 * 新派工保存后同事务冻结真实包装规则及条码原定义；非包装节点不创建采用账。
	 * @param task 原工单，规则与容量须与派工冻结值相同
	 * @param arrange 已保存且有真实ID的本次派工
	 */
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public void freezeForDispatch(MesTaskEntity task, MesTaskArrangeEntity arrange) {
		ReportModeEnum mode = mode(arrange);
		if (!mode.isPackaging()) {
			return;
		}
		String factory = access.require("write");
		installed();
		scope(arrange, factory);
		require(task != null && Objects.equals(task.getTenantId(), arrange.getTenantId())
				&& Objects.equals(task.getFactoryCode(), factory)
				&& Objects.equals(task.getCode(), arrange.getTaskCode()), "包装派工未归属原工单");
		require(findAdoption(arrange) == null, "本派工已采用包装依据，不能覆盖");
		var candidates = rules.selectList(new LambdaQueryWrapper<MesWrapRuleEntity>()
				.eq(MesWrapRuleEntity::getTenantId, tenant()).eq(MesWrapRuleEntity::getFactoryCode, factory)
				.eq(MesWrapRuleEntity::getWrapCode, code(arrange.getWrapRuleCode(), "包装规则"))
				.eq(MesWrapRuleEntity::getStatus, true).last("LIMIT 2"));
		require(candidates.size() == 1, "新包装派工须有唯一已启用的本工厂包装规则");
		compatible(arrange, candidates.get(0));
		writeAdoption(arrange, candidates.get(0), "NEW_DISPATCH", "派工冻结");
	}

	/**
	 * 新报工只认本派工的持久采用；缺失时由有独立权限的管理人员显式采用。
	 * 原号成功回放须在调用本方法前完成，因此历史已成功的回执不受此门限制。
	 * @return 已校验完整原文指纹及实际组织的采用实体
	 */
	public MesPackagingAdoptionEntity requireAdoption(MesTaskArrangeEntity arrange) {
		String factory = access.require("read");
		installed();
		scope(arrange, factory);
		MesPackagingAdoptionEntity adoption = findAdoption(arrange);
		require(adoption != null, "该历史派工尚未采用包装依据，请由包装管理员显式采用后继续");
		validateAdoption(arrange, adoption);
		return adoption;
	}

	/**
	 * 历史任务受控采用；不回写原派工或历史报告，原有非空规则及容量必须保持一致。
	 * @param command 显式选定同工厂已启用规则与采用原因
	 * @return 实际永久采用摘要，未知提交可通过progress重新核对
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
	public MesPackagingDTO.Adoption adopt(MesPackagingDTO.Adopt command) {
		require(command != null, "包装采用命令不能为空");
		String factory = access.require("write");
		installed();
		MesTaskArrangeEntity arrange = lockArrange(code(command.getArrangeCode(), "派工编码"), factory);
		require(mode(arrange).isPackaging(), "只有包装派工可以采用包装依据");
		String reason = reason(command.getReason());
		MesPackagingAdoptionEntity existing = findAdoption(arrange);
		if (existing != null) {
			validateAdoption(arrange, existing);
			require(Objects.equals(existing.getRuleId(), command.getRuleId())
					&& Objects.equals(existing.getActorId(), actorId())
					&& Objects.equals(existing.getReason(), reason), "包装依据已经采用，不能用另一正文覆盖");
			return adoptionView(existing);
		}
		require(!"CLOSE".equals(arrange.getStatus()), "关闭派工不能首次采用包装依据");
		MesWrapRuleEntity rule = rules.selectOne(new LambdaQueryWrapper<MesWrapRuleEntity>()
				.eq(MesWrapRuleEntity::getTenantId, tenant()).eq(MesWrapRuleEntity::getFactoryCode, factory)
				.eq(MesWrapRuleEntity::getId, command.getRuleId()).eq(MesWrapRuleEntity::getStatus, true));
		require(rule != null, "未找到同工厂已启用的指定包装规则");
		compatible(arrange, rule);
		return adoptionView(writeAdoption(arrange, rule, "LEGACY_ADOPTION", reason));
	}

	/**
	 * 真实GOOD报告保存后登记单件待装，按真实合格待装成员封满箱，不使用累计数量取余。
	 * @param params 当前报工已核定的派工与来源身份
	 * @param result 已保存且必要写通过的实际GOOD报告
	 */
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public void stageUnitAndSealIfFull(MesTaskReportVO params, MesTaskReportResult result) {
		MesTaskArrangeEntity arrange = params.getTaskArrange();
		MesPackagingAdoptionEntity adoption = requireAdoption(arrange);
		require(ReportModeEnum.PACKAGE_ONE_CODE.name().equals(adoption.getReportMode()), "包装采用模式与单件报工不一致");
		MesTaskReportEntity report = result.getReport();
		require(report != null && BigDecimal.ONE.compareTo(report.getReportNum()) == 0, "逐件包装须来源真实单件GOOD报告");
		stage(arrange, report, BigDecimal.ONE);
		if (!released(report, BigDecimal.ONE)) {
			return;
		}
		List<MesWrapRecordEntity> eligible = seals.eligibleStaged(tenant(), arrange.getFactoryCode(), arrange.getTaskCode(),
				arrange.getProduceTaskCode(), arrange.getCode(), arrange.getId().toString(), adoption.getCapacity());
		if (eligible.size() < adoption.getCapacity()) {
			return;
		}
		String request = "AUTO-" + report.getId();
		var intent = intent(arrange, adoption, request, "AUTO_FULL", "满箱自动封箱",
				eligible.stream().map(MesWrapRecordEntity::getId).toList(), report.getId(), null,
				arrange.getActualWorksiteCode());
		MesPackagingSealEntity sealed = sealLocked(arrange, adoption, intent);
		result.setWrapMessage("完成满箱封箱，箱码：" + sealed.getWrapCode() + "，数量：" + sealed.getQuantity().toPlainString());
	}

	/**
	 * 数量GOOD报告先登记待装；未获得质量放行时保留合法生产事实，后续由同一封箱入口办理。
	 * 容器合法性只读WMS查询，本服务不修改WMS容器或库存。
	 */
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public void sealQuantity(MesTaskReportVO params, MesTaskReportResult result) {
		MesTaskArrangeEntity arrange = params.getTaskArrange();
		MesPackagingAdoptionEntity adoption = requireAdoption(arrange);
		require(ReportModeEnum.PACKAGE_QUANTITY.name().equals(adoption.getReportMode()), "包装采用模式与数量报工不一致");
		MesTaskReportEntity report = result.getReport();
		require(report != null && positive(report.getReportNum())
				&& report.getReportNum().compareTo(BigDecimal.valueOf(adoption.getCapacity())) <= 0,
				"本次数量包装须大于零且不超过已采用的每箱容量");
		// 待检也必须先有本组织有效外部容器身份，不能先记产再等封箱时发现外码串厂或已占用。
		assertExternalContainer(report.getTransferCode(), arrange.getFactoryCode());
		MesWrapRecordEntity record = stage(arrange, report, report.getReportNum());
		if (!released(report, report.getReportNum())) {
			return;
		}
		var intent = intent(arrange, adoption, "QTY-" + report.getId(), "QUANTITY", "数量报工封箱",
				List.of(record.getId()), report.getId(), code(report.getTransferCode(), "外部容器码"),
				arrange.getActualWorksiteCode());
		MesPackagingSealEntity sealed = sealLocked(arrange, adoption, intent);
		result.setWrapMessage("完成数量封箱，箱码：" + sealed.getWrapCode() + "，数量：" + sealed.getQuantity().toPlainString());
	}

	/**
	 * 手工尾箱只使用当前原派工的显式成员；先核原号，已有成功不再次检查当前生产门。
	 * @return 永久原结果及另列的当前包装状态
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
	public MesPackagingDTO.Receipt seal(MesPackagingDTO.Seal command) {
		require(command != null, "原封箱命令不能为空");
		String factory = access.require("write");
		installed();
		MesTaskArrangeEntity owner = arrange(code(command.getArrangeCode(), "派工编码"), factory, false);
		MesPackagingAdoptionEntity adoption = requireAdoption(owner);
		Intent intent = tailIntent(owner, adoption, command);
		lockRequest(intent.requestId());
		MesPackagingSealEntity existing = replay(intent);
		if (existing != null) {
			return receiptView(existing, true);
		}
		MesTaskArrangeEntity locked = lockArrange(owner.getCode(), factory);
		taskLock.assertArrange(owner, locked);
		assertOperator(locked, command.getWorksiteCode());
		require(Set.of("PRODUCT", "FINISH").contains(locked.getStatus()), "尾箱只能在原派工生产中或完工后办理，暂停或关闭须先受控恢复");
		return receiptView(sealLocked(locked, adoption, intent), true);
	}

	/**
	 * 未受理原号永久封存不修改任何生产事实，也不要求取得当前生产/修订许可。
	 * 已有成功时只回原成功；同号迟到提交会返回CANCELLED并永不执行。
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
	public MesPackagingDTO.Receipt closeUnaccepted(MesPackagingDTO.Close command) {
		require(command != null && command.getCommand() != null, "须提供原封箱正文");
		String factory = access.require("read");
		installed();
		MesTaskArrangeEntity owner = arrange(code(command.getCommand().getArrangeCode(), "派工编码"), factory, false);
		MesPackagingAdoptionEntity adoption = requireAdoption(owner);
		Intent intent = tailIntent(owner, adoption, command.getCommand());
		String closeReason = reason(command.getReason());
		lockRequest(intent.requestId());
		MesPackagingSealEntity existing = replay(intent);
		if (existing != null) {
			return receiptView(existing, true);
		}
		LocalDateTime now = LocalDateTime.now();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("schemaVersion", RECEIPT_VERSION);
		result.put("requestId", intent.requestId());
		result.put("acceptedStatus", "CANCELLED");
		result.put("closeReason", closeReason);
		MesPackagingSealEntity row = sealRow(owner, adoption, intent, now)
				.setAcceptedStatus("CANCELLED").setCloseReason(closeReason).setQuantity(BigDecimal.ZERO)
				.setResultSnapshot(json(result));
		require(seals.insert(row) == 1, "原封箱号未执行封存必要写失败");
		return receiptView(row, true);
	}

	/** 当前实名原号只读回执；不取生产许可、不重放报工、不重算当时箱码。 */
	@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
	public MesPackagingDTO.Receipt receipt(String requestId) {
		String factory = access.require("read");
		MesPackagingSealEntity row = seals.permanent(tenant(), code(requestId, "封箱原请求号"));
		if (row == null) {
			// 仅说明当前未查到永久结果；调用方仍须保留原号原文，只能原文续投或正式封存。
			return null;
		}
		require(Objects.equals(row.getFactoryCode(), factory)
				&& Objects.equals(row.getActorId(), actorId()), "未找到当前工厂本人原封箱回执，请保留原号原正文");
		return receiptView(row, true);
	}

	/**
	 * 当前工作区只按实际派工读取STAGED及最近真实箱；不把历史记录总数当作当前箱进度。
	 * 共享任务可查看最近箱摘要，其他人的永久原命令/原结果不会经此入口泄漏。
	 */
	@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
	public MesPackagingDTO.Progress progress(String arrangeCode) {
		String factory = access.require("read");
		installed();
		MesTaskArrangeEntity arrange = arrange(code(arrangeCode, "派工编码"), factory, false);
		require(mode(arrange).isPackaging(), "该派工不是包装任务");
		if (!permissions.hasPermission("mesWrap:show")) {
			assertAssigned(arrange);
		}
		MesPackagingAdoptionEntity adoption = findAdoption(arrange);
		if (adoption != null) {
			validateAdoption(arrange, adoption);
		}
		List<MesWrapRecordEntity> staged = pending(arrange);
		Map<Long, MesTaskReportEntity> sourceReports = pendingReports(arrange, staged);
		Set<Long> blockedReports = staged.isEmpty() ? Set.of() : Set.copyOf(seals.blockedPendingReports(tenant(),
				arrange.getFactoryCode(), arrange.getTaskCode(), arrange.getProduceTaskCode(), arrange.getCode(), arrange.getId().toString()));
		List<MesPackagingDTO.Pending> pending = staged.stream().map(record -> {
			String hold = pendingHold(record, sourceReports.get(record.getReportId()), adoption, blockedReports);
			return new MesPackagingDTO.Pending().setRecordId(record.getId().toString()).setReportId(record.getReportId().toString())
					.setTransferCode(record.getTransferCode()).setQuantity(record.getQuantity()).setCreatedAt(record.getCreateTime())
					.setSealAllowed(hold == null).setHoldReason(hold);
		}).toList();
		MesPackagingSealEntity latest = seals.selectOne(new LambdaQueryWrapper<MesPackagingSealEntity>()
				.eq(MesPackagingSealEntity::getTenantId, tenant()).eq(MesPackagingSealEntity::getFactoryCode, factory)
				.eq(MesPackagingSealEntity::getArrangeId, arrange.getId())
				.eq(MesPackagingSealEntity::getAcceptedStatus, "ACCEPTED")
				.orderByDesc(MesPackagingSealEntity::getAcceptedAt).orderByDesc(MesPackagingSealEntity::getId).last("LIMIT 1"));
		return new MesPackagingDTO.Progress().setArrangeCode(arrange.getCode()).setReportMode(mode(arrange).name())
				.setAdoption(adoption == null ? null : adoptionView(adoption)).setStagedRecords(pending)
				.setStagedQuantity(staged.stream().map(MesWrapRecordEntity::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add))
				.setLatestSeal(latest == null ? null : receiptView(latest, Objects.equals(latest.getActorId(), actorId())));
	}

	/** 批量只读源报告，当前行级限制只作交互提示，不替代提交时根锁及行锁核验。 */
	private Map<Long, MesTaskReportEntity> pendingReports(MesTaskArrangeEntity arrange, List<MesWrapRecordEntity> staged) {
		if (staged.isEmpty()) {
			return Map.of();
		}
		List<Long> ids = staged.stream().map(MesWrapRecordEntity::getReportId).distinct().toList();
		return reports.selectList(new LambdaQueryWrapper<MesTaskReportEntity>()
				.eq(MesTaskReportEntity::getTenantId, tenant()).eq(MesTaskReportEntity::getFactoryCode, arrange.getFactoryCode())
				.eq(MesTaskReportEntity::getTaskCode, arrange.getTaskCode()).eq(MesTaskReportEntity::getProduceTaskCode, arrange.getProduceTaskCode())
				.eq(MesTaskReportEntity::getArrangeCode, arrange.getCode()).eq(MesTaskReportEntity::getReportMode, mode(arrange).name())
				.in(MesTaskReportEntity::getId, ids)).stream().collect(Collectors.toMap(MesTaskReportEntity::getId, report -> report));
	}

	private String pendingHold(MesWrapRecordEntity record, MesTaskReportEntity report,
			MesPackagingAdoptionEntity adoption, Set<Long> blockedReports) {
		if (adoption == null) {
			return "尚未采用包装依据";
		}
		if (report == null || !"EFFECTIVE".equals(report.getStatus())) {
			return "原报工不可用或已经冲销";
		}
		if (!released(report, record.getQuantity())) {
			return "待质量放行或原报工数量已变更";
		}
		if (blockedReports.contains(record.getReportId())) {
			return "来源已被实物转换占用或消耗";
		}
		if (record.getSourceRecordId() != null || !Objects.equals(record.getTransferCode(), report.getTransferCode())
				|| !positive(record.getQuantity()) || record.getQuantity().compareTo(BigDecimal.valueOf(adoption.getCapacity())) > 0
				|| (ReportModeEnum.PACKAGE_ONE_CODE.name().equals(adoption.getReportMode())
						&& BigDecimal.ONE.compareTo(record.getQuantity()) != 0)) {
			return "内容不符合本次初封要求";
		}
		return null;
	}

	private MesPackagingAdoptionEntity writeAdoption(MesTaskArrangeEntity arrange, MesWrapRuleEntity rule,
			String kind, String reason) {
		require(rule.getCapacity() != null && rule.getCapacity() > 0, "包装规则容量须为正整数");
		FrozenBarcodeDefinition definition = null;
		if (mode(arrange) == ReportModeEnum.PACKAGE_ONE_CODE) {
			// 单次封箱原文最多2000个实名成员；在采用时拒绝，不能到第N件满箱时才回滚报工。
			require(rule.getCapacity() <= 2000, "逐件包装每箱最多2000件，请拆分包规或使用适用的数量包装");
			var request = new BarcodeDefinitionFreezeReq();
			request.setBarcodeRuleCode(rule.getWrapCode());
			var response = barcodes.freezeDefinition(request);
			require(response != null && response.isOk() && response.getData() != null, "无法取得有权限的原条码定义，包装采用未保存");
			definition = response.getData();
			FrozenBarcodeCodec.validate(definition, tenant(), arrange.getFactoryCode());
			require(Objects.equals(rule.getWrapCode(), definition.getCode()), "条码原定义编码与指定包装规则不一致");
			require(definition.getFields().stream().allMatch(field -> Set.of("boxCode", "capacity", "taskCode").contains(field.getValue())),
					"包装条码只支持箱码唯一段、容量和工单号，请先调整条码规则后重新采用");
			// 采用时即核最长唯一令牌和本工单业务值，不能等满箱时报错而回滚已采集的合法单件。
			String sample = FrozenBarcodeCodec.render(definition,
					Map.of("boxCode", Long.toString(Long.MAX_VALUE), "capacity", rule.getCapacity().toString(),
							"taskCode", arrange.getTaskCode()), tenant(), arrange.getFactoryCode());
			code(sample, "包装条码配置生成内容，须兼容完整19位唯一箱码段且总长不超过100");
		}
		Map<String, Object> ruleSnapshot = new LinkedHashMap<>();
		ruleSnapshot.put("id", rule.getId().toString());
		ruleSnapshot.put("code", rule.getWrapCode());
		ruleSnapshot.put("name", rule.getWrapName());
		ruleSnapshot.put("capacity", rule.getCapacity());
		ruleSnapshot.put("status", rule.getStatus());
		Map<String, Object> source = new LinkedHashMap<>();
		source.put("schemaVersion", ADOPTION_VERSION);
		source.put("reportMode", mode(arrange).name());
		source.put("capacity", rule.getCapacity());
		source.put("rule", ruleSnapshot);
		if (definition == null) {
			source.put("quantityContract", "EXTERNAL_CONTAINER_V1");
		} else {
			source.put("barcodeDefinition", definition);
		}
		String snapshot = json(source);
		MesPackagingAdoptionEntity row = new MesPackagingAdoptionEntity().setFactoryCode(arrange.getFactoryCode())
				.setArrangeId(arrange.getId()).setArrangeCode(arrange.getCode()).setTaskCode(arrange.getTaskCode())
				.setProduceTaskCode(arrange.getProduceTaskCode()).setReportMode(mode(arrange).name())
				.setAdoptionVersion(ADOPTION_VERSION).setAdoptionKind(kind).setRuleId(rule.getId())
				.setRuleCode(rule.getWrapCode()).setCapacity(rule.getCapacity()).setSourceSnapshot(snapshot)
				.setSourceFingerprint(hash(snapshot)).setBarcodeDefinitionSnapshot(definition == null ? null : json(definition))
				.setActorId(actorId()).setActorCode(actorCode()).setReason(reason).setAdoptedAt(LocalDateTime.now());
		row.setId(IdWorker.getId());
		row.setTenantId(tenant());
		row.setDelFlag("0");
		require(adoptions.insert(row) == 1, "包装采用必要写失败，整笔事务回滚");
		return row;
	}

	private MesWrapRecordEntity stage(MesTaskArrangeEntity arrange, MesTaskReportEntity report, BigDecimal quantity) {
		require(report != null && report.getId() != null && positive(quantity)
				&& Objects.equals(report.getTenantId(), tenant()) && Objects.equals(report.getFactoryCode(), arrange.getFactoryCode())
				&& Objects.equals(report.getArrangeCode(), arrange.getCode()) && Objects.equals(report.getTaskCode(), arrange.getTaskCode())
				&& Objects.equals(report.getProduceTaskCode(), arrange.getProduceTaskCode()), "包装内容必须引用本事务真实同派工GOOD报告");
		MesTaskReportEntity persisted = report(report.getId(), arrange, false);
		require("EFFECTIVE".equals(persisted.getStatus()) && quantity.compareTo(persisted.getReportNum()) == 0,
				"真实报告尚未保存或包装数量与报告不一致");
		MesWrapRecordEntity row = new MesWrapRecordEntity().setReportId(report.getId()).setFactoryCode(arrange.getFactoryCode())
				.setTaskCode(arrange.getTaskCode()).setProcedureTaskCode(arrange.getProduceTaskCode()).setArrangeCode(arrange.getCode())
				.setTransferCode(code(report.getTransferCode(), "包装成员标识")).setQuantity(quantity)
				.setAvailableQuantity(BigDecimal.ZERO).setPackingStatus("STAGED");
		row.setId(IdWorker.getId());
		row.setTenantId(tenant());
		row.setDelFlag("0");
		require(records.insert(row) == 1, "包装待装内容必要写失败，整笔报工回滚");
		return row;
	}

	/** 调用方已取得真实工单mutex/hold及派工行锁，成员按报告后内容顺序加锁。 */
	private MesPackagingSealEntity sealLocked(MesTaskArrangeEntity arrange, MesPackagingAdoptionEntity adoption, Intent intent) {
		validateAdoption(arrange, adoption);
		MesTaskEntity task = tasks.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
				.eq(MesTaskEntity::getTenantId, tenant()).eq(MesTaskEntity::getFactoryCode, arrange.getFactoryCode())
				.eq(MesTaskEntity::getCode, arrange.getTaskCode()).last("FOR UPDATE"));
		require(task != null && !"CLOSE".equals(task.getStatus()), "原工单不存在或已经关闭，不能新增封箱事实");
		List<MesWrapRecordEntity> sources = lockMembers(arrange, intent.ids());
		BigDecimal total = BigDecimal.ZERO;
		for (MesWrapRecordEntity source : sources) {
			MesTaskReportEntity report = report(source.getReportId(), arrange, false);
			require("STAGED".equals(source.getPackingStatus()) && StrUtil.isBlank(source.getWrapCode())
					&& source.getSourceRecordId() == null && released(report, source.getQuantity())
					&& !occupied(arrange, report), "成员已封装、冲销、待判或被实物转换占用，请按原号核对");
			require(Objects.equals(report.getTransferCode(), source.getTransferCode()), "包装内容与原报告标识不一致");
			if (ReportModeEnum.PACKAGE_ONE_CODE.name().equals(adoption.getReportMode())) {
				require(BigDecimal.ONE.compareTo(source.getQuantity()) == 0, "单件包装内容必须每成员一件");
			}
			total = total.add(source.getQuantity());
		}
		require(total.signum() > 0 && total.compareTo(BigDecimal.valueOf(adoption.getCapacity())) <= 0,
				"封箱数量须大于零且不超过冻结包装容量");
		if ("AUTO_FULL".equals(intent.mode())) {
			require(total.compareTo(BigDecimal.valueOf(adoption.getCapacity())) == 0, "自动封箱须恰好达到冻结容量");
		}
		Long sealId = IdWorker.getId();
		String wrapCode = intent.externalCode();
		if (ReportModeEnum.PACKAGE_QUANTITY.name().equals(adoption.getReportMode())) {
			require(sources.size() == 1 && Objects.equals(wrapCode, sources.get(0).getTransferCode()),
					"数量包装只能封装一条完整原报告内容，外箱码不得替换或混装");
			assertExternalContainer(wrapCode, arrange.getFactoryCode());
		} else {
			FrozenBarcodeDefinition definition = JSONUtil.toBean(adoption.getBarcodeDefinitionSnapshot(), FrozenBarcodeDefinition.class);
			wrapCode = FrozenBarcodeCodec.render(definition,
					Map.of("boxCode", sealId.toString(), "capacity", adoption.getCapacity().toString(),
							"taskCode", arrange.getTaskCode()), tenant(), arrange.getFactoryCode());
		}
		code(wrapCode, "实际箱码");
		MesWrapEntity wrap = new MesWrapEntity().setFactoryCode(arrange.getFactoryCode()).setWrapCode(wrapCode)
				.setRuleCode(adoption.getRuleCode()).setWrapCapacity(adoption.getCapacity()).setTaskCode(arrange.getTaskCode())
				.setProcedureTaskCode(arrange.getProduceTaskCode()).setArrangeCode(arrange.getCode()).setStatus("SEALED")
				.setCurrentQuantity(total).setSourceMode(adoption.getReportMode()).setWrapType(task.getType());
		wrap.setId(IdWorker.getId());
		wrap.setTenantId(tenant());
		wrap.setDelFlag("0");
		require(wraps.insert(wrap) == 1, "包装主档必要写失败，封箱全部回滚");
		LocalDateTime now = LocalDateTime.now();
		MesPackagingSealEntity seal = sealRow(arrange, adoption, intent, now).setAcceptedStatus("ACCEPTED")
				.setQuantity(total).setWrapId(wrap.getId()).setWrapCode(wrapCode);
		seal.setId(sealId);
		seal.setResultSnapshot(resultSnapshot(seal, sources));
		require(seals.insert(seal) == 1, "封箱永久回执必要写失败，全部回滚");
		// 先登记永久初封成员，此时原记录仍为STAGED；最后才条件更新动态包装关系。
		for (int index = 0; index < sources.size(); index++) {
			MesWrapRecordEntity source = sources.get(index);
			MesPackagingSealMemberEntity member = new MesPackagingSealMemberEntity().setFactoryCode(arrange.getFactoryCode())
					.setSealId(sealId).setRecordId(source.getId()).setReportId(source.getReportId())
					.setTransferCode(source.getTransferCode()).setQuantity(source.getQuantity()).setMemberOrdinal(index + 1);
			member.setId(IdWorker.getId());
			member.setTenantId(tenant());
			member.setDelFlag("0");
			require(members.insert(member) == 1, "封箱永久成员必要写失败，全部回滚");
			int changed = records.update(null, new LambdaUpdateWrapper<MesWrapRecordEntity>()
					.eq(MesWrapRecordEntity::getTenantId, tenant()).eq(MesWrapRecordEntity::getFactoryCode, arrange.getFactoryCode())
					.eq(MesWrapRecordEntity::getId, source.getId()).eq(MesWrapRecordEntity::getPackingStatus, "STAGED")
					.isNull(MesWrapRecordEntity::getWrapCode).set(MesWrapRecordEntity::getWrapCode, wrapCode)
					.set(MesWrapRecordEntity::getPackingStatus, "PACKED").set(MesWrapRecordEntity::getAvailableQuantity, BigDecimal.ZERO)
					.set(MesWrapRecordEntity::getOperationRequestId, intent.requestId()));
			require(changed == 1, "真实待装成员已变化，封箱全部回滚");
		}
		return seal;
	}

	private List<MesWrapRecordEntity> lockMembers(MesTaskArrangeEntity arrange, List<Long> ids) {
		List<MesWrapRecordEntity> observed = records.selectList(recordScope(arrange).in(MesWrapRecordEntity::getId, ids));
		require(observed.size() == ids.size(), "指定成员没有唯一归属原派工");
		require(observed.stream().allMatch(record -> record.getReportId() != null), "指定成员缺少原GOOD报告身份");
		List<Long> reportIds = observed.stream().map(MesWrapRecordEntity::getReportId).distinct().sorted().toList();
		for (Long id : reportIds) {
			report(id, arrange, true);
		}
		List<MesWrapRecordEntity> locked = records.selectList(recordScope(arrange).in(MesWrapRecordEntity::getId, ids)
				.orderByAsc(MesWrapRecordEntity::getId).last("FOR UPDATE"));
		require(locked.size() == ids.size(), "待装成员已变化，请保留原号重新核对");
		return locked;
	}

	private MesTaskReportEntity report(Long id, MesTaskArrangeEntity arrange, boolean lock) {
		var query = new LambdaQueryWrapper<MesTaskReportEntity>().eq(MesTaskReportEntity::getTenantId, tenant())
				.eq(MesTaskReportEntity::getFactoryCode, arrange.getFactoryCode()).eq(MesTaskReportEntity::getId, id)
				.eq(MesTaskReportEntity::getTaskCode, arrange.getTaskCode())
				.eq(MesTaskReportEntity::getProduceTaskCode, arrange.getProduceTaskCode())
				.eq(MesTaskReportEntity::getArrangeCode, arrange.getCode());
		if (lock) {
			query.last("FOR UPDATE");
		}
		MesTaskReportEntity report = reports.selectOne(query);
		require(report != null && Objects.equals(report.getReportMode(), mode(arrange).name()), "包装成员缺少真实同派工同模式GOOD来源");
		return report;
	}

	private boolean released(MesTaskReportEntity report, BigDecimal quantity) {
		return report != null && positive(quantity) && "EFFECTIVE".equals(report.getStatus())
				&& report.getReportNum() != null && report.getReportNum().compareTo(quantity) == 0
				&& report.getEffectiveReportNum() != null && report.getEffectiveReportNum().compareTo(quantity) == 0
				&& ("NOT_REQUIRED".equals(report.getQualityQuantityStatus()) || "RELEASED".equals(report.getQualityQuantityStatus()))
				&& report.getQualityReleasedNum() != null && report.getQualityReleasedNum().compareTo(quantity) >= 0;
	}

	private boolean occupied(MesTaskArrangeEntity arrange, MesTaskReportEntity report) {
		return seals.occupied(tenant(), arrange.getFactoryCode(), arrange.getTaskCode(), arrange.getProduceTaskCode(),
				arrange.getId().toString(), arrange.getCode(), report.getId().toString(), report.getTransferCode()) > 0;
	}

	private MesPackagingSealEntity sealRow(MesTaskArrangeEntity arrange, MesPackagingAdoptionEntity adoption,
			Intent intent, LocalDateTime now) {
		MesPackagingSealEntity row = new MesPackagingSealEntity().setFactoryCode(arrange.getFactoryCode())
				.setAdoptionId(adoption.getId()).setRequestId(intent.requestId()).setCommandVersion(COMMAND_VERSION)
				.setCommandSnapshot(intent.snapshot()).setRequestFingerprint(hash(intent.snapshot()))
				.setActorId(actorId()).setActorCode(actorCode()).setTaskCode(arrange.getTaskCode())
				.setProduceTaskCode(arrange.getProduceTaskCode()).setArrangeId(arrange.getId()).setArrangeCode(arrange.getCode())
				.setSealMode(intent.mode()).setReason(intent.reason()).setCapacity(adoption.getCapacity())
				.setSourceReportId(intent.reportId()).setAcceptedAt(now);
		row.setId(IdWorker.getId());
		row.setTenantId(tenant());
		row.setDelFlag("0");
		return row;
	}

	private String resultSnapshot(MesPackagingSealEntity seal, List<MesWrapRecordEntity> sources) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("schemaVersion", RECEIPT_VERSION);
		result.put("sealId", seal.getId().toString());
		result.put("requestId", seal.getRequestId());
		result.put("arrangeCode", seal.getArrangeCode());
		result.put("adoptionId", seal.getAdoptionId().toString());
		result.put("sealMode", seal.getSealMode());
		result.put("wrapId", seal.getWrapId().toString());
		result.put("wrapCode", seal.getWrapCode());
		result.put("quantity", seal.getQuantity().stripTrailingZeros().toPlainString());
		result.put("capacity", seal.getCapacity());
		result.put("recordIds", sources.stream().map(row -> row.getId().toString()).toList());
		result.put("acceptedAt", seal.getAcceptedAt().toString());
		result.put("acceptedWrapStatus", "SEALED");
		return json(result);
	}

	private Intent tailIntent(MesTaskArrangeEntity arrange, MesPackagingAdoptionEntity adoption, MesPackagingDTO.Seal command) {
		String request = code(command.getRequestId(), "封箱原请求号");
		require(!request.startsWith("AUTO-") && !request.startsWith("QTY-"), "手工封箱不能占用系统自动封箱的保留请求号");
		String externalCode = null;
		if (ReportModeEnum.PACKAGE_QUANTITY.name().equals(adoption.getReportMode())) {
			require(command.getRecordIds() != null && command.getRecordIds().size() == 1
					&& command.getRecordIds().get(0) != null, "数量包装须选择一条完整原待装内容，不能拆分或合并");
			MesWrapRecordEntity source = records.selectOne(recordScope(arrange)
					.eq(MesWrapRecordEntity::getId, command.getRecordIds().get(0)));
			require(source != null, "未找到原数量包装内容，请保留原号核对");
			externalCode = code(source.getTransferCode(), "原数量包装外箱码");
		}
		return intent(arrange, adoption, request, "TAIL", reason(command.getReason()),
				command.getRecordIds(), null, externalCode, code(command.getWorksiteCode(), "实际工位"));
	}

	/**
	 * 数量包装只读取WMS既有容器身份；最终MES唯一箱码约束仍在本地封箱事务内防止重复初封。
	 * 外部容器分配及占用仍由WMS负责，查询失败不能被解释为可用。
	 */
	private void assertExternalContainer(String wrapCode, String factory) {
		var response = containerBarcodes.queryBarcode(code(wrapCode, "外部容器码"));
		require(response != null && response.isOk() && response.getData() != null, "无法取得已验证的外部容器身份，请保留原号稍后续办");
		var container = response.getData();
		require(Objects.equals(container.getCode(), wrapCode) && Objects.equals(container.getTenantId(), tenant())
				&& Objects.equals(container.getFactoryCode(), factory) && Integer.valueOf(0).equals(container.getIsUsed()),
				"外部容器不属于当前组织或已经占用，不能形成生产包装");
	}

	private Intent intent(MesTaskArrangeEntity arrange, MesPackagingAdoptionEntity adoption, String request,
			String mode, String reason, List<Long> recordIds, Long reportId, String externalCode, String worksite) {
		require(recordIds != null && !recordIds.isEmpty() && recordIds.size() <= 2000
				&& recordIds.stream().allMatch(id -> id != null && id > 0)
				&& recordIds.stream().distinct().count() == recordIds.size(), "包装成员须为不重复的真实内容ID");
		List<Long> ids = recordIds.stream().sorted().toList();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("schemaVersion", COMMAND_VERSION);
		body.put("requestId", request);
		body.put("tenantId", tenant().toString());
		body.put("factoryCode", arrange.getFactoryCode());
		body.put("actorId", actorId().toString());
		body.put("actorCode", actorCode());
		body.put("arrangeId", arrange.getId().toString());
		body.put("arrangeCode", arrange.getCode());
		body.put("taskCode", arrange.getTaskCode());
		body.put("produceTaskCode", arrange.getProduceTaskCode());
		body.put("adoptionId", adoption.getId().toString());
		body.put("sealMode", mode);
		body.put("reason", reason);
		body.put("recordIds", ids.stream().map(Object::toString).toList());
		body.put("sourceReportId", reportId == null ? null : reportId.toString());
		body.put("externalWrapCode", externalCode);
		body.put("worksiteCode", code(worksite, "实际接收工位"));
		return new Intent(request, mode, reason, ids, reportId, externalCode, json(body));
	}

	private MesPackagingSealEntity replay(Intent intent) {
		MesPackagingSealEntity existing = seals.permanent(tenant(), intent.requestId());
		if (existing != null) {
			require(Objects.equals(existing.getFactoryCode(), access.require("read"))
					&& Objects.equals(existing.getActorId(), actorId())
					&& Objects.equals(existing.getCommandSnapshot(), intent.snapshot())
					&& Objects.equals(existing.getRequestFingerprint(), hash(intent.snapshot())),
					"封箱原请求号已被另一实名、工厂或正文使用，禁止重开");
		}
		return existing;
	}

	private void lockRequest(String request) {
		require(Integer.valueOf(1).equals(seals.lockRequest(json(List.of(COMMAND_VERSION, tenant().toString(), request)))),
				"封箱原请求互斥未取得，请保留原号续办");
	}

	private MesTaskArrangeEntity lockArrange(String code, String factory) {
		MesTaskArrangeEntity owner = taskLock.arrange(code);
		MesTaskArrangeEntity locked = arrange(code, factory, true);
		taskLock.assertArrange(owner, locked);
		return locked;
	}

	private MesTaskArrangeEntity arrange(String code, String factory, boolean lock) {
		var query = new LambdaQueryWrapper<MesTaskArrangeEntity>().eq(MesTaskArrangeEntity::getTenantId, tenant())
				.eq(MesTaskArrangeEntity::getFactoryCode, factory).eq(MesTaskArrangeEntity::getCode, code);
		query.last(lock ? "FOR UPDATE" : "LIMIT 2");
		List<MesTaskArrangeEntity> rows = arrangements.selectList(query);
		require(rows.size() == 1, "未找到当前工厂唯一派工");
		return rows.get(0);
	}

	private MesPackagingAdoptionEntity findAdoption(MesTaskArrangeEntity arrange) {
		return adoptions.selectOne(new LambdaQueryWrapper<MesPackagingAdoptionEntity>()
				.eq(MesPackagingAdoptionEntity::getTenantId, tenant())
				.eq(MesPackagingAdoptionEntity::getFactoryCode, arrange.getFactoryCode())
				.eq(MesPackagingAdoptionEntity::getArrangeId, arrange.getId()));
	}

	private void validateAdoption(MesTaskArrangeEntity arrange, MesPackagingAdoptionEntity adoption) {
		require(Objects.equals(adoption.getTenantId(), tenant()) && Objects.equals(adoption.getFactoryCode(), arrange.getFactoryCode())
				&& Objects.equals(adoption.getArrangeId(), arrange.getId()) && Objects.equals(adoption.getArrangeCode(), arrange.getCode())
				&& Objects.equals(adoption.getTaskCode(), arrange.getTaskCode())
				&& Objects.equals(adoption.getProduceTaskCode(), arrange.getProduceTaskCode())
				&& Objects.equals(adoption.getReportMode(), mode(arrange).name())
				&& ADOPTION_VERSION.equals(adoption.getAdoptionVersion()) && adoption.getCapacity() != null
				&& adoption.getCapacity() > 0 && Objects.equals(hash(adoption.getSourceSnapshot()), adoption.getSourceFingerprint()),
				"包装采用原组织、模式或完整原文不一致，禁止执行");
		if (mode(arrange) == ReportModeEnum.PACKAGE_ONE_CODE) {
			FrozenBarcodeCodec.validate(JSONUtil.toBean(adoption.getBarcodeDefinitionSnapshot(), FrozenBarcodeDefinition.class),
					tenant(), arrange.getFactoryCode());
		}
		var source = JSONUtil.parseObj(adoption.getSourceSnapshot());
		var rule = source.getJSONObject("rule");
		require(ADOPTION_VERSION.equals(source.getStr("schemaVersion"))
				&& Objects.equals(source.getStr("reportMode"), adoption.getReportMode())
				&& Objects.equals(source.getInt("capacity"), adoption.getCapacity()) && rule != null
				&& Objects.equals(rule.getStr("id"), adoption.getRuleId().toString())
				&& Objects.equals(rule.getStr("code"), adoption.getRuleCode())
				&& Objects.equals(rule.getInt("capacity"), adoption.getCapacity()), "包装采用摘要与完整原文不一致");
		if (mode(arrange) == ReportModeEnum.PACKAGE_ONE_CODE) {
			require(source.getJSONObject("barcodeDefinition") != null
					&& Objects.equals(source.getJSONObject("barcodeDefinition").toString(),
							JSONUtil.parseObj(adoption.getBarcodeDefinitionSnapshot()).toString()), "包装条码定义与采用原文不一致");
		} else {
			require("EXTERNAL_CONTAINER_V1".equals(source.getStr("quantityContract"))
					&& adoption.getBarcodeDefinitionSnapshot() == null, "数量包装未采用正式外部容器契约");
		}
	}

	private void compatible(MesTaskArrangeEntity arrange, MesWrapRuleEntity rule) {
		require(rule.getCapacity() != null && rule.getCapacity() > 0, "包装规则容量须为正整数");
		if (StrUtil.isNotBlank(arrange.getWrapRuleCode())) {
			require(Objects.equals(arrange.getWrapRuleCode(), rule.getWrapCode()), "指定规则与原派工包装规则不一致，不能隐式换版");
		}
		if (StrUtil.isNotBlank(arrange.getWrapCapacity())) {
			try {
				require(Integer.valueOf(arrange.getWrapCapacity()).equals(rule.getCapacity()), "指定容量与原派工冻结容量不一致");
			} catch (NumberFormatException exception) {
				throw new CheckedException("原派工包装容量格式无效，不能以当前配置覆盖历史", exception);
			}
		}
	}

	private List<MesWrapRecordEntity> pending(MesTaskArrangeEntity arrange) {
		return records.selectList(recordScope(arrange).eq(MesWrapRecordEntity::getPackingStatus, "STAGED")
				.isNull(MesWrapRecordEntity::getWrapCode).isNotNull(MesWrapRecordEntity::getReportId)
				.orderByAsc(MesWrapRecordEntity::getCreateTime).orderByAsc(MesWrapRecordEntity::getId));
	}

	private LambdaQueryWrapper<MesWrapRecordEntity> recordScope(MesTaskArrangeEntity arrange) {
		return new LambdaQueryWrapper<MesWrapRecordEntity>().eq(MesWrapRecordEntity::getTenantId, tenant())
				.eq(MesWrapRecordEntity::getFactoryCode, arrange.getFactoryCode()).eq(MesWrapRecordEntity::getArrangeCode, arrange.getCode())
				.eq(MesWrapRecordEntity::getTaskCode, arrange.getTaskCode())
				.eq(MesWrapRecordEntity::getProcedureTaskCode, arrange.getProduceTaskCode());
	}

	private void assertOperator(MesTaskArrangeEntity arrange, String worksite) {
		assertAssigned(arrange);
		require(Objects.equals(code(worksite, "当前工位"), arrange.getActualWorksiteCode()), "当前选中工位与原派工实际接收工位不一致");
		var nodes = factoryModels.getNodesByType("STATION", arrange.getFactoryCode());
		require(nodes != null && nodes.isOk() && nodes.getData() != null, "无法核验实际工位，请稍后原号续办");
		var matching = nodes.getData().stream().filter(node -> Objects.equals(node.getFactoryCode(), arrange.getFactoryCode())
				&& Objects.equals(node.getCode(), worksite) && Boolean.TRUE.equals(node.getStatus())
				&& "STATION".equals(node.getType())).toList();
		require(matching.size() == 1, "当前工厂未找到唯一启用的实际工位");
		var write = stationPermissions.hasPermission(actorId(), matching.get(0).getId(), "write");
		var admin = stationPermissions.hasPermission(actorId(), matching.get(0).getId(), "admin");
		require(write != null && write.isOk() && admin != null && admin.isOk()
				&& (Boolean.TRUE.equals(write.getData()) || Boolean.TRUE.equals(admin.getData())), "当前实名没有实际工位读写权限");
		var response = employees.getEmployeeByCode(actorCode());
		require(response != null && response.isOk() && response.getData() != null
				&& response.getData().stream().filter(employee -> Objects.equals(employee.getTenantId(), tenant())
				&& Objects.equals(employee.getFactoryCode(), arrange.getFactoryCode())
				&& Objects.equals(employee.getEmployeeCode(), actorCode()) && "1".equals(employee.getStatus())).count() == 1,
				"当前实名未唯一关联本工厂在职执行员工");
	}

	private void assertAssigned(MesTaskArrangeEntity arrange) {
		require(Arrays.stream(StrUtil.blankToDefault(arrange.getWorkerCode(), "").split(",")).map(String::trim)
				.anyMatch(actorCode()::equals), "当前实名不是原派工指定执行员工");
	}

	private MesPackagingDTO.Receipt receiptView(MesPackagingSealEntity row, boolean ownOriginal) {
		MesWrapEntity wrap = row.getWrapId() == null ? null : wraps.selectOne(new LambdaQueryWrapper<MesWrapEntity>()
				.eq(MesWrapEntity::getTenantId, tenant()).eq(MesWrapEntity::getFactoryCode, row.getFactoryCode())
				.eq(MesWrapEntity::getId, row.getWrapId()).eq(MesWrapEntity::getWrapCode, row.getWrapCode()));
		return new MesPackagingDTO.Receipt().setRequestId(row.getRequestId()).setAcceptedStatus(row.getAcceptedStatus())
				.setCommandSnapshot(ownOriginal ? row.getCommandSnapshot() : null).setResultSnapshot(ownOriginal ? row.getResultSnapshot() : null)
				.setTenantId(row.getTenantId().toString()).setFactoryCode(row.getFactoryCode())
				.setActorId(ownOriginal ? row.getActorId().toString() : null).setActorCode(ownOriginal ? row.getActorCode() : null)
				.setArrangeCode(row.getArrangeCode()).setWrapCode(row.getWrapCode()).setQuantity(row.getQuantity())
				.setCapacity(row.getCapacity()).setSealMode(row.getSealMode()).setAcceptedAt(row.getAcceptedAt())
				.setCurrentWrapStatus(wrap == null ? null : wrap.getStatus()).setCurrentQuantity(wrap == null ? null : wrap.getCurrentQuantity());
	}

	private MesPackagingDTO.Adoption adoptionView(MesPackagingAdoptionEntity row) {
		return new MesPackagingDTO.Adoption().setId(row.getId().toString()).setArrangeCode(row.getArrangeCode())
				.setReportMode(row.getReportMode()).setAdoptionKind(row.getAdoptionKind()).setRuleId(row.getRuleId().toString())
				.setRuleCode(row.getRuleCode()).setCapacity(row.getCapacity()).setSourceFingerprint(row.getSourceFingerprint())
				.setActorId(row.getActorId().toString()).setActorCode(row.getActorCode()).setReason(row.getReason()).setAdoptedAt(row.getAdoptedAt());
	}

	private void installed() {
		try {
			require(Integer.valueOf(1).equals(seals.ready()), "标准包装结构或永久守卫未就绪，请核验累计V4包装章节");
		} catch (CheckedException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new CheckedException("无法核验标准包装结构，请先安装并核验累计V4包装章节", exception);
		}
	}

	private void scope(MesTaskArrangeEntity arrange, String factory) {
		require(arrange != null && arrange.getId() != null && Objects.equals(arrange.getTenantId(), tenant())
				&& Objects.equals(arrange.getFactoryCode(), factory), "包装只能使用当前实际组织已保存的派工");
	}

	private ReportModeEnum mode(MesTaskArrangeEntity arrange) {
		require(arrange != null, "真实派工不能为空");
		return ReportModeEnum.fromCodes(arrange.getReportMode(), arrange.getRouteType());
	}

	private Long tenant() {
		return TenantContextHolder.getTenantId();
	}

	private Long actorId() {
		return SecurityUtils.getUser().getId();
	}

	private String actorCode() {
		return code(SecurityUtils.getUser().getUsername(), "当前实名账号");
	}

	private String code(String value, String label) {
		require(value != null && !value.isBlank() && value.length() <= 100
				&& value.chars().noneMatch(Character::isISOControl), label + "缺失或格式无效");
		return value.trim();
	}

	private String reason(String value) {
		require(value != null && !value.isBlank() && value.trim().length() <= 500, "业务原因须为1至500字符");
		return value.trim();
	}

	private boolean positive(BigDecimal value) {
		if (value == null || value.signum() <= 0) {
			return false;
		}
		BigDecimal canonical = value.stripTrailingZeros();
		return canonical.scale() <= 6 && canonical.precision() - canonical.scale() <= 14;
	}

	private String json(Object value) {
		return JSONUtil.toJsonStr(value, JSONConfig.create().setIgnoreNullValue(false));
	}

	private String hash(String value) {
		require(value != null, "包装原文缺失");
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("JVM缺少SHA-256", exception);
		}
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	/** 已按真实组织和实名固定的原命令，不使用后续当前输入重建成员。 */
	private record Intent(String requestId, String mode, String reason, List<Long> ids, Long reportId,
			String externalCode, String snapshot) {
	}
}
