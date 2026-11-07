package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 移动收发的实际工厂读写准入；仓库按钮及原业务来源仍由收发服务分别核验。
 * 不信任终端传入的工厂选择，也不以内部调用标记代替当前操作人员身份。
 */
@Service
@RequiredArgsConstructor
public class WmsMobileWarehouseAccess {
	private final RemoteMdmFactoryAccessService factories;

	/**
	 * 核对当前实名用户在实际租户工厂的读/写授权；远端不可用或未明确批准即拒绝。
	 *
	 * @param write true表示即将登记新业务或原号结束，false用于来源/既有回执读取
	 */
	public void requireFactory(boolean write) {
		var tenant = TenantContextHolder.getTenantId();
		var factory = FactoryContextHolder.getFactoryCode();
		var actor = SecurityUtils.getUser();
		if (tenant == null || tenant <= 0 || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
				|| Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) || factory == null ||
				factory.isBlank()
				|| actor == null || actor.getId() == null || actor.getId() <= 0
				|| !Objects.equals(actor.getTenantId(), tenant)) {
			throw new CheckedException("请登录实际租户并选择已授权工厂");
		}
		R<Boolean> result;
		try {
			result = factories.check(new MdmFactoryAccessCheck().setUserId(actor.getId())
					.setFactoryCode(factory).setRequiredPermission(write ? "write" : "read"));
		} catch (RuntimeException exception) {
			throw new CheckedException("工厂授权核验失败，请稍后重试");
		}
		if (result == null || !result.isOk() || !Boolean.TRUE.equals(result.getData())) {
			throw new CheckedException(write ? "当前账号没有该工厂的作业权限" : "当前账号没有该工厂的读取权限");
		}
	}
}
