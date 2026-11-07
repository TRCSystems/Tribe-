-- Consumer (WhatsApp shop assistant) orders: allow the new order source.
-- MySQL stores orders.order_source as an ENUM, which Hibernate's ddl-auto=update does not extend reliably.
ALTER TABLE orders MODIFY COLUMN order_source ENUM('DISTRIBUTOR','REP','SHOP','CUSTOMER') NULL;
