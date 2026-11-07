package com.kitex.mom.wms.service.inBill;

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
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.dto.app.WmsInBillDetailAppDTO;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.WmsDeliverGoodsReturnService;
import com.kitex.mom.wms.service.WmsIdentifierPolicyService;
import com.kitex.mom.wms.service.WmsMaterialReservationService;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import com.kitex.mom.wms.service.WmsWarehousePlacementGuard;
import com.kitex.mom.wms.util.WmsQuantityMath;
import com.kitex.mom.wms.util.WmsStockOwnership;
import com.kitex.mom.wms.vo.WmsInBillDetailExcelVO;
import com.kitex.mom.wms.vo.WmsInBillExcelVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 入库单处理器抽象实现 提供通用的业务逻辑实现，子类可以通过继承该类来实现特定类型的入库单处理
 *
 * @author kitex
 * @since 2024-12-16 14:28:29
 */

public abstract class AbstractWmsInBillHandler implements WmsInBillHandler<WmsInBillEntity, WmsInBillDetailEntity> {

	@Autowired
	protected WmsInNoticeMapper wmsInNoticeMapper;
	@Autowired
	protected WmsInNoticeDetailMapper wmsInNoticeDetailMapper;
	@Autowired
	private WmsInBillMapper wmsInBillMapper;
	@Autowired
	private WmsInBillDetailMapper wmsInBillDetailMapper;
	@Autowired
	private WmsLiveStockMapper wmsLiveStockMapper;
	@Autowired
	private WmsInoutFlowMapper wmsInoutFlowMapper;

	@Autowired
	private WmsContainerBarcodeMapper wmsContainerBarcodeMapper;
	@Autowired
	private WmsContainerUseHistoryMapper wmsContainerUseHistoryMapper;

	@Autowired
	private WmsDeliverGoodsReturnService wmsDeliverGoodsReturnService;

	@Autowired
	private WmsMaterialReservationService wmsMaterialReservationService;

	@Autowired
	private WmsIdentifierPolicyService identifierPolicyService;

	@Autowired
	private RemoteSeqConfigService remoteSeqConfigService;

	@Autowired
	private RemoteWarehouseLocationService remoteWarehouseLocationService;

	@Autowired
	private WmsWarehouseBusinessGuard warehouseBusinessGuard;

	private static void requireInbound(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}

