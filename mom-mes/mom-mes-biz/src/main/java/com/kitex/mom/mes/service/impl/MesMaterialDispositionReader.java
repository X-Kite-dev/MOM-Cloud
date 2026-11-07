package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesInventoryQualityOrigin;
import com.kitex.mom.mes.dto.MesMaterialConsumptionTraceDTO;
import com.kitex.mom.mes.dto.MesMaterialDispositionDTO;
import com.kitex.mom.mes.entity.MesInventoryQualityCaseEntity;
import com.kitex.mom.mes.entity.MesMaterialReconcileIssueEntity;
import com.kitex.mom.mes.entity.MesTaskReportUnqualifiedEntity;
import com.kitex.mom.mes.mapper.MesTaskReportUnqualifiedMapper;
import com.kitex.mom.mes.mapper.MesInventoryQualityCaseMapper;
import com.kitex.mom.mes.mapper.MesMaterialReconcileIssueMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 原耗料按需关联业务单据，不读取整个工厂的模糊物料清单，不执行扫描或建单。
 * 产出NG隔离案件与原料预留差异分开，不宣称一方是另一方的根因。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MesMaterialDispositionReader {
	private final MesMaterialConsumptionTraceService trace;
	private final MesMaterialReconcileIssueMapper issues;
	private final MesInventoryQualityCaseMapper cases;
	private final MesTaskReportUnqualifiedMapper ngReportsMapper;
	private final MesCaseDispositionService caseDisposition;
	private final MesActualFactoryAccessService factoryAccess;

	/**
	 * 原投料/倒冲流水冻结的预留物料行关联；不取当前共享库存身份补历史。
	 */
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show') && @pms.hasPermission('mesMaterialReconcileIssue:show')")
	public MesMaterialDispositionDTO.Result reconciliation(String kind, String sourceId,
	                                                       MesMaterialDispositionDTO.Query query) {
		var detail = detail(kind, sourceId, query);
		var row = detail.getRecord();
		var result = result(detail, query, "RECONCILIATION");
		boolean policyStock = "NG_POLICY_BACKFLUSH".equals(kind);
		String table = "GOOD_BACKFLUSH".equals(kind) || policyStock ? "mes_warehouse_inout" : "mes_warehouse_feed";
		String originalId = "GOOD_BACKFLUSH".equals(kind) ? row.getSourceId() : row.getFeedId();
		if (policyStock) {
			originalId = detail.getRelatedSources().stream()
					.filter(fact -> "mes_ng_material_policy_allocation".equals(fact.getTableName())
							&& Objects.equals(row.getSourceId(), fact.getSourceId()))
					.map(fact -> fact.getFields().get("source_flow_id")).filter(Objects::nonNull).findFirst()
					.orElse(null);
		}
		String sourceKey = originalId;
		var source = detail.getRelatedSources().stream().filter(fact -> table.equals(fact.getTableName())
				&& Objects.equals(sourceKey, fact.getSourceId())).findFirst().orElse(null);
		String reservation = source == null ? null : source.getFields().get("reservation_no");
		if (!StringUtils.hasText(reservation) || !StringUtils.hasText(row.getRequirementMaterialCode())
				|| !StringUtils.hasText(row.getActualMaterialCode())) {
			return result.setAssociation("NO_LINK").setMessage("原供料未保存完整预留物料关联");
		}
		var page = issues.selectPage(Page.of(query.getCurrent(), query.getSize()),
				new LambdaQueryWrapper<MesMaterialReconcileIssueEntity>()
						.eq(MesMaterialReconcileIssueEntity::getTenantId, TenantContextHolder.getTenantId())
						.eq(MesMaterialReconcileIssueEntity::getFactoryCode, detail.getFactoryCode())
						.eq(MesMaterialReconcileIssueEntity::getReservationNo, reservation)
						.eq(MesMaterialReconcileIssueEntity::getRequirementMatCode, row.getRequirementMaterialCode())
						.eq(MesMaterialReconcileIssueEntity::getActualMatCode, row.getActualMaterialCode())
						.orderByDesc(MesMaterialReconcileIssueEntity::getLastFoundTime)
						.orderByDesc(MesMaterialReconcileIssueEntity::getId));
		return result.setAssociation(page.getTotal() == 0 ? "EMPTY" : "LINKED")
				.setMessage(
						page.getTotal() == 0 ? "同预留物料暂无对账差异记录" : "按原预留和物料关联，不代表耗料偏差根因")
				.setTotal(page.getTotal())
				.setRecords(page.getRecords().stream().map(issue -> new MesMaterialDispositionDTO.Item()
						.setId(issue.getId().toString()).setTitle(issue.getTitle()).setStatus(issue.getStatus())
						.setReservationNo(issue.getReservationNo())
						.setRequirementMaterialCode(issue.getRequirementMatCode())
						.setMaterialCode(issue.getActualMatCode()).setOwnerName(issue.getOwnerName())
						.setLastFoundTime(issue.getLastFoundTime())).toList());
	}

	/**
	 * 沿原bad:add质量来源预览权限及source工厂核验；只看真实NG产出的原隔离案件。
	 */
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show') && @pms.hasPermission('bad:add')")
	public MesMaterialDispositionDTO.Result outputQuality(String kind, String sourceId,
	                                                      MesMaterialDispositionDTO.Query query) {
		// 原质量来源预览要求工厂write；无关联、空页和总数同样不能绕过此项核验。
		String factory = factoryAccess.require("write");
		var detail = detail(kind, sourceId, query);
		require(Objects.equals(factory, detail.getFactoryCode()), "质量预览工厂与原耗料范围不一致");
		var row = detail.getRecord();
		var result = result(detail, query, "OUTPUT_QUALITY");
		Set<Long> ngReports = new LinkedHashSet<>();
		if (Set.of("NG_FEED", "NG_POLICY_FEED", "NG_POLICY_BACKFLUSH").contains(kind)
				&& "NG".equals(row.getReportDomain()) && Boolean.TRUE.equals(row.getReportLinked())) {
			ngReports.add(id(row.getReportId()));
		} else if ("COMBINED_FEED".equals(kind)) {
			for (var fact : detail.getAttributions()) {
				var fields = fact.getFields();
				if ("NG".equals(fields.get("output_kind")) && fields.get("good_report_id") == null) {
					ngReports.add(id(fields.get("ng_report_id")));
				}
			}
		}
		require(ngReports.size() <= 200, "组合不良产出超过关联范围，请回原质量工作台查询");
		if (ngReports.isEmpty()) {
			return result.setAssociation("NO_LINK").setMessage("该耗料未关联真实不良产出报告");
		}
		var actualReports = ngReportsMapper.selectList(new LambdaQueryWrapper<MesTaskReportUnqualifiedEntity>()
				.eq(MesTaskReportUnqualifiedEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(MesTaskReportUnqualifiedEntity::getFactoryCode, detail.getFactoryCode())
				.eq(MesTaskReportUnqualifiedEntity::getTaskCode, row.getTaskCode())
				.eq(MesTaskReportUnqualifiedEntity::getArrangeCode, row.getArrangeCode())
				.eq(MesTaskReportUnqualifiedEntity::getProduceTaskCode, row.getProduceTaskCode())
				.in(MesTaskReportUnqualifiedEntity::getId, ngReports));
		require(actualReports.size() == ngReports.size(), "原不良产出报告不完整或不属于本次工单、工序与派工");
		var page = cases.selectPage(Page.of(query.getCurrent(), query.getSize()),
				new LambdaQueryWrapper<MesInventoryQualityCaseEntity>()
						.eq(MesInventoryQualityCaseEntity::getTenantId, TenantContextHolder.getTenantId())
						.eq(MesInventoryQualityCaseEntity::getFactoryCode, detail.getFactoryCode())
						.eq(MesInventoryQualityCaseEntity::getOriginType, "PRODUCTION_NG")
						.in(MesInventoryQualityCaseEntity::getSourceUnqualifiedReportId, ngReports)
						.orderByDesc(MesInventoryQualityCaseEntity::getId));
		return result.setAssociation(page.getTotal() == 0 ? "EMPTY" : "LINKED")
				.setMessage(page.getTotal() ==
						0 ? "真实不良产出暂无实物隔离案件，不代表质量已结案" : "仅关联不良产出案件，原料质量须另行核查")
				.setTotal(page.getTotal()).setRecords(page.getRecords().stream().map(candidate -> {
					// 复用原实物余额与永久处置账核定；不取最新一条回执猜案件已结案。
					var actual = caseDisposition.source(candidate.getId());
					require(Objects.equals(actual.getTenantId(), TenantContextHolder.getTenantId())
							&& Objects.equals(actual.getFactoryCode(), detail.getFactoryCode())
							&& ngReports.contains(actual.getSourceUnqualifiedReportId())
							&& MesInventoryQualityOrigin.PRODUCTION_RESOURCE.equals(
							MesInventoryQualityOrigin.resourceType(actual)), "质量案件不属于原不良产出范围");
					boolean waitingVerification = actual.getReservedQuantity().signum() > 0
							|| actual.getQuantity().subtract(actual.getRemainingQuantity())
							.compareTo(actual.getClosedQuantity()) > 0;
					String status =
							actual.getClosedQuantity().compareTo(actual.getQuantity()) == 0 ? "PHYSICAL_VERIFIED"
									: waitingVerification ? "WAIT_VERIFY" : "IN_PROGRESS";
					return new MesMaterialDispositionDTO.Item().setId(actual.getId().toString())
							.setTitle("不良产出实物隔离")
							.setStatus(status).setResourceType(MesInventoryQualityOrigin.PRODUCTION_RESOURCE)
							.setNgReportId(actual.getSourceUnqualifiedReportId().toString())
							.setMaterialCode(actual.getMatCode())
							.setUnit(actual.getMatUnit()).setOriginalQuantity(text(actual.getQuantity()))
							.setRemainingQuantity(text(actual.getRemainingQuantity()))
							.setReservedQuantity(text(actual.getReservedQuantity()))
							.setVerifiedQuantity(text(actual.getClosedQuantity()));
				}).toList());
	}

	private MesMaterialConsumptionTraceDTO.Detail detail(String kind, String id,
	                                                     MesMaterialDispositionDTO.Query query) {
		require(query != null && query.getCurrent() != null && query.getCurrent() > 0 && query.getCurrent() <= 100000
						&& query.getSize() != null && query.getSize() > 0 && query.getSize() <= 50,
				"关联分页每页1至50条，页码须为正数");
		var detail = trace.detail(kind, id, query.getTaskCode(), query.getArrangeCode());
		require(detail.getOriginalRoot() != null, "原生产主账关联未核清，暂不能定位业务处置");
		return detail;
	}

	private MesMaterialDispositionDTO.Result result(MesMaterialConsumptionTraceDTO.Detail detail,
	                                                MesMaterialDispositionDTO.Query query, String section) {
		return new MesMaterialDispositionDTO.Result().setTenantId(detail.getTenantId())
				.setFactoryCode(detail.getFactoryCode())
				.setRecordKey(detail.getRecord().getKey()).setTaskCode(detail.getRecord().getTaskCode())
				.setArrangeCode(detail.getRecord().getArrangeCode()).setSection(section)
				.setObservedAt(LocalDateTime.now())
				.setCurrent(query.getCurrent()).setSize(query.getSize()).setTotal(0L).setRecords(List.of());
	}

	private Long id(String value) {
		try {
			require(value != null && value.matches("[1-9][0-9]{0,18}"), "原不良报告主键未核实");
			return Long.valueOf(value);
		} catch (NumberFormatException invalid) {
			throw new CheckedException("原不良报告主键超出有效范围");
		}
	}

	private String text(BigDecimal value) {
		return value == null ? null : value.stripTrailingZeros().toPlainString();
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
