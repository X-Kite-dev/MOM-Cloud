package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteProcessRouteService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.ReportStatusEnum;
import com.kitex.mom.mes.enums.WarehouseOutBizTypeEnum;
import com.kitex.mom.mes.event.FeedRecordSaveEvent;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.MesReportMaterialConsumeService;
import com.kitex.mom.mes.service.MesWarehouseFeedBirthService;
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.service.impl.material.MesProductionFeedAllocationPlanner;
import com.kitex.mom.mes.util.MesMaterialSubstituteResolver;
import com.kitex.mom.qms.api.feign.RemotePurchaseService;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 线边库投料记录
 *
 * @author kitex
 * @date 2025-02-20 17:22:19
 */
@Service
@RequiredArgsConstructor
public class MesWarehouseFeedServiceImpl extends ServiceImpl<MesWarehouseFeedMapper, MesWarehouseFeedEntity> implements MesWarehouseFeedService {

	private final MesWarehouseLiveStockService liveStockService;
	private final MesTaskMapper mesTaskMapper;
	private final MesReworkMaterialPlanService reworkMaterialPlans;
	private final MesTaskArrangeMapper arrangeMapper;
	private final RemoteMaterialService remoteMaterialService;
	private final ApplicationEventPublisher applicationEventPublisher;
	private final RemoteProcessRouteService remoteProcessRouteService;
	private final RemotePurchaseService remotePurchaseService;
	private final MesMaterialConsumptionModeResolver consumptionModes;
	private final MesStartMaterialBindingReader materialBindings;
	private final MesReportMaterialConsumeService reportMaterialConsumeService;
	private final MesMaterialSourceEligibility sourceEligibility;
	private final MesActualFactoryAccessService factoryAccess;
	private final MesTaskReportMapper reportMapper;
	private final MesExecutionTaskLock executionTaskLock;
	private final MesProductionFeedAllocationPlanner feedAllocationPlanner;
	private final MesProductionMaterialReversalMapper materialReversalMapper;
	private final MesPhysicalTransformCodec proofCodec;
	private final MesWarehouseFeedBirthService feedBirths;
	private final MesPhysicalTransformSourceScopeLock executionLocks;
	private final MesTerminalExecutionGuard terminalGuard;
	private final MesPieceMaterialSelectionService pieceMaterials;
	private final MesMaterialExecutionGuard materialExecution;


