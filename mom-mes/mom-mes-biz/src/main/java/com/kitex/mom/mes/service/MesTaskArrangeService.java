package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.MesChangeoverExecutionEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskExecutionSegmentEntity;
import com.kitex.mom.mes.entity.MesToolingExecutionEntity;
import com.kitex.mom.tpm.dto.DeviceCheckTaskStartDto;
import com.kitex.mom.tpm.dto.TpmDevicePauseReceipt;

import java.util.List;

/**
 * 生产派工
 *
 * @author kitex
 * @since 2025-05-06
 */
public interface MesTaskArrangeService extends IService<MesTaskArrangeEntity> {

    /**
     * 批量派工
     *
     * @param dto
     */
    void arrange(MesTaskArrangeDTO dto);

    /**
     * 根据工位获取正在加工的任务
     *
     * @param shiftCode
     * @return
     */
    MesArrangeTaskVO getCurrentTask(String shiftCode, String workshopCode, String worksiteCode);

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

    /** 移动人工暂停使用永久原号贯穿TPM；不与安灯等安全控制入口混用。 */
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

    /** 明确有据取消本地计时，不代表撤销TPM/MDM已发生的物理换模；普通关窗不得调用。 */
    void cancelChangeover(MesChangeoverCancelDTO dto);
}
