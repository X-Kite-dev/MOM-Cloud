package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.MesChangeoverExecutionEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskExecutionSegmentEntity;
import com.kitex.mom.mes.entity.MesToolingExecutionEntity;
import com.kitex.mom.tpm.dto.DeviceCheckTaskStartDto;
import com.kitex.mom.tpm.dto.TpmDevicePauseReceipt;

import java.util.List;
import java.util.function.Consumer;

/**
 * 生产派工
 *
 * @author kitex
 * @since 2025-05-06
 */
public interface MesTaskArrangeService extends IService<MesTaskArrangeEntity> {

    /**
     * 当前实际工厂的工单派工工作台；状态与派工计数均由服务端查询。
     */
    IPage<MesDispatchWorkbenchDTO> workbenchPage(long current, long size, String taskCode,
                                                 String matCode, String workshopCode, String status);

    /**
     * 批量派工
     *
     * @param dto
     */
    void arrange(MesTaskArrangeDTO dto);

    /**
     * 一个工单的多班次派工同事务提交，任一班次失败则整体回滚。
     */
    void arrangeBatch(List<MesTaskArrangeDTO> batches);

    /**
     * 创建真实分配派工：先按输入顺序生成ID/编码和冻结配置，回调写完整永久账后保存真实行。
     * @param taskCode 当前实际组织的原工单编码
     * @param lines 有序分配行，每行产生一条真实派工，回传列表顺序一致
     * @param beforeSave 必须在同一事务写完整命令和配额；失败使实际派工一起回滚
     * @return 本次实际保存的派工，严格与输入顺序一致
     */
    List<MesTaskArrangeEntity> arrangeAllocated(String taskCode, List<MesDispatchAllocationDTO.Line> lines,
            Consumer<List<MesTaskArrangeEntity>> beforeSave);

    /**
     * 修正审批事务的窄补派入口；不能绕过精确来源、员工和候选资源约束。
     */
    void arrangeCorrection(MesTaskArrangeDTO dto, Long correctionId);

    /**
     * 现场任务按当前租户工厂、班次、人员和工位统一查询，和状态计数保持同一口径。
     */
    IPage<MesTaskArrangeEntity> padPage(Page<MesTaskArrangeEntity> page, MesTaskArrangeEntity query);

    /**
     * 根据工位获取正在加工的任务
     *
     * @param shiftCode
     * @return
     */
    MesArrangeTaskVO getCurrentTask(String shiftCode, String workshopCode, String worksiteCode);

    /** 按精确ID读取当前实名工站任务；不传ID仍保持原唯一候选语义。 */
    MesArrangeTaskVO getCurrentTask(String shiftCode, String workshopCode, String worksiteCode, Long arrangeId);

    /** 当前实名工站的在制候选真分页，不以返回首条替用户选择。 */
    IPage<MesRunningDispatchVO> runningCandidates(Page<MesTaskArrangeEntity> page,
            String shiftCode, String workshopCode, String worksiteCode, String keyword);

    /**
     * 获取正在加工任务的设备
     *
     * @return
     */
    MesArrangeDeviceVO getCurrentDevice(String shiftCode, String workshopCode, String worksiteCode);

    MesArrangeDeviceVO getRunningDevice(String shiftCode, String workshopCode, String worksiteCode);

    /**
     * 获取bom信息
     *
     * @param code
     * @return
     */
    List<MdmBomChildListDTO> getBomList(String code);

    List<MdmBomChildListDTO> getBomListByTaskCode(String code);

    /**
     * pad查询状态列表
     *
     * @return
     */
    List<MesTaskArrangeStatusDTO> queryStatusList(String worksiteCode, String shiftCode, String workshopCode);

    /**
     * 暂停
     *
     * @param id
     */
    void pause(Long id);

    /**
     * 移动人工暂停使用永久原号贯穿TPM；不与安灯等安全控制入口混用。
     */
    TpmDevicePauseReceipt pauseFromTerminal(Long id, String requestId);

    /**
     * 安灯事件联动暂停，不依赖当前登录人是派工任务执行人。
     */
    void pauseByAndon(String arrangeCode);

    TpmDevicePauseReceipt pauseByAndon(String arrangeCode, String requestId);

    /**
     * 变化点事件联动暂停。请求号贯穿MES状态转换与TPM/MDM回执，供失败重试和审计使用。
     */
    TpmDevicePauseReceipt pauseByChangePoint(String arrangeCode, String requestId);

    /**
     * 接收
     *
     * @param id
     */
    void receive(Long id, String worksiteCode);

    void checkInToolings(MesToolingCheckInDTO dto);

    List<MesToolingExecutionEntity> listToolingExecutions(Long arrangeId);

    /**
     * 任务开工
     *
     * @param id
     */
    void start(Long id);

    /**
     * 首次开工绑定协议内部入口；来源由领域服务完整核验。
     */
    void startWithMaterialSources(Long id, List<Long> feedIds);

    List<MesTaskExecutionSegmentEntity> listExecutionSegments(Long arrangeId);

    /**
     * 关闭
     *
     * @param id
     */
    void close(Long id);

    /**
     * 完工
     *
     * @param id
     */
    void finish(Long id);

    /**
     * 设备开机/换模
     *
     * @param dto
     */
    void deviceStart(DeviceCheckTaskStartDto dto);

    /**
     * 明确开始换型；实际组织/观察实名核对，已有活动账必须从只读入口恢复。
     * 原号只能重放同设备、工位及原账号，不能用新原号冒充另一条活动账。
     */
    MesChangeoverExecutionEntity beginChangeover(MesChangeoverStartDTO dto);

    /**
     * 明确有据取消本地计时，不代表撤销TPM/MDM已发生的物理换模；普通关窗不得调用。
     */
    void cancelChangeover(MesChangeoverCancelDTO dto);
}
