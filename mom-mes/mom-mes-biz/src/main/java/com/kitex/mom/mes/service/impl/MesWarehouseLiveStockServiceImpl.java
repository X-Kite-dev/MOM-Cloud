package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.entity.SysDictItemEntity;
import com.kitex.admin.api.feign.RemoteDictService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.MesLiveStockQueryDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedDTO;
import com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO;
import com.kitex.mom.mes.dto.MesWarehouseStockOutReceiptDTO;
import com.kitex.mom.mes.entity.MesMaterialReceiptEntity;
import com.kitex.mom.mes.entity.MesWarehouseInoutEntity;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.enums.*;
import com.kitex.mom.mes.mapper.MesWarehouseInoutMapper;
import com.kitex.mom.mes.mapper.MesWarehouseLiveStockMapper;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.vo.MesWarehouseLiveStockByMaterialExcelVO;
import com.kitex.mom.mes.vo.MesWarehouseLiveStockDetailExcelVO;
import com.kitex.mom.mes.vo.MesWarehouseLiveStockVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 线边库实时库存
 *
 * @author kitex
 * @date 2025-02-20 13:54:13
 */
@Service
@RequiredArgsConstructor
public class MesWarehouseLiveStockServiceImpl extends ServiceImpl<MesWarehouseLiveStockMapper, MesWarehouseLiveStockEntity> implements MesWarehouseLiveStockService {

	private final RemoteDictService remoteDictService;
	private final RemoteMaterialService remoteMaterialService;
	private final MesWarehouseInoutMapper mesWarehouseInoutMapper;
	private final MesActualFactoryAccessService factoryAccess;
	private final MesPhysicalTransformCodec proofCodec;
	private final MesMaterialSourceEligibility sourceEligibility;

