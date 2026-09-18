package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.AndonNoticeResp;
import com.kitex.mom.mes.dto.AndonRecordCancelVO;
import com.kitex.mom.mes.dto.AndonRecordHandleVO;
import com.kitex.mom.mes.dto.AndonRecordVerifyVO;
import com.kitex.mom.mes.entity.MesAndonOperationEntity;
import com.kitex.mom.mes.entity.MesAndonRecordEntity;

import java.util.List;

public interface MesAndonRecordService extends IService<MesAndonRecordEntity> {

    /**
     * 安灯记录列表
     *
     * @param pageParam
     * @param query
     * @return
     */
    IPage<AndonNoticeResp> noticePage(Page<AndonNoticeResp> pageParam, AndonNoticeResp query);

    /**
     * 创建安灯处理
     *
     * @param mesAndonRecord
     */
    MesAndonRecordEntity saveData(MesAndonRecordEntity mesAndonRecord);

    /**
     * 撤销
     *
     * @param id
     */
    void cancel(AndonRecordCancelVO vo);

    /**
     * 关闭
     *
     * @param id
     */
    void close(Long id);

    /**
     * 接收
     *
     * @param id
     */
    void receive(Long id);

    /**
     * 处理
     *
     * @param vo
     */
    void handle(AndonRecordHandleVO vo);

    void verify(AndonRecordVerifyVO vo);

    List<MesAndonOperationEntity> listOperations(Long recordId);

}
