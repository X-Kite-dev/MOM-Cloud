package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.mapper.WmsInventoryCountTemplateMapper;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import com.kitex.mom.wms.service.impl.WmsInventoryWarehousePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.factory;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 可复用盘点任务模板；预览只读，触发后仍走盘点计划的全部保存校验。
 */
@Service
@RequiredArgsConstructor
public class WmsInventoryCountTemplateService {
	private static final int MAX_SITES = 10;
	private static final int MAX_ROWS = 1000;
	private static final TypeReference<List<String>> SITE_CODES = new TypeReference<>() {
	};

	private final WmsInventoryCountTemplateMapper templates;
	private final WmsInventoryScopeSuggestionService suggestions;
	private final WmsInsideInventoryPlanService plans;
	private final RemoteSeqConfigService sequences;
	private final RemoteWarehouseService warehouses;
	private final RemoteWarehouseLocationService locations;
	private final WmsWarehouseMenuMapper menus;
	private final WmsInventoryWarehousePermission inventoryPermission;
	private final PermissionService permissions;
	private final ObjectMapper json;

	public List<Template> list(String warehouseCode) {
		Scope scope = scope(warehouseCode);
		List<WmsInventoryCountTemplateMapper.TemplateRow> rows = templates.list(
				scope.tenant(), scope.factory(), scope.warehouse());
		require(rows != null && rows.size() <= 100, "盘点模板列表超过100条，请核对配置");
		List<Template> result = new ArrayList<>(rows.size());
		for (var row : rows) {
			result.add(readTemplate(row, scope));
		}
		return List.copyOf(result);
	}

	@Transactional(rollbackFor = Exception.class)
	public Template save(SaveCommand command) {
		require(command != null, "请填写盘点模板");
		Scope scope = scope(command.warehouseCode());
		String code = code(command.templateCode());
		String name = StrUtil.trim(command.templateName());
		require(StrUtil.isNotBlank(name) && name.length() <= 80, "模板名称须为1至80个字符");
		require(command.expectedVersion() >= 0, "模板版本无效");
		require(StrUtil.isNotBlank(command.ruleCode())
				&& command.ruleCode().equals(command.ruleCode().trim()), "盘点规则无效");
		WmsInventoryScopeSuggestionService.validateRule(command.ruleCode(), command.thresholdDays());
		require("SNAPSHOT".equals(command.stockFreezeMode())
				|| "FREEZE_COUNT".equals(command.stockFreezeMode()), "盘点冻结模式无效");
		List<String> siteCodes = validateSiteCodes(command.siteCodes());
		requireConfigurationAccess(scope);
		requireWarehouseAndSites(scope, siteCodes);
		var previous = templates.find(scope.tenant(), scope.factory(), code);
		int version = previous == null ? 0 : previous.version();
		require((previous == null || scope.warehouse().equals(previous.warehouseCode()))
						&& version == command.expectedVersion() && version < Integer.MAX_VALUE,
				"模板已更新或所属仓库不同，请刷新后重试");
		if (previous == null) {
			Long count = templates.countInWarehouse(scope.tenant(), scope.factory(), scope.warehouse());
			require(count != null && count >= 0 && count < 100, "当前仓库最多配置100个盘点模板");
		}
		String operator = operator();
		String siteCodesJson;
		try {
			siteCodesJson = json.writeValueAsString(siteCodes);
		} catch (JsonProcessingException exception) {
			throw new CheckedException("盘点库位配置序列化失败");
		}
		require(templates.upsert(scope.tenant(), scope.factory(), code, name, scope.warehouse(),
						command.ruleCode(), command.thresholdDays(), siteCodesJson,
						command.stockFreezeMode(), command.enabled(), version, operator) == 1,
				"模板已更新，请刷新后重试");
		return new Template(code, name, scope.warehouse(), command.ruleCode(), command.thresholdDays(),
				siteCodes, command.stockFreezeMode(), command.enabled(), version + 1);
	}

	public Preview preview(String warehouseCode, String templateCode, int expectedVersion) {
		Scope scope = scope(warehouseCode);
		Template template = requireTemplate(scope, templateCode, false);
		require(template.version() == expectedVersion, "模板已更新，请刷新后重试");
		return preview(template, false);
	}

