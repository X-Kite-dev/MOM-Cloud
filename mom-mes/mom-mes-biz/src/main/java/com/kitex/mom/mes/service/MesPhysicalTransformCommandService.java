package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesPhysicalTransformCommandDTO;
import com.kitex.mom.mes.entity.MesPhysicalTransformCommandEntity;

/** 标准持久服务，永久原账不提供通用增删改API。 */
public interface MesPhysicalTransformCommandService extends IService<MesPhysicalTransformCommandEntity> {
    /** 独立提交完整原始字符串与实际owner；不执行库存或接受policyDraft权限。 */
    MesPhysicalTransformCommandDTO.Receipt register(String requestId, String rawJson);
    /** 本人原号精确查询，ABSENT不证明未投递，不按当前库存猜成功。 */
    MesPhysicalTransformCommandDTO.Receipt receipt(String requestId);
    /** 原号锁内显式永久封存REGISTERED；不能释放原号或逆向产量。 */
    MesPhysicalTransformCommandDTO.Receipt seal(String requestId, String rawJson);
}
