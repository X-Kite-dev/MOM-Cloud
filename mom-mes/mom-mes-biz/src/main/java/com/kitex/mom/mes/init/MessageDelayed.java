package com.kitex.mom.mes.init;

import com.kitex.mom.mes.entity.MesAndonNoticeEntity;
import lombok.Getter;

import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

public class MessageDelayed implements Delayed {

    /**
     * 报文内容
     */
    @Getter
    private final MesAndonNoticeEntity notice;

    /**
     * 计时开始时间
     */
    private final long startTime;
    /**
     * 超时时间
     */
    private final long expireTime;

    /**
     * 构造函数
     *
     * @param notice     报文内容
     * @param expireTime
     */
    public MessageDelayed(MesAndonNoticeEntity notice, long expireTime) {
        this.notice = notice;
        this.expireTime = expireTime * 60 * 1000;
        this.startTime = System.currentTimeMillis() + this.expireTime;
    }


    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(startTime - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
        if (this == o) {
            return 0;
        }
        // 根据剩余时间来进行排序
        long diff = getDelay(TimeUnit.MILLISECONDS) - o.getDelay(TimeUnit.MILLISECONDS);
        if (diff == 0) {
            return 0;
        } else if (diff < 0) {
            return -1;
        } else {
            return 1;
        }
    }
}