	/**
	 * 实际新投料：原工单/派工锁内核BOM，保存一份新feed并真实领出库存。
	 * 原单位数量及每条实际OUT来源须精确一致；Boolean外部接口保持兼容，不把库存条码当重试号。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean saveFeed(MesWarehouseFeedEntity feed) {
		String factory = factoryAccess.require("write");
		feedBirths.assertReady();
		Long tenant = TenantContextHolder.getTenantId();
		if (feed == null || feed.getId() != null || feed.getFeedNum() == null
				|| (feed.getTenantId() != null && !Objects.equals(feed.getTenantId(), tenant))
				|| (StrUtil.isNotBlank(feed.getFactoryCode()) && !Objects.equals(feed.getFactoryCode(), factory))) {
			throw new CheckedException("新投料禁止指定主键或跨租户工厂，数量必须明确");
		}
		proofCodec.code(feed.getTaskCode(), "投料工单", 100);
		proofCodec.code(feed.getArrangeCode(), "投料派工", 100);
		proofCodec.quantity(proofCodec.amount(feed.getFeedNum()), false, "投料原量");
		// 独立saveFeed也先定位真实派工；不能只在外层命令补冻结，或凭客户端工单选锁。
		var ownership = arrangeMapper.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
				.eq(MesTaskArrangeEntity::getTenantId, tenant).eq(MesTaskArrangeEntity::getFactoryCode, factory)
				.eq(MesTaskArrangeEntity::getCode, feed.getArrangeCode()).last("LIMIT 2"));
		require(ownership.size() == 1 && Objects.equals(ownership.get(0).getTaskCode(), feed.getTaskCode()),
				"投料工单与实际派工来源不一致");
		materialExecution.task(ownership.get(0).getTaskCode());
		executionLocks.task(tenant, factory, feed.getTaskCode());
		var tasks = mesTaskMapper.selectList(new LambdaQueryWrapper<MesTaskEntity>()
				.eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, factory)
				.eq(MesTaskEntity::getCode, feed.getTaskCode()).orderByAsc(MesTaskEntity::getId)
				.last("LIMIT 2 FOR UPDATE NOWAIT"));
		if (tasks.size() != 1) {
			throw new CheckedException("投料工单未唯一归属当前实际租户工厂");
		}
		MesTaskEntity mesTask = tasks.get(0);
		var arranges = arrangeMapper.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
				.eq(MesTaskArrangeEntity::getTenantId, tenant).eq(MesTaskArrangeEntity::getFactoryCode, factory)
				.eq(MesTaskArrangeEntity::getTaskCode, mesTask.getCode())
				.eq(MesTaskArrangeEntity::getCode, feed.getArrangeCode()).orderByAsc(MesTaskArrangeEntity::getId)
				.last("LIMIT 2 FOR UPDATE NOWAIT"));
		if (arranges.size() != 1) {
			throw new CheckedException("投料派工未唯一归属当前工单");
		}
		MesTaskArrangeEntity arrange = arranges.get(0);
		require(Objects.equals(ownership.get(0).getId(), arrange.getId())
						&& Objects.equals(ownership.get(0).getProduceTaskCode(), arrange.getProduceTaskCode()),
				"投料派工归属已变化，请重新核对");
		var consumptionMode = consumptionModes.resolve(arrange);
		require(consumptionMode != MesMaterialConsumptionModeResolver.Mode.BACKFLUSH,
				"原派工固定为倒冲模式，不能再次手工投料扣库");
		terminalGuard.assertUnoccupied(arrange);
		if (!Set.of("DISPATCH", "PRODUCT").contains(mesTask.getStatus())
				|| !Set.of("UN_RECEIVE", "RECEIVE", "PRODUCT", "PAUSE").contains(arrange.getStatus())) {
			throw new CheckedException("只有有效已派工/生产中的工单及未完工关闭派工可新增投料");
		}
		proofCodec.code(arrange.getProduceTaskCode(), "实际投料工序任务", 100);
		if (StrUtil.isNotBlank(feed.getProduceTaskCode())
				&& !Objects.equals(feed.getProduceTaskCode(), arrange.getProduceTaskCode())) {
			throw new CheckedException("请求工序任务与实际投料派工不一致");
		}
		feed.setProduceTaskCode(arrange.getProduceTaskCode());
		feed.setFactoryCode(factory);
		feed.setTenantId(tenant);
		String bomSnapshot = mesTask.getBomSnapshot();
		reworkMaterialPlans.assertReady(mesTask);
		MesMaterialSubstituteResolver.Binding binding = MesMaterialSubstituteResolver.resolveFeedBinding(
				bomSnapshot, feed.getMatCode(), feed.getRequirementMatCode());
		if (consumptionMode == MesMaterialConsumptionModeResolver.Mode.MIXED) {
			var demand = com.kitex.mom.mes.util.MesTaskUtils.getCurrentProcess(mesTask.getProcessRouteSnapshot(),
					arrange.getRouteCode(), arrange.getSourceRouteNodeCode()).getMdmProcessProcedureMatList();
			require(MesMaterialConsumptionModeResolver.partition(consumptionMode, demand,
									MesMaterialConsumptionModeResolver.Mode.FEED).stream()
							.anyMatch(row -> binding.requirementMatCode().equals(row.getMaterialCode())),
					"本需求冻结为倒冲方式，不能手工投料再次扣库");
		}
		feed.setRequirementMatCode(binding.requirementMatCode());
		feed.setSubstituteFlag(binding.substitute());
		feed.setSubstituteRatio(binding.ratio());
		feed.setSubstitutePolicySnapshot(JSONUtil.toJsonStr(binding));
		if (mesTask.getReworkMaterialPlanId() != null) {
			assertReworkFeedBudget(mesTask, feed, binding);
		}
		var lineage = bindLineStockLineage(mesTask, feed);
		// 2.每次实际领料保存独立新记录，不按相同批次合并来源。
		feed.setFeedTime(LocalDateTime.now());
		MomUser user = SecurityUtils.getUser();
		feed.setFeedPersonCode(user.getUsername());
		feed.setFeedPersonName(user.getName());
		// 查找物料信息
		var materialResponse = remoteMaterialService.getDetails(feed.getMatCode());
		MdmMaterialEntity material = materialResponse == null ? null : materialResponse.getData();
		if (materialResponse == null || !materialResponse.isOk() || material == null
				|| !Boolean.TRUE.equals(material.getStatus())
				|| !Objects.equals(material.getMaterialCode(), feed.getMatCode())
				|| !Objects.equals(material.getTenantId(), tenant) ||
				!Objects.equals(material.getFactoryCode(), factory)) {
			throw new CheckedException("投料物料未启用或当前租户工厂原计量不可核验");
		}
		proofCodec.code(material.getBasicUnit(), "投料原单位", 100);
		feed.setMatUnit(material.getBasicUnit());
		feed.setMatSpec(material.getSpecificationModel());
		feed.setMatName(material.getMaterialName());
		// 设置物料锁定状态为未锁定
		feed.setIsLock(Boolean.FALSE);

		// 查询质量 得到质检结果
		QmsInspectionPurchaseTaskEntity inspectionPurchaseTask = remotePurchaseService.getTaskByMatCodeAndSupplierCodeAndBatchNo(
				feed.getMatCode(), feed.getSupplierCode(), feed.getMatBatch()).getData();
		if (Objects.nonNull(inspectionPurchaseTask)) {
			feed.setInspectionTaskId(inspectionPurchaseTask.getId());
			feed.setInspectionResult(inspectionPurchaseTask.getResult());
		}
		feed.setFactoryCode(mesTask.getFactoryCode());
		feed.setTenantId(mesTask.getTenantId());
		feed.setUsedNum(BigDecimal.ZERO);
		feed.setId(IdWorker.getId());
		feed.setDelFlag("0");
		feed.setCreateBy(user.getUsername());
		feed.setCreateTime(feed.getFeedTime());
		feed.setUpdateBy(null);
		feed.setUpdateTime(null);
		if (!save(feed)) {
			throw new CheckedException("投料记录保存失败");
		}
		verifySavedFeed(feed);
		// 3.出库
		MesWarehouseLiveStockInoutDTO inoutDTO = new MesWarehouseLiveStockInoutDTO();
		BeanUtil.copyProperties(feed, inoutDTO);
		inoutDTO.setBillId(feed.getId());
		inoutDTO.setBillNo(feed.getArrangeCode());
		inoutDTO.setResourceNo("MES-FEED-" + feed.getId());
		inoutDTO.setNum(feed.getFeedNum());
		inoutDTO.setProduceTaskCode(null);
		inoutDTO.setBizType(WarehouseOutBizTypeEnum.FEEDING.getCode());
		var receipt = liveStockService.outStockWithReceipt(inoutDTO, lineage);
		verifyFeedOut(feed, receipt);
		feedBirths.record(feed, mesTask, arrange, receipt);
		// 4.保存操作记录
		MesTaskReportFeedVO params = new MesTaskReportFeedVO();
		BeanUtil.copyProperties(feed, params);
		params.setWorkerCode(feed.getFeedPersonCode());
		params.setWorkerName(feed.getFeedPersonName());
		params.setFeedNum(feed.getFeedNum());
		params.setTransferCode(feed.getMatBarcode());
		params.setFactoryCode(mesTask.getFactoryCode());
		applicationEventPublisher.publishEvent(new FeedRecordSaveEvent(this, params));
		return Boolean.TRUE;
	}

	/**
	 * 普通投料和返工投料均核真实持久值，不允许feed先舍入再扣出更大的原实物量。
	 */
	private void verifySavedFeed(MesWarehouseFeedEntity expected) {
		var stored = getById(expected.getId());
		if (stored == null || stored.getFeedNum() == null || stored.getFeedNum().compareTo(expected.getFeedNum()) != 0
				|| stored.getSubstituteRatio() == null ||
				stored.getSubstituteRatio().compareTo(expected.getSubstituteRatio()) != 0
				|| stored.getUsedNum() == null || stored.getUsedNum().signum() != 0
				|| !Objects.equals(stored.getTenantId(), expected.getTenantId())
				|| !Objects.equals(stored.getFactoryCode(), expected.getFactoryCode())
				|| !Objects.equals(stored.getTaskCode(), expected.getTaskCode())
				|| !Objects.equals(stored.getProduceTaskCode(), expected.getProduceTaskCode())
				|| !Objects.equals(stored.getArrangeCode(), expected.getArrangeCode())
				|| !Objects.equals(stored.getMatCode(), expected.getMatCode())
				|| !Objects.equals(stored.getMatUnit(), expected.getMatUnit())
				|| !Objects.equals(stored.getMatBatch(), expected.getMatBatch())
				|| !Objects.equals(stored.getMatBarcode(), expected.getMatBarcode())
				|| !Objects.equals(stored.getSupplierCode(), expected.getSupplierCode())
				|| !Objects.equals(stored.getSiteCode(), expected.getSiteCode())
				|| !Objects.equals(stored.getRequirementMatCode(), expected.getRequirementMatCode())
				|| !Objects.equals(stored.getSubstituteFlag(), expected.getSubstituteFlag())
				|| !Objects.equals(stored.getReservationNo(), expected.getReservationNo())
				|| !"0".equals(stored.getDelFlag()) || !Boolean.FALSE.equals(stored.getIsLock())) {
			throw new CheckedException("实际投料原量、单位或派工归属回读不一致，已回滚");
		}
	}

