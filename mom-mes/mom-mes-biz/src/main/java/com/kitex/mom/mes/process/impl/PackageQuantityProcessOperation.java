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
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.service.impl.MesPackagingExecutionService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


/**
 * 包装数量报工：每次容器扫码形成原产出及待装内容，获质量放行后沿冻结依据初封。
 *
 * @author kitex
 * @since 2025-03-25
 */
@Component
public class PackageQuantityProcessOperation extends AbstractProcessOperation {

	private final MesPackagingExecutionService packagingExecution;

	/** 构造数量包装执行器；容器身份由WMS确认，封箱事实由MES统一协调。 */
	public PackageQuantityProcessOperation(MesWarehouseLiveStockService liveStockService,
			RemoteParamService remoteParamService, RemoteMaterialService matService,
			MesWarehouseFeedService feedService, MesPackagingExecutionService packagingExecution) {
		super(liveStockService, remoteParamService, matService, feedService);
		this.packagingExecution = packagingExecution;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public MesTaskReportResult execute(MesTaskReportVO params) {
		MomUser currentUser = SecurityUtils.getUser();
		// 实现包装+数量报工逻辑
		// 1. 验证参数
		if (StrUtil.isEmpty(params.getTransferCode())) {
			throw new RuntimeException("箱码不能为空");
		}
		// 容器身份、组织、可用状态及本地占用由统一包装协调器在本事务核验。
		// 2. 构造报告，由统一入口先保存；此处不能提前保存引用reportId的包装明细。
		MesTaskReportEntity report = createReport(params, currentUser);
		params.setReportId(report.getId());
		String reportMessage = operatorName(params, currentUser) + "于"
				+ DateUtil.format(report.getReportTime(), "yyyy-MM-dd HH:mm:ss")
				+ "进行箱码扫描数量报工，箱码：" + params.getTransferCode() + "，装箱数量：" + report.getReportNum();
		return new MesTaskReportResult().setReport(report).setReportMessage(reportMessage);
	}

	/**
	 * 实际报告存在后同事务登记原待装内容；质量未放行时保留待装，不回滚合法报工。
	 */
	@Override
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public void persistAssociations(MesTaskReportVO params, MesTaskReportResult result) {
		// 外部容器码保持原文；只建立原报工的包装成员，不二次记产或扣料。
		packagingExecution.sealQuantity(params, result);
	}
	@Override
	public ReportModeEnum getMode() {
		return ReportModeEnum.PACKAGE_QUANTITY;
	}
}