	@Override
	public List<MesWarehouseLiveStockDetailExcelVO> listDetailExcel(
			LambdaQueryWrapper<MesWarehouseLiveStockEntity> wrapper) {
		List<MesWarehouseLiveStockEntity> list = list(wrapper);
		// 获取单位字典
		List<SysDictItemEntity> material_unit = Optional.ofNullable(
				remoteDictService.getDictByType("material_unit").getData()).orElse(new ArrayList<>());
		Map<String, String> unitMap = material_unit.stream().collect(
				Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel, (v1, v2) -> v1));
		List<MesWarehouseLiveStockDetailExcelVO> result = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			MesWarehouseLiveStockEntity liveStock = list.get(i);
			MesWarehouseLiveStockDetailExcelVO excelVO = new MesWarehouseLiveStockDetailExcelVO();
			BeanUtil.copyProperties(liveStock, excelVO);
			excelVO.setRowNo(i + 1);
			excelVO.setStatus(Optional.ofNullable(YesNoEnum.getByCode(Integer.parseInt(liveStock.getStatus())))
					.orElse(YesNoEnum.NO).getDesc());
			excelVO.setMatUnit(Optional.ofNullable(unitMap.get(liveStock.getMatUnit())).orElse(liveStock.getMatUnit()));
			result.add(excelVO);
		}
		return result;
	}

	@Override
	public List<MesWarehouseLiveStockByMaterialExcelVO> listByMatExcel(
			QueryWrapper<MesWarehouseLiveStockEntity> wrapper) {
		List<MesWarehouseLiveStockEntity> list = list(wrapper);
		// 获取单位字典
		List<SysDictItemEntity> material_unit = Optional.ofNullable(
				remoteDictService.getDictByType("material_unit").getData()).orElse(new ArrayList<>());
		Map<String, String> unitMap = material_unit.stream().collect(
				Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel, (v1, v2) -> v1));
		List<MesWarehouseLiveStockByMaterialExcelVO> result = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			MesWarehouseLiveStockEntity liveStock = list.get(i);
			MesWarehouseLiveStockByMaterialExcelVO excelVO = new MesWarehouseLiveStockByMaterialExcelVO();
			BeanUtil.copyProperties(liveStock, excelVO);
			excelVO.setRowNo(i + 1);
			excelVO.setMatUnit(Optional.ofNullable(unitMap.get(liveStock.getMatUnit())).orElse(liveStock.getMatUnit()));
			result.add(excelVO);
		}
		return result;
	}

	/**
	 * 线边库实时库存入库
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean inStock(MesWarehouseLiveStockInoutDTO liveStock) {
		beforeInCheck(liveStock);
		String factory = requireStockScope(liveStock);
		LocalDateTime now = LocalDateTime.now();
		MesWarehouseLiveStockEntity liveStockEntity = new MesWarehouseLiveStockEntity();
		BeanUtil.copyProperties(liveStock, liveStockEntity);
		liveStockEntity.setQualityCaseId(null);
		liveStockEntity.setQualityDispositionReceiptId(null);
		liveStockEntity.setQualityOriginCaseId(null);
		liveStockEntity.setQualityReleaseReceiptId(null);
		liveStockEntity.setSourceUnqualifiedReportId(null);
		liveStockEntity.setProductionNgCaptureId(null);
		// 普通入库/反向恢复不证明已确认实物位置，须由独立转运交接确认。
		liveStockEntity.setPhysicalLocationCode(null);
		liveStockEntity.setPhysicalLocationName(null);
		liveStockEntity.setPhysicalConfirmedTime(null);
		liveStockEntity.setFactoryCode(factory);
		liveStockEntity.setTenantId(TenantContextHolder.getTenantId());
		liveStockEntity.setInTime(now);
		String inspectionStatus = StrUtil.blankToDefault(liveStockEntity.getInspectionStatus(),
				QualityQuantityStatusEnum.NOT_REQUIRED.name());
		liveStockEntity.setInspectionStatus(inspectionStatus);
		boolean qualityBlocked = QualityQuantityStatusEnum.PENDING.name().equals(inspectionStatus)
				|| QualityQuantityStatusEnum.REJECTED.name().equals(inspectionStatus)
				|| QualityQuantityStatusEnum.PARTIAL.name().equals(inspectionStatus);
		liveStockEntity.setStatus(qualityBlocked ? YesNoEnum.YES.getCode() : YesNoEnum.NO.getCode());
		if (!save(liveStockEntity)) {
			throw new CheckedException("库存入库保存失败");
		}
		var persisted = getById(liveStockEntity.getId());
		if (persisted == null || persisted.getNum() == null || persisted.getNum().compareTo(liveStock.getNum()) != 0) {
			throw new CheckedException("入库数量超出当前库存字段精度，已回滚，禁止静默舍入");
		}
		// 入库流水
		MesWarehouseInoutEntity inout = new MesWarehouseInoutEntity();
		BeanUtil.copyProperties(liveStock, inout, "id");
		inout.setOperateNum(liveStock.getNum());
		inout.setOperateType(InOutEnum.IN.getCode());
		inout.setFactoryCode(factory);
		inout.setTenantId(liveStockEntity.getTenantId());
		inout.setSourceStockId(liveStockEntity.getId());
		inout.setSourceReportId(liveStockEntity.getSourceReportId());
		inout.setStockBeforeNum(BigDecimal.ZERO);
		inout.setStockAfterNum(liveStockEntity.getNum());
		inout.setStockProofVersion("STOCK_LINE_V1");
		inout.setAllocationPolicy("NEW_STOCK_LINE");
		if (mesWarehouseInoutMapper.insert(inout) != 1) {
			throw new CheckedException("库存入库证据保存失败");
		}
		return Boolean.TRUE;
	}

	/**
	 * 预检与真实扣减共用资格查询；不在预检中预留或改库存。
	 */
	private QueryWrapper<MesWarehouseLiveStockEntity> stockCandidates(MesWarehouseLiveStockInoutDTO liveStock,
	                                                                  String factory, java.time.LocalDate stockDate,
	                                                                  MesWarehouseStockOutReceiptDTO.Lineage lineage) {
		QueryWrapper<MesWarehouseLiveStockEntity> queryWrapper = new QueryWrapper<>();
		queryWrapper.lambda().eq(MesWarehouseLiveStockEntity::getMatCode, liveStock.getMatCode())
				.isNull(MesWarehouseLiveStockEntity::getQualityCaseId)
				// NG放行也保持独立来源；未接 typed 下游前不能当无GOOD来源的普通库存消费。
				.isNull(MesWarehouseLiveStockEntity::getSourceUnqualifiedReportId)
				.isNull(MesWarehouseLiveStockEntity::getProductionNgCaptureId)
				.eq(MesWarehouseLiveStockEntity::getFactoryCode, factory)
				.eq(MesWarehouseLiveStockEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(StrUtil.isNotBlank(liveStock.getReservationNo()), MesWarehouseLiveStockEntity::getReservationNo,
						liveStock.getReservationNo())
				.eq(lineage == null && StrUtil.isNotBlank(liveStock.getReservationNo())
								&& StrUtil.isNotBlank(liveStock.getRequirementMatCode()),
						MesWarehouseLiveStockEntity::getRequirementMatCode, liveStock.getRequirementMatCode())
				.eq(StrUtil.isNotBlank(liveStock.getMatBatch()), MesWarehouseLiveStockEntity::getMatBatch,
						liveStock.getMatBatch())
				.and(Boolean.TRUE.equals(liveStock.getAllowUnassignedProduceTask())
						&& StrUtil.isNotBlank(liveStock.getProduceTaskCode()), wrapper -> wrapper
						.eq(MesWarehouseLiveStockEntity::getProduceTaskCode, liveStock.getProduceTaskCode())
						.or().isNull(MesWarehouseLiveStockEntity::getProduceTaskCode)
						.or().eq(MesWarehouseLiveStockEntity::getProduceTaskCode, ""))
				.eq(!Boolean.TRUE.equals(liveStock.getAllowUnassignedProduceTask())
								&& StrUtil.isNotBlank(liveStock.getProduceTaskCode()),
						MesWarehouseLiveStockEntity::getProduceTaskCode, liveStock.getProduceTaskCode())
				.eq(StrUtil.isNotBlank(liveStock.getSiteCode()), MesWarehouseLiveStockEntity::getSiteCode,
						liveStock.getSiteCode())
				.eq(lineage != null && StrUtil.isNotBlank(liveStock.getWarehouseCode()),
						MesWarehouseLiveStockEntity::getWarehouseCode, liveStock.getWarehouseCode())
				.eq(StrUtil.isNotBlank(liveStock.getSupplierCode()), MesWarehouseLiveStockEntity::getSupplierCode,
						liveStock.getSupplierCode())
				.eq(liveStock.getSourceReportId() != null, MesWarehouseLiveStockEntity::getSourceReportId,
						liveStock.getSourceReportId())
				.eq(StrUtil.isNotBlank(liveStock.getMatBarcode()), MesWarehouseLiveStockEntity::getMatBarcode,
						liveStock.getMatBarcode())
				.eq(StrUtil.isNotBlank(liveStock.getMatDetailType()), MesWarehouseLiveStockEntity::getMatDetailType,
						liveStock.getMatDetailType())
				.eq(!Boolean.TRUE.equals(liveStock.getAllowQualityBlocked()),
						MesWarehouseLiveStockEntity::getStatus, YesNoEnum.NO.getCode())
				.and(!Boolean.TRUE.equals(liveStock.getAllowQualityBlocked()), wrapper -> wrapper
						.isNull(MesWarehouseLiveStockEntity::getMatValidEndDate)
						.or().ge(MesWarehouseLiveStockEntity::getMatValidEndDate, stockDate))
				.and(!Boolean.TRUE.equals(liveStock.getAllowQualityBlocked()), wrapper -> wrapper
						.isNull(MesWarehouseLiveStockEntity::getInspectionStatus)
						.or().in(MesWarehouseLiveStockEntity::getInspectionStatus,
								QualityQuantityStatusEnum.NOT_REQUIRED.name(),
								QualityQuantityStatusEnum.RELEASED.name(),
								"QUALIFIED", "RELEASE", "RECEIVE", "EXEMPTION"));
		// 原GOOD倒冲、领出及独立NG倒冲共用同一原预留/BOM资格，不降级来源检查。
		if (MesMaterialSourceEligibility.requiresProductionContext(liveStock.getBizType())) {
			var context = sourceEligibility.productionContext(liveStock, factory);
			queryWrapper.lambda()
					.and(scope -> scope.eq(MesWarehouseLiveStockEntity::getProduceTaskCode, context.produceTaskCode())
							.or().isNull(MesWarehouseLiveStockEntity::getProduceTaskCode).or()
							.eq(MesWarehouseLiveStockEntity::getProduceTaskCode, ""));
			sourceEligibility.constrain(queryWrapper, context.task(), liveStock.getRequirementMatCode(),
					liveStock.getMatCode(), liveStock.getSubstituteRatio());
		}
		return queryWrapper;
	}

	public void beforeInCheck(MesWarehouseLiveStockInoutDTO liveStock) {
		if (liveStock == null || StrUtil.isBlank(liveStock.getBizType()) || StrUtil.isBlank(liveStock.getMatCode())) {
			throw new CheckedException("库存请求、业务类型和物料编码不能为空");
		}
		if (liveStock.getNum() == null || liveStock.getNum().compareTo(BigDecimal.ZERO) <= 0) {
			throw new CheckedException("库存操作数量必须大于0！");
		}
	}

	@Override
	public List<MesWarehouseLiveStockEntity> previewOutCandidates(MesWarehouseLiveStockInoutDTO request) {
		beforeInCheck(request);
		return previewCandidates(request, false);
	}

	@Override
	public List<MesWarehouseLiveStockEntity> previewFeedSources(MesWarehouseLiveStockInoutDTO request) {
		if (request == null || StrUtil.isBlank(request.getMatCode())) {
			throw new CheckedException("投料来源观察须明确物料编码");
		}
		return previewCandidates(request, true);
	}

	/**
	 * 共用单位、质量、数量完整性及FIFO资格；新观察仓库条件不改变旧齐套预检语义。
	 */
	private List<MesWarehouseLiveStockEntity> previewCandidates(MesWarehouseLiveStockInoutDTO request,
	                                                            boolean feedSources) {
		String factory = requireStockScope(request);
		if (Boolean.TRUE.equals(request.getAllowQualityBlocked())) {
			throw new CheckedException("生产齐套预检不能包含质量阻断库存");
		}
		var response = remoteMaterialService.getDetails(request.getMatCode());
		var material = response == null ? null : response.getData();
		if (response == null || !response.isOk() || material == null || !Boolean.TRUE.equals(material.getStatus())
				|| !Objects.equals(material.getMaterialCode(), request.getMatCode()) ||
				!Objects.equals(material.getTenantId(), TenantContextHolder.getTenantId()) ||
				!Objects.equals(material.getFactoryCode(), factory)) {
			throw new CheckedException("预检物料未启用或当前工厂主数据不可核验");
		}
		var query = stockCandidates(request, factory, java.time.LocalDate.now(), null);
		query.lambda().eq(feedSources && StrUtil.isNotBlank(request.getWarehouseCode()),
				MesWarehouseLiveStockEntity::getWarehouseCode, request.getWarehouseCode());
		var invalid = query.clone();
		invalid.lambda().and(w -> w.isNull(MesWarehouseLiveStockEntity::getNum).or()
				.lt(MesWarehouseLiveStockEntity::getNum, BigDecimal.ZERO));
		if (count(invalid) > 0) {
			throw new CheckedException("匹配库存存在未知或负数量，不能将其忽略后认定齐套");
		}
		query.lambda().gt(MesWarehouseLiveStockEntity::getNum, BigDecimal.ZERO);
		query.orderByAsc("id").last("LIMIT 5001");
		var rows = list(query);
		if (rows.size() > 5000) {
			throw new CheckedException("预检候选超过5000行，须收窄范围而非截断后认定齐套");
		}
		boolean fifo = Boolean.TRUE.equals(material.getFifo());
		String field = resolveFifoField(fifo, material.getFifoField());
		if (rows.stream().anyMatch(
				r -> !Objects.equals(r.getMatUnit(), material.getBasicUnit()) || StrUtil.isBlank(r.getMatUnit()))) {
			throw new CheckedException("候选库存单位与物料基本单位不一致，不能隐式换算");
		}
		if (fifo && rows.stream().anyMatch(r -> fifoTime(r, field) == null)) {
			throw new CheckedException("FIFO候选缺少规定日期，不能预判为可扣减");
		}
		rows.sort(Comparator.comparing((MesWarehouseLiveStockEntity r) -> fifoTime(r, field),
						Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(MesWarehouseLiveStockEntity::getInTime, Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(MesWarehouseLiveStockEntity::getId));
		return rows;
	}

	/**
	 * 线边库实时库存出库
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean outStock(MesWarehouseLiveStockInoutDTO liveStock) {
		applyStockOut(liveStock, null);
		return Boolean.TRUE;
	}

	/**
	 * 内部业务须持有自己的事务；严格原单位回执不是独立业务执行许可。
	 */
	@Override
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public MesWarehouseStockOutReceiptDTO outStockWithReceipt(MesWarehouseLiveStockInoutDTO request,
	                                                          MesWarehouseStockOutReceiptDTO.Lineage lineage) {
		if (lineage == null) {
			throw new CheckedException("内部原单位出库回执必须明确完整的库存预留谱系");
		}
		List<MesWarehouseStockOutReceiptDTO.Line> rows = applyStockOut(request, lineage);
		return new MesWarehouseStockOutReceiptDTO("STOCK_LINE_V1", TenantContextHolder.getTenantId(),
				requireStockScope(request), request.getBillId(), request.getBillNo(), request.getBizType(),
				request.getMatCode(), request.getMatUnit(), request.getNum(), rows);
	}

	/**
	 * 唯一真实扣库内核。旧Boolean调用保留既有计量合同；新回执调用必须核实际原单位。
	 * 原谱系仅来自内部调用，不读取客户端skip字段，也不改变资格、锁序或FIFO规则。
	 */
	private List<MesWarehouseStockOutReceiptDTO.Line> applyStockOut(
			MesWarehouseLiveStockInoutDTO liveStock, MesWarehouseStockOutReceiptDTO.Lineage lineage) {
		boolean nativeReceipt = lineage != null;
		beforeInCheck(liveStock);
		String factory = requireStockScope(liveStock);
		var materialResponse = remoteMaterialService.getDetails(liveStock.getMatCode());
		MdmMaterialEntity mdmMaterial = materialResponse == null ? null : materialResponse.getData();
		if (materialResponse == null || !materialResponse.isOk() || mdmMaterial == null
				|| !Objects.equals(mdmMaterial.getMaterialCode(), liveStock.getMatCode())
				|| !Objects.equals(mdmMaterial.getTenantId(), TenantContextHolder.getTenantId())
				|| !Objects.equals(mdmMaterial.getFactoryCode(), factory)) {
			throw new CheckedException("MDM物料及实际工厂归属不可核验，不能猜测库存选择规则");
		}
		if (nativeReceipt && (!Boolean.TRUE.equals(mdmMaterial.getStatus())
				|| StrUtil.isBlank(liveStock.getMatUnit())
				|| !Objects.equals(liveStock.getMatUnit(), mdmMaterial.getBasicUnit()))) {
			throw new CheckedException("真实出库回执要求已启用物料及明确一致的原单位，禁止隐式换算");
		}
		if (nativeReceipt) {
			proofCodec.quantity(proofCodec.amount(liveStock.getNum()), false, "实际出库原量");
		}
		boolean fifo = Boolean.TRUE.equals(mdmMaterial.getFifo());
		String orderByField = resolveFifoField(fifo, mdmMaterial.getFifoField());
		java.time.LocalDate stockDate = java.time.LocalDate.now();
		String policy = cn.hutool.json.JSONUtil.toJsonStr(Map.of("version", "STOCK_LINE_V1", "fifo", fifo,
				"field", orderByField, "materialId", Objects.toString(mdmMaterial.getId(), ""),
				"materialUpdatedAt", Objects.toString(mdmMaterial.getUpdateTime(), ""),
				"tieBreak", "in_time NULLS LAST, id", "observedAt", LocalDateTime.now().toString(),
				"qualityBlockedAllowed", Boolean.TRUE.equals(liveStock.getAllowQualityBlocked()), "validOn",
				stockDate.toString(),
				"fifoDeclared", Objects.toString(mdmMaterial.getFifo(), "UNDECLARED")));
		if (nativeReceipt && lineage.stockId() != null) {
			var pinnedPolicy = cn.hutool.json.JSONUtil.parseObj(policy);
			pinnedPolicy.set("selectionScope", "PINNED_PLANNED_STOCK");
			pinnedPolicy.set("selectedStockId", lineage.stockId().toString());
			policy = pinnedPolicy.toString();
		}
		// 查找库存并扣除
		QueryWrapper<MesWarehouseLiveStockEntity> queryWrapper = stockCandidates(liveStock, factory, stockDate,
				lineage);
		if (nativeReceipt) {
			if (lineage.stockId() != null && lineage.stockId() <= 0) {
				throw new CheckedException("精确出库来源主键无效，不能降级为自动选料");
			}
			// 明确无预留也必须过滤；SQL NULL不等于空文本，更不等于“任意预留”。
			queryWrapper.lambda()
					.isNull(lineage.reservationNo() == null, MesWarehouseLiveStockEntity::getReservationNo)
					.eq(lineage.reservationNo() != null, MesWarehouseLiveStockEntity::getReservationNo,
							lineage.reservationNo())
					.isNull(lineage.requirementMaterialCode() == null,
							MesWarehouseLiveStockEntity::getRequirementMatCode)
					.eq(lineage.requirementMaterialCode() != null,
							MesWarehouseLiveStockEntity::getRequirementMatCode, lineage.requirementMaterialCode())
					.eq(lineage.stockId() != null, MesWarehouseLiveStockEntity::getId, lineage.stockId());
		}
		var invalidQuantity = queryWrapper.clone();
		invalidQuantity.lambda().and(w -> w.isNull(MesWarehouseLiveStockEntity::getNum).or()
				.lt(MesWarehouseLiveStockEntity::getNum, BigDecimal.ZERO));
		if (count(invalidQuantity) > 0) {
			throw new CheckedException("匹配库存含空数量或负数量，须先治理库存账，不能忽略后继续扣减");
		}
		queryWrapper.lambda().gt(MesWarehouseLiveStockEntity::getNum, BigDecimal.ZERO);
		// 锁按主键排序，业务分配随后按白名单规则排序；主数据字符串永不拼入SQL排序表达式。
		queryWrapper.orderByAsc("id").last(nativeReceipt ? "LIMIT 5001 FOR UPDATE NOWAIT" : "LIMIT 5001 FOR UPDATE");
		List<MesWarehouseLiveStockEntity> list = list(queryWrapper);
		if (list.size() > 5000) {
			throw new CheckedException("候选库存超过5000行，请缩小物料批次/任务范围，不能截断后声称库存不足");
		}
		if (list.size() == 0) {
			throw new CheckedException("库存中未查找到物料[" + liveStock.getMatCode() + "]");
		}
		BigDecimal matSum = list.stream()
				.map(MesWarehouseLiveStockEntity::getNum)
				.filter(Objects::nonNull)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal outNum = liveStock.getNum();
		if (outNum.compareTo(matSum) > 0) {
			throw new CheckedException("库存不足！");
		}
		if (nativeReceipt && list.stream().anyMatch(stock ->
				!Objects.equals(stock.getMatUnit(), liveStock.getMatUnit()))) {
			throw new CheckedException("实际候选库存与请求原单位不一致，不能合并不同单位后扣减");
		}
		if (fifo && list.stream().anyMatch(stock -> fifoTime(stock, orderByField) == null)) {
			throw new CheckedException("FIFO候选缺少[" + orderByField + "]日期，须治理来源后再扣减，不能猜测先后");
		}
		list.sort(Comparator.comparing((MesWarehouseLiveStockEntity stock) -> fifoTime(stock, orderByField),
						Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(MesWarehouseLiveStockEntity::getInTime, Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(MesWarehouseLiveStockEntity::getId));
		var actualRows = new ArrayList<MesWarehouseStockOutReceiptDTO.Line>();
		Set<Long> receiptIds = list.stream().map(MesWarehouseLiveStockEntity::getMaterialReceiptId)
				.filter(Objects::nonNull).collect(Collectors.toSet());
		Map<Long, MesMaterialReceiptEntity> receipts = receiptIds.isEmpty() ? Map.of()
				: baseMapper.receiptSources(TenantContextHolder.getTenantId(), factory, receiptIds).stream()
						.collect(Collectors.toMap(MesMaterialReceiptEntity::getId, receipt -> receipt));
		for (MesWarehouseLiveStockEntity stock : list) {
			BigDecimal deductNum = stock.getNum().min(outNum);
			if (stock.getMaterialReceiptId() != null) {
				verifyIssuedPiece(stock, receipts.get(stock.getMaterialReceiptId()), liveStock, deductNum);
			}
			BigDecimal after = stock.getNum().subtract(deductNum);
			if (nativeReceipt) {
				proofCodec.quantity(proofCodec.amount(stock.getNum()), false, "源库存操作前量");
				proofCodec.quantity(proofCodec.amount(deductNum), false, "逐源实际扣量");
				proofCodec.quantity(proofCodec.amount(after), true, "源库存操作后量");
			}
			// 创建出库流水
			MesWarehouseInoutEntity inout = new MesWarehouseInoutEntity();
			BeanUtil.copyProperties(stock, inout, "id", "createTime", "createBy", "updateTime", "updateBy", "delFlag");
			inout.setBillId(liveStock.getBillId());
			inout.setBillNo(liveStock.getBillNo());
			inout.setResourceNo(liveStock.getResourceNo());
			inout.setProduceTaskCode(Boolean.TRUE.equals(liveStock.getAllowUnassignedProduceTask())
					? stock.getProduceTaskCode()
					: StrUtil.blankToDefault(liveStock.getProduceTaskCode(), stock.getProduceTaskCode()));
			inout.setRequirementMatCode(liveStock.getRequirementMatCode());
			inout.setRequirementMatName(liveStock.getRequirementMatName());
			inout.setSubstituteFlag(liveStock.getSubstituteFlag());
			inout.setSubstituteRatio(liveStock.getSubstituteRatio());
			inout.setBizType(liveStock.getBizType());
			inout.setMatDetailType(StrUtil.blankToDefault(liveStock.getMatDetailType(), stock.getMatDetailType()));
			inout.setOperateType(InOutEnum.OUT.getCode());
			inout.setOperateNum(deductNum);
			inout.setTenantId(stock.getTenantId());
			inout.setFactoryCode(stock.getFactoryCode());
			inout.setSourceStockId(stock.getId());
			inout.setSourceReportId(stock.getSourceReportId());
			inout.setStockBeforeNum(stock.getNum());
			inout.setStockAfterNum(after);
			inout.setStockProofVersion("STOCK_LINE_V1");
			inout.setAllocationPolicy(policy);
			boolean updated = update(new LambdaUpdateWrapper<MesWarehouseLiveStockEntity>()
					.eq(MesWarehouseLiveStockEntity::getId, stock.getId())
					.eq(MesWarehouseLiveStockEntity::getFactoryCode, factory)
					.eq(MesWarehouseLiveStockEntity::getNum, stock.getNum())
					.set(MesWarehouseLiveStockEntity::getNum, after)
					.set(after.signum() == 0, MesWarehouseLiveStockEntity::getDelFlag, "1"));
			if (!updated) {
				throw new CheckedException("库存已被其他业务更新，请刷新后重试");
			}
			var persisted = baseMapper.selectOutProof(stock.getId(), stock.getTenantId(), factory);
			if (persisted == null || persisted.getNum() == null || persisted.getNum().compareTo(after) != 0
					|| !Objects.equals(persisted.getDelFlag(), after.signum() == 0 ? "1" : stock.getDelFlag())) {
				throw new CheckedException("出库数量或归零归档回读不一致，已回滚，禁止静默舍入");
			}
			if (mesWarehouseInoutMapper.insert(inout) != 1) {
				throw new CheckedException("库存扣减证据保存失败");
			}
			// 必须从本次已知主键回读；SQL字段精度不足或来源被改写不能返回“成功”。
			MesWarehouseInoutEntity persistedFlow = mesWarehouseInoutMapper.selectById(inout.getId());
			var expectedLine = stockOutLine(inout);
			if (persistedFlow == null || !InOutEnum.OUT.getCode().equals(persistedFlow.getOperateType())
					|| !"0".equals(persistedFlow.getDelFlag())
					|| !expectedLine.equals(stockOutLine(persistedFlow))) {
				throw new CheckedException("实际OUT流水数量、单位或来源回读不一致，已回滚");
			}
			actualRows.add(stockOutLine(persistedFlow));
			outNum = outNum.subtract(deductNum);
			if (outNum.signum() == 0) {
				break;
			}
		}
		if (outNum.signum() != 0) {
			throw new CheckedException("库存分配数量不守恒，已回滚");
		}
		if (actualRows.stream().map(MesWarehouseStockOutReceiptDTO.Line::quantity)
				.reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(liveStock.getNum()) != 0) {
			throw new CheckedException("实际OUT回执全集与请求数量不守恒，已回滚");
		}
		return List.copyOf(actualRows);
	}

	/**
	 * 固定业务字段，不反射可变实体，也不以当前主数据重新解释原流水。
	 */
	private MesWarehouseStockOutReceiptDTO.Line stockOutLine(MesWarehouseInoutEntity row) {
		return new MesWarehouseStockOutReceiptDTO.Line(row.getId(), row.getSourceStockId(),
				row.getSourceReportId(), row.getMaterialReceiptId(), row.getTenantId(), row.getFactoryCode(),
				row.getBillId(), row.getBillNo(), row.getResourceNo(), row.getBizType(), row.getProduceTaskCode(),
				row.getMatCode(), row.getMatUnit(), row.getMatBatch(), row.getMatBarcode(), row.getReservationNo(),
				row.getRequirementMatCode(), row.getSubstituteFlag(), row.getSubstituteRatio(), row.getOperateNum(),
				row.getStockBeforeNum(), row.getStockAfterNum(), row.getStockProofVersion(), row.getAllocationPolicy());
	}

	private String requireStockScope(MesWarehouseLiveStockInoutDTO command) {
		String factory = FactoryContextHolder.getFactoryCode();
		if (TenantContextHolder.getTenantId() == null || TenantContextHolder.getTenantSkip()
				|| Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) || StrUtil.isBlank(factory)
				||
				(StrUtil.isNotBlank(command.getFactoryCode()) && !Objects.equals(factory, command.getFactoryCode()))) {
			throw new CheckedException("库存业务缺少实际租户/工厂上下文或请求跨厂");
		}
		return factory;
	}

	/**
	 * 原签收确认的单件在离开线边库存时必须整件流转。手工投料还须核对本次选择的条码，
	 * 避免同批不同 SN 的实物按 FIFO 替代。旧签收保留原有规则，不能按条码外观补判历史。
	 */
	private void verifyIssuedPiece(MesWarehouseLiveStockEntity stock, MesMaterialReceiptEntity receipt,
	                               MesWarehouseLiveStockInoutDTO request, BigDecimal deduction) {
		if (receipt == null || !JSONUtil.isTypeJSONObject(receipt.getSourceSnapshot())) {
			throw new CheckedException("线边库存 " + stock.getId() + " 的原签收快照缺失，请核对收料记录");
		}
		var snapshot = JSONUtil.parseObj(receipt.getSourceSnapshot());
		Object serial = snapshot.get("issuedAsSerial");
		if (serial == null || Boolean.FALSE.equals(serial)) {
			return;
		}
		if (!Boolean.TRUE.equals(serial) || receipt.getNoticeId() == null || receipt.getWmsOutDetailId() == null
				|| !receipt.getNoticeId().toString().equals(snapshot.getStr("id"))
				|| !receipt.getWmsOutDetailId().toString().equals(snapshot.getStr("wmsOutDetailId"))
				|| !Objects.equals(snapshot.getStr("matCode"), stock.getMatCode())
				|| !Objects.equals(snapshot.getStr("matUnit"), stock.getMatUnit())
				|| !Objects.equals(snapshot.getStr("matBatch"), stock.getMatBatch())
				|| StrUtil.isBlank(stock.getMatBarcode()) || !stock.getMatBarcode().equals(snapshot.getStr("matBarcode"))
				|| stock.getNum().compareTo(BigDecimal.ONE) != 0 || deduction.compareTo(BigDecimal.ONE) != 0) {
			throw new CheckedException("出库单件与原签收实物不一致或数量被拆分，请核对原序列号");
		}
		if (WarehouseOutBizTypeEnum.FEEDING.getCode().equals(request.getBizType())
				&& !stock.getMatBarcode().equals(request.getMatBarcode())) {
			throw new CheckedException("单件原料投料须选择或扫描原签收条码");
		}
	}

	private String resolveFifoField(boolean fifo, String configured) {
		if (!fifo) {
			return "in_time";
		}
		return switch (Objects.toString(configured, "").trim()) {
			case "in_time", "inTime" -> "in_time";
			case "create_time", "createTime" -> "create_time";
			case "mat_produce_date", "matProduceDate" -> "mat_produce_date";
			case "mat_valid_end_date", "matValidEndDate" -> "mat_valid_end_date";
			default -> throw new CheckedException("不支持的FIFO字段，请配置入库时间、建账时间、生产日期或有效截止日期");
		};
	}

	private LocalDateTime fifoTime(MesWarehouseLiveStockEntity stock, String field) {
		return switch (field) {
			case "create_time" -> stock.getCreateTime();
			case "mat_produce_date" ->
					stock.getMatProduceDate() == null ? null : stock.getMatProduceDate().atStartOfDay();
			case "mat_valid_end_date" ->
					stock.getMatValidEndDate() == null ? null : stock.getMatValidEndDate().atStartOfDay();
			default -> stock.getInTime();
		};
	}

	/**
	 * 生产退料分页
	 *
	 * @param pageParam
	 * @param query
	 * @return
	 */
	@Override
	public IPage<MesWarehouseFeedDTO> getPageFeedBack(Page<MesWarehouseFeedDTO> pageParam, MesWarehouseFeedDTO query) {
		query.setFactoryCode(factoryAccess.require("read"));
		return baseMapper.getPageFeedBack(pageParam, query);
	}

	/**
	 * 分页查询-线边库类型（原材料、在制品、半成品、成品）
	 *
	 * @param page         分页对象
	 * @param mesLiveStock 实时库存
	 * @return
	 */
	@Override
	public Page<MesWarehouseLiveStockVO> getWmsLiveStockPageByMatType(Page page, MesLiveStockQueryDTO mesLiveStock) {
		if (MesWarehouseMatDetailTypeEnum.RAW.name().equals(mesLiveStock.getMatDetailType())) {
			return baseMapper.getWmsLiveStockPageByMatRaw(page, mesLiveStock);
		}
		return baseMapper.getWmsLiveStockPageByMatOther(page, mesLiveStock);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean updateManual(MesWarehouseLiveStockEntity stock) {
		if (stock == null || stock.getId() == null) {
			throw new CheckedException("库存记录ID不能为空");
		}
		if (stock.getQualityCaseId() != null || stock.getQualityOriginCaseId() != null ||
				stock.getQualityDispositionReceiptId() != null || stock.getQualityReleaseReceiptId() != null ||
				stock.getPhysicalLocationCode() != null || stock.getPhysicalLocationName() != null ||
				stock.getPhysicalConfirmedTime() != null) {
			throw new CheckedException("现场库位确认须由实际转运交接生成，不得手工修改");
		}
		MesWarehouseLiveStockEntity persisted = this.getOne(new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
				.eq(MesWarehouseLiveStockEntity::getId, stock.getId()).last("FOR UPDATE"));
		if (persisted == null) {
			throw new CheckedException("库存记录不存在或已删除");
		}
		if (persisted.getSourceReportId() != null || persisted.getMaterialReceiptId() != null
				|| stock.getSourceReportId() != null || stock.getMaterialReceiptId() != null) {
			throw new CheckedException("报工/签收来源库存不可直接修改或伪造来源，请通过质量同步、冲销或受控库存调整处理");
		}
		return this.updateById(stock);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean removeManual(Long[] ids) {
		if (ids == null || ids.length == 0) {
			throw new CheckedException("请选择库存记录");
		}
		List<MesWarehouseLiveStockEntity> locked = this.list(new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
				.in(MesWarehouseLiveStockEntity::getId, Arrays.asList(ids))
				.orderByAsc(MesWarehouseLiveStockEntity::getId).last("FOR UPDATE"));
		if (locked.stream().anyMatch(s -> s.getSourceReportId() != null || s.getMaterialReceiptId() != null)) {
			throw new CheckedException("报工/签收来源库存不可直接删除，请通过业务冲销或受控库存调整处理");
		}
		return this.removeBatchByIds(Arrays.asList(ids));
	}
}