	/**
	 * 只核本次真实OUT全集；不能按批次事后拼来源，也不把该回执冒充完整永久出生账。
	 */
	private void verifyFeedOut(MesWarehouseFeedEntity feed, MesWarehouseStockOutReceiptDTO receipt) {
		if (receipt == null || !"STOCK_LINE_V1".equals(receipt.version())
				|| !Objects.equals(receipt.tenantId(), feed.getTenantId())
				|| !Objects.equals(receipt.factoryCode(), feed.getFactoryCode())
				|| !Objects.equals(receipt.billId(), feed.getId())
				|| !Objects.equals(receipt.billNo(), feed.getArrangeCode())
				|| !WarehouseOutBizTypeEnum.FEEDING.getCode().equals(receipt.bizType())
				|| !Objects.equals(receipt.materialCode(), feed.getMatCode())
				|| !Objects.equals(receipt.materialUnit(), feed.getMatUnit())
				|| receipt.quantity() == null || receipt.quantity().compareTo(feed.getFeedNum()) != 0
				|| receipt.lines().isEmpty() || receipt.lines().size() > 5000) {
			throw new CheckedException("实际投料与逐源OUT原回执不一致，已回滚");
		}
		Set<Long> stocks = new HashSet<>();
		Set<Long> flows = new HashSet<>();
		BigDecimal total = BigDecimal.ZERO;
		for (var line : receipt.lines()) {
			if (line.inoutId() == null || line.stockId() == null
					|| !flows.add(line.inoutId()) || !stocks.add(line.stockId())
					|| !Objects.equals(line.billId(), feed.getId())
					|| !Objects.equals(line.billNo(), feed.getArrangeCode())
					|| !Objects.equals(line.resourceNo(), "MES-FEED-" + feed.getId())
					|| !Objects.equals(line.tenantId(), feed.getTenantId())
					|| !Objects.equals(line.factoryCode(), feed.getFactoryCode())
					|| !WarehouseOutBizTypeEnum.FEEDING.getCode().equals(line.bizType())
					|| !"STOCK_LINE_V1".equals(line.proofVersion())
					|| !Objects.equals(line.materialCode(), feed.getMatCode())
					|| !Objects.equals(line.materialUnit(), feed.getMatUnit())
					|| !Objects.equals(line.batchNo(), feed.getMatBatch())
					||
					(StrUtil.isNotBlank(feed.getMatBarcode()) && !Objects.equals(line.barcode(), feed.getMatBarcode()))
					|| !Objects.equals(line.reservationNo(), feed.getReservationNo())
					|| !Objects.equals(line.requirementMaterialCode(), feed.getRequirementMatCode())
					|| !Objects.equals(line.substitute(), feed.getSubstituteFlag())
					|| line.substituteRatio() == null ||
					line.substituteRatio().compareTo(feed.getSubstituteRatio()) != 0
					|| line.quantity() == null || line.quantity().signum() <= 0
					|| line.beforeQuantity() == null || line.afterQuantity() == null ||
					line.afterQuantity().signum() < 0
					|| line.beforeQuantity().subtract(line.afterQuantity()).compareTo(line.quantity()) != 0) {
				throw new CheckedException("实际投料逐源OUT身份、原单位或前后量不守恒，已回滚");
			}
			total = total.add(line.quantity());
		}
		if (total.compareTo(feed.getFeedNum()) != 0) {
			throw new CheckedException("实际OUT全集与投料原量不守恒，已回滚");
		}
	}

