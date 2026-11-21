package com.kitex.mom.mes.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 单件指定用料查询及核对；预览不代替报工的锁内重验。 */
public final class MesPieceMaterialSelectionDTO {
	private MesPieceMaterialSelectionDTO() {
	}

	@Data
	public static class Input {
		private String arrangeCode;
		private String identifier;
		private String materialSelectionJson;
	}

	public record Requirement(String materialCode, String unit, String quantity) {
	}

	/**
	 * 派工内可选的真实投料来源。主键、指纹及原单位数量以文本传输。
	 * @param wholePiece 原仓储签收已证明该来源为整件，只能选择原单位数量 1
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Source(String feedId, String birthId, String birthFingerprint, String requirementCode,
			String actualCode, String batch, String barcode, String unit, String availableQuantity, boolean wholePiece) {
	}

	public record Preparation(String arrangeCode, String identifier, List<Requirement> requirements,
			List<Source> sources, int unavailableCount) {
	}

	public record Checked(String arrangeCode, String identifier, String materialSelectionJson) {
	}
}
