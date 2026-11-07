package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only original consumption evidence. Quantities are original decimal text, never net balances.
 */
public final class MesMaterialConsumptionTraceDTO {
	private MesMaterialConsumptionTraceDTO() {
	}

	@Data
	@Accessors(chain = true)
	public static class Query {
		private String taskCode;
		/** 原生产报告规范标识，与原料条码分别筛选。 */
		private String productIdentifier;
		/** 报告ID必须同时指定GOOD/NG域，禁止同数值跨域猜配。 */
		private String reportId;
		private String reportDomain;
		private String arrangeCode;
		private String requirementMaterialCode;
		private String actualMaterialCode;
		private String batchNo;
		private String sourceKind;
		private Integer current;
		private Integer size;
	}

	@Data
	@Accessors(chain = true)
	public static class Row {
		private String key;
		private String sourceKind;
		private String sourceId;
		private String sourceTable;
		/** 原报告字段缺失保持null，不由今日模式或数量猜单件身份。 */
		@JsonInclude(JsonInclude.Include.ALWAYS)
		private String productIdentifier;
		@JsonInclude(JsonInclude.Include.ALWAYS)
		private String productionReportMode;
		@JsonInclude(JsonInclude.Include.ALWAYS)
		private String productionReportQuantity;
		private String taskCode;
		private String produceTaskCode;
		private String arrangeCode;
		private String feedId;
		private String stockId;
		private String reportId;
		private String reportDomain;
		private String rootId;
		private String requestId;
		private String actualMaterialCode;
		private String requirementMaterialCode;
		private String batchNo;
		private String barcode;
		private String unit;
		private String requirementUnit;
		private String quantity;
		private String requirementQuantity;
		/** 原报告精确关联是否存在；无报告域的组合耗料不适用。 */
		private Boolean reportLinked;
		/** 原投料精确关联是否存在；倒冲使用库存主键，不适用投料关联。 */
		private Boolean feedLinked;
		private String sourceStatus;
		private String restorationStatus;
		private LocalDateTime occurredAt;
		private List<String> dataGaps = new ArrayList<>();
	}

	@Data
	@Accessors(chain = true)
	public static class Page {
		private String contractVersion = "MES_MATERIAL_TRACE_V1";
		private String tenantId;
		private String factoryCode;
		private LocalDateTime observedAt;
		private Query query;
		private Integer current;
		private Integer size;
		private long total;
		private List<Row> records = new ArrayList<>();
		private List<String> dataGaps = new ArrayList<>();
	}

	@Data
	@Accessors(chain = true)
	public static class Fact {
		private String tableName;
		private String sourceId;
		private Map<String, String> fields;
	}

	@Data
	@Accessors(chain = true)
	public static class Detail {
		private String contractVersion = "MES_MATERIAL_TRACE_V1";
		private String tenantId;
		private String factoryCode;
		private LocalDateTime observedAt;
		private Row record;
		private Fact originalRoot;
		private List<Fact> attributions = new ArrayList<>();
		private List<Fact> restores = new ArrayList<>();
		private List<Fact> reversals = new ArrayList<>();
		private List<Fact> relatedSources = new ArrayList<>();
		private List<String> dataGaps = new ArrayList<>();
	}
}
