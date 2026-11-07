package com.kitex.mom.wms.service.special;

import cn.hutool.core.util.IdUtil;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.wms.dto.WmsSpecialMovementDTO.*;
import com.kitex.mom.wms.service.special.WmsSpecialAccess.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.kitex.mom.wms.service.special.WmsSpecialRules.*;

@Service
@RequiredArgsConstructor
public class WmsSpecialMaterialService {
	private final RemoteMaterialService materials;

	public Plan freeze(Scope scope, Plan plan) {
		return new Plan(plan.kind(), plan.direction(), plan.partyCode(), plan.reference(), plan.reason(),
				freeze(scope, plan.lines()), freeze(scope, plan.outputs()));
	}

	public void verifyCurrent(Scope scope, Line line) {
		var response = materials.getDetails(line.materialCode());
		var row = response == null ? null : response.getData();
		require(response != null && response.isOk() && row != null && Boolean.TRUE.equals(row.getStatus()) &&
				Objects.equals(row.getTenantId(), scope.tenant()) &&
				Objects.equals(row.getFactoryCode(), scope.factory()) &&
				Objects.equals(row.getMaterialCode(), line.materialCode()) &&
				Objects.equals(row.getBasicUnit(), line.unit()), "当前物料或基本单位已变化，请核对原申请");
	}

	private List<Line> freeze(Scope scope, List<Line> inputs) {
		var result = new ArrayList<Line>();
		for (var input : inputs) {
			require(input.lineId() == null, "新申请不能指定旧明细标识");
			var response = materials.getDetails(input.materialCode());
			var row = response == null ? null : response.getData();
			require(response != null && response.isOk() && row != null &&
					Boolean.TRUE.equals(row.getStatus()) &&
					Objects.equals(row.getTenantId(), scope.tenant()) &&
					Objects.equals(row.getFactoryCode(), scope.factory()) &&
					Objects.equals(row.getMaterialCode(), input.materialCode()) &&
					Objects.equals(row.getBasicUnit(), input.unit()), "物料不存在、停用、组织或基本单位不一致");
			result.add(
					new Line(Long.toString(IdUtil.getSnowflakeNextId()), row.getMaterialCode(),
							row.getMaterialName(),
							row.getBasicUnit(), input.quantity()));
		}
		return List.copyOf(result);
	}
}
