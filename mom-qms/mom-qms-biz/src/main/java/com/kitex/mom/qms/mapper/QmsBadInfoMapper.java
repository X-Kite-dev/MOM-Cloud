package com.kitex.mom.qms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.qms.entity.QmsBadInfoEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 不合格品管理
 *
 * @author kitex
 * @since 2025-01-21
 */
@Mapper
public interface QmsBadInfoMapper extends BaseMapper<QmsBadInfoEntity> {
    @org.apache.ibatis.annotations.Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    void lockSource(@org.apache.ibatis.annotations.Param("key") String key);

}