	public List<RunHistory> recentRuns(
			String warehouseCode, String templateCode) {
		Scope scope = scope(warehouseCode);
		String code = code(templateCode);
		requireTemplate(scope, code, false);
		var rows = templates.recentRuns(scope.tenant(), scope.factory(), scope.warehouse(), code);
		require(rows != null && rows.size() <= 20, "盘点模板触发历史读取失败");
		List<RunHistory> history = new ArrayList<>(rows.size());
		for (var row : rows) {
			require(row != null, "盘点模板触发历史不完整");
			boolean valid = StrUtil.isNotBlank(row.requestId())
					&& row.requestId().matches("[A-Za-z0-9_-]{1,64}")
					&& row.templateVersion() >= 1
					&& row.planId() != null && row.planId() > 0
					&& StrUtil.isNotBlank(row.planNo())
					&& StrUtil.isNotBlank(row.triggeredBy())
					&& row.createdAt() != null
					&& StrUtil.isNotBlank(row.templateSnapshotJson());
			require(valid, "盘点模板触发历史不完整");
			history.add(new RunHistory(row.requestId(), row.templateVersion(), row.planId(),
					row.planNo(), row.triggeredBy(), row.createdAt(), readSnapshot(row, scope, code)));
		}
		return List.copyOf(history);
	}

	private Template readSnapshot(WmsInventoryCountTemplateMapper.HistoryRow row, Scope scope, String code) {
		Template snapshot;
		try {
			snapshot = json.readValue(row.templateSnapshotJson(), Template.class);
		} catch (JsonProcessingException exception) {
			throw new CheckedException("盘点模板触发快照无效");
		}
		require(snapshot != null, "盘点模板触发快照无效");
		boolean valid = code.equals(snapshot.templateCode())
				&& scope.warehouse().equals(snapshot.warehouseCode())
				&& snapshot.version() == row.templateVersion()
				&& StrUtil.isNotBlank(snapshot.templateName())
				&& snapshot.templateName().length() <= 80
				&& ("SNAPSHOT".equals(snapshot.stockFreezeMode())
				|| "FREEZE_COUNT".equals(snapshot.stockFreezeMode()));
		require(valid, "盘点模板触发快照范围或版本无效");
		WmsInventoryScopeSuggestionService.validateRule(snapshot.ruleCode(), snapshot.thresholdDays());
		validateSiteCodes(snapshot.siteCodes());
		return snapshot;
	}

	@Transactional(rollbackFor = Exception.class)
	public RunResult trigger(RunCommand command) {
		require(command != null, "请选择盘点模板");
		Scope scope = scope(command.warehouseCode());
		String code = code(command.templateCode());
		String requestId = StrUtil.trim(command.requestId());
		require(StrUtil.isNotBlank(requestId) && requestId.matches("[A-Za-z0-9_-]{1,64}"),
				"触发请求号无效");
		require(!requestId.startsWith("EVT_"), "事件盘点请求号不能用于手动触发");
		require(command.expectedVersion() >= 1, "模板版本无效");
		return triggerInternal(scope, code, requestId, command.expectedVersion(), operator(), false, null);
	}

	/**
	 * 仅供同包内受事务保护的定时执行器调用；不暴露为 HTTP 接口。
	 */
	RunResult triggerScheduled(String warehouseCode, String templateCode, LocalDateTime dueUtc) {
		require(dueUtc != null, "盘点周期到期时刻不能为空");
		String warehouse = StrUtil.trim(warehouseCode);
		require(StrUtil.isNotBlank(warehouse), "请选择盘点仓库");
		Scope scope = new Scope(TenantContextHolder.getTenantId(), factory(), warehouse);
		String code = code(templateCode);
		String source = scope.tenant() + ":" + scope.factory().length() + ":" + scope.factory()
				+ ":" + code + ":" + dueUtc;
		String requestId = "SCH_" + UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
		return triggerInternal(scope, code, requestId, null, "WMS_SCHEDULE", true, null);
	}

