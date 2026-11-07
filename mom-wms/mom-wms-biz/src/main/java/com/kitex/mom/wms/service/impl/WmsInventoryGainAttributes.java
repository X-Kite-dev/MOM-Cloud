package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.service.WmsIdentifierPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.*;

/**
 * 盘盈候选标识在申请前准备；不会建立实物或代替人工货权确认。
 */
@Component
@RequiredArgsConstructor
public class WmsInventoryGainAttributes {
	private static final String BILL_TYPE = "other_inventory";
	private final WmsIdentifierPolicyService identifiers;
	private final RemoteMaterialService materials;

	public WmsInBillDetailEntity prepare(WmsInsideInventoryPlanDetailEntity row, WmsInBillDetailEntity input) {
		var detail = canonical(row, input);
		fillExpiry(detail, material(row.getMatCode()));
		var policy = identifiers.resolveEffective(BILL_TYPE, row.getMatCode());
		require(policy == null || !Boolean.TRUE.equals(policy.getSerialManaged()) || detail.getMatNum() == 1d,
				"单件管理物料的盘盈差异必须为1，请细分盘点范围后办理");
		require(StrUtil.isNotBlank(detail.getMatBatch()) ||
						policy != null && Boolean.TRUE.equals(policy.getAutoBatchOnMissing()),
				"请填写实际批次，或先配置该物料的自动批次规则");
		identifiers.fillMissingIdentifiers(BILL_TYPE, detail);
		identifiers.validateInbound(BILL_TYPE, detail);
		WmsInventorySelectionSnapshot.capture(row, detail, true); // 仅结构校验；不保存确认事实。
		return detail;
	}

	/**
	 * 新申请只核验，不分配号码，不悄悄改变客户端将交审的原件。
	 */
	public void requireReady(WmsInsideInventoryPlanDetailEntity row, WmsInBillDetailEntity selected) {
		var normalized = canonical(row, selected);
		fillExpiry(normalized, material(row.getMatCode()));
		identifiers.validateInbound(BILL_TYPE, normalized);
		require(WmsInventorySelectionSnapshot.capture(row, selected, true).equals(
						WmsInventorySelectionSnapshot.capture(row, normalized, true)),
				"盘盈属性尚未按规则补全，请先补全后核对提交");
	}

	private WmsInBillDetailEntity canonical(WmsInsideInventoryPlanDetailEntity row, WmsInBillDetailEntity input) {
		require(row != null && input != null && "OWNED".equals(input.getStockOwnerType()) &&
						StrUtil.isBlank(input.getStockOwnerCode()),
				"本流程仅准备自有库存盘盈属性");
		require(StrUtil.isNotBlank(row.getMatUnit()) && Objects.equals(row.getMatUnit(), input.getMatUnit()),
				"请按原盘点计量单位准备属性");
		var quantity = number(row.getInventoryNum(), "实盘数量").subtract(number(row.getCurrentNumber(), "账面数量"));
		require(quantity.signum() > 0, "仅盘盈差异需要准备入库属性");
		var detail = new WmsInBillDetailEntity().setMatCode(row.getMatCode()).setMatUnit(row.getMatUnit())
				.setMatNum(quantity.doubleValue()).setWarehouseCode(row.getWarehouseCode())
				.setSiteCode(row.getSiteCode())
				.setFactoryCode(factory()).setStockOwnerType("OWNED").setStockOwnerCode(null)
				.setSourceBusinessNo(clean(input.getSourceBusinessNo())).setMatBatch(clean(input.getMatBatch()))
				.setMatBarcode(clean(input.getMatBarcode())).setContainerCode(clean(input.getContainerCode()))
				.setSupplierCode(clean(input.getSupplierCode())).setSupplierName(clean(input.getSupplierName()))
				.setMatProduceDate(input.getMatProduceDate()).setMatValidPeriod(input.getMatValidPeriod())
				.setMatValidEndDate(input.getMatValidEndDate());
		require(StrUtil.isBlank(row.getMatBatch()) || Objects.equals(row.getMatBatch(), detail.getMatBatch()),
				"批次必须与原盘点范围一致");
		require(StrUtil.isAllNotBlank(detail.getSupplierCode(), detail.getSupplierName()) &&
						detail.getMatProduceDate() != null,
				"请先填写实际供应商和生产日期");
		require(detail.getMatProduceDate().getYear() >= 1 && detail.getMatProduceDate().getYear() <= 9999,
				"生产日期超出支持范围");
		require(detail.getSourceBusinessNo() == null || (detail.getSourceBusinessNo().length() <= 128
				&& !detail.getSourceBusinessNo().chars().anyMatch(Character::isISOControl)), "物料来源单号格式无效");
		require(detail.getMatValidEndDate() == null || (detail.getMatValidEndDate().getYear() >= 1
				&& detail.getMatValidEndDate().getYear() <= 9999), "到期日超出支持范围");
		return detail;
	}

	private void fillExpiry(WmsInBillDetailEntity detail, MdmMaterialEntity material) {
		Integer days = detail.getMatValidPeriod();
		if (Boolean.TRUE.equals(material.getExpiration())) {
			require(material.getExpirationDay() != null && material.getExpirationDay() >= 0,
					"物料有效期配置不完整，请先维护物料");
			if (days == null) {
				days = material.getExpirationDay();
			}
		}
		require(days == null || days >= 0, "有效期须为非负整数天");
		if (days != null) {
			java.time.LocalDate end;
			try {
				end = detail.getMatProduceDate().plusDays(days);
			} catch (java.time.DateTimeException ex) {
				throw new com.kitex.common.core.exception.CheckedException("有效期超出支持日期范围");
			}
			require(end.getYear() <= 9999, "有效期超出支持日期范围");
			require(detail.getMatValidEndDate() == null || detail.getMatValidEndDate().equals(end),
					"到期日与生产日期、有效期不一致");
			detail.setMatValidPeriod(days);
			detail.setMatValidEndDate(end);
		}
		require(detail.getMatValidEndDate() == null ||
				!detail.getMatValidEndDate().isBefore(detail.getMatProduceDate()), "到期日不能早于生产日期");
	}

	private MdmMaterialEntity material(String code) {
		var response = materials.getDetails(code);
		var material = response == null || !response.isOk() ? null : response.getData();
		require(material != null && Objects.equals(material.getTenantId(), TenantContextHolder.getTenantId())
				&& Objects.equals(material.getFactoryCode(), factory()) &&
				Objects.equals(material.getMaterialCode(), code), "物料不存在或不属于当前租户工厂");
		return material;
	}

	private static String clean(String value) {
		return StrUtil.emptyToNull(StrUtil.trim(value));
	}
}
