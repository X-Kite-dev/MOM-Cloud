package com.kitex.mom.pms.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.pms.dto.PmsSupplierDTO;
import com.kitex.mom.pms.entity.PmsSupplierAptitudeEntity;

import java.math.BigDecimal;

/**
 * 供应商资料维护的数值及日期边界；在MDM远端和本地资料写入之前统一校验。
 * 可选字段为空保持原合同，历史过期资质允许登记，但有效期起止不能倒置。
 */
final class PmsSupplierMaintenanceValidator {

	private PmsSupplierMaintenanceValidator() {
	}

	/**
	 * 校验本次提交的交付天数、税率及证件日期，不推断缺失值或变更审核状态。
	 * @param supplier 待维护的原供应商资料
	 * @throws CheckedException 数值越界、日期倒置或资质集合含空项时拒绝全部写入
	 */
	static void validate(PmsSupplierDTO supplier) {
		if (supplier == null) {
			throw new CheckedException("供应商资料不能为空");
		}
		nonNegative(supplier.getDeliveryLeadTime(), "交货提前期");
		nonNegative(supplier.getDeliveryCycle(), "交货周期");
		nonNegative(supplier.getOutsourcingIssuanceCycle(), "委外发料周期");
		nonNegative(supplier.getBeginningOfMonthOutsourcingIssuanceCycle(), "月初委外发料周期");
		BigDecimal rate = supplier.getTaxRate();
		if (rate != null && (rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0)) {
			throw new CheckedException("税率必须在0至100%之间");
		}
		if (supplier.getEstablishmentDate() != null && supplier.getLicenseExpiryDate() != null
				&& supplier.getLicenseExpiryDate().isBefore(supplier.getEstablishmentDate())) {
			throw new CheckedException("证件有效截止日期不能早于公司成立日期");
		}
		if (supplier.getAptitudeList() != null) {
			for (PmsSupplierAptitudeEntity aptitude : supplier.getAptitudeList()) {
				if (aptitude == null) {
					throw new CheckedException("资质信息不能包含空记录");
				}
				if (aptitude.getAptitudeValidFrom() != null && aptitude.getAptitudeValidTo() != null
						&& aptitude.getAptitudeValidTo().isBefore(aptitude.getAptitudeValidFrom())) {
					throw new CheckedException("资质有效期止不能早于有效期起");
				}
			}
		}
	}

	/** Integer天数保持原精度，可空或零；不接受负数作为未知值。 */
	private static void nonNegative(Integer days, String field) {
		if (days != null && days < 0) {
			throw new CheckedException(field + "不能为负数");
		}
	}
}
