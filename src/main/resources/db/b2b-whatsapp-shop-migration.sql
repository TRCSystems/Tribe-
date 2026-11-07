-- WhatsApp B2B ordering (liquor demo, 2026-10): new cart states while a new shop gives its details.
-- ShopCart.status is a MySQL ENUM, which ddl-auto=update doesn't widen. The new columns
-- (whatsapp_shop_cart.shop_name / contact_person, crm_contact.contact_name) are added by ddl-auto.
ALTER TABLE whatsapp_shop_cart
  MODIFY status ENUM('OPEN','AWAITING_ADDRESS','AWAITING_SHOP_NAME','AWAITING_CONTACT','ORDERED') NOT NULL;
