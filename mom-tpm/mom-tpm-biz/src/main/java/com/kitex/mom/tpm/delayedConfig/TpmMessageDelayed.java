package com.kitex.mom.tpm.delayedConfig;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

public class TpmMessageDelayed implements Delayed {
    /**
     * 报文内容
     */
    @Getter
    private final TpmTaskNotice notice;
    /**
     * 计时开始时间
     */
    private final long startTime;
    /**
     * 超时时间（分钟）
     */
    private final long expireTime;

    /**
     * 构造函数
     *
     * @param notice     报文内容
     * @param expireTime
     */
    public TpmMessageDelayed(TpmTaskNotice notice, long expireTime) {
        this.notice = notice;
        this.expireTime = expireTime * 60 * 1000;
        this.startTime = System.currentTimeMillis() + this.expireTime;
    }

    @Override
    public long getDelay(@NotNull TimeUnit unit) {
        return unit.convert(startTime - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(@NotNull Delayed o) {
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
