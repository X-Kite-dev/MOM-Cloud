package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mdm.entity.MdmSopOperationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 正式SOP永久命令审计持久层。 */
@Mapper
public interface MdmSopOperationMapper extends BaseMapper<MdmSopOperationEntity> {
    /** 事务级精确命令/版本族互斥；不使用进程内锁冒充跨实例互斥。 */
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    void lockKey(@Param("key") String key);
}

