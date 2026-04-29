-- pln_issue 补充排产结束日期字段（PostgreSQL 幂等）
-- 用途：记录计划排产占用的结束工作日，与 scheduled_date 共同表示排产周期
ALTER TABLE public.pln_issue
    ADD COLUMN IF NOT EXISTS scheduled_end_date date;

COMMENT ON COLUMN public.pln_issue.scheduled_end_date IS '排产结束日期，与 scheduled_date 共同表示排产占用周期';

-- pln_scheduling_task_detail 同步补充排产结束日期快照字段
ALTER TABLE public.pln_scheduling_task_detail
    ADD COLUMN IF NOT EXISTS scheduled_end_date date;

COMMENT ON COLUMN public.pln_scheduling_task_detail.scheduled_end_date IS '排产结束日期快照';