	private void assertReworkFeedBudget(MesTaskEntity task, MesWarehouseFeedEntity feed,
	                                    MesMaterialSubstituteResolver.Binding binding) {
		var arrange = arrangeMapper.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
				.eq(MesTaskArrangeEntity::getCode, feed.getArrangeCode())
				.eq(MesTaskArrangeEntity::getTaskCode, task.getCode())
				.eq(MesTaskArrangeEntity::getTenantId, task.getTenantId())
				.eq(MesTaskArrangeEntity::getFactoryCode, task.getFactoryCode()));
		if (arrange == null) {
			throw new CheckedException("返工投料派工不属于当前工单");
		}
		var requirement = MesMaterialSubstituteResolver.flattenBom(task.getBomSnapshot()).stream()
				.filter(x -> binding.requirementMatCode().equals(x.getMaterialCode())).findFirst()
				.orElseThrow(() -> new CheckedException("物料不在批准返工补料清单"));
		var prior = list(
				new LambdaQueryWrapper<MesWarehouseFeedEntity>().eq(MesWarehouseFeedEntity::getTaskCode, task.getCode())
						.eq(MesWarehouseFeedEntity::getFactoryCode, task.getFactoryCode())
						.eq(MesWarehouseFeedEntity::getTenantId, task.getTenantId())
						.eq(MesWarehouseFeedEntity::getRequirementMatCode, binding.requirementMatCode())
						.last("LIMIT 5001"));
		if (prior.size() > 5000 || feed.getFeedNum() == null || feed.getFeedNum().signum() <= 0) {
			throw new CheckedException("返工投料数量无效或历史超过核验容量");
		}
		BigDecimal used = feed.getFeedNum().divide(binding.ratio(), 8, java.math.RoundingMode.UNNECESSARY);
		for (var row : prior) {
			if (row.getFeedNum() == null || row.getFeedNum().signum() < 0 || row.getSubstituteRatio() == null ||
					row.getSubstituteRatio().signum() <= 0) {
				throw new CheckedException("返工历史投料量或替代比例不明，须先核对实物");
			}
			used = used.add(row.getFeedNum().divide(row.getSubstituteRatio(), 8, java.math.RoundingMode.UNNECESSARY));
		}
		if (used.compareTo(requirement.getQuantity().multiply(task.getNoticeNum())) > 0) {
			throw new CheckedException("返工累计投料超过批准补料总量，不得以重复投料绕过额度");
		}
	}

	/**
	 * 条码投料不能只按物料/批次扣减，否则同批次下不同正式预留可能串用。
	 * 由线边实物库存反查并冻结唯一预留来源；无预留号的历史库存保持兼容。
	 */
	private MesWarehouseStockOutReceiptDTO.Lineage bindLineStockLineage(MesTaskEntity task,
	                                                                    MesWarehouseFeedEntity feed) {
		List<MesWarehouseLiveStockEntity> candidates = liveStockService.list(
				new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
						.eq(MesWarehouseLiveStockEntity::getTenantId, feed.getTenantId())
						.eq(MesWarehouseLiveStockEntity::getFactoryCode, feed.getFactoryCode())
						.eq(MesWarehouseLiveStockEntity::getMatCode, feed.getMatCode())
						.and(process -> process.eq(MesWarehouseLiveStockEntity::getProduceTaskCode,
										feed.getProduceTaskCode())
								.or().isNull(MesWarehouseLiveStockEntity::getProduceTaskCode)
								.or().eq(MesWarehouseLiveStockEntity::getProduceTaskCode, ""))
						.isNull(MesWarehouseLiveStockEntity::getQualityCaseId)
						.isNull(MesWarehouseLiveStockEntity::getSourceUnqualifiedReportId)
						.isNull(MesWarehouseLiveStockEntity::getProductionNgCaptureId)
						.eq(StrUtil.isNotBlank(feed.getMatBatch()), MesWarehouseLiveStockEntity::getMatBatch,
								feed.getMatBatch())
						.eq(StrUtil.isNotBlank(feed.getSiteCode()), MesWarehouseLiveStockEntity::getSiteCode,
								feed.getSiteCode())
						.eq(StrUtil.isNotBlank(feed.getWarehouseCode()), MesWarehouseLiveStockEntity::getWarehouseCode,
								feed.getWarehouseCode())
						.eq(StrUtil.isNotBlank(feed.getSupplierCode()), MesWarehouseLiveStockEntity::getSupplierCode,
								feed.getSupplierCode())
						.eq(StrUtil.isNotBlank(feed.getMatBarcode()), MesWarehouseLiveStockEntity::getMatBarcode,
								feed.getMatBarcode())
						.eq(StrUtil.isNotBlank(feed.getReservationNo()), MesWarehouseLiveStockEntity::getReservationNo,
								feed.getReservationNo())
						.eq(MesWarehouseLiveStockEntity::getStatus, "0")
						.and(w -> w.isNull(MesWarehouseLiveStockEntity::getMatValidEndDate)
								.or().ge(MesWarehouseLiveStockEntity::getMatValidEndDate, LocalDate.now()))
						.and(w -> w.isNull(MesWarehouseLiveStockEntity::getInspectionStatus)
								.or().in(MesWarehouseLiveStockEntity::getInspectionStatus,
										"NOT_REQUIRED", "RELEASED", "QUALIFIED", "RELEASE", "RECEIVE", "EXEMPTION"))
						.gt(MesWarehouseLiveStockEntity::getNum, BigDecimal.ZERO)
						.orderByAsc(MesWarehouseLiveStockEntity::getId).last("LIMIT 5001"));
		if (candidates.size() > 5000) {
			throw new CheckedException("投料预留谱系候选超过5000行，请收窄范围，不能截断后选择");
		}
		candidates = candidates.stream().filter(stock -> sourceEligibility.eligible(task, stock,
				feed.getRequirementMatCode(), feed.getSubstituteRatio())).toList();
		if (CollUtil.isEmpty(candidates)) {
			throw new CheckedException("线边库存中未找到本次投料对应的实物批次");
		}
		Map<MesWarehouseStockOutReceiptDTO.Lineage, List<MesWarehouseLiveStockEntity>> lineages = candidates.stream()
				.collect(Collectors.groupingBy(item -> new MesWarehouseStockOutReceiptDTO.Lineage(
						item.getReservationNo(), item.getRequirementMatCode())));
		List<MesWarehouseLiveStockEntity> selected;
		if (lineages.size() == 1) {
			selected = lineages.values().iterator().next();
		} else {
			List<List<MesWarehouseLiveStockEntity>> sufficient = lineages.values().stream()
					.filter(items -> items.stream().map(MesWarehouseLiveStockEntity::getNum)
							.filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add)
							.compareTo(feed.getFeedNum()) >= 0)
					.toList();
			if (sufficient.size() != 1) {
				throw new CheckedException("同一实物批次存在多个物料预留来源，请按预留单号分开投料");
			}
			selected = sufficient.get(0);
		}
		MesWarehouseLiveStockEntity source = selected.get(0);
		if (StrUtil.isNotBlank(source.getRequirementMatCode())
				&& !Objects.equals(source.getRequirementMatCode(), feed.getRequirementMatCode())) {
			throw new CheckedException("该库存已预留给需求物料[" + source.getRequirementMatCode()
					+ "]，不能用于需求物料[" + feed.getRequirementMatCode() + "]");
		}
		feed.setReservationNo(source.getReservationNo());
		return new MesWarehouseStockOutReceiptDTO.Lineage(source.getReservationNo(), source.getRequirementMatCode());
	}

	/**
	 * 退料
	 *
	 * @param feed
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void feedBack(MesWarehouseFeedEntity feed) {
		throw new CheckedException("旧投料记录直接退料入口已关闭，请从线边库存发起受控退库并完成异岗审核");
	}

	/**
	 * 合格报工的实际投料耗用。
	 *
	 * <p>与不良报工共用精确分配内核，但仍保存原GOOD消耗表及原冲销语义。
	 * 这里的唯一产出仅为当前合格报告，不能传GOOD+NG总量或代替组合生产独立账。</p>
	 *
	 * @param useDTO 原合格报告关联及原派工快照；身份、单位和数量再从实际原报告核验
	 */
	@Override
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public void use(MesWarehouseUseDTO useDTO) {
		require(useDTO != null && useDTO.getReportId() != null && useDTO.getReportId() > 0
				&& StrUtil.isNotBlank(useDTO.getReportRequestId()), "报工物料消耗缺少实际报告及原请求号");
		String factory = factoryAccess.require("write");
		Long tenant = TenantContextHolder.getTenantId();
		require(Objects.equals(factory, useDTO.getFactoryCode()), "耗料不能跨认证工厂归属");
		require(Boolean.TRUE.equals(materialReversalMapper.ready()),
				"GOOD/NG投料真实变动及受控恢复保护未就绪，请核V2第257—258节");

		// 普通报工在此之前已经取得同一父工单锁；重入仍按真实派工定位，不能按传入taskCode猜锁。
		var ownership = executionTaskLock.arrange(useDTO.getArrangeCode());
		var arrangeRows = arrangeMapper.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
				.eq(MesTaskArrangeEntity::getTenantId, tenant).eq(MesTaskArrangeEntity::getFactoryCode, factory)
				.eq(MesTaskArrangeEntity::getCode, useDTO.getArrangeCode()).last("LIMIT 2 FOR UPDATE NOWAIT"));
		require(arrangeRows.size() == 1, "实际耗料派工不唯一，不能选择首项");
		var arrange = arrangeRows.get(0);
		executionTaskLock.assertArrange(ownership, arrange);
		var taskRows = mesTaskMapper.selectList(new LambdaQueryWrapper<MesTaskEntity>()
				.eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, factory)
				.eq(MesTaskEntity::getCode, arrange.getTaskCode()).last("LIMIT 2 FOR UPDATE NOWAIT"));
		require(taskRows.size() == 1, "实际耗料父工单不唯一，不能猜快照");
		var task = taskRows.get(0);
		var report = reportMapper.selectOne(new LambdaQueryWrapper<MesTaskReportEntity>()
				.eq(MesTaskReportEntity::getId, useDTO.getReportId())
				.eq(MesTaskReportEntity::getTenantId, tenant).eq(MesTaskReportEntity::getFactoryCode, factory));
		require(report != null && ReportStatusEnum.EFFECTIVE.name().equals(report.getStatus())
						&& Objects.equals(report.getRequestId(), useDTO.getReportRequestId())
						&& Objects.equals(report.getArrangeCode(), arrange.getCode())
						&& Objects.equals(report.getTaskCode(), task.getCode())
						&& Objects.equals(report.getProduceTaskCode(), arrange.getProduceTaskCode())
						&& Objects.equals(report.getMatCode(), arrange.getMatCode())
						&& report.getReportNum() != null && report.getReportNum().signum() > 0
						&& sameAmount(report.getReportNum(), useDTO.getReportNum())
						&& sameAmount(report.getEffectiveReportNum(), report.getReportNum())
						&& sameAmount(report.getCorrectedReportNum(), BigDecimal.ZERO)
						&& !"ROUTE_RETURN".equals(report.getExecutionKind())
						&& StrUtil.isNotBlank(report.getReportUnit()),
				"耗料须属于尚未纠正的本次正量GOOD原报告，不允许以总加工量或旧报告代替");
		require(Objects.equals(useDTO.getSnapshot(), task.getProcessRouteSnapshot())
						&& Objects.equals(useDTO.getBomSnapshot(), task.getBomSnapshot())
						&& Objects.equals(useDTO.getRouteCode(), arrange.getRouteCode())
						&& Objects.equals(useDTO.getProcessRouteCode(), task.getProcessRouteCode()),
				"耗料参数与实际父工单冻结路线/BOM不同，不能重解释原工程依据");
		require(reportMaterialConsumeService.count(new LambdaQueryWrapper<MesReportMaterialConsumeEntity>()
						.eq(MesReportMaterialConsumeEntity::getTenantId, tenant)
						.eq(MesReportMaterialConsumeEntity::getFactoryCode, factory)
						.eq(MesReportMaterialConsumeEntity::getReportId, report.getId())) == 0,
				"原GOOD报告已经有耗料分配，禁止再次扣料");

		ProcessProcedureDTO route = com.kitex.mom.mes.util.MesTaskUtils.getCurrentProcess(
				task.getProcessRouteSnapshot(),
				arrange.getRouteCode(), arrange.getSourceRouteNodeCode());
		var requirements = Objects.requireNonNullElse(route.getMdmProcessProcedureMatList(),
				List.<ProcessProcedureDTO.ProcessProcedureMatDTO>of());
		var consumptionMode = consumptionModes.resolve(arrange);
		require(consumptionMode != MesMaterialConsumptionModeResolver.Mode.BACKFLUSH,
				"原派工固定为倒冲模式，不能在GOOD投料链继续耗料");
		requirements = MesMaterialConsumptionModeResolver.partition(consumptionMode, requirements,
				MesMaterialConsumptionModeResolver.Mode.FEED);
		var feedQuery = new LambdaQueryWrapper<MesWarehouseFeedEntity>()
				.eq(MesWarehouseFeedEntity::getTenantId, tenant)
				.eq(MesWarehouseFeedEntity::getFactoryCode, factory)
				.eq(MesWarehouseFeedEntity::getTaskCode, task.getCode())
				.eq(MesWarehouseFeedEntity::getProduceTaskCode, arrange.getProduceTaskCode())
				.eq(MesWarehouseFeedEntity::getArrangeCode, arrange.getCode())
				.eq(MesWarehouseFeedEntity::getIsLock, Boolean.FALSE)
				.and(w -> w.isNull(MesWarehouseFeedEntity::getMatValidEndDate)
						.or().ge(MesWarehouseFeedEntity::getMatValidEndDate, LocalDate.now()))
				.orderByAsc(MesWarehouseFeedEntity::getId);
		materialBindings.constrain(arrange, feedQuery);
		var locked = requirements.isEmpty() ? List.<MesWarehouseFeedEntity>of() : list(
				feedQuery.last("LIMIT 5001 FOR UPDATE NOWAIT"));
		require(locked.size() <= 5000, "实际投料超过5000条，须收窄而非截断耗用");
		String outputKey = report.getId().toString();
		if (useDTO.getMaterialSelectionJson() != null) {
			require(com.kitex.mom.mes.enums.ReportModeEnum.ONE_CODE.name().equals(report.getReportMode())
							&& BigDecimal.ONE.compareTo(report.getReportNum()) == 0
							&& "NORMAL".equals(report.getExecutionKind()) &&
							Integer.valueOf(1).equals(report.getIdentifierRouteVersion())
							&& report.getIdentifierBindingId() == null,
					"指定用料须对应当前普通单件原报告");
		}
		var plan = useDTO.getMaterialSelectionJson() != null
				? pieceMaterials.plan(arrange, task, locked, useDTO.getMaterialSelectionJson(), outputKey,
				report.getReportUnit())
				: feedAllocationPlanner.plan(
				new MesProductionFeedAllocationPlanner.Scope(tenant, factory, task.getCode(),
						arrange.getProduceTaskCode(), arrange.getCode(), report.getReportUnit()),
				List.of(new MesProductionFeedAllocationPlanner.OutputDemand(outputKey,
						MesProductionFeedAllocationPlanner.OutputKind.GOOD,
						report.getReportNum(), report.getReportUnit())),
				requirements, task.getBomSnapshot(), locked, this::readConsumptionMeasure);
		require(sameAmount(plan.processedQuantity(), report.getReportNum())
						&& plan.attributions().size() == plan.consumptions().size()
						&& plan.attributions().stream().allMatch(edge -> outputKey.equals(edge.outputKey())
						&& edge.kind() == MesProductionFeedAllocationPlanner.OutputKind.GOOD),
				"旧GOOD协议只能接当前合格报告份额，组合总料须独立永久协议");

		var feedById = locked.stream().collect(Collectors.toMap(MesWarehouseFeedEntity::getId, row -> row));
		var plannedIds = new HashSet<Long>();
		var consumes = new ArrayList<MesReportMaterialConsumeEntity>();
		for (var consumption : plan.consumptions()) {
			require(plannedIds.add(consumption.feedId()),
					"旧GOOD协议不能以同一feed多次CAS接组合消耗，须使用独立总耗用账");
			var feed = feedById.get(consumption.feedId());
			require(feed != null, "实际耗料来源未在锁定全集中");
			var update = new LambdaUpdateWrapper<MesWarehouseFeedEntity>()
					.eq(MesWarehouseFeedEntity::getId, feed.getId())
					.eq(MesWarehouseFeedEntity::getTenantId, tenant)
					.eq(MesWarehouseFeedEntity::getFactoryCode, factory)
					.eq(MesWarehouseFeedEntity::getTaskCode, task.getCode())
					.eq(MesWarehouseFeedEntity::getProduceTaskCode, arrange.getProduceTaskCode())
					.eq(MesWarehouseFeedEntity::getArrangeCode, arrange.getCode())
					.eq(MesWarehouseFeedEntity::getFeedNum, consumption.feedQuantity())
					.eq(MesWarehouseFeedEntity::getMatUnit, consumption.matUnit())
					.eq(MesWarehouseFeedEntity::getIsLock, Boolean.FALSE);
			if (feed.getUsedNum() == null) {
				update.isNull(MesWarehouseFeedEntity::getUsedNum);
			} else {
				update.eq(MesWarehouseFeedEntity::getUsedNum, consumption.usedBefore());
			}
			require(baseMapper.update(null, update.set(MesWarehouseFeedEntity::getUsedNum, consumption.usedAfter())) ==
							1,
					"实际GOOD投料耗用更新失败，整次报工回滚");
			var savedFeed = getById(feed.getId());
			require(savedFeed != null && sameAmount(savedFeed.getUsedNum(), consumption.usedAfter()),
					"实际投料已用量不能精确落库，禁止静默舍入");

			var row = new MesReportMaterialConsumeEntity()
					.setReportId(report.getId()).setReportRequestId(report.getRequestId())
					.setArrangeCode(arrange.getCode()).setFeedId(feed.getId())
					.setRequirementMatCode(consumption.requirementMatCode())
					.setMatCode(consumption.matCode()).setMatBatch(feed.getMatBatch())
					.setMatBarcode(feed.getMatBarcode()).setConsumeNum(consumption.consumeQuantity())
					.setRequirementNum(consumption.requirementQuantity())
					.setSubstituteFlag(consumption.substitute()).setSubstituteRatio(consumption.substituteRatio())
					.setStatus(ReportStatusEnum.EFFECTIVE.name()).setFactoryCode(factory);
			row.setId(IdWorker.getId());
			row.setTenantId(tenant);
			row.setCreateBy(SecurityUtils.getUser().getUsername());
			row.setDelFlag("0");
			consumes.add(row);
		}
		if (!consumes.isEmpty()) {
			require(reportMaterialConsumeService.saveBatch(consumes), "GOOD实际耗用分配必要写失败，整次报工回滚");
		}
		verifyConsumptionRows(report, consumes, tenant, factory);
	}

	/**
	 * 必须核真实计量观察；不从替代料名称或历史“个”推断原投料单位。
	 */
	private MdmMaterialMeasureDTO readConsumptionMeasure(String materialCode) {
		var response = remoteMaterialService.productionMeasure(materialCode);
		require(response != null && response.isOk() && response.getData() != null, "无法取得实际投料物料计量");
		return response.getData();
	}

	/**
	 * 保存后的完整原分配逐字段回读，捕捉数量列舍入、部分批量失败或错误关联。
	 */
	private void verifyConsumptionRows(MesTaskReportEntity report, List<MesReportMaterialConsumeEntity> expected,
	                                   Long tenant, String factory) {
		var rows = reportMaterialConsumeService.list(new LambdaQueryWrapper<MesReportMaterialConsumeEntity>()
				.eq(MesReportMaterialConsumeEntity::getTenantId, tenant)
				.eq(MesReportMaterialConsumeEntity::getFactoryCode, factory)
				.eq(MesReportMaterialConsumeEntity::getReportId, report.getId())
				.orderByAsc(MesReportMaterialConsumeEntity::getId).last("LIMIT 5001"));
		require(rows.size() == expected.size() && rows.size() <= 5000, "GOOD实际耗用分配全集回读不一致");
		var byId = rows.stream().collect(Collectors.toMap(MesReportMaterialConsumeEntity::getId, row -> row));
		for (var row : expected) {
			var saved = byId.get(row.getId());
			require(saved != null && Objects.equals(saved.getTenantId(), tenant)
							&& Objects.equals(saved.getFactoryCode(), factory)
							&& Objects.equals(saved.getReportId(), row.getReportId())
							&& Objects.equals(saved.getReportRequestId(), row.getReportRequestId())
							&& Objects.equals(saved.getArrangeCode(), row.getArrangeCode())
							&& Objects.equals(saved.getFeedId(), row.getFeedId())
							&& Objects.equals(saved.getRequirementMatCode(), row.getRequirementMatCode())
							&& Objects.equals(saved.getMatCode(), row.getMatCode())
							&& Objects.equals(saved.getMatBatch(), row.getMatBatch())
							&& Objects.equals(saved.getMatBarcode(), row.getMatBarcode())
							&& Objects.equals(saved.getSubstituteFlag(), row.getSubstituteFlag())
							&& sameAmount(saved.getConsumeNum(), row.getConsumeNum())
							&& sameAmount(saved.getRequirementNum(), row.getRequirementNum())
							&& sameAmount(saved.getSubstituteRatio(), row.getSubstituteRatio())
							&& Objects.equals(saved.getStatus(), row.getStatus()) && "0".equals(saved.getDelFlag())
							&& saved.getReverseRequestId() == null && saved.getReverseTime() == null,
					"GOOD实际耗用关联或精确数量回读不一致，整次报工回滚");
		}
	}

	private boolean sameAmount(BigDecimal left, BigDecimal right) {
		return left != null && right != null && left.compareTo(right) == 0;
	}

	/**
	 * 查询投料列表
	 *
	 * @param query
	 * @return
	 */
	@Override
	public List<MesWarehouseFeedLeftDTO> queryTaskFeedList(MesWarehouseFeedLeftDTO query) {
		query.setFactoryCode(factoryAccess.require("read"));
		return baseMapper.queryTaskFeedList(query);
	}

	/**
	 * 余料分页查询
	 *
	 * @param pageParam
	 * @param query
	 * @return
	 */
	@Override
	public IPage<MesWarehouseFeedLeftDTO> queryLeftPage(Page<MesWarehouseFeedLeftDTO> pageParam,
	                                                    MesWarehouseFeedLeftDTO query) {
		query.setFactoryCode(factoryAccess.require("read"));
		return baseMapper.queryLeftPage(pageParam, query);
	}

}
