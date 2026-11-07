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

	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Source(String feedId, String birthId, String birthFingerprint, String requirementCode,
			String actualCode, String batch, String barcode, String unit, String availableQuantity) {
	}

	public record Preparation(String arrangeCode, String identifier, List<Requirement> requirements,
			List<Source> sources, int unavailableCount) {
	}

	public record Checked(String arrangeCode, String identifier, String materialSelectionJson) {
	}
}
