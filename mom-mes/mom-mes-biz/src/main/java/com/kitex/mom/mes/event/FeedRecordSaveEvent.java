package com.kitex.mom.mes.event;

import com.kitex.mom.mes.dto.MesTaskReportFeedVO;
import org.springframework.context.ApplicationEvent;

/**
 * 投料保存记录
 */
public class FeedRecordSaveEvent extends ApplicationEvent {

    private MesTaskReportFeedVO vo;


    public FeedRecordSaveEvent(Object source, MesTaskReportFeedVO vo) {
        super(source);
        this.vo = vo;
    }

    public MesTaskReportFeedVO getVo() {
        return vo;
    }

    public void setVo(MesTaskReportFeedVO vo) {
        this.vo = vo;
    }
}
