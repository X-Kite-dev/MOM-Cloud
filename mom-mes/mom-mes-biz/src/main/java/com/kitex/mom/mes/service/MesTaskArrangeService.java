package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.tpm.dto.DeviceCheckTaskStartDto;

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

    /**
     * 接收
     *
     * @param id
     */
    void receive(Long id, String worksiteCode);

    /**
     * 任务开工
     *
     * @param id
     */
    void start(Long id);

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
}
