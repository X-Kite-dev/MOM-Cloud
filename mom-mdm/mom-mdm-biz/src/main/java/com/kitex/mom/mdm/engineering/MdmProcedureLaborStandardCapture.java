package com.kitex.mom.mdm.engineering;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.mdm.dto.MdmProcedureLaborStandardDTO;
import com.kitex.mom.mdm.entity.MdmProcedureEntity;
import com.kitex.mom.mdm.mapper.MdmProcedureMapper;
import com.kitex.mom.mdm.support.MdmProcedureLaborStandardCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 工程采集的真实工序标准来源；由上层路线发布事务持有授权与原路线锁。 */
@Component
@RequiredArgsConstructor
public class MdmProcedureLaborStandardCapture {
	private final MdmProcedureMapper procedures;

	/** 多工序调用方按主工序编码排序，确保跨路线采集不会以相反顺序持有标准行锁。 */
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public MdmProcedureLaborStandardDTO capture(String processCode) {
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		if (!TransactionSynchronizationManager.isActualTransactionActive() || tenant == null || tenant <= 0
				|| TenantContextHolder.getTenantSkip() || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
				|| factory == null || factory.isBlank() || processCode == null || processCode.isBlank()) {
			throw new CheckedException("工序标准采集须在真实路线发布事务内使用当前实际租户工厂");
		}
		var rows = procedures.selectList(new LambdaQueryWrapper<MdmProcedureEntity>()
				.eq(MdmProcedureEntity::getTenantId, tenant).eq(MdmProcedureEntity::getFactoryCode, factory)
				.eq(MdmProcedureEntity::getProcessCode, processCode).last("LIMIT 2 FOR UPDATE"));
		if (rows.size() != 1) {
			throw new CheckedException("标准工时原工序缺失或不唯一，禁止猜测来源");
		}
		return MdmProcedureLaborStandardCodec.freeze(rows.get(0));
	}
}