	/**
	 * 仅限同包内事件事务执行器；事件行由服务端不可变原件提供。
	 */
	RunResult triggerEvent(String warehouseCode, String templateCode, String requestId, EventScope event) {
		require(TenantContextHolder.getTenantId() != null && TenantContextHolder.getTenantId() > 0
				&& !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip()), "事件盘点组织上下文无效");
		require(event != null && StrUtil.isNotBlank(event.matCode()) && StrUtil.isNotBlank(event.siteCode())
				&& requestId != null && requestId.matches("EVT_[A-Za-z0-9_-]{1,60}"), "事件盘点来源无效");
		return triggerInternal(new Scope(TenantContextHolder.getTenantId(), factory(), warehouseCode),
				code(templateCode), requestId, null, "WMS_EVENT", true, event);
	}

	record EventScope(String matCode, String siteCode, String matBatch) {
	}

	private RunResult triggerInternal(Scope scope, String code, String requestId,
	                                  Integer expectedVersion, String actor, boolean scheduled, EventScope event) {
		// 与仓库清理、模板修改共用组织锁；触发读取的是一个稳定版本。
		menus.lockConfiguration("WMS_WAREHOUSE_MENU:" + scope.tenant() + ":" + scope.factory());
		Template template = requireTemplate(scope, code, true);
		if (scheduled && !template.enabled()) {
			return null;
		}
		Preview eventPreview = event == null ? null : previewEvent(template, event);
		if (eventPreview != null && eventPreview.rows().isEmpty()) {
			return null;
		}
		int version = scheduled ? template.version() : expectedVersion;
		String snapshot;
		try {
			snapshot = json.writeValueAsString(template);
		} catch (JsonProcessingException exception) {
			throw new CheckedException("盘点模板快照生成失败");
		}
		int inserted = templates.reserveRun(scope.tenant(), scope.factory(), requestId, code,
				version, snapshot, actor);
		require(inserted == 0 || inserted == 1, "盘点触发记录写入结果不确定");
		if (inserted == 0) {
			require(event == null, "事件模板原请求已占用，请核对原始处理履历");
			var prior = templates.findRunForUpdate(scope.tenant(), scope.factory(), requestId);
			require(prior != null && code.equals(prior.templateCode())
							&& version == prior.templateVersion()
							&& prior.planId() != null && StrUtil.isNotBlank(prior.planNo()),
					"请求号已由其他盘点任务使用，或原任务结果不完整");
			WmsInsideInventoryPlanEntity original = plans.getDetails(prior.planId());
			require(original != null && Objects.equals(scope.tenant(), original.getTenantId())
							&& Objects.equals(scope.factory(), original.getFactoryCode())
							&& Objects.equals(scope.warehouse(), original.getWarehouseCode())
							&& Objects.equals(prior.planNo(), original.getPlanNo()),
					"原盘点计划已变化，请核对任务记录");
			return new RunResult(prior.planId(), prior.planNo(), false);
		}
		require(template.enabled() && template.version() == version,
				"模板已停用或更新，请刷新后重试");
		Preview preview = eventPreview == null ? preview(template, scheduled) : eventPreview;
		R<String> sequence = sequences.create("inside_inventory");
		require(sequence != null && sequence.getCode() == 0 && StrUtil.isNotBlank(sequence.getData())
				&& sequence.getData().length() <= 32, "盘点编号生成失败");
		String planNo = StrUtil.trim(sequence.getData());
		WmsInsideInventoryPlanEntity plan = new WmsInsideInventoryPlanEntity();
		plan.setPlanNo(planNo);
		plan.setWarehouseCode(scope.warehouse());
		plan.setStockFreezeMode(template.stockFreezeMode());
		if (scheduled) {
			// 后台任务没有登录用户，审计字段必须显式记录受控系统执行者。
			plan.setCreateBy(actor);
			plan.setUpdateBy(actor);
		}
		List<WmsInsideInventoryPlanDetailEntity> details = new ArrayList<>();
		for (var row : preview.rows()) {
			WmsInsideInventoryPlanDetailEntity detail = new WmsInsideInventoryPlanDetailEntity();
			detail.setMatCode(row.matCode());
			detail.setMatBatch(row.matBatch());
			detail.setMatUnit(row.matUnit());
			detail.setSiteCode(row.siteCode());
			if (scheduled) {
				detail.setCreateBy(actor);
				detail.setUpdateBy(actor);
			}
			details.add(detail);
		}
		plan.setWmsInsideInventoryPlanDetailList(details);
		require(Boolean.TRUE.equals(plans.saveDeep(plan)) && plan.getId() != null,
				"模板盘点计划保存失败");
		require(templates.completeRun(scope.tenant(), scope.factory(), requestId, plan.getId(), planNo) == 1,
				"盘点触发结果登记失败");
		return new RunResult(plan.getId(), planNo, true);
	}

	private Preview previewEvent(Template template, EventScope event) {
		if (!template.siteCodes().contains(event.siteCode())) {
			return new Preview(template, 0, List.of());
		}
		var suggestion = suggestions.suggestEvent(template.warehouseCode(), event.siteCode(),
				template.ruleCode(), template.thresholdDays(), event.matCode(), event.matBatch());
		require(suggestion != null && template.warehouseCode().equals(suggestion.warehouseCode())
				&& event.siteCode().equals(suggestion.siteCode()) && suggestion.rows() != null
				&& template.ruleCode().equals(suggestion.ruleCode())
				&& Objects.equals(template.thresholdDays(), suggestion.thresholdDays()), "事件盘点范围建议无效");
		var rows = suggestion.rows().stream().filter(row -> row != null
				&& event.matCode().equals(row.matCode()) && event.siteCode().equals(row.siteCode())
				&& Objects.equals(StrUtil.blankToDefault(event.matBatch(), null),
				StrUtil.blankToDefault(row.matBatch(), null))).toList();
		require(rows.size() <= 1, "事件盘点物料批次范围重复");
		return new Preview(template, suggestion.stockRowCount(), rows);
	}

	private Preview preview(Template template, boolean scheduled) {
		List<WmsInventoryScopeSuggestionService.Row> rows = new ArrayList<>();
		int stockRows = 0;
		for (String site : template.siteCodes()) {
			var result = scheduled
					? suggestions.suggestScheduled(template.warehouseCode(), site,
					template.ruleCode(), template.thresholdDays())
					: suggestions.suggest(template.warehouseCode(), site,
					template.ruleCode(), template.thresholdDays());
			require(result != null && Objects.equals(template.warehouseCode(), result.warehouseCode())
							&& Objects.equals(site, result.siteCode())
							&& Objects.equals(template.ruleCode(), result.ruleCode())
							&& Objects.equals(template.thresholdDays(), result.thresholdDays())
							&& result.rows() != null && result.stockRowCount() >= 0,
					"盘点模板范围建议无效，请刷新重试");
			for (var row : result.rows()) {
				require(row != null && Objects.equals(site, row.siteCode())
								&& StrUtil.isNotBlank(row.matCode()) && StrUtil.isNotBlank(row.matUnit())
								&& row.currentNumber() != null
								&& row.currentNumber().compareTo(BigDecimal.ZERO) > 0,
						"盘点模板建议明细无效");
				rows.add(row);
				require(rows.size() <= MAX_ROWS, "建议明细超过1000条，请缩小模板范围");
			}
			stockRows += result.stockRowCount();
		}
		require(!rows.isEmpty(), "当前规则没有匹配库存，未生成盘点计划");
		return new Preview(template, stockRows, List.copyOf(rows));
	}

	private Template requireTemplate(Scope scope, String templateCode, boolean lock) {
		String code = code(templateCode);
		var row = lock
				? templates.findForUpdate(scope.tenant(), scope.factory(), code)
				: templates.find(scope.tenant(), scope.factory(), code);
		require(row != null, "盘点模板不存在");
		return readTemplate(row, scope);
	}

	private Template readTemplate(WmsInventoryCountTemplateMapper.TemplateRow row, Scope scope) {
		require(row != null && StrUtil.isNotBlank(row.templateCode())
						&& row.templateCode().matches("[A-Z][A-Z0-9_]{1,31}")
						&& StrUtil.isNotBlank(row.templateName())
						&& row.templateName().length() <= 80
						&& Objects.equals(scope.warehouse(), row.warehouseCode())
						&& row.version() >= 1
						&& ("SNAPSHOT".equals(row.stockFreezeMode())
						|| "FREEZE_COUNT".equals(row.stockFreezeMode())),
				"盘点模板组织范围或结构无效");
		require(StrUtil.isNotBlank(row.ruleCode())
				&& row.ruleCode().equals(row.ruleCode().trim()), "盘点模板规则无效");
		WmsInventoryScopeSuggestionService.validateRule(row.ruleCode(), row.thresholdDays());
		try {
			List<String> sites = json.readValue(row.siteCodesJson(), SITE_CODES);
			return new Template(row.templateCode(), row.templateName(), row.warehouseCode(),
					row.ruleCode(), row.thresholdDays(), validateSiteCodes(sites),
					row.stockFreezeMode(), row.enabled(), row.version());
		} catch (JsonProcessingException | IllegalArgumentException exception) {
			throw new CheckedException("盘点模板库位配置无效");
		}
	}

	private List<String> validateSiteCodes(List<String> sites) {
		require(sites != null && !sites.isEmpty() && sites.size() <= MAX_SITES,
				"模板须选择1至10个物理库位");
		List<String> result = new ArrayList<>(sites.size());
		Set<String> unique = new HashSet<>();
		for (String value : sites) {
			String site = StrUtil.trim(value);
			require(StrUtil.isNotBlank(site) && site.length() <= 100 && unique.add(site),
					"模板库位为空、重复或超过100个字符");
			result.add(site);
		}
		return List.copyOf(result);
	}

	private void requireWarehouseAndSites(Scope scope, List<String> sites) {
		R<MdmWarehouseEntity> response = warehouses.getByCode(scope.warehouse());
		require(response != null && response.isOk(), "盘点仓库读取失败");
		var warehouse = response.getData();
		require(warehouse != null && Objects.equals(scope.tenant(), warehouse.getTenantId())
						&& Objects.equals(scope.factory(), warehouse.getFactoryCode())
						&& Objects.equals(scope.warehouse(), warehouse.getWarehouseCode())
						&& Boolean.TRUE.equals(warehouse.getStatus()),
				"仅允许配置当前工厂已启用的仓库");
		for (String site : sites) {
			R<MdmWarehouseLocationEntity> locationResponse = locations.getByCode(site);
			require(locationResponse != null && locationResponse.isOk(), "盘点库位读取失败");
			var location = locationResponse.getData();
			require(location != null && Objects.equals(scope.tenant(), location.getTenantId())
							&& (StrUtil.isBlank(location.getFactoryCode())
							|| Objects.equals(scope.factory(), location.getFactoryCode()))
							&& Objects.equals(scope.warehouse(), location.getWarehouseCode())
							&& Objects.equals(site, location.getLocationCode())
							&& Boolean.TRUE.equals(location.getStatus())
							&& Objects.equals(3, location.getLocationLevel()),
					"模板库位不存在、未启用或不属于当前仓库");
		}
	}

	private void requireConfigurationAccess(Scope scope) {
		require(permissions.hasPermission("wms_wmsWarehouseMenu_add"), "无仓库配置权限");
		menus.lockConfiguration("WMS_WAREHOUSE_MENU:" + scope.tenant() + ":" + scope.factory());
	}

	private Scope scope(String warehouseCode) {
		String warehouse = StrUtil.trim(warehouseCode);
		require(StrUtil.isNotBlank(warehouse), "请选择盘点仓库");
		inventoryPermission.require("add", warehouse);
		return new Scope(TenantContextHolder.getTenantId(), factory(), warehouse);
	}

	private String code(String templateCode) {
		String code = StrUtil.trim(templateCode);
		require(StrUtil.isNotBlank(code) && code.matches("[A-Z][A-Z0-9_]{1,31}"),
				"模板编码须为2至32位大写字母、数字或下划线");
		return code;
	}

	private String operator() {
		var user = SecurityUtils.getUser();
		require(user != null && StrUtil.isNotBlank(user.getUsername()), "无法识别当前操作人");
		return user.getUsername();
	}

	private record Scope(Long tenant, String factory, String warehouse) {
	}

	public record Template(String templateCode, String templateName, String warehouseCode,
	                       String ruleCode, Integer thresholdDays, List<String> siteCodes,
	                       String stockFreezeMode, boolean enabled, int version) {
	}

	public record SaveCommand(String templateCode, String templateName, String warehouseCode,
	                          List<String> siteCodes, String ruleCode, Integer thresholdDays,
	                          String stockFreezeMode, boolean enabled, int expectedVersion) {
	}

	public record Preview(Template template, int stockRowCount,
	                      List<WmsInventoryScopeSuggestionService.Row> rows) {
	}

	public record RunCommand(String warehouseCode, String templateCode, int expectedVersion, String requestId) {
	}

	public record RunResult(Long planId, String planNo, boolean created) {
	}

	public record RunHistory(String requestId, int templateVersion, Long planId, String planNo,
	                         String triggeredBy, LocalDateTime createdAt, Template snapshot) {
	}
}
