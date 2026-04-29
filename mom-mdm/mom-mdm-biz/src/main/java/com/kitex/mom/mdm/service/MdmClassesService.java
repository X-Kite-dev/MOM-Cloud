package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import com.kitex.mom.mdm.vo.excel.MdmClassesExcelVO;
import org.springframework.validation.BindingResult;

import java.time.LocalDateTime;
import java.util.List;

public interface MdmClassesService extends IService<MdmClassesEntity> {

    /**
     * 导入
     *
     * @param mdmCalendarList
     * @return
     */
    R importExcel(List<MdmClassesExcelVO> mdmCalendarList, BindingResult bindingResult);

    /**
     * 获取当前班次
     *
     * @return
     */
    MdmClassesEntity getBanci();

    /**
     * 获取班次规则
     *
     * @return R 对象列表
     */
    String getClassesRule(LocalDateTime dateTime);

    /**
     * 新增班次
     *
     * @param mdmClasses 班次
     * @return R
     */
    Boolean saveClasses(MdmClassesEntity mdmClasses);

    /**
     * 修改班次
     *
     * @param mdmClasses 班次
     * @return R
     */
    Boolean updateClasses(MdmClassesEntity mdmClasses);

    /**
     * 根据当前时间获取包含的班次
     *
     * @param dateTime 时间
     * @param number   获取当前班次接下来的几个班次
     * @return R 对象列表
     */
    List<MdmClassesEntity> getClassesBydate(LocalDateTime dateTime, Integer number);

    /**
     * 根据班次获取后几个班次信息
     *
     * @param classCode 班次
     * @param number    获取当前班次接下来的几个班次
     * @return R 对象列表
     */
    List<MdmClassesEntity> getClassesByCode(String classCode, Integer number);
}
