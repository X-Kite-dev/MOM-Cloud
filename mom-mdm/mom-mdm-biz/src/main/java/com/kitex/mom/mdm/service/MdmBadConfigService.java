package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmBadConfigEntity;
import com.kitex.mom.mdm.vo.excel.MdmBadExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;

/**
 * 不良品项管理
 *
 * @author kitex
 * @since 2025-05-17
 */
public interface MdmBadConfigService extends IService<MdmBadConfigEntity> {

    /**
     * 不良品项树
     *
     * @param mdmBadConfig
     * @return
     */
    List<MdmBadConfigEntity> tree(MdmBadConfigEntity mdmBadConfig);

    /**
     * 新增
     *
     * @param mdmBadConfig
     */
    void add(MdmBadConfigEntity mdmBadConfig);

    /**
     * 导入
     *
     * @param list
     * @param bindingResult
     * @return
     */
    R importExcel(List<MdmBadExcelVO> list, BindingResult bindingResult);

}
