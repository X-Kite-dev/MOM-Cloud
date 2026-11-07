package com.kitex.mom.wms.service.inNotice;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ObjectUtil;
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
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.wms.dto.WmsInNoticeDetailQueryDTO;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.enums.InOutBillStateEnum;
import com.kitex.mom.wms.enums.InOutNoticeStateEnum;
import com.kitex.mom.wms.enums.OtherInBillTypeEnum;
import com.kitex.mom.wms.mapper.WmsInBillMapper;
import com.kitex.mom.wms.mapper.WmsInNoticeDetailMapper;
import com.kitex.mom.wms.mapper.WmsInNoticeMapper;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import com.kitex.mom.wms.util.WmsQuantityMath;
import com.kitex.mom.wms.util.WmsStockOwnership;
import com.kitex.mom.wms.vo.WmsInNoticeDetailExcelVO;
import com.kitex.mom.wms.vo.WmsInNoticeExcelVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class AbstractWmsInNoticeHandler
		implements WmsInNoticeHandler<WmsInNoticeEntity, WmsInNoticeDetailEntity> {

	@Autowired
	protected WmsInNoticeDetailMapper wmsInNoticeDetailMapper;
	@Autowired
	private WmsInNoticeMapper wmsInNoticeMapper;
	@Autowired
	private WmsInBillMapper wmsInBillMapper;

	@Autowired
	private RemoteMaterialService remoteMaterialService;

	@Autowired
	private WmsWarehouseBusinessGuard warehouseBusinessGuard;

	/**
	 * @param wmsInNotice 通知单信息
	 * @return
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean saveDeep(WmsInNoticeEntity wmsInNotice) {

		// 先校验业务载荷，再由WMS自身生成指纹，调用方不能用伪造指纹绕过同号异载荷校验。
		checkData(wmsInNotice);

		if (StrUtil.isNotBlank(wmsInNotice.getSourceRequestId())) {
			String requestId = wmsInNotice.getSourceRequestId().trim();
			if (requestId.length() > 200) {
				throw new CheckedException("入库通知来源幂等号长度不能超过200");
			}
			requireSourceScope(wmsInNotice);
			String sourceFingerprint = WmsInboundNoticeOriginal.fingerprint(wmsInNotice);
			wmsInNoticeMapper.lockSourceRequest(requestId);
			WmsInNoticeEntity existing = findSourceOriginal(requestId);
			if (existing != null) {
				wmsInNoticeMapper.lockNotice(existing.getId());
				existing = wmsInNoticeMapper.selectById(existing.getId());
				verifySourceOriginal(wmsInNotice, existing, requestId);
				wmsInNotice.setId(existing.getId());
				wmsInNotice.setSourceRequestFingerprint(existing.getSourceRequestFingerprint());
				return true;
			}
			wmsInNotice.setSourceRequestId(requestId);
			wmsInNotice.setSourceRequestFingerprint(sourceFingerprint);
		}

		// 进行校验
		checkWarehouse(wmsInNotice.getWarehouseCode(), wmsInNotice.getBillType());
		wmsInNotice.setFactoryCode(FactoryContextHolder.getFactoryCode());
		wmsInNotice.setTenantId(TenantContextHolder.getTenantId());

		// 设置默认状态
		setDefaultState(wmsInNotice);
		wmsInNotice.setId(null);

		// 设置初始数量
		List<WmsInNoticeDetailEntity> details = wmsInNotice.getDetailList();
		double sum = validatedNoticeTotal(details);
		wmsInNotice.setNoticeInNum(sum);
		wmsInNotice.setAllNotInNum(sum);
		wmsInNotice.setAllInNum(0.0);

		// 保存入库通知单信息
		int result = wmsInNoticeMapper.insert(wmsInNotice);
		if (result <= 0) {
			throw new CheckedException("保存入库通知单失败");
		}
		if (CollUtil.isNotEmpty(details)) {
			// 补齐明细归属和初始数量。
			for (WmsInNoticeDetailEntity detail : details) {
				detail.setId(null);
				detail.setNoticeId(wmsInNotice.getId());
				detail.setWorkshop(wmsInNotice.getWorkshop());
				detail.setNotInNum(detail.getNoticeInNum());
				detail.setInNum(0.0);
				detail.setWarehouseCode(wmsInNotice.getWarehouseCode());
				detail.setWarehouseName(wmsInNotice.getWarehouseName());
				detail.setFactoryCode(wmsInNotice.getFactoryCode());
				detail.setTenantId(wmsInNotice.getTenantId());
			}
			for (WmsInNoticeDetailEntity detail : details) {
				if (wmsInNoticeDetailMapper.insert(detail) != 1) {
					throw new CheckedException("保存入库通知单明细失败");
				}
			}
		}
		if (StrUtil.isNotBlank(wmsInNotice.getSourceRequestId())) {
			verifySourceOriginal(wmsInNotice, findSourceOriginal(wmsInNotice.getSourceRequestId()),
					wmsInNotice.getSourceRequestId());
		}
		return true;
	}

	private void requireSourceScope(WmsInNoticeEntity input) {
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		if (tenant == null || StrUtil.isBlank(factory) || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip()) ||
				Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
				|| (input.getTenantId() != null && !Objects.equals(input.getTenantId(), tenant))
				|| (StrUtil.isNotBlank(input.getFactoryCode()) && !Objects.equals(input.getFactoryCode(), factory))) {
			throw new CheckedException("入库来源请求缺少有效组织，或与当前组织不一致");
		}
	}

	private WmsInNoticeEntity findSourceOriginal(String requestId) {
		List<WmsInNoticeEntity> rows = wmsInNoticeMapper.selectList(new LambdaQueryWrapper<WmsInNoticeEntity>()
				.eq(WmsInNoticeEntity::getSourceRequestId, requestId)
				.eq(WmsInNoticeEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsInNoticeEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 2"));
		if (rows == null || rows.size() > 1) {
			throw new CheckedException("入库来源原通知缺失或不唯一，请核对");
		}
		return rows.isEmpty() ? null : rows.get(0);
	}

	private void verifySourceOriginal(WmsInNoticeEntity input, WmsInNoticeEntity stored, String requestId) {
		if (stored == null || stored.getId() == null || !Objects.equals(stored.getSourceRequestId(), requestId)
				|| !Objects.equals(stored.getTenantId(), TenantContextHolder.getTenantId())
				|| !Objects.equals(stored.getFactoryCode(), FactoryContextHolder.getFactoryCode()) ||
				"1".equals(stored.getDelFlag())) {
			throw new CheckedException("入库来源原通知组织或身份不一致，请核对");
		}
		List<WmsInNoticeDetailEntity> lines = wmsInNoticeDetailMapper.selectList(
				new LambdaQueryWrapper<WmsInNoticeDetailEntity>()
						.eq(WmsInNoticeDetailEntity::getNoticeId, stored.getId())
						.eq(WmsInNoticeDetailEntity::getTenantId, TenantContextHolder.getTenantId())
						.eq(WmsInNoticeDetailEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()));
		if (lines == null ||
				lines.stream().anyMatch(line -> line == null || !Objects.equals(line.getNoticeId(), stored.getId())
						|| !Objects.equals(line.getTenantId(), stored.getTenantId()) ||
						!Objects.equals(line.getFactoryCode(), stored.getFactoryCode())
						|| !Objects.equals(line.getWarehouseCode(), stored.getWarehouseCode()) ||
						"1".equals(line.getDelFlag()))) {
			throw new CheckedException("入库来源原明细组织或归属不一致，请核对");
		}
		String fingerprint = stored.getSourceRequestFingerprint();
		if ((!Objects.equals(fingerprint, WmsInboundNoticeOriginal.fingerprint(input))
				&& !Objects.equals(fingerprint, WmsInboundNoticeOriginal.legacyFingerprint(input)))
				|| !WmsInboundNoticeOriginal.canonical(input, input.getDetailList())
				.equals(WmsInboundNoticeOriginal.canonical(stored, lines))) {
			throw new CheckedException("入库来源请求已使用且原内容不一致，请保留原号核对");
		}
	}

	/**
	 * 作废
	 *
	 * @param id 通知单id
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void voidObjs(Long id) {
		if (id == null) {
			throw new CheckedException("入库通知单ID不能为空");
		}
		wmsInNoticeMapper.lockNotice(id);
		WmsInNoticeEntity noticeEntity = Optional.ofNullable(wmsInNoticeMapper.selectById(id))
				.orElseThrow(() -> new CheckedException("单据不存在！"));

		if (!InOutNoticeStateEnum.READY.getCode().equals(noticeEntity.getState())
				|| (noticeEntity.getAllInNum() != null && noticeEntity.getAllInNum() != 0D)) {
			throw new CheckedException("入库通知单已开始处理或已作废，不能再次作废");
		}
		Long count = wmsInBillMapper.selectCount(new LambdaQueryWrapper<WmsInBillEntity>()
				.eq(WmsInBillEntity::getNoticeId, id));
		if (count > 0) {
			throw new CheckedException("入库通知单已关联入库单，请先处理关联单据");
		}

		if (wmsInNoticeMapper.update(new LambdaUpdateWrapper<WmsInNoticeEntity>()
				.set(WmsInNoticeEntity::getState, InOutNoticeStateEnum.CANCELED.getCode())
				.eq(BaseEntity::getId, id)
				.eq(WmsInNoticeEntity::getState, InOutNoticeStateEnum.READY.getCode())) != 1) {
			throw new CheckedException("作废入库通知单失败");
		}

		voidObjsBack(noticeEntity);
	}

	@Override
	public void setDefaultState(WmsInNoticeEntity notice) {
		notice.setState(InOutNoticeStateEnum.READY.getCode());
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean updateDeep(WmsInNoticeEntity input) {
		Assert.notNull(input, "入库通知单不能为空");
		Assert.notNull(input.getId(), "入库通知单ID不能为空");
		checkData(input);
		wmsInNoticeMapper.lockNotice(input.getId());
		WmsInNoticeEntity notice = wmsInNoticeMapper.selectById(input.getId());
		Assert.notNull(notice, "入库通知单不存在");
		Assert.isTrue("0".equals(notice.getState())
						&& (notice.getAllInNum() == null || notice.getAllInNum() == 0D),
				"通知单已开始入库，不能修改");
		Assert.isTrue(notice.getDocumentId() == null && StrUtil.isBlank(notice.getSourceRequestId()),
				"来源单据生成的入库通知单须由来源业务调整");
		Assert.isTrue(Objects.equals(notice.getBillType(), input.getBillType()), "通知单业务类型不能修改");
		Assert.isTrue(wmsInBillMapper.selectCount(new LambdaQueryWrapper<WmsInBillEntity>()
						.eq(WmsInBillEntity::getNoticeId, notice.getId())) == 0,
				"通知单已关联入库单，不能修改");
		checkWarehouse(input.getWarehouseCode(), input.getBillType());

		double total = validatedNoticeTotal(input.getDetailList());
		notice.setWarehouseCode(input.getWarehouseCode());
		notice.setWarehouseName(input.getWarehouseName());
		notice.setSupplierCode(input.getSupplierCode());
		notice.setWorkshop(input.getWorkshop());
		notice.setRemark(input.getRemark());
		notice.setNoticeInNum(total);
		notice.setAllNotInNum(total);
		if (wmsInNoticeMapper.updateById(notice) != 1) {
			throw new CheckedException("保存入库通知单失败");
		}
		return replaceDetails(notice, input.getDetailList());
	}

	/**
	 * @param page    分页参数
	 * @param wrapper 查询条件
	 * @return
	 */
	@Override
	public Page<WmsInNoticeEntity> page(Page page, QueryWrapper<WmsInNoticeEntity> wrapper) {
		Page result = wmsInNoticeMapper.selectPage(page, wrapper);
		return result;
	}

	@Override
	public Map<String, Long> getCount(QueryWrapper<WmsInNoticeEntity> wrapper) {
		List<WmsInNoticeEntity> wmsInNoticeEntities = wmsInNoticeMapper.selectList(wrapper);
		return wmsInNoticeEntities
				.stream()
				.collect(Collectors.groupingBy(WmsInNoticeEntity::getState, Collectors.counting()));
	}

	/**
	 * 分页查询详情列表
	 *
	 * @param page   分页参数
	 * @param params 查询条件
	 * @return
	 */
	@Override
	public Page<WmsInNoticeDetailEntity> pageDetails(Page page, WmsInNoticeDetailQueryDTO params) {
		// 先核完整通知号，避免格式错误被MyBatis表达式包装成系统异常。
		params.noticeIdValue();
		return wmsInNoticeDetailMapper.fetchDetails(page, params, null);
	}

	/**
	 * 根据id查询数据
	 *
	 * @param id
	 * @return
	 */
	@Override
	public WmsInNoticeEntity selectById(Long id) {
		WmsInNoticeEntity wmsInNoticeEntity = Optional.ofNullable(wmsInNoticeMapper.selectById(id))
				.orElseThrow(() -> new CheckedException("入库通知单查找失败，请确认单号！"));
		List<WmsInNoticeDetailEntity> wmsInNoticeDetailEntities = wmsInNoticeDetailMapper.selectList(
				Wrappers.<WmsInNoticeDetailEntity>lambdaQuery().eq(WmsInNoticeDetailEntity::getNoticeId, id));
		//有效期是否有效（不为空），如果存在无效有效期，查询物料中的有效期进行覆盖
		Long countVp = wmsInNoticeDetailEntities.stream().filter(item -> ObjectUtil.isEmpty(item.getMatValidPeriod()))
				.count();
		if (countVp.compareTo(0L) > 0) {
			R<List<MdmMaterialEntity>> matData = remoteMaterialService.getList();
			if (matData.getCode() != 0) {
				throw new CheckedException("查询物料信息失败，请检查主数据服务是否正常运行！");
			}
			List<MdmMaterialEntity> matList = matData.getData();
			Map<String, MdmMaterialEntity> matMap = matList.stream()
					.collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, Function.identity(), (k1, k2) -> k1));
			wmsInNoticeDetailEntities.forEach(item -> {
				if (ObjectUtil.isEmpty(item.getMatValidPeriod())) {
					MdmMaterialEntity mdmMaterial = matMap.get(item.getMatCode());
					if (ObjectUtil.isNotEmpty(mdmMaterial)) {
						item.setMatValidPeriod(mdmMaterial.getExpirationDay());
						if (ObjectUtil.isNotEmpty(item.getMatProduceDate())) {
							item.setMatValidEndDate(
									ObjectUtil.isNotEmpty(item.getMatValidPeriod()) ? item.getMatProduceDate()
											.plusDays(item.getMatValidPeriod()) : null);
						}
					}
				}
			});
		}

		wmsInNoticeEntity.setDetailList(wmsInNoticeDetailEntities);
		return wmsInNoticeEntity;
	}

	/**
	 * 根据id查询数据-App
	 *
	 * @param id
	 * @return
	 */
	@Override
	public WmsInNoticeEntity selectByIdApp(Long id) {
		WmsInNoticeEntity wmsInNoticeEntity = selectById(id);
		// 根据通知单号查询出库单号（app操作时需要用到）
		List<WmsInBillEntity> inBills = wmsInBillMapper.selectList(new LambdaQueryWrapper<WmsInBillEntity>()
				.eq(WmsInBillEntity::getNoticeId, id)
				.ne(WmsInBillEntity::getState, InOutBillStateEnum.PASSED.getCode())
				.orderByDesc(BaseCore::getCreateTime)
				.last("limit 1")
		);
		if (inBills.size() > 0) {
			wmsInNoticeEntity.setBillNo(inBills.get(0).getBillNo());
		}
		return wmsInNoticeEntity;
	}

	@Override
	public void checkData(WmsInNoticeEntity wmsInNotice) {
		if (wmsInNotice == null || CollUtil.isEmpty(wmsInNotice.getDetailList())) {
			throw new CheckedException("明细数据不能为空");
		}
		if (StrUtil.isBlank(wmsInNotice.getWarehouseCode()) || StrUtil.isBlank(wmsInNotice.getBillType())) {
			throw new CheckedException("仓库编码和单据类型不能为空");
		}
		Assert.isTrue(InBillTypeEnum.isValid(wmsInNotice.getBillType())
						|| OtherInBillTypeEnum.isValid(wmsInNotice.getBillType()),
				"无效的单据类型：" + wmsInNotice.getBillType());
		validatedNoticeTotal(wmsInNotice.getDetailList());
		wmsInNotice.getDetailList().forEach(detail ->
				WmsStockOwnership.validateInboundNotice(wmsInNotice.getBillType(), detail));
	}

	private double validatedNoticeTotal(List<WmsInNoticeDetailEntity> details) {
		double total = 0D;
		for (WmsInNoticeDetailEntity detail : details) {
			if (detail == null || StrUtil.isBlank(detail.getMatCode()) || StrUtil.isBlank(detail.getMatUnit())) {
				throw new CheckedException("入库明细物料和单位不能为空");
			}
			Double quantity = detail.getNoticeInNum();
			if (quantity == null || !Double.isFinite(quantity) || quantity <= 0D) {
				throw new CheckedException("通知入库数量必须为正数");
			}
			total = WmsQuantityMath.add(total, quantity);
		}
		if (!Double.isFinite(total)) {
			throw new CheckedException("通知入库总数超出有效范围");
		}
		return total;
	}

	@Override
	public Boolean updateState(Long id, String state) {
		throw new CheckedException("入库通知单状态须通过作废或作业完成等业务操作变更");
	}

	/**
	 * 更新通知单明细信息
	 *
	 * @param id      通知单id
	 * @param details 通知单详情列表
	 * @return
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean updateDetails(Long id, List<WmsInNoticeDetailEntity> details) {
		Assert.notNull(id, "通知单ID不能为空");
		wmsInNoticeMapper.lockNotice(id);
		WmsInNoticeEntity notice = wmsInNoticeMapper.selectById(id);
		Assert.notNull(notice, "入库通知单不存在");
		WmsInNoticeEntity input = new WmsInNoticeEntity();
		input.setId(id);
		input.setBillType(notice.getBillType());
		input.setWarehouseCode(notice.getWarehouseCode());
		input.setWarehouseName(notice.getWarehouseName());
		input.setSupplierCode(notice.getSupplierCode());
		input.setWorkshop(notice.getWorkshop());
		input.setRemark(notice.getRemark());
		input.setDetailList(details);
		return updateDeep(input);
	}

	private Boolean replaceDetails(WmsInNoticeEntity notice, List<WmsInNoticeDetailEntity> details) {
		Long id = notice.getId();
		List<WmsInNoticeDetailEntity> replacements = new ArrayList<>(details.size());
		for (WmsInNoticeDetailEntity detail : details) {
			Assert.notNull(detail, "入库明细不能为空");
			detail.setId(null);
			detail.setNoticeId(id);
			detail.setInNum(0D);
			detail.setNotInNum(detail.getNoticeInNum());
			detail.setTenantId(notice.getTenantId());
			detail.setFactoryCode(notice.getFactoryCode());
			detail.setWarehouseCode(notice.getWarehouseCode());
			detail.setWarehouseName(notice.getWarehouseName());
			replacements.add(detail);
		}
		wmsInNoticeDetailMapper.delete(new LambdaQueryWrapper<WmsInNoticeDetailEntity>()
				.eq(WmsInNoticeDetailEntity::getNoticeId, id));
		for (WmsInNoticeDetailEntity replacement : replacements) {
			if (wmsInNoticeDetailMapper.insert(replacement) != 1) {
				throw new CheckedException("保存入库通知单明细失败");
			}
		}
		return Boolean.TRUE;
	}

	/**
	 * @param warehouseCode 仓库编码
	 * @param billType      单据类型
	 * @return
	 */
	@Override
	public Boolean checkWarehouse(String warehouseCode, String billType) {
		return warehouseBusinessGuard.check(warehouseCode, billType, true);
	}

	/**
	 * @param ids
	 * @return
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean removeDeep(String[] ids) {
		if (ids == null || ids.length == 0) {
			return Boolean.TRUE;
		}
		List<Long> noticeIds = new ArrayList<>(ids.length);
		for (String id : ids) {
			if (StrUtil.isBlank(id) || !StrUtil.isNumeric(id)) {
				throw new CheckedException("入库通知单ID无效");
			}
			noticeIds.add(Long.valueOf(id));
		}
		noticeIds = noticeIds.stream().distinct().sorted().toList();
		List<WmsInNoticeEntity> notices = wmsInNoticeMapper.selectList(
				Wrappers.<WmsInNoticeEntity>lambdaQuery().in(WmsInNoticeEntity::getId, noticeIds)
						.orderByAsc(WmsInNoticeEntity::getId).last("FOR UPDATE"));
		if (notices.size() != noticeIds.size()) {
			throw new CheckedException("存在已删除或无权访问的入库通知单");
		}
		for (WmsInNoticeEntity notice : notices) {
			if (!"0".equals(notice.getState())
					|| (notice.getAllInNum() != null && notice.getAllInNum() != 0D)) {
				throw new CheckedException("已开始入库的通知单必须保留业务记录");
			}
			if (notice.getDocumentId() != null || StrUtil.isNotBlank(notice.getSourceRequestId())) {
				throw new CheckedException("来源单据生成的入库通知单请通过业务作废流程处理");
			}
		}
		if (wmsInBillMapper.selectCount(Wrappers.<WmsInBillEntity>lambdaQuery()
				.in(WmsInBillEntity::getNoticeId, noticeIds)) > 0) {
			throw new CheckedException("通知单已关联入库单，不能删除");
		}
		wmsInNoticeDetailMapper.delete(Wrappers.<WmsInNoticeDetailEntity>lambdaQuery()
				.in(WmsInNoticeDetailEntity::getNoticeId, noticeIds));
		if (wmsInNoticeMapper.deleteByIds(noticeIds) != noticeIds.size()) {
			throw new CheckedException("删除入库通知单失败");
		}
		return Boolean.TRUE;
	}

	/**
	 * 查询主表列表
	 */
	@Override
	public List<WmsInNoticeExcelVO> list(QueryWrapper<WmsInNoticeEntity> wrapper) {
		List<WmsInNoticeEntity> list = wmsInNoticeMapper.selectList(wrapper);
		List<WmsInNoticeExcelVO> result = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			WmsInNoticeExcelVO vo = new WmsInNoticeExcelVO();
			WmsInNoticeEntity entity = list.get(i);
			BeanUtil.copyProperties(entity, vo);
			vo.setRowNo(i + 1);
			vo.setState(InOutNoticeStateEnum.getByCode(entity.getState()).getName());
			result.add(vo);
		}
		return result;
	}

	/**
	 * 查询明细列表
	 *
	 * @param params 查询条件
	 * @param ids    指定ID
	 */
	@Override
	public List<WmsInNoticeDetailExcelVO> listDetails(WmsInNoticeDetailQueryDTO params, Long[] ids) {
		// 先核完整通知号，避免格式错误被MyBatis表达式包装成系统异常。
		params.noticeIdValue();
		List<WmsInNoticeDetailEntity> list = wmsInNoticeDetailMapper.fetchDetails(params, ids);
		List<WmsInNoticeDetailExcelVO> result = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			WmsInNoticeDetailExcelVO vo = new WmsInNoticeDetailExcelVO();
			WmsInNoticeDetailEntity entity = list.get(i);
			BeanUtil.copyProperties(entity, vo);
			vo.setRowNo(i + 1);
			vo.setNoticeInNum(entity.getNoticeInNum() + entity.getMatUnit());
			vo.setInNum(entity.getInNum() + entity.getMatUnit());
			vo.setNotInNum(entity.getNotInNum() + entity.getMatUnit());
			vo.setState(InOutNoticeStateEnum.getByCode(entity.getState()).getName());
			result.add(vo);
		}
		return result;
	}


}
