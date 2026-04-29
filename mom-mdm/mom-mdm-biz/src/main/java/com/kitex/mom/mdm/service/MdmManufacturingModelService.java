package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mdm.entity.MdmManufacturingModelEntity;

import java.util.List;

/**
 * 制造模型服务接口
 *
 * @author claude
 * @date 2025-08-11
 */
public interface MdmManufacturingModelService extends IService<MdmManufacturingModelEntity> {

    /**
     * 根据车间编码获取制造模型
     *
     * @param workshopCode
     * @return
     */
    MdmManufacturingModelEntity getByWorkshop(String workshopCode);

    /**
     * 获取制造模型列表
     *
     * @return 制造模型列表
     */
    List<MdmManufacturingModelEntity> getManufacturingModelList(MdmManufacturingModelEntity query);

    /**
     * 更新制造模型
     *
     * @param manufacturingModelEntityList 制造模型信息
     * @return 更新结果
     */
    Boolean updateBatchManufacturingModel(List<MdmManufacturingModelEntity> manufacturingModelEntityList);
}