-- pln_scheduling_task_detail 补充工艺路线编码快照字段（PostgreSQL 幂等）
-- 用途：ConflictEngine 检测 ROUTE_NOT_ASSIGNED 软冲突时读取该字段
ALTER TABLE public.pln_scheduling_task_detail
    ADD COLUMN IF NOT EXISTS process_route_code varchar(64);

COMMENT ON COLUMN public.pln_scheduling_task_detail.process_route_code IS '工艺路线编码快照，用于排产冲突检测';
