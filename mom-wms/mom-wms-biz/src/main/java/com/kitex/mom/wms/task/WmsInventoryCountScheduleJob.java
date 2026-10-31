package com.kitex.mom.wms.task;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.service.WmsInventoryCountScheduleDispatchService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 手工触发或周期触发均须在 XXL-JOB 中配置明确的租户与工厂。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WmsInventoryCountScheduleJob {
	private final WmsInventoryCountScheduleDispatchService dispatcher;
	private final ObjectMapper json;

	/** 参数示例：{"tenantId":11,"factoryCode":"F1","limit":50}。 */
	@XxlJob("wms_inventory_count_schedule")
	public void dispatch() throws Exception {
		var summary = dispatchParameter(XxlJobHelper.getJobParam());
		log.info("WMS盘点周期扫描={}，创建={}，失败={}",
				summary.scanned(), summary.created(), summary.failed());
		XxlJobHelper.log("WMS盘点周期扫描={}，创建={}，失败={}",
				summary.scanned(), summary.created(), summary.failed());
		if (summary.failed() > 0) {
			throw new CheckedException("盘点周期存在失败任务，请查看执行日志并重试");
		}
	}

	WmsInventoryCountScheduleDispatchService.Summary dispatchParameter(String parameter) throws Exception {
		if (StrUtil.isBlank(parameter)) {
			throw new CheckedException("盘点周期任务缺少租户与工厂参数");
		}
		var scope = json.readTree(parameter);
		if (!scope.isObject() || !scope.path("tenantId").isIntegralNumber()
				|| !scope.path("tenantId").canConvertToLong()
				|| !scope.path("factoryCode").isTextual()
				|| (scope.has("limit") && !scope.path("limit").isInt())) {
			throw new CheckedException("盘点周期任务范围参数无效");
		}
		int limit = scope.has("limit") ? scope.path("limit").intValue() : 50;
		long tenant = scope.path("tenantId").longValue();
		String factory = scope.path("factoryCode").textValue();
		if (tenant <= 0 || StrUtil.isBlank(factory) || limit < 1 || limit > 100) {
			throw new CheckedException("盘点周期任务租户、工厂或扫描上限无效");
		}
		return dispatcher.runScope(tenant, factory, limit);
	}
}
