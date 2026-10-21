package com.kitex.mom.qms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.qms.dto.QmsCapaExternalCreateCommand;
import com.kitex.mom.qms.dto.QmsCapaLinkDTO;
import com.kitex.mom.qms.dto.req.*;
import com.kitex.mom.qms.entity.QmsCapaActionEntity;
import com.kitex.mom.qms.entity.QmsCapaCauseEntity;
import com.kitex.mom.qms.entity.QmsCapaEntity;
import com.kitex.mom.qms.entity.QmsCapaOperationEntity;

import java.util.List;

public interface QmsCapaService extends IService<QmsCapaEntity> {
    Page<QmsCapaEntity> queryPage(Page<QmsCapaEntity> page, QmsCapaEntity query);

    QmsCapaEntity getDetail(Long id);

    List<QmsCapaActionEntity> actions(Long capaId);

    List<QmsCapaCauseEntity> causes(Long capaId);

    List<QmsCapaOperationEntity> operations(Long capaId);

    Long create(QmsCapaCreateReq req);

    QmsCapaLinkDTO createExternal(QmsCapaExternalCreateCommand command);

    QmsCapaLinkDTO getExternalLink(Long id);

    void submit(QmsCapaStageReq req);

    Long addAction(QmsCapaActionAddReq req);

    void acceptAction(QmsCapaActionAcceptReq req);

    void cancelAction(QmsCapaActionCancelReq req);

    void completeAction(QmsCapaActionCompleteReq req);

    Long addCause(QmsCapaCauseAddReq req);

    void verifyCause(QmsCapaCauseVerifyReq req);

    void advance(QmsCapaStageReq req);

    void verifyEffectiveness(QmsCapaEffectivenessReq req);

    void cancel(QmsCapaStageReq req);
}
