package com.kitex.mom.pms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.pms.entity.PmsWarningEntity;
import com.kitex.mom.pms.vo.PmsWarningExcelVO;

import java.util.List;

public interface PmsWarningService extends IService<PmsWarningEntity> {

    /**
     * 导出excel 表格
     *
     * @param pmsWarning 查询条件
     * @param ids        导出指定ID
     * @return excel 文件流
     */
    List<PmsWarningExcelVO> export(PmsWarningEntity pmsWarning, Long[] ids);
}