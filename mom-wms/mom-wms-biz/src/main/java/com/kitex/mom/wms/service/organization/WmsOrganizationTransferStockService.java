package com.kitex.mom.wms.service.organization;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.*;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.organization.*;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.mapper.WmsContainerBarcodeMapper;
import com.kitex.mom.wms.entity.WmsContainerBarcodeEntity;
import com.kitex.mom.wms.service.*;
import com.kitex.mom.wms.service.organization.WmsOrganizationTransferAccess.Scope;
import com.kitex.mom.wms.util.WmsStockAvailability;
import com.kitex.mom.wms.util.WmsSerialStockGuard;
import com.kitex.mom.wms.util.WmsStockOwnership;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static com.kitex.mom.wms.service.organization.WmsOrganizationTransferRules.require;

/**
 * 仅在当前一端上下文执行实物写入；共享协同单不赋予跨工厂库存读取能力。
 */
@Service
@RequiredArgsConstructor
public class WmsOrganizationTransferStockService {
	private final WmsLiveStockMapper stocks;
	private final WmsLiveStockService live;
	private final WmsInoutFlowMapper flows;
	private final WmsMaterialReservationService productionReservations;
	private final WmsSalesStockReservationService salesReservations;
	private final RemoteMaterialService materials;
	private final RemoteWarehouseLocationService locations;
	private final WmsOrganizationTransferAccess access;
	private final WmsContainerBarcodeMapper containers;

	public StockPage candidates(Scope scope, String material, String barcode, Long beforeStockId) {
		require(beforeStockId == null || beforeStockId > 0, "库存分页位置无效");
		require(text(material).length() <= 100 && text(barcode).length() <= 200, "物料或条码筛选内容过长");
		access.permission(scope, "add");
		var rows = stocks.selectList(new LambdaQueryWrapper<WmsLiveStockEntity>()
				.eq(WmsLiveStockEntity::getTenantId, scope.tenant()).eq(WmsLiveStockEntity::getFactoryCode,
						scope.factory())
				.eq(WmsLiveStockEntity::getWarehouseCode, scope.warehouse()).eq(WmsLiveStockEntity::getStatus, 0)
				.like(material != null && !material.isBlank(), WmsLiveStockEntity::getMatCode, text(material))
				.eq(!text(barcode).isEmpty(), WmsLiveStockEntity::getMatBarcode, text(barcode))
				.lt(beforeStockId != null, WmsLiveStockEntity::getId, beforeStockId)
				.orderByDesc(WmsLiveStockEntity::getId).last("LIMIT 101"));
		Long nextCursor = rows.size() > 100 ? rows.get(99).getId() : null;
		var visible = rows.stream().limit(100).filter(WmsStockAvailability::isUsable).map(row -> {
			var copy = new WmsLiveStockEntity();
			BeanUtils.copyProperties(row, copy);
			copy.setMatNum(available(row).doubleValue());
			return copy;
		}).filter(row -> row.getMatNum() > 0).toList();
		return new StockPage(visible, nextCursor);
	}

	public WmsLiveStockEntity freeze(Scope scope, Long stockId, BigDecimal quantity, String company) {
		quantity(quantity);
		require(stockId != null && stockId > 0, "请选择发出库存");
		var row = stocks.selectOne(
				new LambdaQueryWrapper<WmsLiveStockEntity>().eq(WmsLiveStockEntity::getTenantId, scope.tenant())
						.eq(WmsLiveStockEntity::getFactoryCode, scope.factory()).eq(
								WmsLiveStockEntity::getWarehouseCode, scope.warehouse())
						.eq(WmsLiveStockEntity::getId, stockId));
		require(row != null && Objects.equals(row.getTenantId(), scope.tenant()) &&
						Objects.equals(row.getFactoryCode(), scope.factory())
						&& Objects.equals(row.getWarehouseCode(), scope.warehouse()) && WmsStockAvailability.isUsable(row),
				"源库存不可用或不属于当前仓库");
		require(text(row.getStockOwnerCode()).isEmpty() || company.equals(row.getStockOwnerCode()),
				"源库存自有归属与库存组织公司不一致");
		require(available(row).compareTo(quantity) >= 0, "扣除生产、销售预留后可调拨数量不足");
		WmsSerialStockGuard.requireWhole(row, quantity);
		material(scope, row);
		var copy = new WmsLiveStockEntity();
		BeanUtils.copyProperties(row, copy);
		copy.setMatNum(quantity.doubleValue());
		return copy;
	}

	public void material(Scope scope, WmsLiveStockEntity original) {
		var response = materials.getDetails(original.getMatCode());
		var row = response == null ? null : response.getData();
		require(response != null && response.isOk() && row != null && Boolean.TRUE.equals(row.getStatus())
				&& Objects.equals(row.getTenantId(), scope.tenant()) &&
				Objects.equals(row.getFactoryCode(), scope.factory())
				&& Objects.equals(row.getMaterialCode(), original.getMatCode()), "当前组织没有可用的对应物料主数据");
		require(Objects.equals(row.getBasicUnit(), original.getMatUnit()), "收发组织物料标准单位不一致，禁止静默换算");
		require(Boolean.TRUE.equals(row.getSerialManaged()) == Boolean.TRUE.equals(original.getSerialManaged()),
				"收发组织单件序列号管理要求不一致");
		require(!Boolean.TRUE.equals(row.getBatchManaged()) || !text(original.getMatBatch()).isEmpty(),
				"接收物料要求原始批次");
	}

