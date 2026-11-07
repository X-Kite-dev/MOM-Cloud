package com.kitex.mom.mes.process.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.MesTaskReportResult;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.process.AbstractProcessOperation;
import com.kitex.mom.mes.service.impl.MesPackagingExecutionService;
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 包装逐件报工：只构造一次生产事实，待装及封箱沿冻结采用同事务完成。
 *
 * @author kitex
 * @since 2025-03-25
 */
@Component
public class PackageOneCodeProcessOperation extends AbstractProcessOperation {

	private final MesPackagingExecutionService packagingExecution;

	/** 构造正式逐件包装执行器；装箱事实统一交由采用和封箱协调器维护。 */
	public PackageOneCodeProcessOperation(MesWarehouseLiveStockService liveStockService,
			RemoteParamService remoteParamService, RemoteMaterialService matService,
			MesWarehouseFeedService feedService, MesPackagingExecutionService packagingExecution) {
		super(liveStockService, remoteParamService, matService, feedService);
		this.packagingExecution = packagingExecution;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public MesTaskReportResult execute(MesTaskReportVO params) {
		MomUser currentUser = SecurityUtils.getUser();
		// 1. 验证参数
		if (StrUtil.isEmpty(params.getTransferCode())) {
			throw new RuntimeException("报工条码不能为空");
		}
		// 2. 条码规则、错料和重复过站已由统一过站防错门校验。
		if (params.getPassNum() == null || params.getPassNum().compareTo(BigDecimal.ONE) != 0
				|| params.getCapacity() == null || params.getCapacity() <= 0) {
			throw new IllegalArgumentException("包装逐件报工须单件合格量1及正包装容量");
		}
		// 3. 构造报告；包装关联后移到统一入口保存并核验报告之后。
		MesTaskReportEntity report = createReport(params, currentUser);
		params.setReportId(report.getId());
		String reportMessage = operatorName(params, currentUser) + "于"
				+ DateUtil.format(report.getReportTime(), "yyyy-MM-dd HH:mm:ss")
				+ "进行包装装箱报工，合格数量为：" + report.getReportNum();
		return new MesTaskReportResult().setReport(report).setReportMessage(reportMessage);
	}

	/**
	 * 已保存报告的单件内容入待装区；达到容量时，整箱关联和履历同事务完成。
	 */
	@Override
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public void persistAssociations(MesTaskReportVO params, MesTaskReportResult result) {
		// 由真实待装成员决定是否满箱；封过尾箱后不再按累计产量取模。
		packagingExecution.stageUnitAndSealIfFull(params, result);
	}

	@Override
	public ReportModeEnum getMode() {
		return ReportModeEnum.PACKAGE_ONE_CODE;
	}
}
