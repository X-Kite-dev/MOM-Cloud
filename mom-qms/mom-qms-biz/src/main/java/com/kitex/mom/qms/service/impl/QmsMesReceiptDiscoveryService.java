package com.kitex.mom.qms.service.impl;

import com.kitex.mom.qms.entity.QmsBadInfoDetailEntity;
import com.kitex.mom.qms.mapper.QmsMesReceiptReconcileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.kitex.mom.qms.service.impl.QmsMesReceiptReconcileQueue.require;

/**
 * 全局只发现精确待办，游标先提交；失败/进程中断后绕回，不让坏历史行饿死后续记录。
 */
@Service
@RequiredArgsConstructor
public class QmsMesReceiptDiscoveryService {
    private final QmsMesReceiptReconcileMapper jobs;

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public List<QmsBadInfoDetailEntity> next() {
        require(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() == null && org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication() == null, "跨租户发现只允许后台调度");
        Long cursor = jobs.lockDiscoveryCursor();
        require(cursor != null, "MES对账发现游标不存在，请核对累计升级");
        var page = jobs.undiscovered(50, cursor);
        if (page.isEmpty() && cursor > 0) page = jobs.undiscovered(50, 0);
        require(jobs.advanceDiscovery(page.isEmpty() ? 0 : page.get(page.size() - 1).getId()) == 1, "历史对账发现游标保存失败");
        return page;
    }
}
