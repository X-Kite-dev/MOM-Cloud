package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.vo.MesTaskReportOmsVO;

import java.math.BigDecimal;
import java.util.List;


/**
 * 工单获取
 *
 * @author kitex
 * @since 2025-05-06
 */
public interface MesTaskService extends IService<MesTaskEntity> {

    /**
     * 最新独立事务核对原入库参数；无MES回执只返回UNKNOWN，不写业务或调用远端。
     */
    MesTaskInWmsReceiptDTO resolveInWms(MesTaskInWmsDTO command);

    /**
     * 查询分页
     *
     * @param pageParam
     * @param query
     * @return
     */
    IPage<MesTaskEntity> queryPage(Page<MesTaskEntity> pageParam, MesTaskEntity query);

    /**
     * 生产计划接收生成生产工单
     *
     * @param receive
     */
    MesTaskIssueReceiptDTO add(MesTaskReceiveDTO receive);

    String addRework(MesTaskReceiveDTO receive);

    Long notifyReworkCompletion(Long taskId, String produceTaskCode, BigDecimal completedQuantity);

    Long retryReworkCallback(Long taskId);

    void syncReworkInspection(MesReworkInspectionResultDTO result);

    void recordQualityDisposition(MesQualityDispositionDTO command);

    /**
     * 解析工单或工序任务背后的APS拆合批来源计划；供QMS等权威事件源取证。
     */
    MesPlanSourceMappingDTO planSourceMapping(String taskCode, String produceTaskCode, String factoryCode);

    /**
     * 在APS来源账完整且单一、MES尚未形成任何现场执行事实时关闭关联工单。
     * 支持未派工、待接收及已接收未开工场景；已接收资源随撤回释放，调用永久幂等。
     */
    MesPlanWithdrawalDTO.Receipt withdrawPlan(MesPlanWithdrawalDTO.Command command);

    /**
     * 按一次MES来源数量请求返回实际形成的逐计划增量，禁止用整单分配量代替事件数量。
     */
    MesSourceQuantityMappingDTO sourceQuantityMapping(String requestId, String factoryCode);

    /**
     * 在实际工厂、同一生产根锁和未下达状态内编辑白名单配置，保留冻结来源与执行事实。
     *
     * @param mesTask 含准确原工单ID及本次允许修改的字段，不能改变组织或来源
     */
    void edit(MesTaskEntity mesTask);

    /**
     * 查询详情
     *
     * @param id
     * @return
     */
    MesTaskDTO findBydId(Long id);

    /**
     * 在原工单根锁及修订准入内冻结执行依据并生成工序任务，状态须仍为CREATE。
     *
     * @param id 当前租户实际工厂内的准确工单ID
     */
    void issue(Long id);

    /**
     * 仅作废无执行、资源、材料或上游分配事实的正式独立手工CREATE草稿。
     * 已关闭只返回当前观察，不修改原事实；不能代办完工、计划撤回或在制整单停止。
     *
     * @param id 当前租户实际工厂内的准确工单ID
     */
    void close(Long id);

    /**
     * 生产工单对接生产计划接口
     *
     * @param planMesQueryDTO 查询条件
     * @return R
     */
    List<MesTaskEntity> planMesList(PlanMesQueryDTO planMesQueryDTO);

    /**
     * 根据生产订单号查询生产工单最后一道工序的报工记录
     *
     * @param orders 生产订单列表
     * @return R
     */
    List<MesTaskReportOmsVO> getByOrders(List<String> orders);

    /**
     * 生产工单入库
     *
     * @param mesTaskInWmsDTO 生产工单入库DTO
     * @return R
     */
    Boolean inWms(MesTaskInWmsDTO mesTaskInWmsDTO);

    /**
     * 入库前预览本次数量将占用的末工序报工来源。
     */
    MesTaskInWmsSourceDTO previewInWmsSource(MesTaskInWmsDTO mesTaskInWmsDTO);

    /**
     * 将MES整批完工状态按APS批次来源账回传给全部原计划。
     */
    void feedbackPlanCompletion(MesTaskEntity task, BigDecimal completeNum);

    /**
     * 根据生产计划单号查询生产工单
     *
     * @param planNo 生产计划单号
     * @return R
     */
    List<MesTaskEntity> omsLife(String planNo);

    /**
     * 根据产品编码和工位编码查询当前用户需要做的工单信息
     * 如果有已经开始的则查询开始的，没有开始的则查询最近需要开始的工单信息
     *
     * @param queryDTO 查询参数
     * @return 当前用户需要做的工单信息
     */
    MesTaskCurrentWorkVO getCurrentWorkByProductAndWorksite(MesTaskQueryByProductWorksiteDTO queryDTO);

	/**
	 * 按准确工单编码读取当前实名租户和已授权实际工厂内唯一的未删除父工单详情。
	 * 编码保持原值，不修剪或默认；编码为空、无读取权限、无唯一原单或归属无法核验时拒绝。
	 *
	 * @param taskCode 非空白的准确原始工单编码
	 * @return 保留原工单ID、租户、工厂及业务事实的详情
	 */
	MesTaskDTO getByTaskCode(String taskCode);
}
