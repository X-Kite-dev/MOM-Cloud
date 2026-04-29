package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mdm.entity.MdmCalendarEntity;

import java.util.List;

public interface MdmCalendarService extends IService<MdmCalendarEntity> {

    /**
     * 查询工作日历列表
     *
     * @param mdmCalendar 工作日历实体
     * @return R
     */
    List<MdmCalendarEntity> getList(MdmCalendarEntity mdmCalendar);
}