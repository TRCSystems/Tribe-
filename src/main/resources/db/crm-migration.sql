-- CRM phase 1 — run once on each database BEFORE starting the new backend.
-- user.role is a MySQL ENUM; Hibernate (ddl-auto=update) never extends an existing enum,
-- so the new FIELD_REP role (distributor field sales reps) must be added by hand.

ALTER TABLE user MODIFY role ENUM('ADMIN','LEAD_COLLECTOR','MERCHANT','SALES_PERSON','FIELD_REP');