	public Effect ship(Scope scope, WmsOrganizationTransferBill bill, WmsOrganizationTransferLine line,
	                   InputLine input, String request) {
		var original = source(line);
		require(input.getQuantity().compareTo(line.getQuantity()) == 0, "发出须逐行核对已确认数量");
		if (Boolean.TRUE.equals(original.getSerialManaged()))
			require(Objects.equals(original.getMatBarcode(), input.getBarcode()) && !text(input.getBarcode()).isEmpty(),
					"请扫描本行实际发出序列号");
		material(scope, original);
		container(scope, original.getContainerCode());
		var actual = live.transferOut(line.getSourceStockId(), line.getQuantity().doubleValue(), scope.warehouse());
		require(actual != null && Objects.equals(actual.getTenantId(), scope.tenant()) &&
						Objects.equals(actual.getFactoryCode(), scope.factory())
						&& Objects.equals(identity(original), identity(actual)),
				"源库存批次、条码、单位、货权或来源已变化，请重新申请");
		var flow = new WmsInoutFlowEntity();
		BeanUtils.copyProperties(actual, flow);
		flow.setStockInTime(actual.getInTime());
		flow.setSourceStockId(line.getSourceStockId());
		prepareFlow(flow, scope, bill, line, request, "ISSUE", false);
		line.setIssueFlowId(flow.getId());
		line.setIssueSnapshot(JSONUtil.toJsonStr(flow));
		live.releaseTransferSourceContainer(flow);
		return new Effect(line.getId(), scope.factory(), scope.warehouse(), "SHIP", actual.getId(), flow.getId(),
				line.getQuantity(), LocalDateTime.now());
	}

	public Effect receive(Scope scope, WmsOrganizationTransferBill bill, WmsOrganizationTransferLine line,
	                      InputLine input, String request, boolean returning) {
		quantity(input.getQuantity());
		require(line.getIssueSnapshot() != null && line.getIssueFlowId() != null, "缺少原始发出实物凭证");
		var original = JSONUtil.toBean(line.getIssueSnapshot(), WmsInoutFlowEntity.class);
		require(Objects.equals(original.getId(), line.getIssueFlowId()) &&
				Objects.equals(original.getTenantId(), scope.tenant())
				&& Objects.equals(original.getFactoryCode(), bill.getSourceFactoryCode())
				&& Objects.equals(original.getWarehouseCode(), bill.getSourceWarehouseCode())
				&& Objects.equals(original.getBillId(), bill.getId()) &&
				Objects.equals(original.getBillDetailId(), line.getId()), "组织调拨原始发出范围不一致");
		if (Boolean.TRUE.equals(original.getSerialManaged())) {
			require(Objects.equals(original.getMatBarcode(), input.getBarcode()) && !text(input.getBarcode()).isEmpty(),
					"请扫描本行原始序列号核对实物");
		}
		material(scope, source(line));
		var warehouse = access.warehouse(scope);
		container(scope, input.getContainerCode());
		MdmWarehouseLocationEntity location = location(scope, input.getSiteCode());
		var incoming = new WmsInoutFlowEntity();
		BeanUtils.copyProperties(original, incoming);
		incoming.setMatNum(input.getQuantity().doubleValue());
		incoming.setFactoryCode(scope.factory());
		incoming.setTenantId(scope.tenant());
		if (!returning && !bill.getSourceCompanyCode().equals(bill.getTargetCompanyCode())) {
			incoming.setStockOwnerType(WmsStockOwnership.OWNED);
			incoming.setStockOwnerCode("");
			incoming.setSourceBusinessNo(bill.getBillNo());
		}
		Long stockId = live.transferInWithEvidence(incoming, scope.warehouse(), warehouse.getWarehouseName(),
				location.getLocationCode(), location.getLocationName(), input.getContainerCode());
		require(stockId != null && stockId > 0, "入库未返回可核对的实物标识");
		incoming.setSourceStockId(stockId);
		incoming.setSourceFlowId(line.getIssueFlowId());
		incoming.setWarehouseCode(scope.warehouse());
		incoming.setWarehouseName(warehouse.getWarehouseName());
		incoming.setSiteCode(location.getLocationCode());
		incoming.setSiteName(location.getLocationName());
		incoming.setContainerCode(input.getContainerCode());
		prepareFlow(incoming, scope, bill, line, request, returning ? "CANCEL_RETURN" : "RECEIVE", true);
		return new Effect(line.getId(), scope.factory(), scope.warehouse(), returning ? "RETURN" : "RECEIVE", stockId,
				incoming.getId(), input.getQuantity(), LocalDateTime.now());
	}

