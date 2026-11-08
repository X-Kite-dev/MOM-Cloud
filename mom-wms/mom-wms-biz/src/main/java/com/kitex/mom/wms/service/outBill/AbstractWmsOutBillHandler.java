package com.kitex.mom.wms.service.outBill;

import com.kitex.mom.wms.service.impl.externalinventory.ExternalLossContext;
import cn.hutool.core.bean.BeanUtil;
import com.kitex.mom.wms.service.special.WmsSpecialExecutionContext;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.dto.app.WmsOutBillDetailAppDTO;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.*;
import com.kitex.mom.wms.util.WmsQuantityMath;
import com.kitex.mom.wms.util.WmsOutboundStockIdentity;
import com.kitex.mom.wms.util.WmsStockAvailability;
import com.kitex.mom.wms.util.WmsStockOwnership;
import com.kitex.mom.wms.vo.WmsOutBillDetailExcelVO;
import com.kitex.mom.wms.vo.WmsOutBillExcelVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 出库单处理器抽象实现 提供通用的业务逻辑实现，子类可以通过继承该类来实现特定类型的出库单处理
 *
 * @author kitex
 * @since 2024-12-16 14:28:29
 */

public abstract class AbstractWmsOutBillHandler implements WmsOutBillHandler<WmsOutBillEntity, WmsOutBillDetailEntity> {

	@Autowired
	protected WmsOutBillMapper wmsOutBillMapper;
	@Autowired
	protected WmsOutBillDetailMapper wmsOutBillDetailMapper;

	@Autowired
	protected WmsOutNoticeMapper wmsOutNoticeMapper;
	@Autowired
	protected WmsOutNoticeDetailMapper wmsOutNoticeDetailMapper;

	@Autowired
	private WmsLiveStockService wmsLiveStockService;
	@Autowired
	private WmsPickingAreaService wmsPickingAreaService;
	@Autowired
	private WmsInoutFlowMapper wmsInoutFlowMapper;

	@Autowired
	private WmsDeliverGoodsBaseService wmsDeliverGoodsBaseService;

	@Autowired
	private RemoteSeqConfigService remoteSeqConfigService;

	@Autowired
	private WmsMaterialReservationService materialReservationService;
	@Autowired
	private WmsSalesStockReservationService salesStockReservationService;

	@Autowired
	private WmsWarehouseBusinessGuard warehouseBusinessGuard;

	private static boolean nonnegativeOutboundQuantity(Double value) {
		return value != null && Double.isFinite(value) && value >= 0;
	}

