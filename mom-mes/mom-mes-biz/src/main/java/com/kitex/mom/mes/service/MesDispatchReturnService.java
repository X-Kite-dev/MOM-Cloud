package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesDispatchReturnDTO;
import com.kitex.mom.mes.entity.MesDispatchReturnRequestEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;

import java.util.List;

/**
 * 普通零执行配额与历史未开工派工的受控退派服务。
 */
public interface MesDispatchReturnService extends IService<MesDispatchReturnRequestEntity> {

	/**
	 * 按当前租户和实际工厂查询退派申请工作台。
	 */
	IPage<MesDispatchReturnRequestEntity> pageRequests(Page<MesDispatchReturnRequestEntity> page,
			MesDispatchReturnRequestEntity query);

	/**
	 * 重新采集派工现场事实，普通配额自动选择目标证据，其余保持历史V1。
	 *
	 * @param arrangeId 当前实际租户和工厂的派工主键
	 * @return 当前版本、申请资格、配额摘要、资源、阻断项和证据指纹
	 */
	MesDispatchReturnDTO.Evidence preview(Long arrangeId);

	/**
	 * 修正专用预览：始终沿原V1整根未执行证据，不因普通配额退派放宽修正资格。
	 *
	 * @param arrangeId 当前实际租户和工厂的原派工主键
	 * @return 原V1证据、资源和阻断项
	 */
	MesDispatchReturnDTO.Evidence previewForCorrection(Long arrangeId);

	/**
	 * 按原申请冻结版本查询申请、当前事实和不可变操作履历，未知版本直接拒绝。
	 *
	 * @param requestId 当前实际组织下的原申请主键
	 * @return 原申请、同版本当前证据及永久履历
	 */
	MesDispatchReturnDTO.Detail detail(Long requestId);

	/**
	 * 冻结当前事实并提交退派申请；同一永久请求号只允许原实名人员按相同命令重放。
	 *
	 * @param command 含永久请求号、派工、原预览指纹和原因的完整命令
	 * @return 新申请或原号已经受理的当前申请；事实变化、执行/修订门阻断时拒绝
	 */
	MesDispatchReturnRequestEntity submit(MesDispatchReturnDTO.SubmitCommand command);

	/**
	 * 由非申请人复核；批准时按原版本重新取证，原子关闭目标、结算配额及受控回退未执行父任务。
	 *
	 * @param command 含原申请、原冻结指纹、结论、原因和永久操作号的完整命令
	 * @return 审核结果或原号当前申请；不允许同人审批、跨组织或复用异文操作号
	 */
	MesDispatchReturnRequestEntity review(MesDispatchReturnDTO.ReviewCommand command);

	/**
	 * 由原申请人在待审核阶段撤回申请。
	 *
	 * @param command 含原申请、原因和永久操作号的完整命令
	 * @return 撤回结果或原号当前申请；非原申请人、非待审和异文复用均拒绝
	 */
	MesDispatchReturnRequestEntity withdraw(MesDispatchReturnDTO.WithdrawCommand command);

	/**
	 * 只读核对完整原提交命令，返回原永久操作和当前申请；ABSENT不能证明未执行。
	 *
	 * @param command 含原永久号及全部原字段的提交命令，不接受只传请求号
	 * @return ABSENT或APPLIED；已受理时附原操作及当前申请，身份、组织或异文不符拒绝
	 */
	MesDispatchReturnDTO.Receipt submitReceipt(MesDispatchReturnDTO.SubmitCommand command);

	/**
	 * 只读核对完整原审批命令及原审批人，不能按当前事实重新生成证据。
	 *
	 * @param command 含原永久号及全部原字段的审批命令，不接受只传请求号
	 * @return ABSENT或APPLIED；已受理时附原操作及当前申请，身份、组织或异文不符拒绝
	 */
	MesDispatchReturnDTO.Receipt reviewReceipt(MesDispatchReturnDTO.ReviewCommand command);

	/**
	 * 只读核对完整原撤回命令及原申请人，不生成取消墓碑。
	 *
	 * @param command 含原永久号及全部原字段的撤回命令，不接受只传请求号
	 * @return ABSENT或APPLIED；已受理时附原操作及当前申请，身份、组织或异文不符拒绝
	 */
	MesDispatchReturnDTO.Receipt withdrawReceipt(MesDispatchReturnDTO.WithdrawCommand command);

	/**
	 * 开工门禁：存在待审核退派申请时禁止形成新的生产事实。
	 */
	void assertExecutionAllowed(MesTaskArrangeEntity arrange);

	/**
	 * 仅同事务精确待审修正可调用；保留原资源释放与未执行门。
	 */
	void retireForCorrection(Long correctionId, String expectedSourceFingerprint);

	/**
	 * 上游PLAN撤回已批准时，终止同一批派工的待审现场申请。
	 */
	void supersedeByPlan(List<MesTaskArrangeEntity> arranges, String planRequestId);
}
