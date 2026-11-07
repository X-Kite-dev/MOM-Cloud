package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * MES生产领料V1持久命令的人员接口；所有身份和数量输出为精确文本。
 */
public final class MesPickingWmsV1DTO {

	private MesPickingWmsV1DTO() {
	}

	/**
	 * 准备不可变原命令，不表示已被WMS受理。
	 *
	 * @param billId                   当前组织实际领料主单ID
	 * @param action                   SUBMIT提交或CANCEL撤回来源
	 * @param expectedPreviousRevision 提交的前修订；首次为0，撤回为当前修订
	 * @param reason                   撤回原因，提交可空
	 */
	@Schema(description = "领料WMS原命令准备")
	public record Prepare(Long billId, String action, Integer expectedPreviousRevision, String reason) {
	}

	/**
	 * 本人原号执行或核对；核对不运输新命令，但会登记回执/应用本地状态，仍需维护权限。
	 *
	 * @param billId    当前组织原领料ID
	 * @param requestId MES永久原操作号
	 */
	public record Request(Long billId, String requestId) {
	}

	/**
	 * 当前实名范围下的单据与选定修订，不拉取无界历史。
	 *
	 * @param contractVersion  固定1
	 * @param mode             V1、LEGACY或UNAVAILABLE
	 * @param tenantId         实际租户ID文本
	 * @param factoryCode      实际授权工厂
	 * @param operatorId       当前实名用户ID文本
	 * @param billId           原领料主单ID文本
	 * @param billNo           原领料单号
	 * @param taskCode         原生产工单号
	 * @param sourceRevision   当前来源修订，未提交为0
	 * @param selectedRevision 本次读取修订
	 * @param phase            当前本地来源阶段，不冒充WMS执行状态
	 * @param history          true表示历史修订只读
	 * @param commands         选定修订至多SUBMIT/CANCEL两份永久命令
	 * @param canPrepare       当前可准备新提交
	 * @param canExecute       当前本人可运输原命令
	 * @param canReconcile     当前本人可核对原回执
	 * @param canCancel        当前可准备来源撤回
	 * @param blockReason      简短阻断说明
	 */
	public record Context(int contractVersion, String mode, String tenantId, String factoryCode, String operatorId,
	                      String billId, String billNo, String taskCode, int sourceRevision, int selectedRevision,
	                      String phase,
	                      boolean history, List<CommandView> commands, boolean canPrepare, boolean canExecute,
	                      boolean canReconcile, boolean canCancel, String blockReason) {
	}

	/**
	 * 冻结命令及已核验回执，正文仅按需展示。
	 *
	 * @param requestId      本域原操作号
	 * @param action         SUBMIT或CANCEL
	 * @param sourceRevision 来源修订
	 * @param state          PREPARED未决或ACCEPTED已核验回执
	 * @param operatorId     原操作者ID文本
	 * @param operatorName   原账号快照
	 * @param createTime     本域原命令登记时间
	 * @param updateTime     最近本域处理时间
	 * @param payloadSha256  冻结正文SHA256
	 * @param payload        完整本域冻结正文
	 * @param receiptSha256  已核验回执SHA256，可空
	 * @param receipt        已核验回执原文，可空
	 * @param warehouses     分仓原号及精确行关联；撤回命令不重复展开行
	 */
	public record CommandView(String requestId, String action, int sourceRevision, String state, String operatorId,
	                          String operatorName, String createTime, String updateTime, String payloadSha256,
	                          String payload,
	                          String receiptSha256, String receipt, List<WarehouseView> warehouses) {
	}

	/**
	 * 分仓原件，受理不等于已出库。
	 *
	 * @param requestId     WMS分仓原请求号
	 * @param warehouseCode 实际仓编码
	 * @param warehouseName 仓名快照
	 * @param noticeId      已核验通知ID文本，可空
	 * @param status        PREPARED或ACCEPTED
	 * @param lines         完整原行及真实WMS明细关联
	 */
	public record WarehouseView(String requestId, String warehouseCode, String warehouseName, String noticeId,
	                            String status, List<LineView> lines) {
	}

	/**
	 * 精确原行证明。
	 *
	 * @param sourceDocumentDetailId MES实际原领料明细ID文本
	 * @param noticeDetailId         WMS实际通知明细ID文本，可空
	 * @param materialCode           实际主料或替代料编码
	 * @param unit                   原冻结标准单位
	 * @param quantity               原标准单位数量精确文本
	 */
	public record LineView(String sourceDocumentDetailId, String noticeDetailId, String materialCode, String unit,
	                       String quantity) {
	}
}