	private static void requireOutbound(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean saveDeep(WmsOutBillEntity entity) {
		// 1、保存前的校验和处理
		beforeSaveDeep(entity);

		// 2、执行保存
		doSaveDeep(entity);

		return Boolean.TRUE;
	}

	/**
	 * 保存前的处理
	 *
	 * @param entity 出库单
	 */
	public void beforeSaveDeep(WmsOutBillEntity entity) {
		ExternalLossContext.guardHead(entity);
		if (entity != null) {
			WmsSpecialExecutionContext.check(entity.getBillType(), entity.getDocumentId(),
					entity.getDocumentTable());
		}
		// 1. 校验数据完整性
		checkData(entity);
		validateAndBindStockSources(entity);

		// 预留批次可能在页面打开后发生质量漂移或受控重配，落单前必须在同一事务内重新锁定复核。
		materialReservationService.validateOutboundReservation(entity.getDetails());
		salesStockReservationService.validateOutboundReservation(entity.getDetails());

		// 2. 校验仓库是否支持该业务
		Assert.isTrue(Boolean.TRUE.equals(checkWarehouse(entity.getWarehouseCode(), entity.getBillType())),
				"仓库不支持当前业务");

		// 3. 设置默认状态
		setDefaultState(entity);

		// 4. 设置初始数据
		if (StrUtil.isBlank(entity.getBillNo())) {
			R<String> wms_out_bill_R = remoteSeqConfigService.create("wms_out_bill");
			requireOutbound(wms_out_bill_R != null && wms_out_bill_R.isOk()
							&& StrUtil.isNotBlank(wms_out_bill_R.getData()),
					"生成出库单号失败！" + (wms_out_bill_R == null ? "" : wms_out_bill_R.getMsg()));
			String billNo = wms_out_bill_R.getData();
			entity.setBillNo(billNo);
		}

		entity.setDetailFinishNum(0d);
		entity.setDetailNotFinishNum(Double.parseDouble(String.valueOf(entity.getDetails().size())));
	}

	/**
	 * 执行保存单据
	 *
	 * @param entity 出库单
	 * @return 通知单未出库总数
	 */
	public Double doSaveDeep(WmsOutBillEntity entity) {
		// 1、 保存主表数据
		int result = wmsOutBillMapper.insert(entity);
		if (result != 1) {
			throw new CheckedException("保存出库单失败");
		}

		// 2、 保存明细数据
		List<WmsOutBillDetailEntity> detailList = entity.getDetails();
		if (CollUtil.isNotEmpty(detailList)) {
			// 每条明细都必须确认写入，批量方法的调用完成不等于全部行已保存。
			for (WmsOutBillDetailEntity detail : detailList) {
				detail.setBillId(entity.getId())
						.setFactoryCode(entity.getFactoryCode())
						.setWarehouseCode(entity.getWarehouseCode())
						.setWarehouseName(entity.getWarehouseName());
				requireOutbound(wmsOutBillDetailMapper.insert(detail) == 1, "出库明细新增未确认，出库整体回滚");
			}
		}

		// 3、更新通知单数量
		return createBillHandleNoticeOutNum(entity);
	}

	/**
	 * 新增出库单并审核通过
	 *
	 * @param outBill 出库单信息（包含明细）
	 * @return true-成功，false-失败
	 */
	@Override
	@Transactional
	public Boolean addAndPass(WmsOutBillEntity outBill) {
		// 1、保存前的处理
		beforeSaveDeep(outBill);
		// 2、设置状态、明细数、出库时间
		LocalDateTime now = LocalDateTime.now();
		List<WmsOutBillDetailEntity> details = outBill.getDetails();
		outBill.setState(InOutBillStateEnum.PASSED.getCode());
		outBill.setDetailFinishNum(Double.parseDouble(String.valueOf(details.size())));
		outBill.setDetailNotFinishNum(0d);
		outBill.setOutTime(now);
		outBill.setReviewer(SecurityUtils.getUser().getUsername());
		outBill.setReviewTime(now);
		// 3、执行保存
		doSaveDeep(outBill);
		// 4、判断通知单物料是否已全部完成出库
		passBillHandleNoticeState(outBill);
		// 5、扣减实时库存、生成出库流水记录
		afterOutStock(outBill);

		// 6、对documentId的单据进行操作
		handleDocumentBizWhenAdd(outBill);

		try {
			// 如果来源于发货，更新发货数据
			if (DocumentEnum.DELIVER.getCode().equals(outBill.getDocumentTable())) {
				wmsDeliverGoodsBaseService.updateDeliverByBill(outBill);
			}
		} catch (Exception e) {
			throw new CheckedException("更新发货信息失败！原因：" + e.getMessage(), e);
		}

		return Boolean.TRUE;
	}

	@Override
	public void setDefaultState(WmsOutBillEntity entity) {
		// 默认状态为待提交
		entity.setState(InOutBillStateEnum.PENDING_COMMIT.getCode());
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean updateDeep(WmsOutBillEntity entity) {
		Assert.notNull(entity, "出库单不能为空");
		Assert.notNull(entity.getId(), "出库单ID不能为空");
		wmsOutBillMapper.lockBill(entity.getId());
		WmsOutBillEntity existingNotice = wmsOutBillMapper.selectById(entity.getId());
		Assert.notNull(existingNotice, "出库单不存在");
		if (existingNotice.getNoticeId() != null) {
			throw new CheckedException("关联通知单的出库单须删除草稿并重新创建，不能直接修改明细");
		}

		NoticeStateEnum currentState = NoticeStateEnum.getByCode(existingNotice.getState());
		Assert.notNull(currentState, "出库单状态无效");
		Assert.isTrue(currentState.isEditable(), "当前状态不允许修改");

		if (existingNotice.getNoticeId() == null) {
			existingNotice.setWarehouseCode(entity.getWarehouseCode());
			existingNotice.setWarehouseName(entity.getWarehouseName());
		}
		existingNotice.setWorkshop(entity.getWorkshop());
		existingNotice.setRemark(entity.getRemark());
		existingNotice.setOutTime(entity.getOutTime());
		existingNotice.setDetails(entity.getDetails());
		checkData(existingNotice);
		validateAndBindStockSources(existingNotice);
		Assert.isTrue(Boolean.TRUE.equals(checkWarehouse(
						existingNotice.getWarehouseCode(), existingNotice.getBillType())),
				"仓库不支持当前业务");

		int result = wmsOutBillMapper.update(Wrappers.<WmsOutBillEntity>lambdaUpdate()
				.set(WmsOutBillEntity::getWarehouseCode, existingNotice.getWarehouseCode())
				.set(WmsOutBillEntity::getWarehouseName, existingNotice.getWarehouseName())
				.set(WmsOutBillEntity::getWorkshop, existingNotice.getWorkshop())
				.set(WmsOutBillEntity::getRemark, existingNotice.getRemark())
				.set(WmsOutBillEntity::getOutTime, existingNotice.getOutTime())
				.eq(WmsOutBillEntity::getId, existingNotice.getId())
				.eq(WmsOutBillEntity::getState, InOutBillStateEnum.PENDING_COMMIT.getCode()));
		if (result != 1) {
			throw new CheckedException("更新出库单失败");
		}

		// 4. 更新明细数据
		if (CollUtil.isNotEmpty(entity.getDetails())) {
			updateDetails(entity.getId(), entity.getDetails());
		}

		return Boolean.TRUE;
	}

	@Override
	public Page<WmsOutBillEntity> page(Page page, QueryWrapper<WmsOutBillEntity> wrapper) {
		return wmsOutBillMapper.selectPage(page, wrapper);
	}

	@Override
	public Map<String, Long> getCount(QueryWrapper<WmsOutBillEntity> wrapper) {
		return wmsOutBillMapper.selectList(wrapper)
				.stream()
				.collect(Collectors.groupingBy(WmsOutBillEntity::getState, Collectors.counting()));
	}

	@Override
	public Page<WmsOutBillDetailEntity> pageDetails(Page page, WmsInBillDetailQueryDTO params) {
		// 先在Java边界核完整通知号，避免格式错误被MyBatis表达式包装成系统异常。
		params.noticeIdValue();
		return wmsOutBillDetailMapper.fetchDetails(page, params, null);
	}

	@Override
	public WmsOutBillEntity selectById(Long id) {
		// 1. 查询主表数据
		WmsOutBillEntity notice = wmsOutBillMapper.selectById(id);
		if (notice == null) {
			return null;
		}

		// 2. 查询明细数据
		List<WmsOutBillDetailEntity> details = wmsOutBillDetailMapper.selectList(
				Wrappers.<WmsOutBillDetailEntity>lambdaQuery()
						.eq(WmsOutBillDetailEntity::getBillId, id));
		// 3. 查找库存量
		List<WmsLiveStockEntity> liveStocks = wmsLiveStockService.list(
				new QueryWrapper<WmsLiveStockEntity>()
						.select("mat_code", "supplier_code", "mat_batch", "container_code", "site_code",
								"sum(mat_num) as mat_num")
						.eq("warehouse_code", notice.getWarehouseCode())
						.eq("status", 0)
						.groupBy("mat_code", "supplier_code", "mat_batch", "container_code", "site_code")
						.orderByAsc("supplier_code", "mat_batch", "site_code", "container_code"));
		Map<String, Double> map = liveStocks.stream().collect(Collectors.toMap(
				it -> it.getMatCode() + it.getSupplierCode() + it.getMatBatch() + it.getContainerCode()
						+ it.getSiteCode(),
				WmsLiveStockEntity::getMatNum));
		details.forEach(item -> {
			Double num = Optional.ofNullable(
					map.get(item.getMatCode() + item.getSupplierCode() + item.getMatBatch()
							+ item.getContainerCode() + item.getSiteCode())).orElse(0d);
			item.setMaxOutNum(num);
		});

		notice.setDetails(details);

		return notice;
	}

	@Override
	public WmsOutBillEntity getDeepById(Long id) {
		// 1. 查询主表数据
		WmsOutBillEntity notice = wmsOutBillMapper.selectById(id);
		if (notice == null) {
			return null;
		}
		// 2. 查询明细数据
		List<WmsOutBillDetailEntity> details = wmsOutBillDetailMapper.selectList(
				Wrappers.<WmsOutBillDetailEntity>lambdaQuery()
						.eq(WmsOutBillDetailEntity::getBillId, id));
		notice.setDetails(details);

		if (notice.getNoticeId() != null) {
			WmsOutNoticeEntity sourceNotice = wmsOutNoticeMapper.selectById(notice.getNoticeId());
			if (sourceNotice == null) {
				throw new CheckedException("出库来源通知不存在，无法核验业务单据");
			}
			notice.setDocumentId(sourceNotice.getDocumentId());
			notice.setDocumentTable(sourceNotice.getDocumentTable());
		}

		return notice;
	}

	@Override
	public void checkData(WmsOutBillEntity entity) {
		// 1. 校验必填数据
		if (entity == null || CollUtil.isEmpty(entity.getDetails())) {
			throw new CheckedException("明细数据不能为空");
		}
		Optional.ofNullable(entity.getWarehouseCode())
				.orElseThrow(() -> new CheckedException("仓库编码不能为空"));
		Optional.ofNullable(entity.getBillType()).orElseThrow(() -> new CheckedException("单据类型不能为空"));

		// 2. 校验单据类型是否有效
		Assert.isTrue(
				OutBillTypeEnum.isValid(entity.getBillType()) ||
						OtherOutBillTypeEnum.isValid(entity.getBillType()),
				"无效的单据类型：" + entity.getBillType());

		for (WmsOutBillDetailEntity detail : entity.getDetails()) {
			if (detail == null || StrUtil.isBlank(detail.getMatCode()) ||
					StrUtil.isBlank(detail.getMatUnit())) {
				throw new CheckedException("出库明细物料和标准单位不能为空");
			}
			if (detail.getMatNum() == null || !Double.isFinite(detail.getMatNum())
					|| detail.getMatNum() <= 0D) {
				throw new CheckedException("出库明细标准数量必须为大于0的有限数值");
			}
		}
		checkOutboundPlacements(entity);
	}

	private void checkOutboundPlacements(WmsOutBillEntity bill) {
		requireOutbound(bill != null && CollUtil.isNotEmpty(bill.getDetails()), "出库明细不能为空");
		warehouseBusinessGuard.checkPlacements(bill.getWarehouseCode(), bill.getDetails().stream()
				.map(row -> row == null ? null : new WmsWarehousePlacementGuard.Placement(
						row.getSiteCode(), row.getContainerCode())).toList());
	}

	/**
	 * 落单或提交前核对指定原库存；自有未指定原行仍沿既有FIFO，外部货权保留原来源绑定规则。
	 * 这里只做当前身份预检，不锁库存或占用实物，实际审核仍须锁内重核。
	 */
	private void validateAndBindStockSources(WmsOutBillEntity bill) {
		if (CollUtil.isEmpty(bill.getDetails())) {
			return;
		}
		for (WmsOutBillDetailEntity detail : bill.getDetails()) {
			if (detail == null) {
				throw new CheckedException("出库明细不能为空");
			}
			String ownerType = StrUtil.blankToDefault(detail.getStockOwnerType(), WmsStockOwnership.OWNED)
					.trim();
			if (WmsStockOwnership.OWNED.equals(ownerType)) {
				if (detail.getSourceStockId() != null) {
					validateSpecifiedOwnedStockSource(bill, detail);
				}
				continue;
			}
			Long stockId = detail.getSourceStockId();
			if (stockId == null || stockId <= 0) {
				throw new CheckedException("客供或寄售出库须锁定原库存行");
			}
			WmsLiveStockEntity stock = wmsLiveStockService.getById(stockId);
			Long tenantId = TenantContextHolder.getTenantId();
			String factoryCode = FactoryContextHolder.getFactoryCode();
			if (stock == null || tenantId == null || StrUtil.isBlank(factoryCode)
					|| !Objects.equals(tenantId, stock.getTenantId())
					|| !Objects.equals(factoryCode, stock.getFactoryCode())
					|| (StrUtil.isNotBlank(bill.getFactoryCode())
					&& !Objects.equals(factoryCode, bill.getFactoryCode()))
					|| !Objects.equals(bill.getWarehouseCode(), stock.getWarehouseCode())
					|| !Objects.equals(detail.getMatCode(), stock.getMatCode())
					|| !Objects.equals(detail.getMatUnit(), stock.getMatUnit())
					|| !WmsStockAvailability.isPhysicallyUsable(stock)) {
				throw new CheckedException("出库锁定的外部库存不属于当前仓库、物料或已不可用");
			}
			bill.setFactoryCode(factoryCode);
			detail.setFactoryCode(factoryCode);
			detail.setWarehouseCode(bill.getWarehouseCode());
			detail.setBillType(bill.getBillType());
			WmsStockOwnership.validateOutbound(bill.getBillType(), detail, stock);
		}
	}

	/**
	 * 自有库存只要指定原行就须在占用通知数量前核对完整身份，缺值不能借原库存悄悄补齐。
	 * 不提前锁库存，以免倒置生产预留的预留账→库存锁序；当前可用量及最终扣减仍沿审核合同。
	 */
	private void validateSpecifiedOwnedStockSource(WmsOutBillEntity bill, WmsOutBillDetailEntity detail) {
		Long stockId = detail.getSourceStockId();
		requireOutbound(stockId != null && stockId > 0, "请选择有效的来源库存");
		Long tenantId = TenantContextHolder.getTenantId();
		String factoryCode = FactoryContextHolder.getFactoryCode();
		requireOutbound(tenantId != null && tenantId > 0 && StrUtil.isNotBlank(factoryCode)
				&& (bill.getTenantId() == null || Objects.equals(tenantId, bill.getTenantId()))
				&& (StrUtil.isBlank(bill.getFactoryCode())
				|| Objects.equals(factoryCode, bill.getFactoryCode())), "出库单不属于当前租户和工厂");
		WmsLiveStockEntity stock = wmsLiveStockService.getById(stockId);
		// 组织及仓库沿实际主单设置；供应商、批次、单位、条码和容器保持原提交值。
		bill.setFactoryCode(factoryCode);
		detail.setFactoryCode(factoryCode);
		detail.setWarehouseCode(bill.getWarehouseCode());
		detail.setBillType(bill.getBillType());
		WmsOutboundStockIdentity.requireMatches(detail, stock, tenantId, factoryCode);
		WmsStockOwnership.validateOutbound(bill.getBillType(), detail, stock);
		requireOutbound(WmsStockAvailability.isPhysicallyUsable(stock),
				"所选来源库存已被锁定、过期、待检或质量隔离，请重新选择库存");
	}

	@Override
	public Boolean updateState(Long id, String state) {
		throw new CheckedException("出库单状态须通过提交或审核等业务操作变更");
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean updateDetails(Long id, List<WmsOutBillDetailEntity> details) {
		// 1. 校验通知单状态
		wmsOutBillMapper.lockBill(id);
		WmsOutBillEntity notice = wmsOutBillMapper.selectById(id);
		Assert.notNull(notice, "出库单不存在");
		if (notice.getNoticeId() != null) {
			throw new CheckedException("关联通知单的出库单须删除草稿并重新创建，不能直接修改明细");
		}

		InOutBillStateEnum currentState = InOutBillStateEnum.getByCode(notice.getState());
		Assert.notNull(currentState, "出库单状态无效");
		Assert.isTrue(currentState.isEditable(), "当前状态不允许修改明细");
		notice.setDetails(details);
		checkData(notice);
		validateAndBindStockSources(notice);
		Assert.isTrue(Boolean.TRUE.equals(checkWarehouse(notice.getWarehouseCode(), notice.getBillType())),
				"仓库不支持当前业务");

		// 2. 删除原有明细
		wmsOutBillDetailMapper.delete(
				Wrappers.<WmsOutBillDetailEntity>lambdaQuery()
						.eq(WmsOutBillDetailEntity::getBillId, id));

		// 3. 插入新明细
		for (WmsOutBillDetailEntity detail : details) {
			detail.setBillId(id).setId(null);
			requireOutbound(wmsOutBillDetailMapper.insert(detail) == 1, "更新出库单明细失败");
		}

		return Boolean.TRUE;
	}

	@Override
	public Boolean checkWarehouse(String warehouseCode, String billType) {
		return warehouseBusinessGuard.check(warehouseCode, billType, false);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean removeDeep(String[] ids) {
		if (ids == null || ids.length == 0) {
			return Boolean.TRUE;
		}
		List<Long> billIds = new ArrayList<>(ids.length);
		for (String id : ids) {
			if (StrUtil.isBlank(id) || !StrUtil.isNumeric(id)) {
				throw new CheckedException("出库单ID无效");
			}
			billIds.add(Long.valueOf(id));
		}
		billIds = billIds.stream().distinct().sorted().toList();
		for (Long billId : billIds) {
			wmsOutBillMapper.lockBill(billId);
		}

		// 1. 校验出库单状态
		List<WmsOutBillEntity> entities = wmsOutBillMapper.selectBatchIds(billIds);
		if (entities.size() != billIds.size()) {
			throw new CheckedException("存在已删除或无权访问的出库单");
		}
		for (WmsOutBillEntity entity : entities) {
			InOutBillStateEnum state = InOutBillStateEnum.getByCode(entity.getState());
			Assert.notNull(state, "出库单状态无效");
			Assert.isTrue(state.isEditable(),
					String.format("出库单[%s]当前状态[%s]不允许删除", entity.getBillNo(), state.getDesc()));
		}

		// 2. 回退通知单的数量
		for (WmsOutBillEntity entity : entities) {
			List<WmsOutBillDetailEntity> details = wmsOutBillDetailMapper
					.selectList(Wrappers.<WmsOutBillDetailEntity>lambdaQuery()
							.eq(WmsOutBillDetailEntity::getBillId, entity.getId()));
			entity.setDetails(details);
			removeBillHandleNoticeOutNum(entity);
		}

		// 2. 删除主表数据
		if (wmsOutBillMapper.deleteByIds(billIds) != billIds.size()) {
			throw new CheckedException("删除出库单失败");
		}

		// 3. 删除明细数据
		wmsOutBillDetailMapper.delete(
				Wrappers.<WmsOutBillDetailEntity>lambdaQuery()
						.in(WmsOutBillDetailEntity::getBillId, billIds));

		return Boolean.TRUE;
	}

	/**
	 * 提交出库单
	 *
	 * @param id 出库单id
	 * @return true-成功，false-失败
	 */
	@Override
	public Boolean commitBill(Long id) {
		// 1. 校验出库单状态
		WmsOutBillEntity bill = wmsOutBillMapper.selectById(id);
		Assert.notNull(bill, "出库单不存在");
		InOutBillStateEnum state = InOutBillStateEnum.getByCode(bill.getState());
		Assert.notNull(state, "出库单状态无效");
		Assert.isTrue(state.isEditable(),
				String.format("出库单[%s]当前状态[%s]不允许提交", bill.getBillNo(), state.getDesc()));
		Assert.isTrue(Boolean.TRUE.equals(checkWarehouse(bill.getWarehouseCode(), bill.getBillType())),
				"仓库不支持当前业务");
		bill.setDetails(wmsOutBillDetailMapper.selectList(Wrappers.<WmsOutBillDetailEntity>lambdaQuery()
				.eq(WmsOutBillDetailEntity::getBillId, id)));
		checkOutboundPlacements(bill);
		// 历史草稿同样在进入审核前核对原件；不改写已保存的物流身份或预留数量。
		validateAndBindStockSources(bill);

		// 2. 更新状态为待审核
		if (wmsOutBillMapper.update(new LambdaUpdateWrapper<WmsOutBillEntity>()
				.set(WmsOutBillEntity::getState, InOutBillStateEnum.PENDING_REVIEW.getCode())
				.eq(BaseEntity::getId, id)
				.eq(WmsOutBillEntity::getState, InOutBillStateEnum.PENDING_COMMIT.getCode())) != 1) {
			throw new CheckedException("提交出库单失败，请刷新单据状态");
		}
		return Boolean.TRUE;
	}

	/**
	 * 审核通过出库单
	 *
	 * @param id 出库单id
	 * @return true-成功，false-失败
	 */
	@Override
	@Transactional
	public Boolean passBill(Long id) {
		// 1. 校验出库单状态
		WmsOutBillEntity bill = wmsOutBillMapper.selectOne(Wrappers.<WmsOutBillEntity>lambdaQuery()
				.eq(BaseEntity::getId, id).last("FOR UPDATE"));
		Assert.notNull(bill, "出库单不存在");
		InOutBillStateEnum state = InOutBillStateEnum.getByCode(bill.getState());
		Assert.notNull(state, "出库单状态无效");
		Assert.isTrue(state.isReviewable(),
				String.format("出库单[%s]当前状态[%s]不允许审核", bill.getBillNo(), state.getDesc()));

		Assert.isTrue(Boolean.TRUE.equals(checkWarehouse(bill.getWarehouseCode(), bill.getBillType())),
				"仓库不支持当前业务");

		// 3. 更新状态为已通过
		List<WmsOutBillDetailEntity> billDetails = wmsOutBillDetailMapper.selectList(
				Wrappers.<WmsOutBillDetailEntity>lambdaQuery()
						.eq(WmsOutBillDetailEntity::getBillId, id));
		requireOutbound(CollUtil.isNotEmpty(billDetails), "出库单明细为空，不能审核");
		bill.setDetails(billDetails);
		checkOutboundPlacements(bill);
		// 修改出库单明细数、出库时间和状态
		LocalDateTime now = LocalDateTime.now();
		bill.setState(InOutBillStateEnum.PASSED.getCode());
		bill.setOutTime(now);
		if (wmsOutBillMapper.update(Wrappers.<WmsOutBillEntity>lambdaUpdate()
				.set(WmsOutBillEntity::getState, InOutBillStateEnum.PASSED.getCode())
				.set(WmsOutBillEntity::getDetailFinishNum, billDetails.size())
				.set(WmsOutBillEntity::getDetailNotFinishNum, 0.0)
				.set(WmsOutBillEntity::getOutTime, now)
				.set(WmsOutBillEntity::getReviewer, SecurityUtils.getUser().getUsername())
				.set(WmsOutBillEntity::getReviewTime, now)
				.eq(BaseEntity::getId, id)
				.eq(WmsOutBillEntity::getState, InOutBillStateEnum.PENDING_REVIEW.getCode())) != 1) {
			throw new CheckedException("出库单审核状态更新失败");
		}

		// 2. 更新通知单状态 判断通知单物料是否已全部完成出库
		passBillHandleNoticeState(bill);

		// 4、修改实时库存、生成出库流水记录
		afterOutStock(bill);

		// 5、对documentId的单据进行操作
		handleDocumentBizWhenAdd(bill);

		try {
			// 如果来源于发货，更新发货数据
			if (DocumentEnum.DELIVER.getCode().equals(bill.getDocumentTable())) {
				wmsDeliverGoodsBaseService.updateDeliverByBill(bill);
			}
		} catch (Exception e) {
			throw new RuntimeException(e.getMessage());
		}

		return Boolean.TRUE;
	}

	/**
	 * 创建出库单时修改通知单的出库数
	 *
	 * @param bill 出库单
	 * @return 通知单未出库总数
	 */
	@Transactional(rollbackFor = Exception.class)
	public Double createBillHandleNoticeOutNum(WmsOutBillEntity bill) {
		if (bill.getNoticeId() == null) {
			// 没有通知单的时候直接返回
			return 0d;
		}
		List<WmsOutBillDetailEntity> billDetails = bill.getDetails();
		requireOutbound(CollUtil.isNotEmpty(billDetails), "出库单明细为空，无法占用通知单数量");
		// 判断是否超出通知出库总数
		wmsOutNoticeMapper.lockNotice(bill.getNoticeId());
		WmsOutNoticeEntity notice = Optional.ofNullable(wmsOutNoticeMapper.selectById(bill.getNoticeId()))
				.orElseThrow(() -> new CheckedException("出库通知单查询失败"));
		if (!Objects.equals(notice.getWarehouseCode(), bill.getWarehouseCode())
				|| !Objects.equals(notice.getBillType(), bill.getBillType())) {
			throw new CheckedException("出库单与来源通知单的仓库或业务类型不一致");
		}
		if (!InOutNoticeStateEnum.READY.getCode().equals(notice.getState())
				&& !InOutNoticeStateEnum.RUNNING.getCode().equals(notice.getState())) {
			throw new CheckedException("出库通知单已作废或已完成，不能继续出库");
		}
		List<WmsOutNoticeDetailEntity> noticeDetails = wmsOutNoticeDetailMapper.selectList(
				Wrappers.<WmsOutNoticeDetailEntity>lambdaQuery()
						.eq(WmsOutNoticeDetailEntity::getNoticeId, bill.getNoticeId()));
		Map<Long, WmsOutNoticeDetailEntity> map = noticeDetails.stream()
				.collect(Collectors.toMap(BaseEntity::getId, Function.identity()));
		// 同一通知明细可能对应多条出库明细，累加后仅更新一次。
		Set<Long> updatedSet = new HashSet<>();
		double totalNum = 0d;
		for (WmsOutBillDetailEntity billDetail : billDetails) {
			WmsOutNoticeDetailEntity noticeDetail = Optional.ofNullable(map.get(billDetail.getNoticeDetailId()))
					.orElseThrow(() -> new CheckedException(
							"出库通知单明细[" + billDetail.getNoticeDetailId() + "]查找失败！物料编码：["
									+ billDetail.getMatCode() + "]"));
			if (!matchesNoticeDetail(noticeDetail, billDetail)) {
				throw new CheckedException("出库单明细与来源通知明细的物料、批次或单位不一致");
			}
			if (!WmsStockOwnership.matchesOutboundNotice(noticeDetail, billDetail)) {
				throw new CheckedException("出库单明细与来源通知明细的货权、归属方或锁定库存行不一致");
			}
			Double billNum = Optional.ofNullable(billDetail.getMatNum())
					.orElseThrow(() -> new CheckedException("出库单明细标准单位数量为空"));
			Double noticeNum = Optional.ofNullable(noticeDetail.getNotOutNum())
					.orElseThrow(() -> new CheckedException("出库通知单明细通知未出库量为空"));
			requireOutbound(Double.isFinite(billNum) && billNum > 0
							&& nonnegativeOutboundQuantity(noticeNum)
							&& nonnegativeOutboundQuantity(noticeDetail.getOutNum()),
					"出库单或通知单明细数量无效");
			if (billNum > noticeNum) {
				throw new CheckedException(
						String.format("物料编码为[%s]的物料超出通知出库量！", billDetail.getMatCode()));
			}
			// 修改通知单明细的出库数
			noticeDetail.setNotOutNum(WmsQuantityMath.subtract(noticeNum, billNum));
			noticeDetail.setOutNum(WmsQuantityMath.add(noticeDetail.getOutNum(), billNum));
			updatedSet.add(billDetail.getNoticeDetailId());

			// 累计出库总数
			totalNum = WmsQuantityMath.add(totalNum, billNum);
		}
		Double allNotOutNum = notice.getAllNotOutNum();
		Double allOutNum = notice.getAllOutNum();
		requireOutbound(Double.isFinite(totalNum) && nonnegativeOutboundQuantity(allNotOutNum)
						&& allNotOutNum >= totalNum && nonnegativeOutboundQuantity(allOutNum),
				"出库通知单汇总数量无效或不足");
		// 修改通知单明细
		for (Long noticeDetailId : updatedSet) {
			WmsOutNoticeDetailEntity handDetail = map.get(noticeDetailId);
			requireOutbound(wmsOutNoticeDetailMapper.update(Wrappers.<WmsOutNoticeDetailEntity>lambdaUpdate()
					.set(WmsOutNoticeDetailEntity::getNotOutNum, handDetail.getNotOutNum())
					.set(WmsOutNoticeDetailEntity::getOutNum, handDetail.getOutNum())
					.eq(BaseEntity::getId, noticeDetailId)) == 1, "出库通知明细数量更新未确认，出库整体回滚");
		}

		// 修改通知单总数量
		double remainingTotal = WmsQuantityMath.subtract(allNotOutNum, totalNum);
		double issuedTotal = WmsQuantityMath.add(allOutNum, totalNum);
		requireOutbound(wmsOutNoticeMapper.update(Wrappers.<WmsOutNoticeEntity>lambdaUpdate()
				.set(WmsOutNoticeEntity::getAllNotOutNum, remainingTotal)
				.set(WmsOutNoticeEntity::getAllOutNum, issuedTotal)
				.set(WmsOutNoticeEntity::getState, InOutNoticeStateEnum.RUNNING.getCode())
				.eq(BaseEntity::getId, notice.getId())) == 1, "出库通知总数量更新未确认，出库整体回滚");
		return remainingTotal;
	}

	/**
	 * 删除出库单时修改通知单的出库数
	 *
	 * @param bill 出库单
	 */
	@Transactional(rollbackFor = Exception.class)
	public void removeBillHandleNoticeOutNum(WmsOutBillEntity bill) {
		if (bill.getNoticeId() == null) {
			// 没有通知单的时候直接返回
			return;
		}
		List<WmsOutBillDetailEntity> billDetails = bill.getDetails();
		requireOutbound(CollUtil.isNotEmpty(billDetails), "出库单明细为空，无法回退通知单数量");
		// 查找通知单
		wmsOutNoticeMapper.lockNotice(bill.getNoticeId());
		WmsOutNoticeEntity notice = Optional.ofNullable(wmsOutNoticeMapper.selectById(bill.getNoticeId()))
				.orElseThrow(() -> new CheckedException("出库通知单查询失败"));
		List<WmsOutNoticeDetailEntity> noticeDetails = wmsOutNoticeDetailMapper.selectList(
				Wrappers.<WmsOutNoticeDetailEntity>lambdaQuery()
						.eq(WmsOutNoticeDetailEntity::getNoticeId, bill.getNoticeId()));
		Map<Long, WmsOutNoticeDetailEntity> map = noticeDetails.stream()
				.collect(Collectors.toMap(BaseEntity::getId, Function.identity()));
		// 同一通知明细可能对应多条出库明细，累加后仅更新一次。
		Set<Long> updatedSet = new HashSet<>();
		double totalNum = 0d;
		for (WmsOutBillDetailEntity billDetail : billDetails) {
			WmsOutNoticeDetailEntity noticeDetail = map.get(billDetail.getNoticeDetailId());
			if (noticeDetail == null) {
				throw new CheckedException("出库通知单明细[" + billDetail.getNoticeDetailId() + "]查找失败");
			}
			if (!matchesNoticeDetail(noticeDetail, billDetail)) {
				throw new CheckedException("出库单明细与来源通知明细的物料、批次或单位不一致");
			}
			Double billNum = billDetail.getMatNum();
			Double notOutNum = noticeDetail.getNotOutNum();
			Double outNum = noticeDetail.getOutNum();
			requireOutbound(billNum != null && Double.isFinite(billNum) && billNum > 0
							&& nonnegativeOutboundQuantity(notOutNum)
							&& nonnegativeOutboundQuantity(outNum) && outNum >= billNum,
					"出库通知单明细数量异常，无法回退");
			// 修改通知单明细的出库数
			noticeDetail.setNotOutNum(WmsQuantityMath.add(notOutNum, billNum));
			noticeDetail.setOutNum(WmsQuantityMath.subtract(outNum, billNum));
			updatedSet.add(billDetail.getNoticeDetailId());

			// 累计出库总数
			totalNum = WmsQuantityMath.add(totalNum, billNum);
		}
		Double allNotOutNum = notice.getAllNotOutNum();
		Double allOutNum = notice.getAllOutNum();
		requireOutbound(Double.isFinite(totalNum) && nonnegativeOutboundQuantity(allNotOutNum)
						&& nonnegativeOutboundQuantity(allOutNum) && allOutNum >= totalNum,
				"出库通知单汇总数量异常，无法回退");
		// 修改通知单明细
		for (Long noticeDetailId : updatedSet) {
			WmsOutNoticeDetailEntity handDetail = map.get(noticeDetailId);
			requireOutbound(wmsOutNoticeDetailMapper.update(Wrappers.<WmsOutNoticeDetailEntity>lambdaUpdate()
							.set(WmsOutNoticeDetailEntity::getNotOutNum, handDetail.getNotOutNum())
							.set(WmsOutNoticeDetailEntity::getOutNum, handDetail.getOutNum())
							.eq(BaseEntity::getId, noticeDetailId)) == 1,
					"出库通知单明细数量回退失败");
		}

		// 修改通知单总数量
		double remainingTotal = WmsQuantityMath.add(allNotOutNum, totalNum);
		double issuedTotal = WmsQuantityMath.subtract(allOutNum, totalNum);
		requireOutbound(wmsOutNoticeMapper.update(Wrappers.<WmsOutNoticeEntity>lambdaUpdate()
						.set(WmsOutNoticeEntity::getAllNotOutNum, remainingTotal)
						.set(WmsOutNoticeEntity::getAllOutNum, issuedTotal)
						// 已出库数量回退到0时恢复待处理，否则保持处理中。
						.set(WmsOutNoticeEntity::getState,
								issuedTotal == 0 ? InOutNoticeStateEnum.READY.getCode()
										: InOutNoticeStateEnum.RUNNING.getCode())
						.eq(BaseEntity::getId, notice.getId())) == 1,
				"出库通知单汇总数量回退失败");
	}

	private static boolean matchesNoticeDetail(WmsOutNoticeDetailEntity noticeDetail,
	                                           WmsOutBillDetailEntity billDetail) {
		return Objects.equals(StrUtil.trim(noticeDetail.getMatCode()), StrUtil.trim(billDetail.getMatCode()))
				&& (StrUtil.isBlank(noticeDetail.getMatBatch())
				|| Objects.equals(noticeDetail.getMatBatch(), billDetail.getMatBatch()))
				&& (StrUtil.isBlank(noticeDetail.getMatUnit())
				|| Objects.equals(noticeDetail.getMatUnit(), billDetail.getMatUnit()));
	}

	/**
	 * 出库时修改通知单的状态 当通知单内的物料全部出库之后设为已完成（出库单全部完成并且通知单未出库数量为0）
	 *
	 * @param bill 出库单
	 */
	@Transactional(rollbackFor = Exception.class)
	public void passBillHandleNoticeState(WmsOutBillEntity bill) {
		if (bill.getNoticeId() == null) {
			// 没有通知单的时候直接返回
			return;
		}
		// 与作业单创建、删除使用同一通知单锁，避免完成状态覆盖新的数量占用。
		wmsOutNoticeMapper.lockNotice(bill.getNoticeId());
		// 查找还有没有未完成的出库单
		List<WmsOutBillEntity> outBills = wmsOutBillMapper.selectList(new LambdaQueryWrapper<WmsOutBillEntity>()
				.eq(WmsOutBillEntity::getNoticeId, bill.getNoticeId())
				.ne(WmsOutBillEntity::getState, InOutBillStateEnum.PASSED.getCode()) // pc不等于已通过
				.ne(WmsOutBillEntity::getState, InOutBillStateEnum.FINISHED.getCode()) // app不等于已完成
		);
		if (outBills.size() == 0) {
			// 判断通知单未出库数量是否为0。
			WmsOutNoticeEntity notice = Optional.ofNullable(wmsOutNoticeMapper.selectById(bill.getNoticeId()))
					.orElseThrow(() -> new CheckedException("出库通知单查询失败"));
			Double allNotOutNum = notice.getAllNotOutNum();
			requireOutbound(nonnegativeOutboundQuantity(allNotOutNum), "出库通知单未出库数量无效");
			if (allNotOutNum == 0) {
				// 修改通知单状态
				requireOutbound(wmsOutNoticeMapper.update(Wrappers.<WmsOutNoticeEntity>lambdaUpdate()
								.set(WmsOutNoticeEntity::getState, InOutNoticeStateEnum.FINISHED.getCode())
								.set(WmsOutNoticeEntity::getCompleteTime, LocalDateTime.now())
								.eq(BaseEntity::getId, notice.getId())
								.eq(WmsOutNoticeEntity::getState, InOutNoticeStateEnum.RUNNING.getCode())) == 1,
						"出库通知完成状态更新未确认，出库整体回滚");
			}
		}
	}

	/**
	 * 出库通过之后 修改实时库存，生成出库流水
	 */
	@Transactional
	public void afterOutStock(WmsOutBillEntity outBill) {
		ExternalLossContext.guardHead(outBill);
		WmsSpecialExecutionContext.check(outBill.getBillType(), outBill.getDocumentId(),
				outBill.getDocumentTable());
		checkOutboundPlacements(outBill);
		Assert.isTrue(Boolean.TRUE.equals(checkWarehouse(outBill.getWarehouseCode(), outBill.getBillType())),
				"仓库不支持当前业务");
		List<WmsOutBillDetailEntity> details = outBill.getDetails();
		for (WmsOutBillDetailEntity detail : details) {
			detail.setBillType(outBill.getBillType());
			// 处理实时库存 由物料编码、供应商编码、批次、容器码、库位 确定出库的物料
			List<WmsLiveStockEntity> stockOuted = wmsLiveStockService.reduceStockWhenOut(detail);
			if (CollUtil.isEmpty(stockOuted)) {
				throw new CheckedException("出库未形成实际库存扣减证据");
			}
			BigDecimal proofTotal = stockOuted.stream().map(item -> BigDecimal.valueOf(item.getMatNum()))
					.reduce(BigDecimal.ZERO, BigDecimal::add);
			if (detail.getMatNum() == null ||
					proofTotal.compareTo(BigDecimal.valueOf(detail.getMatNum())) != 0) {
				throw new CheckedException("实际库存扣减证据与出库明细数量不守恒");
			}
			freezeActualOutboundQuality(outBill, detail, stockOuted);
			// 一条实际被消耗库存行形成一条不可拆分证据，保留批次、库位、容器、质量与首次入库时间。
			for (WmsLiveStockEntity stock : stockOuted) {
				WmsInoutFlowEntity inoutFlow = new WmsInoutFlowEntity();
				BeanUtil.copyProperties(stock, inoutFlow, "id");
				inoutFlow.setBillId(outBill.getId());
				inoutFlow.setBillDetailId(detail.getId());
				inoutFlow.setSourceStockId(stock.getId());
				inoutFlow.setStockProofType(OutBillTypeEnum.SALES_OUT.getCode().equals(outBill.getBillType())
						? "SALES_SHIPMENT"
						: "OUTBOUND");
				inoutFlow.setOperationRequestId("OUT-STOCK:" + detail.getId() + ":" + stock.getId());
				inoutFlow.setBillNo(outBill.getBillNo());
				inoutFlow.setBillType(outBill.getBillType());
				inoutFlow.setOperateType(InOutEnum.OUT.getCode());
				WmsSpecialExecutionContext.markOutboundFlow(detail, inoutFlow);
				inoutFlow.setStockInTime(stock.getInTime());
				inoutFlow.setExpirationStatus(stock.getExpirationStatus());
				inoutFlow.setSlowMaterialStatus(stock.getSlowMaterialStatus());
				if (wmsInoutFlowMapper.insert(inoutFlow) != 1) {
					throw new CheckedException("出库精确库存证据保存失败");
				}
			}
		}

	}

	/**
	 * 业务族可在真实扣库与标准数量守恒确认后，冻结本次出库明细的源质量结论。
	 * 默认不改变其它业务；实现拒绝时由原出库事务回滚，不重新查询或预锁源库存。
	 *
	 * @param outBill    当前原出库主单
	 * @param detail     本次实际出库明细，质量不能信任客户端草稿
	 * @param stockOuted 同事务锁内的实际扣减快照；matNum为本次扣减量，单位为库存标准单位
	 */
	protected void freezeActualOutboundQuality(WmsOutBillEntity outBill, WmsOutBillDetailEntity detail,
	                                           List<WmsLiveStockEntity> stockOuted) {
	}

	/**
	 * 审核驳回出库单
	 *
	 * @param params 出库单id、驳回原因
	 * @return true-成功，false-失败
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean rejectBill(WmsOutBillEntity params) {
		Long id = params == null ? null : params.getId();
		requireOutbound(id != null, "出库单ID不能为空");
		// 1. 校验出库单状态
		wmsOutBillMapper.lockBill(id);
		WmsOutBillEntity bill = wmsOutBillMapper.selectById(id);
		Assert.notNull(bill, "出库单不存在");
		InOutBillStateEnum state = InOutBillStateEnum.getByCode(bill.getState());
		Assert.notNull(state, "出库单状态无效");
		Assert.isTrue(state.isReviewable(),
				String.format("出库单[%s]当前状态[%s]不允许审核", bill.getBillNo(), state.getDesc()));
		// 2. 更新状态为待提交
		if (wmsOutBillMapper.update(new LambdaUpdateWrapper<WmsOutBillEntity>()
				.set(WmsOutBillEntity::getState, InOutBillStateEnum.PENDING_COMMIT.getCode())
				.set(WmsOutBillEntity::getReviewer, SecurityUtils.getUser().getUsername())
				.set(WmsOutBillEntity::getReviewTime, LocalDateTime.now())
				.set(WmsOutBillEntity::getRejectReason, params.getRejectReason())
				.eq(BaseEntity::getId, id)
				.eq(WmsOutBillEntity::getState, InOutBillStateEnum.PENDING_REVIEW.getCode())) != 1) {
			throw new CheckedException("驳回出库单失败，请刷新单据状态");
		}
		return Boolean.TRUE;
	}

	/**
	 * app出库 提交某一条物料数量，更新出库单出库数量或生成出库单 将物料从库存放到拣货区
	 * <p>
	 * 按原通知ID核对唯一通知明细，再新建或继续同组织、同通知的处理中APP出库单。
	 * <p>
	 * 标准数量按本次增量扣减；未提供操作数量时沿用标准数量，已有操作数量继续累计。
	 *
	 * @param params 物料数量等
	 * @return 本次写入确认的原出库单号
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public String setOutBillNum(WmsOutBillDetailAppDTO params) {
		requireOutbound(params != null && params.getNoticeId() != null && params.getNoticeId() > 0,
				"出库必须指定原通知单ID");
		requireOutbound(params.getNoticeDetailId() == null || params.getNoticeDetailId() > 0,
				"来源出库通知明细无效");
		requireOutbound(
				params.getMatNum() != null && Double.isFinite(params.getMatNum()) && params.getMatNum() > 0
						&& (params.getOperateNum() == null
						|| (Double.isFinite(params.getOperateNum()) && params.getOperateNum() > 0)),
				"出库数量和已提供的操作数量必须为大于0的有限数值");
		requireOutbound(StrUtil.isNotBlank(params.getMatCode()) && StrUtil.isNotBlank(params.getMatBatch())
						&& StrUtil.isNotBlank(params.getSiteCode())
						&& (params.getSourceStockId() == null || params.getSourceStockId() > 0),
				"物料、批次、库位或原库存身份无效");
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		requireOutbound(tenant != null && StrUtil.isNotBlank(factory), "出库必须具有实际租户和工厂上下文");
		// 查找通知单
		wmsOutNoticeMapper.lockNotice(params.getNoticeId());
		WmsOutNoticeEntity notice = Optional.ofNullable(wmsOutNoticeMapper.selectById(params.getNoticeId()))
				.orElseThrow(() -> new CheckedException("通知单查找失败！"));
		requireOutbound(
				Objects.equals(params.getNoticeId(), notice.getId()) &&
						Objects.equals(tenant, notice.getTenantId())
						&& Objects.equals(factory, notice.getFactoryCode())
						&& StrUtil.isNotBlank(notice.getWarehouseCode()),
				"原出库通知单不属于当前实际组织或仓库无效");
		requireOutbound(InOutNoticeStateEnum.READY.getCode().equals(notice.getState())
						|| InOutNoticeStateEnum.RUNNING.getCode().equals(notice.getState()),
				"原通知单状态不允许继续出库");
		requireOutbound(nonnegativeOutboundQuantity(notice.getAllNotOutNum())
						&& nonnegativeOutboundQuantity(notice.getAllOutNum())
						&& params.getMatNum() <= notice.getAllNotOutNum()
						&& Double.isFinite(notice.getAllOutNum() + params.getMatNum()),
				"原通知单数量无效或本次数量超过未出库总量");
		Assert.isTrue(Boolean.TRUE.equals(checkWarehouse(notice.getWarehouseCode(), notice.getBillType())),
				"仓库不支持当前业务");
		warehouseBusinessGuard.checkMobile(notice.getWarehouseCode());
		warehouseBusinessGuard.checkPlacements(notice.getWarehouseCode(), List.of(
				new WmsWarehousePlacementGuard.Placement(params.getSiteCode(), params.getContainerCode())));
		// 通过物料查询通知单明细
		List<WmsOutNoticeDetailEntity> noticeDetails = wmsOutNoticeDetailMapper
				.selectList(new LambdaQueryWrapper<WmsOutNoticeDetailEntity>()
						.eq(WmsOutNoticeDetailEntity::getNoticeId, params.getNoticeId())
						.eq(WmsOutNoticeDetailEntity::getMatCode, params.getMatCode())
						.eq(params.getNoticeDetailId() != null, WmsOutNoticeDetailEntity::getId,
								params.getNoticeDetailId())
						.eq(params.getNoticeDetailId() == null && params.getSourceStockId() != null,
								WmsOutNoticeDetailEntity::getSourceStockId,
								params.getSourceStockId()));
		if (CollUtil.isEmpty(noticeDetails)) {
			throw new CheckedException("通知单明细查找失败！");
		}
		if (noticeDetails.size() > 1) {
			throw new CheckedException("同物料存在多条待出库存，请扫描或选择精确库存行后再出库！");
		}
		WmsOutNoticeDetailEntity noticeDetail = noticeDetails.get(0);
		requireOutbound(
				(params.getNoticeDetailId() == null ||
						Objects.equals(params.getNoticeDetailId(), noticeDetail.getId()))
						&& (noticeDetail.getSourceStockId() == null
						|| Objects.equals(noticeDetail.getSourceStockId(), params.getSourceStockId())),
				"来源通知明细或冻结库存不一致，不能替换批准的出库来源");
		requireOutbound(
				Objects.equals(notice.getId(), noticeDetail.getNoticeId())
						&& Objects.equals(tenant, noticeDetail.getTenantId())
						&& Objects.equals(factory, noticeDetail.getFactoryCode())
						&& Objects.equals(notice.getWarehouseCode(), noticeDetail.getWarehouseCode()),
				"原通知明细的组织或仓库不符");
		requireOutbound(nonnegativeOutboundQuantity(noticeDetail.getNotOutNum())
						&& nonnegativeOutboundQuantity(noticeDetail.getOutNum())
						&& params.getMatNum() <= noticeDetail.getNotOutNum()
						&& Double.isFinite(noticeDetail.getOutNum() + params.getMatNum()),
				"原通知明细数量无效或本次数量超过未出库量");

		WmsOutBillEntity newBill = new WmsOutBillEntity();
		BeanUtil.copyProperties(notice, newBill, "id");
		newBill.setNoticeId(notice.getId());
		WmsOutBillDetailEntity newDetail = new WmsOutBillDetailEntity();
		BeanUtil.copyProperties(noticeDetail, newDetail, "id");
		BeanUtil.copyProperties(params, newDetail, CopyOptions.create().ignoreNullValue());
		newDetail.setNoticeDetailId(noticeDetail.getId());
		newDetail.setOperateUnit(
				params.getOperateUnit() == null ? newDetail.getMatUnit() : params.getOperateUnit());
		newDetail.setOperateNum(
				params.getOperateNum() == null ? newDetail.getMatNum() : params.getOperateNum());
		newBill.setDetails(Collections.singletonList(newDetail));
		// 续单也经过既有业务校验，不能绕过盘点损益等统一上下文门。
		checkData(newBill);

		// 显式原号必须对应本通知、实际组织和仍处理中的APP单，查不到不能另建替代单。
		String originalBillNo = StrUtil.trim(params.getBillNo());
		List<WmsOutBillEntity> outBills = wmsOutBillMapper.selectList(new LambdaQueryWrapper<WmsOutBillEntity>()
				.eq(WmsOutBillEntity::getNoticeId, notice.getId())
				.eq(WmsOutBillEntity::getTenantId, tenant)
				.eq(WmsOutBillEntity::getFactoryCode, factory)
				.eq(StrUtil.isNotBlank(originalBillNo), WmsOutBillEntity::getBillNo, originalBillNo)
				.eq(WmsOutBillEntity::getState, InOutBillStateEnum.APP_RUNNING.getCode())
				.eq(WmsOutBillEntity::getSource, InOutBillSourceEnum.APP.getCode())
				.orderByDesc(BaseCore::getCreateTime));
		requireOutbound(outBills != null && outBills.size() <= 1,
				"存在多张处理中的APP出库单，请核对后指定原单号");
		requireOutbound(StrUtil.isBlank(originalBillNo) || !outBills.isEmpty(),
				"原出库单不存在、已结束或不属于本通知及当前组织");
		WmsOutBillEntity outBill;
		Double noticeNotOutNum;
		if (CollUtil.isEmpty(outBills)) {
			// 新增出库单
			outBill = newBill;
			noticeNotOutNum = saveDeepApp(outBill);
		} else {
			// 修改出库单数量
			outBill = outBills.get(0);
			requireOutbound(
					Objects.equals(notice.getId(), outBill.getNoticeId())
							&& Objects.equals(tenant, outBill.getTenantId())
							&& Objects.equals(factory, outBill.getFactoryCode())
							&& Objects.equals(notice.getWarehouseCode(), outBill.getWarehouseCode())
							&& Objects.equals(notice.getBillType(), outBill.getBillType())
							&& StrUtil.isNotBlank(outBill.getBillNo()),
					"原出库单的通知、组织、仓库或业务类型不符");
			// 新精确执行逐次保留明细，供预留消费和拣配使用独立事实号；原请求防重由永久回执处理。
			// 未提供通知明细ID的历史调用继续沿用聚合规则。
			List<WmsOutBillDetailEntity> billDetails = List.of();
			if (params.getNoticeDetailId() == null) {
				billDetails = wmsOutBillDetailMapper
						.selectList(new LambdaQueryWrapper<WmsOutBillDetailEntity>()
								.eq(WmsOutBillDetailEntity::getBillId, outBill.getId())
								.eq(WmsOutBillDetailEntity::getNoticeDetailId, noticeDetail.getId())
								.eq(params.getSourceStockId() != null, WmsOutBillDetailEntity::getSourceStockId,
										params.getSourceStockId())
								.eq(WmsOutBillDetailEntity::getMatCode, params.getMatCode())
								.eq(WmsOutBillDetailEntity::getMatBatch, params.getMatBatch())
								.eq(WmsOutBillDetailEntity::getSiteCode, params.getSiteCode())
								.eq(StrUtil.isNotBlank(newDetail.getContainerCode()),
										WmsOutBillDetailEntity::getContainerCode,
										newDetail.getContainerCode())
								.apply(StrUtil.isBlank(newDetail.getContainerCode()),
										"coalesce(container_code, '') = ''")
								.eq(StrUtil.isNotBlank(params.getSupplierCode()),
										WmsOutBillDetailEntity::getSupplierCode,
										params.getSupplierCode()));
				requireOutbound(billDetails != null && billDetails.size() <= 1,
						"出库单存在重复匹配明细，请先核对，不能取首条继续追加");
			}
			if (CollUtil.isEmpty(billDetails)) {
				// 新增明细
				WmsOutBillDetailEntity detailAdd = new WmsOutBillDetailEntity();
				// 实时库存的内容复制到出库单明细
				BeanUtil.copyProperties(noticeDetail, detailAdd, "id");
				BeanUtil.copyProperties(params, detailAdd, CopyOptions.create().ignoreNullValue());
				detailAdd.setNoticeDetailId(noticeDetail.getId());
				detailAdd.setBillId(outBill.getId());
				// 操作数量
				detailAdd.setOperateUnit(
						params.getOperateUnit() == null ? detailAdd.getMatUnit() : params.getOperateUnit());
				detailAdd
						.setOperateNum(params.getOperateNum() ==
								null ? detailAdd.getMatNum() : params.getOperateNum());
				outBill.setDetails(Collections.singletonList(detailAdd));
				checkOutboundPlacements(outBill);
				requireOutbound(wmsOutBillDetailMapper.insert(detailAdd) == 1,
						"出库明细新增未确认，出库整体回滚");
				// 出库单未完成条数+1
				requireOutbound(wmsOutBillMapper.update(new LambdaUpdateWrapper<WmsOutBillEntity>()
						.set(WmsOutBillEntity::getDetailNotFinishNum, outBill.getDetailNotFinishNum() + 1)
						.eq(BaseEntity::getId, outBill.getId())) == 1, "出库明细条数更新未确认，出库整体回滚");
				// 修改通知单数量 当作新增了一个出库单
				noticeNotOutNum = createBillHandleNoticeOutNum(outBill);
			} else {
				// 修改明细数量
				WmsOutBillDetailEntity detailExist = billDetails.get(0);
				requireOutbound(
						Objects.equals(tenant, detailExist.getTenantId())
								&& Objects.equals(factory, detailExist.getFactoryCode())
								&& Objects.equals(notice.getWarehouseCode(), detailExist.getWarehouseCode())
								&& nonnegativeOutboundQuantity(detailExist.getMatNum())
								&& (detailExist.getOperateNum() == null
								|| nonnegativeOutboundQuantity(detailExist.getOperateNum())),
						"原出库明细组织或数量无效");
				outBill.setDetails(Collections.singletonList(detailExist));
				checkOutboundPlacements(outBill);
				detailExist.setMatNum(WmsQuantityMath.add(detailExist.getMatNum(), params.getMatNum()));
				// 操作数量
				detailExist.setOperateNum(WmsQuantityMath.add(
						Optional.ofNullable(detailExist.getOperateNum()).orElse(0d),
						Optional.ofNullable(params.getOperateNum()).orElse(params.getMatNum())));
				requireOutbound(wmsOutBillDetailMapper.updateById(detailExist) == 1,
						"出库明细累计数量更新未确认，出库整体回滚");
				// 修改通知单数量 当作新增了一个出库单
				detailExist.setMatNum(params.getMatNum());
				noticeNotOutNum = createBillHandleNoticeOutNum(outBill);
			}
		}
		// 将物料从库存中削减
		List<WmsLiveStockEntity> stockOuted = wmsLiveStockService.reduceStockWhenOut(
				outBill.getDetails().get(0));
		WmsOutBillDetailEntity currentDetail = outBill.getDetails().get(0);
		if (CollUtil.isEmpty(stockOuted)) {
			throw new CheckedException("拣货出库未形成实际库存扣减证据");
		}
		BigDecimal proofTotal = stockOuted.stream().map(item -> BigDecimal.valueOf(item.getMatNum()))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		// 既有PDA明细持久量可能已累计，当前内存量仍是本次增量；不能用累计量核本次实物证明。
		if (currentDetail.getMatNum() == null ||
				proofTotal.compareTo(BigDecimal.valueOf(currentDetail.getMatNum())) != 0) {
			throw new CheckedException("拣货实际扣减证据与本次数量不守恒");
		}
		freezeActualOutboundQuality(outBill, currentDetail, stockOuted);
		// 将削减的物料放到拣货区
		requireOutbound(Boolean.TRUE.equals(wmsPickingAreaService.insertByStockList(
						stockOuted, outBill, outBill.getDetails().get(0).getId())),
				"拣货区接收未确认，出库整体回滚");
		if (noticeNotOutNum == 0) {
			// 通知单所有物料处理完了，出库单可以完成了，拣货区的物料可以删掉了
			requireOutbound(wmsOutBillMapper.update(new LambdaUpdateWrapper<WmsOutBillEntity>()
							.set(WmsOutBillEntity::getState, InOutBillStateEnum.FINISHED.getCode())
							.eq(BaseEntity::getId, outBill.getId())
							.eq(WmsOutBillEntity::getState, InOutBillStateEnum.APP_RUNNING.getCode())) == 1,
					"出库单完成状态更新未确认，出库整体回滚");
			// 删除拣货区物料 记录出库流水
			requireOutbound(Boolean.TRUE.equals(wmsPickingAreaService.deleteByOutBill(outBill)),
					"拣货出库及流水未确认，出库整体回滚");
			// 如果出库单都完成了，出库通知单也该完成了
			passBillHandleNoticeState(outBill);

			// 对documentId的单据进行操作
			outBill = wmsOutBillMapper.selectById(outBill.getId());
			List<WmsOutBillDetailEntity> detailEntities = wmsOutBillDetailMapper
					.selectList(new LambdaQueryWrapper<WmsOutBillDetailEntity>()
							.eq(WmsOutBillDetailEntity::getBillId, outBill.getId()));
			outBill.setDetails(detailEntities);
			handleDocumentBizWhenAdd(outBill);
		}

		return outBill.getBillNo();
	}

	/**
	 * app新增
	 *
	 * @return 通知单未出库总数
	 */
	@Transactional(rollbackFor = Exception.class)
	public Double saveDeepApp(WmsOutBillEntity entity) {
		// 1、保存前的校验和处理
		beforeSaveDeep(entity);

		entity.setSource(InOutBillSourceEnum.APP.getCode()); // 来源app
		entity.setState(InOutBillStateEnum.APP_RUNNING.getCode()); // 状态处理中

		// 2、执行保存
		Double noticeNotOutNum = doSaveDeep(entity);

		return noticeNotOutNum;
	}

	@Override
	public List<WmsOutBillEntity> outBillListByDuId(Long id) {
		return wmsOutBillMapper.selectList(new LambdaQueryWrapper<WmsOutBillEntity>()
				.eq(WmsOutBillEntity::getDocumentId, id));
	}

	@Override
	public Boolean outBillRemoveByDuId(Long id) {
		// 删除主表
		List<WmsOutBillEntity> wmsOutBillEntities = wmsOutBillMapper
				.selectList(new LambdaQueryWrapper<WmsOutBillEntity>()
						.eq(WmsOutBillEntity::getDocumentId, id)
						.eq(WmsOutBillEntity::getDocumentTable, DocumentEnum.DELIVER.getCode())
						.and(wq -> wq.eq(WmsOutBillEntity::getState,
										InOutBillStateEnum.PENDING_COMMIT.getCode())
								.or()
								.eq(WmsOutBillEntity::getState, InOutBillStateEnum.PENDING_REVIEW.getCode())));
		List<Long> idList = wmsOutBillEntities.stream().map(BaseEntity::getId).toList();
		// 删除子表
		if (CollUtil.isNotEmpty(idList)) {
			wmsOutBillDetailMapper.delete(new LambdaQueryWrapper<WmsOutBillDetailEntity>()
					.in(WmsOutBillDetailEntity::getBillId, idList));
		}
		wmsOutBillMapper.deleteByIds(idList);
		return Boolean.TRUE;
	}

	/**
	 * 查询主表列表
	 *
	 * @param wrapper 条件
	 */
	@Override
	public List<WmsOutBillExcelVO> list(QueryWrapper<WmsOutBillEntity> wrapper) {
		List<WmsOutBillEntity> list = wmsOutBillMapper.selectList(wrapper);
		List<WmsOutBillExcelVO> result = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			WmsOutBillExcelVO vo = new WmsOutBillExcelVO();
			WmsOutBillEntity entity = list.get(i);
			BeanUtil.copyProperties(entity, vo);
			vo.setRowNo(i + 1);
			vo.setState(InOutBillStateEnum.getByCode(entity.getState()).getDesc());
			result.add(vo);
		}
		return result;
	}

	/**
	 * 查询明细列表
	 *
	 * @param params 查询参数
	 * @param ids    选择id
	 */
	@Override
	public List<WmsOutBillDetailExcelVO> listDetails(WmsInBillDetailQueryDTO params, Long[] ids) {
		// 先在Java边界核完整通知号，避免格式错误被MyBatis表达式包装成系统异常。
		params.noticeIdValue();
		List<WmsOutBillDetailEntity> list = wmsOutBillDetailMapper.fetchDetails(params, ids);
		List<WmsOutBillDetailExcelVO> result = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			WmsOutBillDetailExcelVO vo = new WmsOutBillDetailExcelVO();
			WmsOutBillDetailEntity entity = list.get(i);
			BeanUtil.copyProperties(entity, vo);
			vo.setRowNo(i + 1);
			vo.setOperateNum(entity.getOperateNum() + entity.getOperateUnit());
			vo.setMatNum(entity.getMatNum() + entity.getMatUnit());
			vo.setState(InOutBillStateEnum.getByCode(entity.getState()).getDesc());
			result.add(vo);
		}
		return result;
	}
}