	private void prepareFlow(WmsInoutFlowEntity flow, Scope scope, WmsOrganizationTransferBill bill,
	                         WmsOrganizationTransferLine line, String request, String action, boolean inbound) {
		flow.setId(IdUtil.getSnowflakeNextId());
		flow.setTenantId(scope.tenant());
		flow.setFactoryCode(scope.factory());
		flow.setCreateBy(null);
		flow.setCreateTime(null);
		flow.setUpdateBy(null);
		flow.setUpdateTime(null);
		flow.setDelFlag(null);
		flow.setTransferReceiptId(null);
		flow.setVarianceCaseId(null);
		flow.setVarianceLineId(null);
		flow.setBillId(bill.getId());
		flow.setBillDetailId(line.getId());
		flow.setBillNo(bill.getBillNo());
		flow.setBillType(inbound ? InBillTypeEnum.ALLOCATION.getCode() : OutBillTypeEnum.ALLOCATION_OUT.getCode());
		flow.setTransferAction(action);
		flow.setOperateType(inbound ? "in" : "out");
		flow.setOperationRequestId(request);
		flow.setStockProofType("ORGANIZATION_TRANSFER");
		require(flows.insert(flow) == 1, "组织调拨库存流水写入失败");
	}

	private MdmWarehouseLocationEntity location(Scope scope, String code) {
		require(!text(code).isEmpty(), "请选择实际收货库位");
		var response = locations.getByCode(code.trim());
		var row = response == null ? null : response.getData();
		require(response != null && response.isOk() && row != null && Boolean.TRUE.equals(row.getStatus())
				&& Objects.equals(row.getWarehouseCode(), scope.warehouse()) &&
				Objects.equals(row.getFactoryCode(), scope.factory())
				&& Objects.equals(row.getTenantId(), scope.tenant()) &&
				Objects.equals(row.getLocationCode(), code.trim()), "库位不属于当前仓库或已停用");
		return row;
	}

	private void container(Scope scope, String code) {
		var warehouse = access.warehouse(scope);
		require(!Objects.equals(warehouse.getIsContainer(), 1) || !text(code).isEmpty(),
				"当前仓库启用容器管理，请填写实际容器");
		if (text(code).isEmpty()) return;
		var row = containers.selectOne(new LambdaQueryWrapper<WmsContainerBarcodeEntity>()
				.eq(WmsContainerBarcodeEntity::getTenantId, scope.tenant()).eq(
						WmsContainerBarcodeEntity::getFactoryCode, scope.factory())
				.eq(WmsContainerBarcodeEntity::getWarehouseCode, scope.warehouse()).eq(
						WmsContainerBarcodeEntity::getCode, text(code)));
		require(row != null && Objects.equals(row.getTenantId(), scope.tenant()) &&
						Objects.equals(row.getFactoryCode(), scope.factory()) &&
						Objects.equals(row.getWarehouseCode(), scope.warehouse()) && Objects.equals(row.getCode(), text(code)),
				"容器不存在或不属于当前仓库");
	}

	private BigDecimal available(WmsLiveStockEntity row) {
		require(row.getMatNum() != null && Double.isFinite(row.getMatNum()), "源库存数量无效");
		return BigDecimal.valueOf(row.getMatNum()).subtract(productionReservations.activeReservedNum(row.getId()))
				.subtract(salesReservations.activeReservedNums(Set.of(row.getId())).getOrDefault(row.getId(),
						BigDecimal.ZERO)).max(BigDecimal.ZERO);
	}

	public WmsLiveStockEntity source(WmsOrganizationTransferLine line) {
		return JSONUtil.toBean(line.getSourceSnapshot(), WmsLiveStockEntity.class);
	}

	private Map<String, Object> identity(WmsLiveStockEntity row) {
		var all = BeanUtil.beanToMap(row);
		Map<String, Object> selected = new LinkedHashMap<>();
		for (String field : List.of("id", "tenantId", "factoryCode", "warehouseCode", "siteCode", "containerCode",
				"matCode", "matUnit", "matBatch", "matBarcode", "serialManaged", "supplierCode", "stockOwnerType",
				"stockOwnerCode", "sourceBusinessNo", "sourceInBillId", "sourceInBillDetailId",
				"sourceMesInboundRequestId", "sourceOrderNo", "sourceOrderDetailId", "sourceReceiptId",
				"sourceReceiptDetailId", "sourceQualityTaskIds", "sourceQualityEvidenceFingerprint", "matProduceDate",
				"matValidEndDate", "qualityStatus", "qualityResult", "qualityTaskId"))
			selected.put(field, all.get(field));
		return selected;
	}

	public static void quantity(BigDecimal value) {
		require(value != null && value.signum() > 0 && Double.isFinite(value.doubleValue())
						&& BigDecimal.valueOf(value.doubleValue()).compareTo(value) == 0,
				"实物数量必须为大于0且可准确处理的数值");
	}

	private String text(String value) {
		return value == null ? "" : value.trim();
	}
}
