-- Blind stock count — one-off fix for databases where the earlier CloseDay draft ran.
-- That draft made close_day.trading_date globally unique, which allows only ONE merchant
-- to count per day. Hibernate (ddl-auto=update) creates the new per-merchant unique key
-- but never drops the old index, so drop it by hand. Safe to run once; errors if already dropped.

ALTER TABLE close_day DROP INDEX idx_close_day_date;
