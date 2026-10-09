CREATE TABLE public.visit_events (
  visit_id BIGINT PRIMARY KEY,
  department_code VARCHAR(32) NOT NULL,
  visit_type VARCHAR(16) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL
);
ALTER TABLE public.visit_events REPLICA IDENTITY FULL;
COMMENT ON TABLE public.visit_events IS '隔离合成就诊事件；不含患者信息';
COMMENT ON COLUMN public.visit_events.visit_id IS '合成事件主键';
COMMENT ON COLUMN public.visit_events.department_code IS '合成科室编码';
COMMENT ON COLUMN public.visit_events.visit_type IS '合成事件类型';
COMMENT ON COLUMN public.visit_events.updated_at IS '合成更新时间';
INSERT INTO public.visit_events
SELECT n, 'DEPT_' || (n % 5), 'SYNTHETIC', TIMESTAMP '2026-10-09 00:00:00'
FROM generate_series(1,100) n;
