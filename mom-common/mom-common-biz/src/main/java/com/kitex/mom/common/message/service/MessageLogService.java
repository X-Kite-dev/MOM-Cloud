package com.kitex.mom.common.message.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.common.message.entity.MessageLogEntity;
import com.kitex.mom.common.message.vo.MessageLogExcelVO;

import java.util.List;

public interface MessageLogService extends IService<MessageLogEntity> {

    /**
     * 导出excel 表格
     *
     * @param messageLog 查询条件
     * @param ids        导出指定ID
     * @return excel 文件流
     */
    List<MessageLogExcelVO> export(MessageLogEntity messageLog, Long[] ids);

    /**
     * 消息重推
     *
     * @param id id
     */
    Boolean businessPush(Long id);
}