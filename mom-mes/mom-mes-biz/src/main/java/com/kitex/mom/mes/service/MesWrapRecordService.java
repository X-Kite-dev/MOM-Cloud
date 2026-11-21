package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesWrapRecordEntity;

import java.util.List;

/**
 * 包装装箱扫码记录
 *
 * @author kitex
 * @since 2025-04-01
 */
public interface MesWrapRecordService extends IService<MesWrapRecordEntity> {

    /**
     * 为拆出内容核对原报工模式。重组子行继承原报告，不能从当前目标箱类型推断能否拆量。
     *
     * @param records 本页或本次命令已读取的内容行，模式填入临时展示字段
     * @param factory 已授权的实际工厂
     */
    void loadRepackModes(List<MesWrapRecordEntity> records, String factory);

    /**
     * 删除
     *
     * @param id
     */
    void removeData(Long id);

} 