package com.kitex.mom.mdm.engineering;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.entity.MdmProcessProcedureSettingEntity;
import com.kitex.mom.mdm.mapper.MdmProcessProcedureSettingMapper.NullableRow;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/** 原DB nullable耗料旗标与旧实体投影配对；不能按V2声明掩盖坏的实际布尔值。 */
final class MdmMaterialSettingSource {
	private MdmMaterialSettingSource() {
	}

	/** 按原记录ID及组织/路线/节点配对DB原值；V2必须保留真实NULL，坏的true/false直接拒绝。 */
	static void apply(List<MdmProcessProcedureSettingEntity> entities, List<NullableRow> sources) {
		require(entities != null && sources != null && entities.size() <= 10000 && entities.size() == sources.size(),
				"节点设置原数据库投影不完整，禁止按实体默认布尔值冻结");
		var byId = new HashMap<Long, NullableRow>();
		for (var row : sources) {
			require(row != null && row.getId() != null && row.getId() > 0 && byId.putIfAbsent(row.getId(), row) == null,
					"节点设置原数据库身份缺失或重复");
		}
		for (var entity : entities) {
			require(entity != null, "节点设置实体为空");
			var raw = byId.remove(entity.getId());
			require(raw != null && Objects.equals(raw.getTenantId(), entity.getTenantId())
					&& Objects.equals(raw.getFactoryCode(), entity.getFactoryCode())
					&& Objects.equals(raw.getRouteCode(), entity.getRouteCode())
					&& Objects.equals(raw.getRouteNodeCode(), entity.getRouteNodeCode())
					&& Objects.equals(raw.getProcessCode(), entity.getProcessCode())
					&& Objects.equals(raw.getMaterialConsumptionSchema(), entity.getMaterialConsumptionSchema()),
					"节点设置两次来源的身份或代际已变化，请重新预检");
			String schema = raw.getMaterialConsumptionSchema();
			if (schema == null) {
				// 旧采集器的实体默认值已经进入旧包原文；不借本修复回填/改写旧代际。
				continue;
			}
			require("MATERIAL_CONSUMPTION_V2".equals(schema) && raw.getIsBackFlush() == null,
					"逐料耗料必须保留真实DB NULL的旧统一开关，不能按声明强行抹掉实际false/true");
			entity.setIsBackFlush(null);
		}
		require(byId.isEmpty(), "节点设置原数据库存在未匹配来源");
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
