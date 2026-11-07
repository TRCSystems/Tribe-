-- LOCAL TEST DATA ONLY — blind stock count end-to-end test.
-- Test merchant 990001 with an owner (MERCHANT) and an attendant (SALES_PERSON).
-- business_phone is blank on purpose so no real SMS is sent.
-- Test password for both users: sc-test-1564763f7aad
-- Remove with the cleanup block at the bottom.

INSERT INTO merchants (id, business_name, business_type, location, business_phone)
VALUES (990001, 'STOCKCOUNT TEST SHOP', 'RETAIL', 'Test', '');

INSERT INTO user (user_id, username, password, role, status, merchant_id, is_wholesaler)
VALUES (99001, 'sc_test_owner',     '$2a$10$ynZlvlSLnjm7ihvaWABXSOYeayzR2wxUa5C5DelwHZcAS604ht3Wq', 'MERCHANT',     'ACTIVE', 990001, b'0'),
       (99002, 'sc_test_attendant', '$2a$10$ynZlvlSLnjm7ihvaWABXSOYeayzR2wxUa5C5DelwHZcAS604ht3Wq', 'SALES_PERSON', 'ACTIVE', 990001, b'0');

INSERT INTO inventory_stock (merchant_id, item_name, item_code, starting_stock, added_stock, sold_stock,
       available_stock, closing_stock, total_sales, gross_sales, netl_sales, deductions,
       unit_cost, unit_price, is_active, record_date, reorder_level, meta_synced, last_updated)
VALUES ('990001', 'Test Beer 500ml',   '990001-TBEER',  50, 0, 0, 50, 50, 0, 0, 0, 0, 150.00,  250.00,  b'1', CURDATE(), 10, b'0', NOW()),
       ('990001', 'Test Whisky 750ml', '990001-TWHISK', 10, 0, 0, 10, 10, 0, 0, 0, 0, 2500.00, 3500.00, b'1', CURDATE(), 2,  b'0', NOW()),
       ('990001', 'Test Soda 300ml',   '990001-TSODA',  40, 0, 0, 40, 40, 0, 0, 0, 0, 30.00,   60.00,   b'1', CURDATE(), 10, b'0', NOW()),
       ('990001', 'Test Gin 250ml',    '990001-TGIN',   20, 0, 0, 20, 20, 0, 0, 0, 0, 400.00,  600.00,  b'1', CURDATE(), 5,  b'0', NOW());

-- Cleanup:
-- DELETE FROM stock_count_line WHERE merchant_id = '990001';
-- DELETE FROM close_day WHERE merchant_id = '990001';
-- DELETE FROM inventory_stock_audit WHERE merchant_id = '990001';
-- DELETE FROM sale_transactions WHERE merchant_id = '990001';
-- DELETE FROM product_performance WHERE merchant_id = '990001';
-- DELETE FROM daily_sales_summary WHERE merchant_id = '990001';
-- DELETE FROM payment WHERE transaction_id IN (SELECT transaction_ref FROM sale_transactions WHERE merchant_id = '990001');
-- DELETE FROM inventory_stock WHERE merchant_id = '990001';
-- DELETE FROM user WHERE user_id IN (99001, 99002);
-- DELETE FROM merchants WHERE id = 990001;

-- CRM test shop linked to test distributor 990001 (no phone, so no real SMS is ever sent)
-- INSERT INTO merchants (id, business_name, business_type, location, business_phone, distributor_id) VALUES (990002, 'CRM TEST SHOP', 'LIQUOR', 'Test', '', 990001);
-- Cleanup: DELETE FROM crm_message WHERE distributor_id = 990001; DELETE FROM crm_contact WHERE distributor_id = 990001;
--          DELETE FROM crm_rep_assignment WHERE distributor_id = 990001; DELETE FROM user WHERE role = 'FIELD_REP' AND merchant_id = 990001;
--          DELETE FROM merchants WHERE id = 990002;

-- Local test ADMIN (same test password as above) for viewing other distributors' CRM data, e.g. ?distributorId=23
-- INSERT INTO user (user_id, username, password, role, status, merchant_id, is_wholesaler)
--   SELECT 99010, 'sc_test_admin', password, 'ADMIN', 'ACTIVE', NULL, b'0' FROM user WHERE user_id = 99001;
-- Cleanup: DELETE FROM user WHERE user_id = 99010; DELETE FROM crm_shop_stats WHERE distributor_id IN (23, 990001);

-- Local test login for the ASANO distributor (merchant 23) to view the CRM screens on the local data copy
-- INSERT INTO user (user_id, username, password, role, status, merchant_id, is_wholesaler)
--   SELECT 99011, 'sc_test_asano', password, 'MERCHANT', 'ACTIVE', 23, b'1' FROM user WHERE user_id = 99001;
-- Cleanup: DELETE FROM user WHERE user_id = 99011;

-- Shop order link test (local only): wholesale prices for the test distributor's items, plus links
-- (token "TestLnk7Kp" valid 3 days, token "OldLink9Xy" already expired).
-- Cleanup: DELETE FROM crm_order_link WHERE distributor_id = 990001;
--          DELETE FROM wholesale_price_config WHERE merchant_id = '990001';
--          DELETE oi FROM order_items oi JOIN orders o ON o.id = oi.order_id WHERE o.distributor_id = 990001;
--          DELETE FROM orders WHERE distributor_id = 990001;
