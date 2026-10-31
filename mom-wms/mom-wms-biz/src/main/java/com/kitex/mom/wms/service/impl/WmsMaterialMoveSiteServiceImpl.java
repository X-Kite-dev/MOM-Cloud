package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.dto.WmsMaterialMoveSiteAddDTO;
import com.kitex.mom.wms.dto.WmsMaterialMoveSiteQueryDTO;
import com.kitex.mom.wms.dto.app.WmsLiveStockMoveDTO;
import com.kitex.mom.wms.dto.app.WmsMaterialMoveSiteAppDTO;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsMaterialMoveSiteEntity;
import com.kitex.mom.wms.mapper.WmsMaterialMoveSiteMapper;
import com.kitex.mom.wms.service.WmsLiveStockService;
import com.kitex.mom.wms.service.WmsMaterialMoveSiteService;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import com.kitex.mom.wms.util.WmsQuantityMath;
import com.kitex.mom.wms.vo.WmsMaterialMoveSiteExcelVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 库内移动
 *
 * @author kitex
 * @date 2024-12-30 14:13:22
 */
@Service
@AllArgsConstructor
public class WmsMaterialMoveSiteServiceImpl extends ServiceImpl<WmsMaterialMoveSiteMapper, WmsMaterialMoveSiteEntity>
		implements WmsMaterialMoveSiteService {

	private final WmsLiveStockService wmsLiveStockService;

	private final RemoteWarehouseLocationService remoteWarehouseLocationService;
	private final RemoteWarehouseService remoteWarehouseService;
	private final WmsWarehouseBusinessGuard warehouseBusinessGuard;

	private static void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}

	/**
	 * 新增库内移动
	 *
	 * @param wmsMaterialMoveSiteAddDTO 库内移动
	 * @return R
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean materialMoveSiteSave(WmsMaterialMoveSiteAddDTO wmsMaterialMoveSiteAddDTO) {
		require(wmsMaterialMoveSiteAddDTO != null && wmsMaterialMoveSiteAddDTO.getDetails() != null
				&& !wmsMaterialMoveSiteAddDTO.getDetails().isEmpty(), "移库明细不能为空");
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		require(tenant != null && StrUtil.isNotBlank(factory), "移库必须具有实际租户和工厂上下文");
		String warehouseCode = StrUtil.trim(wmsMaterialMoveSiteAddDTO.getWarehouseCode());
		MdmWarehouseEntity warehouse = requireWarehouse(warehouseCode, tenant, factory);
		require(warehouseBusinessGuard.check(warehouseCode, "move_site", false), "仓库未配置库内移动业务");
		wmsMaterialMoveSiteAddDTO.getDetails().forEach(detail -> {
			require(detail != null && detail.getBillId() != null && detail.getMatNum() != null
							&& Double.isFinite(detail.getMatNum()) && detail.getMatNum() > 0,
					"移库源库存和数量不能为空，数量必须大于0");
			require(StrUtil.isBlank(detail.getNewWarehouseCode())
							|| Objects.equals(warehouseCode, StrUtil.trim(detail.getNewWarehouseCode())),
					"库内移动不能跨仓库");
			WmsLiveStockEntity stock = wmsLiveStockService.getOne(new LambdaQueryWrapper<WmsLiveStockEntity>()
					.eq(WmsLiveStockEntity::getId, detail.getBillId()).last("FOR UPDATE"), false);
			require(stock != null && Objects.equals(tenant, stock.getTenantId())
					&& Objects.equals(factory, stock.getFactoryCode())
					&& Objects.equals(warehouseCode, stock.getWarehouseCode()), "源库存不属于当前仓库");
			MdmWarehouseLocationEntity target = requireLocation(detail.getNewSiteCode(), warehouseCode, tenant, factory);
			require(!Objects.equals(stock.getSiteCode(), target.getLocationCode()), "源库位与目标库位相同，无需移库");
			detail.setOldWarehouseCode(warehouseCode);
			detail.setOldWarehouseName(warehouse.getWarehouseName());
			detail.setOldSiteCode(stock.getSiteCode());
			detail.setOldSiteName(stock.getSiteName());
			detail.setNewWarehouseCode(warehouseCode);
			detail.setNewWarehouseName(warehouse.getWarehouseName());
			detail.setNewSiteCode(target.getLocationCode());
			detail.setNewSiteName(target.getLocationName());
			WmsMaterialMoveSiteEntity wmsMaterialMoveSiteEntity = new WmsMaterialMoveSiteEntity();
			BeanUtils.copyProperties(detail, wmsMaterialMoveSiteEntity);
			BeanUtils.copyProperties(stock, wmsMaterialMoveSiteEntity,
					"id", "createBy", "createTime", "updateBy", "updateTime", "delFlag");
			wmsMaterialMoveSiteEntity.setBillNo(wmsMaterialMoveSiteAddDTO.getBillNo());
			wmsMaterialMoveSiteEntity.setMatNum(detail.getMatNum());
			wmsMaterialMoveSiteEntity.setWarehouseCode(warehouseCode);
			wmsMaterialMoveSiteEntity.setWarehouseName(warehouse.getWarehouseName());
			wmsMaterialMoveSiteEntity.setTenantId(tenant);
			wmsMaterialMoveSiteEntity.setFactoryCode(factory);
			wmsMaterialMoveSiteEntity.setSourceStockId(stock.getId());
			wmsMaterialMoveSiteEntity.setSourceInBillDetailId(stock.getSourceInBillDetailId());
			wmsMaterialMoveSiteEntity.setStockOwnerType(stock.getStockOwnerType());
			wmsMaterialMoveSiteEntity.setStockOwnerCode(stock.getStockOwnerCode());
			wmsMaterialMoveSiteEntity.setSourceBusinessNo(stock.getSourceBusinessNo());
			var slice = wmsLiveStockService.materialSaveOrUpdate(detail);
			require(slice != null && slice.sourceStock() != null
							&& Objects.equals(stock.getId(), slice.sourceStock().getId())
							&& Double.compare(slice.quantity(), detail.getMatNum()) == 0,
					"移库结果与来源库存不一致");
			wmsMaterialMoveSiteEntity.setTargetStockId(slice.targetStockId());
			require(this.save(wmsMaterialMoveSiteEntity), "保存移库记录失败");
		});

		return Boolean.TRUE;
	}

	/**
	 * app-库内移动
	 *
	 * @param wmsMaterialMoveSiteAddDTO 库内移动
	 * @return R
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean appMaterialMoveSiteSave(WmsMaterialMoveSiteAppDTO wmsMaterialMoveSiteAddDTO) {
		require(wmsMaterialMoveSiteAddDTO != null && wmsMaterialMoveSiteAddDTO.getOperateNum() != null
				&& Double.isFinite(wmsMaterialMoveSiteAddDTO.getOperateNum())
				&& wmsMaterialMoveSiteAddDTO.getOperateNum() > 0, "操作数量必须为大于0的有限数值");
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		require(tenant != null && StrUtil.isNotBlank(factory), "移库必须具有实际租户和工厂上下文");
		String warehouseCode = StrUtil.trim(wmsMaterialMoveSiteAddDTO.getWarehouseCode());
		String requestId = StrUtil.trim(wmsMaterialMoveSiteAddDTO.getRequestId());
		String billNo = StrUtil.trim(wmsMaterialMoveSiteAddDTO.getBillNo());
		require(StrUtil.isNotBlank(warehouseCode) && !warehouseCode.contains("*")
				&& !warehouseCode.contains("?"), "移库仓库编码无效");
		require(StrUtil.isNotBlank(requestId) && requestId.length() <= 64,
				"移库请求唯一号不能为空且不能超过64字符");
		require(StrUtil.isNotBlank(billNo) && billNo.length() <= 80,
				"移库单号不能为空且不能超过80字符");
		wmsMaterialMoveSiteAddDTO.setRequestId(requestId);
		wmsMaterialMoveSiteAddDTO.setBillNo(billNo);
		MdmWarehouseEntity warehouse = requireWarehouse(warehouseCode, tenant, factory);
		require(warehouseBusinessGuard.check(warehouseCode, "move_site", false), "仓库未配置库内移动业务");
		String fingerprint = mobileRequestFingerprint(wmsMaterialMoveSiteAddDTO, warehouseCode);
		int reserved = baseMapper.reserveMobileRequest(tenant, factory, requestId, fingerprint, billNo);
		if (reserved == 0) {
			String committed = baseMapper.findMobileRequestFingerprint(tenant, factory, requestId);
			require(Objects.equals(fingerprint, committed),
					committed == null ? "移库单号已由其他请求使用" : "移库幂等号已被不同请求使用");
			return Boolean.TRUE;
		}
		require(reserved == 1, "移库请求回执预留失败");
		warehouseBusinessGuard.checkMobile(warehouseCode);
		var source = requireLocation(wmsMaterialMoveSiteAddDTO.getOldSiteCode(), warehouseCode, tenant, factory);
		var target = requireLocation(wmsMaterialMoveSiteAddDTO.getNewSiteCode(), warehouseCode, tenant, factory);
		require(!Objects.equals(source.getLocationCode(), target.getLocationCode())
						|| !Objects.equals(StrUtil.blankToDefault(wmsMaterialMoveSiteAddDTO.getOldContainerCode(), ""),
						StrUtil.blankToDefault(wmsMaterialMoveSiteAddDTO.getNewContainerCode(), "")),
				"源库位和容器与目标相同，无需移库");
		wmsMaterialMoveSiteAddDTO.setWarehouseCode(warehouseCode);
		wmsMaterialMoveSiteAddDTO.setWarehouseName(warehouse.getWarehouseName());
		wmsMaterialMoveSiteAddDTO.setOldSiteCode(source.getLocationCode());
		wmsMaterialMoveSiteAddDTO.setOldSiteName(source.getLocationName());
		wmsMaterialMoveSiteAddDTO.setNewSiteCode(target.getLocationCode());
		wmsMaterialMoveSiteAddDTO.setNewSiteName(target.getLocationName());
		wmsMaterialMoveSiteAddDTO.setFactoryCode(factory);
		WmsLiveStockMoveDTO wmsLiveStockMoveDTO = new WmsLiveStockMoveDTO();
		BeanUtils.copyProperties(wmsMaterialMoveSiteAddDTO, wmsLiveStockMoveDTO);
		wmsLiveStockMoveDTO.setNewWarehouseCode(wmsMaterialMoveSiteAddDTO.getWarehouseCode());
		wmsLiveStockMoveDTO.setNewWarehouseName(wmsMaterialMoveSiteAddDTO.getWarehouseName());
		wmsLiveStockMoveDTO.setOldWarehouseName(wmsMaterialMoveSiteAddDTO.getWarehouseName());
		wmsLiveStockMoveDTO.setOldWarehouseCode(wmsMaterialMoveSiteAddDTO.getWarehouseCode());
		var slices = wmsLiveStockService.billStockMoveWithEvidence(wmsLiveStockMoveDTO);
		require(slices != null && !slices.isEmpty(), "库存移动未确认");
		double recordedQuantity = 0d;
		for (var slice : slices) {
			require(slice != null && Double.isFinite(slice.quantity()) && slice.quantity() > 0,
					"库存移动返回无效数量");
			WmsLiveStockEntity sourceStock = slice.sourceStock();
			require(sourceStock != null && Objects.equals(tenant, sourceStock.getTenantId())
							&& Objects.equals(factory, sourceStock.getFactoryCode())
							&& Objects.equals(warehouseCode, sourceStock.getWarehouseCode()),
					"库存移动来源与当前组织仓库不一致");
			WmsMaterialMoveSiteEntity record = new WmsMaterialMoveSiteEntity();
			BeanUtils.copyProperties(sourceStock, record,
					"id", "createBy", "createTime", "updateBy", "updateTime", "delFlag");
			record.setBillNo(wmsMaterialMoveSiteAddDTO.getBillNo());
			record.setSourceStockId(sourceStock.getId());
			record.setTargetStockId(slice.targetStockId());
			record.setSourceInBillDetailId(sourceStock.getSourceInBillDetailId());
			record.setStockOwnerType(sourceStock.getStockOwnerType());
			record.setStockOwnerCode(sourceStock.getStockOwnerCode());
			record.setSourceBusinessNo(sourceStock.getSourceBusinessNo());
			record.setWarehouseCode(warehouseCode);
			record.setWarehouseName(warehouse.getWarehouseName());
			record.setOldSiteCode(source.getLocationCode());
			record.setOldSiteName(source.getLocationName());
			record.setNewSiteCode(target.getLocationCode());
			record.setNewSiteName(target.getLocationName());
			record.setContainerCode(wmsMaterialMoveSiteAddDTO.getNewContainerCode());
			record.setMatNum(slice.quantity());
			record.setTenantId(tenant);
			record.setFactoryCode(factory);
			require(this.save(record), "移库记录保存失败");
			recordedQuantity = WmsQuantityMath.add(recordedQuantity, slice.quantity());
		}
		require(Double.compare(recordedQuantity, wmsMaterialMoveSiteAddDTO.getOperateNum()) == 0,
				"移库记录数量与实际移动数量不一致");
		return Boolean.TRUE;
	}

	private String mobileRequestFingerprint(WmsMaterialMoveSiteAppDTO command, String warehouseCode) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("billNo", command.getBillNo());
		snapshot.put("warehouse", warehouseCode);
		snapshot.put("oldSite", StrUtil.trim(command.getOldSiteCode()));
		snapshot.put("oldContainer", StrUtil.trim(command.getOldContainerCode()));
		snapshot.put("newSite", StrUtil.trim(command.getNewSiteCode()));
		snapshot.put("newContainer", StrUtil.trim(command.getNewContainerCode()));
		snapshot.put("material", StrUtil.trim(command.getMatCode()));
		snapshot.put("batch", StrUtil.trim(command.getMatBatch()));
		snapshot.put("supplier", StrUtil.trim(command.getSupplierCode()));
		snapshot.put("unit", StrUtil.trim(command.getMatUnit()));
		snapshot.put("sourceStockId", command.getSourceStockId());
		snapshot.put("matBarcode", command.getMatBarcode());
		snapshot.put("quantity", BigDecimal.valueOf(command.getOperateNum()).stripTrailingZeros().toPlainString());
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(JSONUtil.toJsonStr(snapshot).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256不可用", exception);
		}
	}

	private MdmWarehouseEntity requireWarehouse(String warehouseCode, Long tenant, String factory) {
		require(StrUtil.isNotBlank(warehouseCode), "仓库编码不能为空");
		var response = remoteWarehouseService.getByCode(warehouseCode);
		require(response != null && response.isOk(), "读取移库仓库失败");
		MdmWarehouseEntity warehouse = response.getData();
		require(warehouse != null && Objects.equals(warehouseCode, warehouse.getWarehouseCode())
						&& Boolean.TRUE.equals(warehouse.getStatus()) && Objects.equals(tenant, warehouse.getTenantId())
						&& Objects.equals(factory, warehouse.getFactoryCode()),
				"移库仓库不存在、未启用或不属于当前组织");
		return warehouse;
	}

	private MdmWarehouseLocationEntity requireLocation(String code, String warehouse, Long tenant, String factory) {
		code = StrUtil.trim(code);
		require(StrUtil.isNotBlank(code), "源库位和目标库位不能为空");
		var response = remoteWarehouseLocationService.getByCode(code);
		require(response != null && response.isOk(), "读取移库库位失败");
		var site = response.getData();
		require(site != null && Objects.equals(code, site.getLocationCode())
						&& Objects.equals(warehouse, site.getWarehouseCode())
						&& Objects.equals(tenant, site.getTenantId()) && Boolean.TRUE.equals(site.getStatus())
						&& Objects.equals(3, site.getLocationLevel())
						&& (StrUtil.isBlank(site.getFactoryCode()) || Objects.equals(factory, site.getFactoryCode())),
				"移库库位不存在、未启用、不是实际库位或不属于当前组织仓库");
		return site;
	}

	/**
	 * 导出excel 表格
	 *
	 * @param query 查询条件
	 * @param ids   导出指定ID
	 * @return excel 文件流
	 */
	@Override
	public List<WmsMaterialMoveSiteExcelVO> export(WmsMaterialMoveSiteQueryDTO query, Long[] ids) {
		return this.list(WmsMaterialMoveSiteQueryScope.build(query, ids)).stream()
				.map(moveSite -> {
					WmsMaterialMoveSiteExcelVO wmsMaterialMoveSiteExcelVO = new WmsMaterialMoveSiteExcelVO();
					BeanUtils.copyProperties(moveSite, wmsMaterialMoveSiteExcelVO);
					return wmsMaterialMoveSiteExcelVO;
				})
				.toList();
	}
}
