package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.dto.WmsMaterialMoveSiteAddDTO;
import com.kitex.mom.wms.dto.WmsMaterialMoveSiteQueryDTO;
import com.kitex.mom.wms.dto.app.WmsMaterialMoveSiteAppDTO;
import com.kitex.mom.wms.entity.WmsMaterialMoveSiteEntity;
import com.kitex.mom.wms.vo.WmsMaterialMoveSiteExcelVO;

import java.util.List;

public interface WmsMaterialMoveSiteService extends IService<WmsMaterialMoveSiteEntity> {

    /**
     * 新增库内移动
     *
     * @param wmsMaterialMoveSiteAddDTO 库内移动
     * @return R
     */
    Boolean materialMoveSiteSave(WmsMaterialMoveSiteAddDTO wmsMaterialMoveSiteAddDTO);

    /**
     * app-库内移动
     *
     * @param wmsMaterialMoveSiteAddDTO 库内移动
     * @return R
     */
    Boolean appMaterialMoveSiteSave(WmsMaterialMoveSiteAppDTO wmsMaterialMoveSiteAddDTO);

    /**
     * 导出excel 表格
     *
     * @param query 查询条件
     * @param ids                 导出指定ID
     * @return excel 文件流
     */
    List<WmsMaterialMoveSiteExcelVO> export(WmsMaterialMoveSiteQueryDTO query, Long[] ids);
}
