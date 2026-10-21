package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mdm.dto.MdmSchedulingFamilyAssignDTO;
import com.kitex.mom.mdm.entity.MdmSchedulingFamilyEntity;

/**
 * APS 排产产品族持久化与业务服务。
 *
 * <p>该服务具有唯一聚合根 {@link MdmSchedulingFamilyEntity}，因此遵循 MyBatis-Plus
 * 标准持久化服务规范并继承 {@link IService}。</p>
 */
public interface MdmSchedulingFamilyService extends IService<MdmSchedulingFamilyEntity> {

    /**
     * 校验唯一性及工厂上下文后新增产品族。
     *
     * @param entity 产品族数据
     * @return 是否新增成功
     */
    Boolean saveValidated(MdmSchedulingFamilyEntity entity);

    /**
     * 校验编码不可变、唯一性和停用条件后更新产品族。
     *
     * @param entity 产品族数据
     * @return 是否更新成功
     */
    Boolean updateValidated(MdmSchedulingFamilyEntity entity);

    /**
     * 将一批启用产品归属到指定启用产品族。
     *
     * @param dto 产品族及产品编码集合
     * @return 是否归属成功
     */
    Boolean assignProducts(MdmSchedulingFamilyAssignDTO dto);
}
