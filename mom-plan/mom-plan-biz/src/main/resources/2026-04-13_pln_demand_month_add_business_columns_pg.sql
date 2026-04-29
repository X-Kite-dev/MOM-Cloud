-- pln_demand_month 缺失业务字段补齐（PostgreSQL 幂等）
ALTER TABLE public.pln_demand_month
    ADD COLUMN IF NOT EXISTS source_type varchar(32),
    ADD COLUMN IF NOT EXISTS version_no varchar(64),
    ADD COLUMN IF NOT EXISTS effective_flag boolean,
    ADD COLUMN IF NOT EXISTS frozen_flag boolean,
    ADD COLUMN IF NOT EXISTS executable_num numeric(18,6);
