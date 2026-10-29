-- Listing the recent runs of one schedule must stay fast on a big jobs table.
CREATE INDEX idx_jobs_schedule_created ON jobs (schedule_id, created_at DESC) WHERE schedule_id IS NOT NULL;