	private static boolean nonnegativeInboundQuantity(Double value) {
		return value != null && Double.isFinite(value) && value >= 0;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean saveDeep(WmsInBillEntity entity) {
		// 1、保存前的校验和处理
		beforeSaveDeep(entity);

		// 2、执行保存
		doSaveDeep(entity);

		return Boolean.TRUE;
	}

	/**
	 * 保存前的处理
	 *
	 * @param entity 入库单
	 */
	public void beforeSaveDeep(WmsInBillEntity entity) {
		if (entity != null) {
			WmsSpecialExecutionContext.check(entity.getBillType(), entity.getDocumentId(),
					entity.getDocumentTable());
		}
		validateBasicInboundDetails(entity == null ? null : entity.getDetails());
		Assert.isTrue(Boolean.TRUE.equals(checkWarehouse(entity.getWarehouseCode(), entity.getBillType())),
				"仓库不支持当前业务");
		checkInboundPlacements(entity);
		entity.getDetails().forEach(detail ->
				identifierPolicyService.fillMissingIdentifiers(entity.getBillType(), detail));
		// 1. 校验数据完整性
		checkData(entity);

		// 2. 校验仓库是否支持该业务
		Assert.isTrue(Boolean.TRUE.equals(checkWarehouse(entity.getWarehouseCode(), entity.getBillType())),
				"仓库不支持当前业务");

		// 3. 设置默认状态
		setDefaultState(entity);

		// 4. 设置初始数据
		if (StrUtil.isBlank(entity.getBillNo())) {
			R<String> wms_in_bill_R = remoteSeqConfigService.create("wms_in_bill");
			if (!wms_in_bill_R.isOk()) {
				throw new CheckedException("生成入库单号失败！" + wms_in_bill_R.getMsg());
			}
			String billNo = wms_in_bill_R.getData();
			entity.setBillNo(billNo);
		}

		entity.setDetailFinishNum(0d);
		entity.setDetailNotFinishNum(Double.parseDouble(String.valueOf(entity.getDetails().size())));
	}

	/**
	 * 执行保存单据
	 *
	 * @param entity 入库单
	 * @return 通知单未入库总数
	 */
	@Transactional
	public Double doSaveDeep(WmsInBillEntity entity) {
		// 1、 保存主表数据
		int result = wmsInBillMapper.insert(entity);
		if (result <= 0) {
			throw new CheckedException("保存入库单失败");
		}

		// 2、 保存明细数据
		List<WmsInBillDetailEntity> detailList = entity.getDetails();
		if (CollUtil.isNotEmpty(detailList)) {
			for (WmsInBillDetailEntity detail : detailList) {
				detail.setBillId(entity.getId())
						.setFactoryCode(entity.getFactoryCode())
						.setWarehouseCode(entity.getWarehouseCode())
						.setWarehouseName(entity.getWarehouseName());
				if (wmsInBillDetailMapper.insert(detail) != 1) {
					throw new CheckedException("保存入库单明细失败");
				}
			}
		}

		// 3、更新通知单数量
		return createBillHandleNoticeInNum(entity);
	}

	/**
	 * 新增入库单并审核通过
	 *
	 * @param inBill 入库单信息（包含明细）
	 * @return true-成功，false-失败
	 */
	@Override
	@Transactional
	public Boolean addAndPass(WmsInBillEntity inBill) {
		// 1、保存前的处理
		beforeSaveDeep(inBill);
		// 2、设置状态、明细数、入库时间
		LocalDateTime now = LocalDateTime.now();
		List<WmsInBillDetailEntity> details = inBill.getDetails();
		for (WmsInBillDetailEntity detail : details) {
			detail.setInTime(now);
		}
		inBill.setState(InOutBillStateEnum.PASSED.getCode());
		inBill.setDetailFinishNum(Double.parseDouble(String.valueOf(details.size())));
		inBill.setDetailNotFinishNum(0d);
		inBill.setInTime(now);
		inBill.setReviewer(SecurityUtils.getUser().getUsername());
		inBill.setReviewTime(now);
		// 3、执行保存
		doSaveDeep(inBill);
		// 4、判断通知单物料是否已全部完成入库
		passBillHandleNoticeState(inBill);
		// 5、增加实时库存、生成入库流水记录
		afterInStock(inBill);

		// 6、对documentId的单据进行操作
		handleDocumentBizWhenAdd(inBill);

		try {
			// 如果来源于退货，更新退货单据数据
			if (DocumentEnum.D_RETURN.getCode().equals(inBill.getDocumentTable())) {
				wmsDeliverGoodsReturnService.updateReturnByBill(inBill);
			}
		} catch (Exception e) {
			throw new RuntimeException("更新退货信息失败：" + e.getMessage(), e);
		}

		return Boolean.TRUE;
	}

	@Override
	public void setDefaultState(WmsInBillEntity entity) {
		// 默认状态为待提交
		entity.setState(InOutBillStateEnum.PENDING_COMMIT.getCode());
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean updateDeep(WmsInBillEntity entity) {
		Assert.notNull(entity, "入库单不能为空");
		Assert.notNull(entity.getId(), "入库单ID不能为空");
		// 先确认可编辑，再只为新增明细分配标识；已有明细沿用保存值。
		wmsInBillMapper.lockBill(entity.getId());
		WmsInBillEntity existingNotice = wmsInBillMapper.selectById(entity.getId());
		Assert.notNull(existingNotice, "入库单不存在");
		if (existingNotice.getNoticeId() != null) {
			throw new CheckedException("关联通知单的入库单须删除草稿并重新创建，不能直接修改明细");
		}

		InOutBillStateEnum currentState = InOutBillStateEnum.getByCode(existingNotice.getState());
		Assert.notNull(currentState, "入库单状态无效");
		Assert.isTrue(currentState.isEditable(), "当前状态不允许修改");

		if (existingNotice.getNoticeId() == null) {
			existingNotice.setWarehouseCode(entity.getWarehouseCode());
			existingNotice.setWarehouseName(entity.getWarehouseName());
		}
		existingNotice.setSupplierCode(entity.getSupplierCode());
		existingNotice.setWorkshop(entity.getWorkshop());
		existingNotice.setRemark(entity.getRemark());
		existingNotice.setInTime(entity.getInTime());
		existingNotice.setDetails(entity.getDetails());
		validateBasicInboundDetails(entity.getDetails());
		checkInboundPlacements(existingNotice);
		prepareDraftIdentifiers(existingNotice.getBillType(), entity.getDetails(),
				persistedDetailIds(entity.getId()));
		checkData(existingNotice);
		Assert.isTrue(Boolean.TRUE.equals(checkWarehouse(
						existingNotice.getWarehouseCode(), existingNotice.getBillType())),
				"仓库不支持当前业务");

		int result = wmsInBillMapper.update(Wrappers.<WmsInBillEntity>lambdaUpdate()
				.set(WmsInBillEntity::getWarehouseCode, existingNotice.getWarehouseCode())
				.set(WmsInBillEntity::getWarehouseName, existingNotice.getWarehouseName())
				.set(WmsInBillEntity::getSupplierCode, existingNotice.getSupplierCode())
				.set(WmsInBillEntity::getWorkshop, existingNotice.getWorkshop())
				.set(WmsInBillEntity::getRemark, existingNotice.getRemark())
				.set(WmsInBillEntity::getInTime, existingNotice.getInTime())
				.eq(WmsInBillEntity::getId, existingNotice.getId())
				.eq(WmsInBillEntity::getState, InOutBillStateEnum.PENDING_COMMIT.getCode()));
		if (result != 1) {
			throw new CheckedException("更新入库单失败");
		}

		// 更新明细数据
		if (CollUtil.isNotEmpty(entity.getDetails())) {
			updateDetails(entity.getId(), entity.getDetails());
		}

		return Boolean.TRUE;
	}

	@Override
	public Page<WmsInBillEntity> page(Page page, QueryWrapper<WmsInBillEntity> wrapper) {
		return wmsInBillMapper.selectPage(page, wrapper);
	}

	@Override
	public Map<String, Long> getCount(QueryWrapper<WmsInBillEntity> wrapper) {
		return wmsInBillMapper.selectList(wrapper)
				.stream()
				.collect(Collectors.groupingBy(WmsInBillEntity::getState, Collectors.counting()));
	}

	@Override
	public Page<WmsInBillDetailEntity> pageDetails(Page page, WmsInBillDetailQueryDTO params) {
		// 先在Java边界核完整通知号，避免格式错误被MyBatis表达式包装成系统异常。
		params.noticeIdValue();
		return wmsInBillDetailMapper.fetchDetails(page, params, null);
	}

	@Override
	public WmsInBillEntity selectById(Long id) {
		// 1. 查询主表数据
		WmsInBillEntity notice = wmsInBillMapper.selectById(id);
		if (notice == null) {
			return null;
		}

		// 2. 查询明细数据
		List<WmsInBillDetailEntity> details = wmsInBillDetailMapper.selectList(
				Wrappers.<WmsInBillDetailEntity>lambdaQuery()
						.eq(WmsInBillDetailEntity::getBillId, id));
		notice.setDetails(details);

		return notice;
	}

	@Override
	public void checkData(WmsInBillEntity entity) {
		// 1. 校验必填数据
		validateBasicInboundDetails(entity == null ? null : entity.getDetails());
		checkInboundPlacements(entity);
		Optional.ofNullable(entity.getWarehouseCode())
				.orElseThrow(() -> new CheckedException("仓库编码不能为空"));
		Optional.ofNullable(entity.getBillType()).orElseThrow(() -> new CheckedException("单据类型不能为空"));

		// 2. 校验单据类型是否有效
		Assert.isTrue(InBillTypeEnum.isValid(entity.getBillType()) ||
						OtherInBillTypeEnum.isValid(entity.getBillType()),
				"无效的单据类型：" + entity.getBillType());

		entity.getDetails().forEach(detail -> {
			WmsStockOwnership.prepareInbound(entity.getBillType(), detail);
			identifierPolicyService.validateInbound(entity.getBillType(), detail);
		});

		// 不同原流水允许同位置分别收回；同原流水仍去重，普通入库沿原物流身份校验。
		Map<List<Object>, List<WmsInBillDetailEntity>> collectGroup = entity.getDetails().stream()
				.collect(Collectors.groupingBy(this::inboundDuplicateKey));
		for (List<WmsInBillDetailEntity> value : collectGroup.values()) {
			if (value.size() > 1) {
				throw new CheckedException("数据重复【物料编码：" + value.get(0).getMatCode()
						+ "，库位：" + value.get(0).getSiteName()
						+ "，容器：" + Optional.ofNullable(value.get(0).getContainerCode()).orElse("空")
						+ "，批次：" + value.get(0).getMatBatch()
						+ "，供应商：" + value.get(0).getSupplierName()
						+ "】");
			}
		}
	}

	/**
	 * 按真实原件和目标位置识别特殊收回重复行，普通入库保留原物流判重合同。
	 * 原流水已冻结物料和批次等身份，不能用本次自动补出的临时标识拆开同一原件。
	 *
	 * @param detail 当前实际入库明细，原流水只取受控事务的同对象绑定
	 * @return 特殊收回的原流水及目标位置组合，或普通入库的完整原物流身份键
	 */
	private List<Object> inboundDuplicateKey(WmsInBillDetailEntity detail) {
		Long sourceFlowId = WmsSpecialExecutionContext.inboundSourceFlowId(detail);
		if (sourceFlowId != null) {
			return Arrays.<Object>asList("SPECIAL_RETURN", sourceFlowId, detail.getSiteCode(),
					detail.getContainerCode());
		}
		return Collections.singletonList(detail.getMatCode() + detail.getSiteCode() + detail.getContainerCode()
				+ detail.getMatBatch() + detail.getMatBarcode() + detail.getSupplierCode()
				+ detail.getReservationNo() + detail.getRequirementMatCode()
				+ detail.getStockOwnerType() + detail.getStockOwnerCode() + detail.getSourceBusinessNo());
	}

	private void checkInboundPlacements(WmsInBillEntity bill) {
		requireInbound(bill != null && CollUtil.isNotEmpty(bill.getDetails()), "入库明细不能为空");
		warehouseBusinessGuard.checkPlacements(bill.getWarehouseCode(), bill.getDetails().stream()
				.map(row -> row == null ? null : new WmsWarehousePlacementGuard.Placement(
						row.getSiteCode(), row.getContainerCode())).toList());
	}

	/**
	 * 基础明细在分配批次、条码前校验，避免无效草稿消耗编码流水。
	 */
	private static void validateBasicInboundDetails(List<WmsInBillDetailEntity> details) {
		if (CollUtil.isEmpty(details)) {
			throw new CheckedException("明细数据不能为空");
		}
		for (WmsInBillDetailEntity detail : details) {
			if (detail == null || StrUtil.isBlank(detail.getMatCode()) ||
					StrUtil.isBlank(detail.getMatUnit())) {
				throw new CheckedException("入库明细物料和标准单位不能为空");
			}
			if (detail.getMatNum() == null || !Double.isFinite(detail.getMatNum())
					|| detail.getMatNum() <= 0D) {
				throw new CheckedException("入库明细标准数量必须为大于0的有限数值");
			}
		}
	}

	/**
	 * App向既有入库单新增明细时也经过与新建单一致的身份校验。
	 */
	void prepareNewInboundDetail(String billType, WmsInBillDetailEntity detail) {
		validateBasicInboundDetails(detail == null ? null : List.of(detail));
		WmsStockOwnership.prepareInbound(billType, detail);
		identifierPolicyService.fillMissingIdentifiers(billType, detail);
		identifierPolicyService.validateInbound(billType, detail);
	}

	void prepareDraftIdentifiers(String billType, List<WmsInBillDetailEntity> details, Set<Long> persistedIds) {
		if (CollUtil.isEmpty(details)) {
			return;
		}
		for (WmsInBillDetailEntity detail : details) {
			if (detail.getId() == null) {
				identifierPolicyService.fillMissingIdentifiers(billType, detail);
			} else if (!persistedIds.contains(detail.getId())) {
				throw new CheckedException("入库明细不存在或不属于当前入库单");
			}
		}
	}

	private Set<Long> persistedDetailIds(Long billId) {
		return wmsInBillDetailMapper.selectList(new LambdaQueryWrapper<WmsInBillDetailEntity>()
						.eq(WmsInBillDetailEntity::getBillId, billId))
				.stream().map(BaseEntity::getId).collect(Collectors.toSet());
	}

	boolean shouldAddAppDetail(String billType, String materialCode, String barcode,
	                           List<WmsInBillDetailEntity> matchedDetails) {
		if (CollUtil.isEmpty(matchedDetails)) {
			return true;
		}
		WmsIdentifierPolicyEntity effective = identifierPolicyService.resolveEffective(billType, materialCode);
		if (effective != null && Boolean.TRUE.equals(effective.getSerialManaged())) {
			if (StrUtil.isNotBlank(barcode)) {
				throw new CheckedException("单件条码[" + StrUtil.trim(barcode) + "]已在当前入库单中");
			}
			return true;
		}
		return false;
	}

	@Override
	public Boolean updateState(Long id, String state) {
		throw new CheckedException("入库单状态须通过提交或审核等业务操作变更");
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean updateDetails(Long id, List<WmsInBillDetailEntity> details) {
		// 1. 校验通知单状态
		wmsInBillMapper.lockBill(id);
		WmsInBillEntity notice = wmsInBillMapper.selectById(id);
		Assert.notNull(notice, "入库单不存在");
		if (notice.getNoticeId() != null) {
			throw new CheckedException("关联通知单的入库单须删除草稿并重新创建，不能直接修改明细");
		}

		InOutBillStateEnum currentState = InOutBillStateEnum.getByCode(notice.getState());
		Assert.notNull(currentState, "入库单状态无效");
		Assert.isTrue(currentState.isEditable(), "当前状态不允许修改明细");

		validateBasicInboundDetails(details);
		notice.setDetails(details);
		checkInboundPlacements(notice);
		prepareDraftIdentifiers(notice.getBillType(), details, persistedDetailIds(id));
		notice.setDetails(details);
		checkData(notice);
		Assert.isTrue(Boolean.TRUE.equals(checkWarehouse(notice.getWarehouseCode(), notice.getBillType())),
				"仓库不支持当前业务");

		// 2. 删除原有明细
		wmsInBillDetailMapper.delete(
				Wrappers.<WmsInBillDetailEntity>lambdaQuery()
						.eq(WmsInBillDetailEntity::getBillId, id));

		// 3. 插入新明细
		for (WmsInBillDetailEntity detail : details) {
			detail.setBillId(id).setId(null);
			if (wmsInBillDetailMapper.insert(detail) != 1) {
				throw new CheckedException("更新入库单明细失败");
			}
		}

		return Boolean.TRUE;
	}

	@Override
	public Boolean checkWarehouse(String warehouseCode, String billType) {
		return warehouseBusinessGuard.check(warehouseCode, billType, true);
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
				throw new CheckedException("入库单ID无效");
			}
			billIds.add(Long.valueOf(id));
		}
		billIds = billIds.stream().distinct().sorted().toList();
		for (Long billId : billIds) {
			wmsInBillMapper.lockBill(billId);
		}

		// 1. 校验入库单状态
		List<WmsInBillEntity> entities = wmsInBillMapper.selectBatchIds(billIds);
		if (entities.size() != billIds.size()) {
			throw new CheckedException("存在已删除或无权访问的入库单");
		}
		for (WmsInBillEntity entity : entities) {
			InOutBillStateEnum state = InOutBillStateEnum.getByCode(entity.getState());
			Assert.notNull(state, "入库单状态无效");
			Assert.isTrue(state.isEditable(),
					String.format("入库单[%s]当前状态[%s]不允许删除", entity.getBillNo(), state.getDesc()));
		}

		// 2. 回退通知单的数量
		for (WmsInBillEntity entity : entities) {
			List<WmsInBillDetailEntity> details = wmsInBillDetailMapper
					.selectList(Wrappers.<WmsInBillDetailEntity>lambdaQuery()
							.eq(WmsInBillDetailEntity::getBillId, entity.getId()));
			entity.setDetails(details);
			removeBillHandleNoticeInNum(entity);
		}

		// 3. 删除主表数据
		if (wmsInBillMapper.deleteByIds(billIds) != billIds.size()) {
			throw new CheckedException("删除入库单失败");
		}

		// 4. 删除明细数据
		wmsInBillDetailMapper.delete(
				Wrappers.<WmsInBillDetailEntity>lambdaQuery()
						.in(WmsInBillDetailEntity::getBillId, billIds));

		return Boolean.TRUE;
	}

	/**
	 * 提交入库单
	 *
	 * @param id 入库单id
	 * @return true-成功，false-失败
	 */
	@Override
	public Boolean commitBill(Long id) {
		// 1. 校验入库单状态
		WmsInBillEntity bill = wmsInBillMapper.selectById(id);
		Assert.notNull(bill, "入库单不存在");
		InOutBillStateEnum state = InOutBillStateEnum.getByCode(bill.getState());
		Assert.notNull(state, "入库单状态无效");
		Assert.isTrue(state.isEditable(),
				String.format("入库单[%s]当前状态[%s]不允许提交", bill.getBillNo(), state.getDesc()));
		Assert.isTrue(Boolean.TRUE.equals(checkWarehouse(bill.getWarehouseCode(), bill.getBillType())),
				"仓库不支持当前业务");
		bill.setDetails(wmsInBillDetailMapper.selectList(Wrappers.<WmsInBillDetailEntity>lambdaQuery()
				.eq(WmsInBillDetailEntity::getBillId, id)));
		checkInboundPlacements(bill);

		// 2. 更新状态为待审核
		if (wmsInBillMapper.update(new LambdaUpdateWrapper<WmsInBillEntity>()
				.set(WmsInBillEntity::getState, InOutBillStateEnum.PENDING_REVIEW.getCode())
				.eq(BaseEntity::getId, id)
				.eq(WmsInBillEntity::getState, InOutBillStateEnum.PENDING_COMMIT.getCode())) != 1) {
			throw new CheckedException("提交入库单失败，请刷新单据状态");
		}
		return Boolean.TRUE;
	}

	/**
	 * 审核通过入库单
	 *
	 * @param id 入库单id
	 * @return true-成功，false-失败
	 */
	@Override
	@Transactional
	public Boolean passBill(Long id) {
		// 1. 校验入库单状态
		WmsInBillEntity bill = wmsInBillMapper.selectOne(Wrappers.<WmsInBillEntity>lambdaQuery()
				.eq(BaseEntity::getId, id).last("FOR UPDATE"));
		Assert.notNull(bill, "入库单不存在");
		InOutBillStateEnum state = InOutBillStateEnum.getByCode(bill.getState());
		Assert.notNull(state, "入库单状态无效");
		Assert.isTrue(state.isReviewable(),
				String.format("入库单[%s]当前状态[%s]不允许审核", bill.getBillNo(), state.getDesc()));

		Assert.isTrue(Boolean.TRUE.equals(checkWarehouse(bill.getWarehouseCode(), bill.getBillType())),
				"仓库不支持当前业务");

		// 2. 更新状态为已通过
		List<WmsInBillDetailEntity> billDetails = wmsInBillDetailMapper.selectList(
				Wrappers.<WmsInBillDetailEntity>lambdaQuery()
						.eq(WmsInBillDetailEntity::getBillId, id));
		if (CollUtil.isEmpty(billDetails)) {
			throw new CheckedException("入库单明细为空，不能审核");
		}
		bill.setDetails(billDetails);
		checkInboundPlacements(bill);
		// 修改入库单明细数、入库时间和状态
		LocalDateTime now = LocalDateTime.now();
		if (wmsInBillMapper.update(Wrappers.<WmsInBillEntity>lambdaUpdate()
				.set(WmsInBillEntity::getState, InOutBillStateEnum.PASSED.getCode())
				.set(WmsInBillEntity::getDetailFinishNum, billDetails.size())
				.set(WmsInBillEntity::getDetailNotFinishNum, 0.0)
				.set(WmsInBillEntity::getInTime, now)
				.set(WmsInBillEntity::getReviewer, SecurityUtils.getUser().getUsername())
				.set(WmsInBillEntity::getReviewTime, now)
				.eq(BaseEntity::getId, id)
				.eq(WmsInBillEntity::getState, InOutBillStateEnum.PENDING_REVIEW.getCode())) != 1) {
			throw new CheckedException("入库单审核状态更新失败");
		}
		wmsInBillDetailMapper.update(Wrappers.<WmsInBillDetailEntity>lambdaUpdate()
				.set(WmsInBillDetailEntity::getInTime, now)
				.in(BaseEntity::getId,
						billDetails.stream().map(BaseEntity::getId).collect(Collectors.toList())));
		bill.setState(InOutBillStateEnum.PASSED.getCode());
		bill.setInTime(now);
		bill.setReviewer(SecurityUtils.getUser().getUsername());
		bill.setReviewTime(now);

		// 3. 更新通知单状态 判断通知单物料是否已全部完成入库
		passBillHandleNoticeState(bill);

		// 4、增加实时库存、生成入库流水记录
		for (WmsInBillDetailEntity detail : billDetails) {
			detail.setInTime(now);
		}
		afterInStock(bill);

		// 5、对documentId的单据进行操作
		handleDocumentBizWhenAdd(bill);

		try {
			// 如果来源于退货，更新退货单据数据
			if (DocumentEnum.D_RETURN.getCode().equals(bill.getDocumentTable())) {
				wmsDeliverGoodsReturnService.updateReturnByBill(bill);
			}
		} catch (Exception e) {
			throw new RuntimeException("更新退货信息失败：" + e.getMessage(), e);
		}

		return Boolean.TRUE;
	}

	/**
	 * 创建入库单时修改通知单的入库数
	 *
	 * @param bill 入库单
	 * @return 通知单未入库总数
	 */
	@Transactional(rollbackFor = Exception.class)
	public Double createBillHandleNoticeInNum(WmsInBillEntity bill) {
		if (bill.getNoticeId() == null) {
			// 没有通知单的时候直接返回
			return 0d;
		}
		List<WmsInBillDetailEntity> billDetails = bill.getDetails();
		if (CollUtil.isEmpty(billDetails)) {
			throw new CheckedException("入库单明细为空，无法占用通知单数量");
		}
		// 判断是否超出通知入库总数
		wmsInNoticeMapper.lockNotice(bill.getNoticeId());
		WmsInNoticeEntity notice = Optional.ofNullable(wmsInNoticeMapper.selectById(bill.getNoticeId()))
				.orElseThrow(() -> new CheckedException("入库通知单查询失败"));
		if (!Objects.equals(notice.getWarehouseCode(), bill.getWarehouseCode())
				|| !Objects.equals(notice.getBillType(), bill.getBillType())) {
			throw new CheckedException("入库单与来源通知单的仓库或业务类型不一致");
		}
		if (!InOutNoticeStateEnum.READY.getCode().equals(notice.getState())
				&& !InOutNoticeStateEnum.RUNNING.getCode().equals(notice.getState())) {
			throw new CheckedException("入库通知单已作废或已完成，不能继续入库");
		}
		List<WmsInNoticeDetailEntity> noticeDetails = wmsInNoticeDetailMapper.selectList(
				Wrappers.<WmsInNoticeDetailEntity>lambdaQuery()
						.eq(WmsInNoticeDetailEntity::getNoticeId, bill.getNoticeId()));
		Map<Long, WmsInNoticeDetailEntity> map = noticeDetails.stream()
				.collect(Collectors.toMap(BaseEntity::getId, Function.identity()));
		double totalNum = 0d;
		for (WmsInBillDetailEntity billDetail : billDetails) {
			WmsInNoticeDetailEntity noticeDetail = Optional.ofNullable(map.get(billDetail.getNoticeDetailId()))
					.orElseThrow(() -> new CheckedException(
							"入库通知单明细[" + billDetail.getNoticeDetailId() + "]查找失败！物料编码：["
									+ billDetail.getMatCode() + "]"));
			if (!matchesNoticeDetail(noticeDetail, billDetail)) {
				throw new CheckedException("入库单明细与来源通知明细的物料、批次或单位不一致");
			}
			if (!WmsStockOwnership.matchesInboundNotice(noticeDetail, billDetail)) {
				throw new CheckedException("入库单明细与来源通知明细的货权、归属方或来源单号不一致");
			}
			Double billNum = Optional.ofNullable(billDetail.getMatNum())
					.orElseThrow(() -> new CheckedException("入库单明细标准单位数量为空"));
			Double noticeNum = Optional.ofNullable(noticeDetail.getNotInNum())
					.orElseThrow(() -> new CheckedException("入库通知单明细通知未入库量为空"));
			Double inNum = noticeDetail.getInNum();
			if (!Double.isFinite(billNum) || billNum <= 0 || !Double.isFinite(noticeNum) || noticeNum < 0
					|| inNum == null || !Double.isFinite(inNum) || inNum < 0) {
				throw new CheckedException("入库单或通知单明细数量无效");
			}
			if (billNum > noticeNum) {
				throw new CheckedException(
						String.format("物料编码为[%s]的物料超出通知未入库量！", billDetail.getMatCode()));
			}
			double remaining = WmsQuantityMath.subtract(noticeNum, billNum);
			double received = WmsQuantityMath.add(inNum, billNum);
			// 修改通知单明细的入库数
			if (wmsInNoticeDetailMapper.update(Wrappers.<WmsInNoticeDetailEntity>lambdaUpdate()
					.set(WmsInNoticeDetailEntity::getNotInNum, remaining)
					.set(WmsInNoticeDetailEntity::getInNum, received)
					.eq(BaseEntity::getId, noticeDetail.getId())) != 1) {
				throw new CheckedException("入库通知单明细数量更新失败");
			}
			noticeDetail.setNotInNum(remaining);
			noticeDetail.setInNum(received);
			// 累计入库总数
			totalNum = WmsQuantityMath.add(totalNum, billNum);
		}
		Double allNotInNum = notice.getAllNotInNum();
		Double allInNum = notice.getAllInNum();
		if (!Double.isFinite(totalNum) || allNotInNum == null || !Double.isFinite(allNotInNum)
				|| allNotInNum < totalNum || allInNum == null || !Double.isFinite(allInNum) || allInNum < 0) {
			throw new CheckedException("入库通知单汇总数量无效或不足");
		}
		// 修改通知单总数量
		double remainingTotal = WmsQuantityMath.subtract(allNotInNum, totalNum);
		double receivedTotal = WmsQuantityMath.add(allInNum, totalNum);
		if (wmsInNoticeMapper.update(Wrappers.<WmsInNoticeEntity>lambdaUpdate()
				.set(WmsInNoticeEntity::getAllNotInNum, remainingTotal)
				.set(WmsInNoticeEntity::getAllInNum, receivedTotal)
				.set(WmsInNoticeEntity::getState, InOutNoticeStateEnum.RUNNING.getCode())
				.eq(BaseEntity::getId, notice.getId())) != 1) {
			throw new CheckedException("入库通知单汇总数量更新失败");
		}
		return remainingTotal;
	}

	/**
	 * 删除入库单时修改通知单的入库数
	 *
	 * @param bill 入库单
	 */
	@Transactional(rollbackFor = Exception.class)
	public void removeBillHandleNoticeInNum(WmsInBillEntity bill) {
		if (bill.getNoticeId() == null) {
			// 没有通知单的时候直接返回
			return;
		}
		List<WmsInBillDetailEntity> billDetails = bill.getDetails();
		if (CollUtil.isEmpty(billDetails)) {
			throw new CheckedException("入库单明细为空，无法回退通知单数量");
		}
		// 查找通知单
		wmsInNoticeMapper.lockNotice(bill.getNoticeId());
		WmsInNoticeEntity notice = Optional.ofNullable(wmsInNoticeMapper.selectById(bill.getNoticeId()))
				.orElseThrow(() -> new CheckedException("入库通知单查询失败"));
		List<WmsInNoticeDetailEntity> noticeDetails = wmsInNoticeDetailMapper.selectList(
				Wrappers.<WmsInNoticeDetailEntity>lambdaQuery()
						.eq(WmsInNoticeDetailEntity::getNoticeId, bill.getNoticeId()));
		Map<Long, WmsInNoticeDetailEntity> map = noticeDetails.stream()
				.collect(Collectors.toMap(BaseEntity::getId, Function.identity()));
		Set<Long> updatedIds = new HashSet<>();
		double totalNum = 0d;
		for (WmsInBillDetailEntity billDetail : billDetails) {
			WmsInNoticeDetailEntity noticeDetail = map.get(billDetail.getNoticeDetailId());
			if (noticeDetail == null) {
				throw new CheckedException("入库通知单明细[" + billDetail.getNoticeDetailId() + "]查找失败");
			}
			if (!matchesNoticeDetail(noticeDetail, billDetail)) {
				throw new CheckedException("入库单明细与来源通知明细的物料、批次或单位不一致");
			}
			Double billNum = billDetail.getMatNum();
			Double notInNum = noticeDetail.getNotInNum();
			Double inNum = noticeDetail.getInNum();
			if (billNum == null || !Double.isFinite(billNum) || billNum <= 0
					|| notInNum == null || !Double.isFinite(notInNum) || notInNum < 0
					|| inNum == null || !Double.isFinite(inNum) || inNum < billNum) {
				throw new CheckedException("入库通知单明细数量异常，无法回退");
			}
			noticeDetail.setNotInNum(WmsQuantityMath.add(notInNum, billNum));
			noticeDetail.setInNum(WmsQuantityMath.subtract(inNum, billNum));
			updatedIds.add(noticeDetail.getId());
			totalNum = WmsQuantityMath.add(totalNum, billNum);
		}
		Double allNotInNum = notice.getAllNotInNum();
		Double allInNum = notice.getAllInNum();
		if (!Double.isFinite(totalNum) || allNotInNum == null || !Double.isFinite(allNotInNum)
				|| allInNum == null || !Double.isFinite(allInNum) || allInNum < totalNum) {
			throw new CheckedException("入库通知单汇总数量异常，无法回退");
		}
		for (Long noticeDetailId : updatedIds) {
			WmsInNoticeDetailEntity detail = map.get(noticeDetailId);
			if (wmsInNoticeDetailMapper.update(Wrappers.<WmsInNoticeDetailEntity>lambdaUpdate()
					.set(WmsInNoticeDetailEntity::getNotInNum, detail.getNotInNum())
					.set(WmsInNoticeDetailEntity::getInNum, detail.getInNum())
					.eq(BaseEntity::getId, noticeDetailId)) != 1) {
				throw new CheckedException("入库通知单明细数量回退失败");
			}
		}
		double remainingTotal = WmsQuantityMath.add(allNotInNum, totalNum);
		double receivedTotal = WmsQuantityMath.subtract(allInNum, totalNum);
		if (wmsInNoticeMapper.update(Wrappers.<WmsInNoticeEntity>lambdaUpdate()
				.set(WmsInNoticeEntity::getAllNotInNum, remainingTotal)
				.set(WmsInNoticeEntity::getAllInNum, receivedTotal)
				// 如果入库数变成0了 状态改为待处理，>0是处理中
				.set(WmsInNoticeEntity::getState,
						receivedTotal == 0 ? InOutNoticeStateEnum.READY.getCode()
								: InOutNoticeStateEnum.RUNNING.getCode())
				.eq(BaseEntity::getId, notice.getId())) != 1) {
			throw new CheckedException("入库通知单汇总数量回退失败");
		}
	}

	private static boolean matchesNoticeDetail(WmsInNoticeDetailEntity noticeDetail,
	                                           WmsInBillDetailEntity billDetail) {
		return Objects.equals(StrUtil.trim(noticeDetail.getMatCode()), StrUtil.trim(billDetail.getMatCode()))
				&& (StrUtil.isBlank(noticeDetail.getMatBatch())
				|| Objects.equals(noticeDetail.getMatBatch(), billDetail.getMatBatch()))
				&& (StrUtil.isBlank(noticeDetail.getMatUnit())
				|| Objects.equals(noticeDetail.getMatUnit(), billDetail.getMatUnit()));
	}

	/**
	 * 入库时修改通知单的状态 当通知单内的物料全部入库之后设为已完成（入库单全部完成并且通知单未入库数量为0）
	 *
	 * @param bill 入库单
	 */
	@Transactional(rollbackFor = Exception.class)
	public void passBillHandleNoticeState(WmsInBillEntity bill) {
		if (bill.getNoticeId() == null) {
			// 没有通知单的时候直接返回
			return;
		}
		// 与作业单创建、删除使用同一通知单锁，避免完成状态覆盖新的数量占用。
		wmsInNoticeMapper.lockNotice(bill.getNoticeId());
		// 查找还有没有未完成的入库单
		List<WmsInBillEntity> inBills = wmsInBillMapper.selectList(new LambdaQueryWrapper<WmsInBillEntity>()
				.eq(WmsInBillEntity::getNoticeId, bill.getNoticeId())
				.ne(WmsInBillEntity::getState, InOutBillStateEnum.PASSED.getCode()) // pc不等于已通过
				.ne(WmsInBillEntity::getState, InOutBillStateEnum.FINISHED.getCode()) // app不等于已完成
		);
		if (inBills.size() == 0) {
			// 判断通知单未入库数是否为0
			WmsInNoticeEntity notice = Optional.ofNullable(wmsInNoticeMapper.selectById(bill.getNoticeId()))
					.orElseThrow(() -> new CheckedException("入库通知单查询失败"));
			Double allNotInNum = notice.getAllNotInNum();
			if (allNotInNum == null || !Double.isFinite(allNotInNum) || allNotInNum < 0) {
				throw new CheckedException("入库通知单未入库数量无效");
			}
			if (allNotInNum == 0) {
				// 修改通知单状态
				if (wmsInNoticeMapper.update(Wrappers.<WmsInNoticeEntity>lambdaUpdate()
						.set(WmsInNoticeEntity::getState, InOutNoticeStateEnum.FINISHED.getCode())
						.set(WmsInNoticeEntity::getCompleteTime, LocalDateTime.now())
						.eq(BaseEntity::getId, notice.getId())
						.eq(WmsInNoticeEntity::getState, InOutNoticeStateEnum.RUNNING.getCode())) != 1) {
					throw new CheckedException("入库通知单完成状态更新失败");
				}
			}
		}
	}

	/**
	 * 入库通过之后 增加实时库存，生成入库流水
	 */
	@Transactional
	public void afterInStock(WmsInBillEntity inBill) {
		WmsSpecialExecutionContext.check(inBill.getBillType(), inBill.getDocumentId(),
				inBill.getDocumentTable());
		checkInboundPlacements(inBill);
		Assert.isTrue(Boolean.TRUE.equals(checkWarehouse(inBill.getWarehouseCode(), inBill.getBillType())),
				"仓库不支持当前业务");
		List<WmsInBillDetailEntity> details = inBill.getDetails();
		WmsInNoticeEntity notice = inBill.getNoticeId() == null
				? null
				: wmsInNoticeMapper.selectById(inBill.getNoticeId());
		String mesInboundRequestId = null;
		if (notice != null && InBillTypeEnum.PRODUCT.getCode().equals(inBill.getBillType())
				&& (StrUtil.startWith(notice.getSourceRequestId(), "MES_TASK_IN_WMS:")
				|| StrUtil.startWith(notice.getSourceRequestId(), "MES_TASK_IN_WMS_V2:"))) {
			// V2只改变出站命名域，实收入库和后续红冲继续保存完整MES原请求号。
			String prefix = StrUtil.startWith(notice.getSourceRequestId(), "MES_TASK_IN_WMS_V2:")
					? "MES_TASK_IN_WMS_V2:"
					: "MES_TASK_IN_WMS:";
			mesInboundRequestId = StrUtil.removePrefix(notice.getSourceRequestId(), prefix);
			if (StrUtil.isBlank(mesInboundRequestId)) {
				throw new CheckedException("MES成品入库通知缺少有效永久请求号");
			}
		}
		for (WmsInBillDetailEntity detail : details) {
			WmsStockOwnership.prepareInbound(inBill.getBillType(), detail);
			boolean serialManaged = identifierPolicyService.validateInboundForPosting(inBill.getBillType(),
					detail);
			if (detail.getMatNum() == null || !Double.isFinite(detail.getMatNum()) || detail.getMatNum() <= 0) {
				throw new CheckedException("入库标准数量必须为正有限数值");
			}
			// 实时库存
			WmsLiveStockEntity liveStock = new WmsLiveStockEntity();
			BeanUtil.copyProperties(detail, liveStock, "id");
			liveStock.setSerialManaged(serialManaged);
			liveStock.setSourceInBillId(inBill.getId());
			liveStock.setSourceInBillDetailId(detail.getId());
			liveStock.setSourceMesInboundRequestId(mesInboundRequestId);
			liveStock.setStatus(0);
			WmsSpecialExecutionContext.restoreInbound(detail, liveStock);
			if (wmsLiveStockMapper.insert(liveStock) != 1) {
				throw new CheckedException("入库库存保存失败，整笔入库回滚");
			}
			if (InBillTypeEnum.RETURN.getCode().equals(inBill.getBillType())
					&& StrUtil.isNotBlank(detail.getReservationNo())) {
				wmsMaterialReservationService.replenishFromProductionReturn(detail, liveStock.getId());
			}
			// 入库流水
			WmsInoutFlowEntity inoutFlow = new WmsInoutFlowEntity();
			BeanUtil.copyProperties(detail, inoutFlow, "id");
			inoutFlow.setSerialManaged(serialManaged);
			inoutFlow.setBillId(inBill.getId());
			inoutFlow.setBillDetailId(detail.getId());
			inoutFlow.setSourceStockId(liveStock.getId());
			inoutFlow.setSourceInBillId(inBill.getId());
			inoutFlow.setSourceInBillDetailId(detail.getId());
			inoutFlow.setSourceMesInboundRequestId(mesInboundRequestId);
			inoutFlow.setStockProofType(StrUtil.isBlank(mesInboundRequestId)
					? "INBOUND"
					: "MES_FINISHED_GOODS_RECEIPT");
			inoutFlow.setOperationRequestId("IN-STOCK:" + detail.getId());
			inoutFlow.setBillNo(inBill.getBillNo());
			inoutFlow.setBillType(inBill.getBillType());
			inoutFlow.setOperateType(InOutEnum.IN.getCode());
			WmsSpecialExecutionContext.restoreInboundFlow(detail, inoutFlow, liveStock);
			if (wmsInoutFlowMapper.insert(inoutFlow) != 1) {
				throw new CheckedException("入库流水保存失败，整笔入库回滚");
			}
			// 容器占用
			if (StrUtil.isNotBlank(detail.getContainerCode())) {
				List<WmsContainerBarcodeEntity> containerList = wmsContainerBarcodeMapper
						.selectList(new LambdaQueryWrapper<WmsContainerBarcodeEntity>()
								.eq(WmsContainerBarcodeEntity::getCode, detail.getContainerCode()));
				if (containerList.size() > 0) {
					WmsContainerBarcodeEntity cont = containerList.get(0);
					// 修改在用标志
					wmsContainerBarcodeMapper.update(new LambdaUpdateWrapper<WmsContainerBarcodeEntity>()
							.set(WmsContainerBarcodeEntity::getIsUsed, YesNoEnum.YES.getCode())
							.eq(WmsContainerBarcodeEntity::getId, cont.getId()));
					// 新增使用历史
					Long count = wmsContainerUseHistoryMapper
							.selectCount(new LambdaQueryWrapper<WmsContainerUseHistoryEntity>()
									.eq(WmsContainerUseHistoryEntity::getCode, cont.getCode())
									.eq(WmsContainerUseHistoryEntity::getMatCode, detail.getMatCode())
									.isNull(WmsContainerUseHistoryEntity::getEndTime));
					// 如果不存在未结束的使用历史，则新增
					if (count <= 0) {
						WmsContainerUseHistoryEntity history = new WmsContainerUseHistoryEntity()
								.setCode(cont.getCode())
								.setSiteCode(detail.getSiteCode())
								.setSiteName(detail.getSiteName())
								.setStartTime(detail.getInTime())
								.setMatCode(detail.getMatCode())
								.setMatName(detail.getMatName())
								.setFactoryCode(detail.getFactoryCode());
						wmsContainerUseHistoryMapper.insert(history);
					}
				}
			}
		}

	}

	/**
	 * 审核驳回入库单
	 *
	 * @param params 入库单id、原因
	 * @return true-成功，false-失败
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean rejectBill(WmsInBillEntity params) {
		Long id = params == null ? null : params.getId();
		requireInbound(id != null, "入库单ID不能为空");
		// 1. 校验入库单状态
		wmsInBillMapper.lockBill(id);
		WmsInBillEntity bill = wmsInBillMapper.selectById(id);
		Assert.notNull(bill, "入库单不存在");
		InOutBillStateEnum state = InOutBillStateEnum.getByCode(bill.getState());
		Assert.notNull(state, "入库单状态无效");
		Assert.isTrue(state.isReviewable(),
				String.format("入库单[%s]当前状态[%s]不允许审核", bill.getBillNo(), state.getDesc()));
		// 2. 更新状态为待提交
		if (wmsInBillMapper.update(new LambdaUpdateWrapper<WmsInBillEntity>()
				.set(WmsInBillEntity::getState, InOutBillStateEnum.PENDING_COMMIT.getCode())
				.set(WmsInBillEntity::getReviewer, SecurityUtils.getUser().getUsername())
				.set(WmsInBillEntity::getReviewTime, LocalDateTime.now())
				.set(WmsInBillEntity::getRejectReason, params.getRejectReason())
				.eq(BaseEntity::getId, id)
				.eq(WmsInBillEntity::getState, InOutBillStateEnum.PENDING_REVIEW.getCode())) != 1) {
			throw new CheckedException("驳回入库单失败，请刷新单据状态");
		}
		return Boolean.TRUE;
	}

	/**
	 * 校验此库位是否属于此仓库
	 *
	 * @param warehouseCode 仓库编码
	 * @param siteCode      库位编码
	 * @return 结果
	 */
	public boolean checkLocationOfWarehouse(String warehouseCode, String siteCode) {
		MdmWarehouseLocationEntity location = Optional
				.ofNullable(remoteWarehouseLocationService.getByCode(siteCode).getData())
				.orElse(new MdmWarehouseLocationEntity());
		return StrUtil.equals(warehouseCode, location.getWarehouseCode());
	}

	/**
	 * 校验此容器是否属于此仓库
	 *
	 * @param warehouseCode 仓库编码
	 * @param containerCode 容器编码
	 * @return 结果
	 */
	public boolean checkContainerOfWarehouse(String warehouseCode, String containerCode) {
		WmsContainerBarcodeEntity container = Optional
				.ofNullable(
						wmsContainerBarcodeMapper.selectOne(new LambdaQueryWrapper<WmsContainerBarcodeEntity>()
								.eq(WmsContainerBarcodeEntity::getCode, containerCode)
								.last("limit 1")))
				.orElse(new WmsContainerBarcodeEntity());
		return StrUtil.equals(warehouseCode, container.getWarehouseCode());
	}

	/**
	 * 入库校验
	 *
	 * @param warehouseCode 仓库编码
	 * @param params        入库参数
	 */
	public void appInCheck(String warehouseCode, WmsInBillDetailAppDTO params) {
		requireInbound(params != null, "入库参数不能为空");
		warehouseBusinessGuard.checkMobile(warehouseCode);
		warehouseBusinessGuard.checkPlacements(warehouseCode, List.of(
				new WmsWarehousePlacementGuard.Placement(params.getSiteCode(), params.getContainerCode())));
	}

	/**
	 * app入库 提交某一条物料数量，更新入库单入库数量或生成入库单
	 * <p>
	 * 根据通知单号、物料编码、批次、供应商查找通知单明细； 查找有没有入库单，查找入库单明细有没有这个物料；
	 *
	 * @param params 物料数量等
	 * @return R 对象
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public String setInBillNum(WmsInBillDetailAppDTO params) {
		requireInbound(params != null && params.getNoticeId() != null && params.getNoticeId() > 0,
				"入库必须指定原通知单ID");
		requireInbound(params.getNoticeDetailId() == null || params.getNoticeDetailId() > 0,
				"来源入库通知明细无效");
		requireInbound(
				params.getMatNum() != null && Double.isFinite(params.getMatNum()) && params.getMatNum() > 0
						&& (params.getOperateNum() == null
						|| Double.isFinite(params.getOperateNum()) && params.getOperateNum() > 0),
				"入库数量和已提供的操作数量必须为大于0的有限数值");
		requireInbound(StrUtil.isNotBlank(params.getMatCode()) && StrUtil.isNotBlank(params.getSiteCode()),
				"物料或库位不能为空");
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		requireInbound(tenant != null && StrUtil.isNotBlank(factory), "入库必须具有实际租户和工厂上下文");
		// 查找通知单
		wmsInNoticeMapper.lockNotice(params.getNoticeId());
		WmsInNoticeEntity notice = Optional.ofNullable(wmsInNoticeMapper.selectById(params.getNoticeId()))
				.orElseThrow(() -> new CheckedException("通知单查找失败！"));
		requireInbound(Objects.equals(params.getNoticeId(), notice.getId())
						&& Objects.equals(tenant, notice.getTenantId())
						&& Objects.equals(factory, notice.getFactoryCode())
						&& StrUtil.isNotBlank(notice.getWarehouseCode()),
				"原入库通知单不属于当前实际组织或仓库无效");
		Assert.isTrue(Boolean.TRUE.equals(checkWarehouse(notice.getWarehouseCode(), notice.getBillType())),
				"仓库不支持当前业务");
		requireInbound(InOutNoticeStateEnum.READY.getCode().equals(notice.getState())
						|| InOutNoticeStateEnum.RUNNING.getCode().equals(notice.getState()),
				"原通知单状态不允许继续入库");
		requireInbound(nonnegativeInboundQuantity(notice.getAllNotInNum())
						&& nonnegativeInboundQuantity(notice.getAllInNum())
						&& notice.getAllNotInNum() >= params.getMatNum(),
				"原通知单数量无效或本次数量超过未入库总量");
		// 入库校验
		appInCheck(notice.getWarehouseCode(), params);
		// 通过物料查询通知单明细
		List<WmsInNoticeDetailEntity> noticeDetails = wmsInNoticeDetailMapper
				.selectList(new LambdaQueryWrapper<WmsInNoticeDetailEntity>()
						.eq(WmsInNoticeDetailEntity::getNoticeId, params.getNoticeId())
						.eq(params.getNoticeDetailId() != null, WmsInNoticeDetailEntity::getId,
								params.getNoticeDetailId())
						.eq(WmsInNoticeDetailEntity::getMatCode, params.getMatCode())
						.eq(WmsInNoticeDetailEntity::getMatBatch, params.getMatBatch())
						.eq(StrUtil.isNotBlank(params.getSupplierCode()),
								WmsInNoticeDetailEntity::getSupplierCode,
								params.getSupplierCode()));
		if (noticeDetails.size() == 0) {// 入库通知单的批次可以不填,没查到的话批次为空查一下
			noticeDetails = wmsInNoticeDetailMapper.selectList(new LambdaQueryWrapper<WmsInNoticeDetailEntity>()
					.eq(WmsInNoticeDetailEntity::getNoticeId, params.getNoticeId())
					.eq(params.getNoticeDetailId() != null, WmsInNoticeDetailEntity::getId,
							params.getNoticeDetailId())
					.eq(WmsInNoticeDetailEntity::getMatCode, params.getMatCode())
					.isNull(WmsInNoticeDetailEntity::getMatBatch) // 入库通知单的批次可以不填
					.eq(StrUtil.isNotBlank(params.getSupplierCode()), WmsInNoticeDetailEntity::getSupplierCode,
							params.getSupplierCode()));
		}
		if (CollUtil.isEmpty(noticeDetails)) {
			throw new CheckedException("通知单明细查找失败！");
		}
		requireInbound(noticeDetails.size() == 1, "同物料存在多条入库通知明细，请选择精确来源后再入库");
		WmsInNoticeDetailEntity noticeDetail = noticeDetails.get(0);
		requireInbound(params.getNoticeDetailId() == null
						|| Objects.equals(params.getNoticeDetailId(), noticeDetail.getId()),
				"来源入库通知明细与所选明细不一致");
		WmsStockOwnership.validateInboundNotice(notice.getBillType(), noticeDetail);
		requireInbound(Objects.equals(notice.getId(), noticeDetail.getNoticeId())
						&& Objects.equals(tenant, noticeDetail.getTenantId())
						&& Objects.equals(factory, noticeDetail.getFactoryCode())
						&& Objects.equals(notice.getWarehouseCode(), noticeDetail.getWarehouseCode()),
				"原通知明细的组织或仓库不符");
		requireInbound(nonnegativeInboundQuantity(noticeDetail.getNotInNum())
						&& nonnegativeInboundQuantity(noticeDetail.getInNum())
						&& noticeDetail.getNotInNum() >= params.getMatNum(),
				"原通知明细数量无效或本次数量超过未入库量");
		// 查找入库单； 如果传来了入库单号，就使用传来的入库单号查，否则使用通知单号查
		String originalBillNo = StrUtil.trim(params.getBillNo());
		List<WmsInBillEntity> inBills = wmsInBillMapper.selectList(new LambdaQueryWrapper<WmsInBillEntity>()
				.eq(WmsInBillEntity::getNoticeId, params.getNoticeId())
				.eq(WmsInBillEntity::getTenantId, tenant)
				.eq(WmsInBillEntity::getFactoryCode, factory)
				.eq(StrUtil.isNotBlank(originalBillNo), WmsInBillEntity::getBillNo, originalBillNo)
				.eq(WmsInBillEntity::getState, InOutBillStateEnum.APP_RUNNING.getCode())
				.eq(WmsInBillEntity::getSource, InOutBillSourceEnum.APP.getCode())
				.orderByDesc(BaseCore::getCreateTime));
		requireInbound(inBills != null && inBills.size() <= 1,
				"存在多张处理中的APP入库单，请核对后指定原单号");
		requireInbound(StrUtil.isBlank(originalBillNo) || !inBills.isEmpty(),
				"原入库单不存在、已结束或不属于本通知及当前组织");
		WmsInBillEntity inBill;
		Double noticeNotInNum;
		if (CollUtil.isEmpty(inBills)) {
			// 新增入库单
			inBill = new WmsInBillEntity();
			BeanUtil.copyProperties(notice, inBill, "id");
			inBill.setNoticeId(params.getNoticeId());
			// 添加入库单明细
			WmsInBillDetailEntity inBillDetailAdd = new WmsInBillDetailEntity();
			BeanUtil.copyProperties(noticeDetail, inBillDetailAdd, "id");
			BeanUtil.copyProperties(params, inBillDetailAdd, CopyOptions.create().ignoreNullValue());
			inBillDetailAdd.setNoticeDetailId(noticeDetail.getId());
			// 操作数量
			inBillDetailAdd.setOperateUnit(
					params.getOperateUnit() == null ? inBillDetailAdd.getMatUnit() : params.getOperateUnit());
			inBillDetailAdd.setOperateNum(
					params.getOperateNum() == null ? inBillDetailAdd.getMatNum() : params.getOperateNum());
			inBill.setDetails(Collections.singletonList(inBillDetailAdd));
			// 调用新增方法
			noticeNotInNum = saveDeepApp(inBill);
		} else {
			// 修改入库单数量
			inBill = inBills.get(0);
			requireInbound(Objects.equals(notice.getId(), inBill.getNoticeId())
							&& Objects.equals(tenant, inBill.getTenantId())
							&& Objects.equals(factory, inBill.getFactoryCode())
							&& Objects.equals(notice.getWarehouseCode(), inBill.getWarehouseCode())
							&& Objects.equals(notice.getBillType(), inBill.getBillType())
							&& StrUtil.isNotBlank(inBill.getBillNo()),
					"原入库单的通知、组织、仓库或业务类型不符");
			// 查找入库单明细，如果有则修改数量，没有则新增明细
			List<WmsInBillDetailEntity> billDetails = wmsInBillDetailMapper
					.selectList(new LambdaQueryWrapper<WmsInBillDetailEntity>()
							.eq(WmsInBillDetailEntity::getBillId, inBill.getId())
							.eq(WmsInBillDetailEntity::getNoticeDetailId, noticeDetail.getId())
							.eq(WmsInBillDetailEntity::getMatCode, params.getMatCode())
							.eq(WmsInBillDetailEntity::getMatBatch, params.getMatBatch())
							.eq(WmsInBillDetailEntity::getSiteCode, params.getSiteCode())
							.eq(StrUtil.isNotBlank(params.getSupplierCode()),
									WmsInBillDetailEntity::getSupplierCode,
									params.getSupplierCode())
							.apply(StrUtil.isBlank(params.getMatBarcode()),
									"coalesce(btrim(mat_barcode), '') = ''")
							.apply(StrUtil.isNotBlank(params.getMatBarcode()),
									"btrim(mat_barcode) = {0}", StrUtil.trim(params.getMatBarcode())));
			requireInbound(billDetails != null, "入库明细读取失败，请重新核对");
			// 先保留跨容器的单件条码查重，再按实际容器区分批量物料的累计行。
			boolean addDetail = shouldAddAppDetail(inBill.getBillType(), params.getMatCode(),
					params.getMatBarcode(), billDetails);
			if (!addDetail) {
				billDetails = billDetails.stream().filter(detail -> Objects.equals(
								StrUtil.emptyToNull(detail.getContainerCode()),
								StrUtil.emptyToNull(params.getContainerCode())))
						.toList();
				requireInbound(billDetails.size() <= 1, "入库单存在重复匹配明细，请先核对，不能取首条继续追加");
				addDetail = billDetails.isEmpty();
			}
			if (addDetail) {
				// 新增明细
				WmsInBillDetailEntity detailAdd = new WmsInBillDetailEntity();
				// 通知单明细的内容复制到入库单明细
				Double notInNum = Optional.ofNullable(noticeDetail.getNotInNum())
						.orElseThrow(() -> new CheckedException("入库通知单明细未入库数异常！"));
				if (notInNum <= 0) {
					throw new CheckedException("该物料已全部入库！");
				}
				BeanUtil.copyProperties(noticeDetail, detailAdd, "id");
				BeanUtil.copyProperties(params, detailAdd, CopyOptions.create().ignoreNullValue());
				detailAdd.setNoticeDetailId(noticeDetail.getId());
				detailAdd.setBillId(inBill.getId());
				// 操作数量
				detailAdd.setOperateUnit(
						params.getOperateUnit() == null ? detailAdd.getMatUnit() : params.getOperateUnit());
				detailAdd
						.setOperateNum(params.getOperateNum() ==
								null ? detailAdd.getMatNum() : params.getOperateNum());
				inBill.setDetails(Collections.singletonList(detailAdd));
				checkInboundPlacements(inBill);
				prepareNewInboundDetail(inBill.getBillType(), detailAdd);
				requireInbound(wmsInBillDetailMapper.insert(detailAdd) == 1,
						"入库明细新增未确认，入库整体回滚");
				// 入库单未完成条数+1
				requireInbound(nonnegativeInboundQuantity(inBill.getDetailNotFinishNum()),
						"原入库单未完成明细数无效");
				requireInbound(wmsInBillMapper.update(new LambdaUpdateWrapper<WmsInBillEntity>()
								.set(WmsInBillEntity::getDetailNotFinishNum, inBill.getDetailNotFinishNum() + 1)
								.eq(BaseEntity::getId, inBill.getId())) == 1,
						"入库明细条数更新未确认，入库整体回滚");
				// 修改通知单数量 当作新增了一个入库单
				noticeNotInNum = createBillHandleNoticeInNum(inBill);
			} else {
				// 修改明细数量
				WmsInBillDetailEntity detailExist = billDetails.get(0);
				requireInbound(WmsStockOwnership.matchesInboundNotice(noticeDetail, detailExist)
								&& Objects.equals(noticeDetail.getMatUnit(), detailExist.getMatUnit()),
						"原入库明细的货权、归属方、来源或单位已变化，请核对后再收货");
				requireInbound(Objects.equals(tenant, detailExist.getTenantId())
								&& Objects.equals(factory, detailExist.getFactoryCode())
								&& Objects.equals(notice.getWarehouseCode(), detailExist.getWarehouseCode())
								&& nonnegativeInboundQuantity(detailExist.getMatNum())
								&& (detailExist.getOperateNum() == null
								|| nonnegativeInboundQuantity(detailExist.getOperateNum())),
						"原入库明细组织或数量无效");
				inBill.setDetails(Collections.singletonList(detailExist));
				checkInboundPlacements(inBill);
				detailExist.setMatNum(WmsQuantityMath.add(detailExist.getMatNum(), params.getMatNum()));
				// 操作数量
				detailExist.setOperateNum(WmsQuantityMath.add(
						Optional.ofNullable(detailExist.getOperateNum()).orElse(0d),
						Optional.ofNullable(params.getOperateNum()).orElse(params.getMatNum())));
				requireInbound(wmsInBillDetailMapper.updateById(detailExist) == 1,
						"入库明细累计数量更新未确认，入库整体回滚");
				// 修改通知单数量 当作新增了一个入库单
				detailExist.setMatNum(params.getMatNum());
				noticeNotInNum = createBillHandleNoticeInNum(inBill);
			}
		}
		if (noticeNotInNum == 0) {
			// 通知单所有物料处理完了，入库单可以完成了
			requireInbound(wmsInBillMapper.update(new LambdaUpdateWrapper<WmsInBillEntity>()
							.set(WmsInBillEntity::getState, InOutBillStateEnum.FINISHED.getCode())
							.set(WmsInBillEntity::getInTime, LocalDateTime.now())
							.eq(BaseEntity::getId, inBill.getId())
							.eq(WmsInBillEntity::getState, InOutBillStateEnum.APP_RUNNING.getCode())) == 1,
					"入库单完成状态更新未确认，入库整体回滚");
			inBill.setState(InOutBillStateEnum.FINISHED.getCode());
			// 如果入库单都完成了，入库通知单也该完成了
			passBillHandleNoticeState(inBill);
			// 增加实时库存、生成入库流水记录
			List<WmsInBillDetailEntity> billDetails = wmsInBillDetailMapper
					.selectList(new LambdaQueryWrapper<WmsInBillDetailEntity>()
							.eq(WmsInBillDetailEntity::getBillId, inBill.getId()));
			requireInbound(CollUtil.isNotEmpty(billDetails), "入库单完成时明细不存在");
			LocalDateTime now = LocalDateTime.now();
			inBill.setInTime(now);
			for (WmsInBillDetailEntity detail : billDetails) {
				detail.setInTime(now);
			}
			requireInbound(wmsInBillDetailMapper.update(Wrappers.<WmsInBillDetailEntity>lambdaUpdate()
					.set(WmsInBillDetailEntity::getInTime, now)
					.in(BaseEntity::getId,
							billDetails.stream().map(BaseEntity::getId).collect(Collectors.toList())))
					== billDetails.size(), "入库单明细完成时间更新未确认，入库整体回滚");
			inBill.setDetails(billDetails);
			afterInStock(inBill);
		}

		// 对documentId的单据进行操作
		handleDocumentBizWhenAdd(inBill);

		return inBill.getBillNo();
	}

	/**
	 * app新增
	 *
	 * @return 通知单未入库总数
	 */
	@Transactional(rollbackFor = Exception.class)
	public Double saveDeepApp(WmsInBillEntity entity) {
		// 1、保存前的校验和处理
		beforeSaveDeep(entity);

		entity.setSource(InOutBillSourceEnum.APP.getCode()); // 来源app
		entity.setState(InOutBillStateEnum.APP_RUNNING.getCode()); // 状态处理中

		// 2、执行保存
		Double notInNum = doSaveDeep(entity);

		return notInNum;
	}

	/**
	 * 查询主表列表
	 *
	 * @param wrapper 条件
	 */
	@Override
	public List<WmsInBillExcelVO> list(QueryWrapper<WmsInBillEntity> wrapper) {
		List<WmsInBillEntity> list = wmsInBillMapper.selectList(wrapper);
		List<WmsInBillExcelVO> result = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			WmsInBillExcelVO vo = new WmsInBillExcelVO();
			WmsInBillEntity entity = list.get(i);
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
	public List<WmsInBillDetailExcelVO> listDetails(WmsInBillDetailQueryDTO params, Long[] ids) {
		// 先在Java边界核完整通知号，避免格式错误被MyBatis表达式包装成系统异常。
		params.noticeIdValue();
		List<WmsInBillDetailEntity> list = wmsInBillDetailMapper.fetchDetails(params, ids);
		List<WmsInBillDetailExcelVO> result = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			WmsInBillDetailExcelVO vo = new WmsInBillDetailExcelVO();
			WmsInBillDetailEntity entity = list.get(i);
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
