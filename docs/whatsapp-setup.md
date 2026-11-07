# WhatsApp ordering — setup (Meta Cloud API, one Tribe number)

Shops order inside WhatsApp through an order form (a WhatsApp Flow). Their usual order is filled in, they can
add items that are not on the list, and they pay on delivery or tap **Pay with M-Pesa** afterwards.
CRM messages go out as approved templates with an **Order now** button. Any chat from a known shop gets the
order form back. When WhatsApp is off, or a message can't be delivered, the CRM falls back to SMS with the web
order link.

Public endpoints (nginx maps `/api/` to `/api/v1/`):

| What | URL |
|---|---|
| Webhook (messages, receipts) | `https://tribessystems.co.ke/api/whatsapp/webhook` |
| Order form data endpoint | `https://tribessystems.co.ke/api/whatsapp/flow` |
| Setup (ADMIN login) | `https://tribessystems.co.ke/api/whatsapp/admin/...` |

## 1. Meta side (done once, in Meta Business Manager)

1. **App:** go to developers.facebook.com → Create app → type *Business* → add the **WhatsApp** product.
2. **Phone number:** in WhatsApp → API setup, add the Tribe number. The number must not be in use on the
   WhatsApp app. Verify it with an SMS code and set the display name "Tribe" (Meta approves the name).
3. **Business verification:** in Business settings → Security centre, verify the business. Unverified accounts
   are limited to 250 new chats a day.
4. **Permanent token:** in Business settings → Users → System users, add a system user (Admin). Assign it the
   app and the WhatsApp account. Then generate a token with `whatsapp_business_messaging` and
   `whatsapp_business_management`, and choose expiry *Never*.
5. Note down these four values:
   - **Phone number ID**, from WhatsApp → API setup
   - **WhatsApp Business Account ID**, from the same page
   - **App secret**, from App settings → Basic
   - **The token**, from step 4

## 2. Server config

Add the following to `/root/TrcTenants/config/application.properties`, then restart the backend.
Keep the token and app secret out of git.

```properties
# Turn on in step 6
crm.whatsapp.enabled=false
whatsapp.phone-number-id=<phone number id>
whatsapp.waba-id=<whatsapp business account id>
whatsapp.access-token=<permanent token>
whatsapp.app-secret=<app secret>
# Any long random string you make up
whatsapp.verify-token=<verify token>
# The Tribe number, for wa.me invite links
whatsapp.display-number=2547XXXXXXXX
whatsapp.flow-private-key-path=/root/TrcTenants/config/whatsapp-flow.pem
# Fill in after step 4
whatsapp.flow-id=
```

(Java properties files have no comments at the end of a line, so each comment sits on its own line.)

Create the order form encryption key on the server. The private key never leaves the server.

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /root/TrcTenants/config/whatsapp-flow.pem && chmod 600 /root/TrcTenants/config/whatsapp-flow.pem
```

Check the setup with `GET /api/whatsapp/admin/status` while logged in as ADMIN. It lists the settings that are
still missing.

## 3. Webhook

In the app, go to WhatsApp → Configuration → Webhook:
- **Callback URL:** `https://tribessystems.co.ke/api/whatsapp/webhook`
- **Verify token:** the same `whatsapp.verify-token`

Click **Verify and save**, then subscribe to the **messages** field.

## 4. Order form (Flow)

1. Register our public key on the number: `POST /api/whatsapp/admin/register-key` (as ADMIN).
2. In WhatsApp Manager, go to Account tools → Flows → **Create flow**, with category *Shopping* and the name
   "Tribe order".
3. Open the JSON editor and paste the JSON from `GET /api/whatsapp/admin/flow-json`. The same JSON is in
   `src/main/resources/whatsapp/order-flow.json`. Fix anything the editor flags.
4. **Endpoint:** set it to `https://tribessystems.co.ke/api/whatsapp/flow` and connect the Meta app. Run the
   health check; it should pass, because our server answers WhatsApp's ping.
5. Use **Preview** with "Request data on first screen" to see the form with real catalogue data. You need a
   valid flow token for this; the simplest is to use the test in step 6.
6. **Publish** the flow. Put its ID into `whatsapp.flow-id` and restart.

## 5. Message templates

Run `POST /api/whatsapp/admin/templates` (as ADMIN). This submits the 10 templates for approval, each named
`tribe_<template>`. Marketing templates get an **Order now** button that opens the flow, plus a "Reply STOP to
opt out" footer. Approval usually takes minutes, sometimes up to 24h; you can follow it in WhatsApp Manager →
Message templates. If Meta rejects a template, change its wording in `WhatsAppTemplates.java` and submit again.

## 6. Switch on and test

1. Set `crm.whatsapp.enabled=true` and restart.
2. From your phone, send **Order** to the Tribe number. Your number must be a shop's SMS or WhatsApp number in
   the CRM. You should get the order form back.
3. Place an order. It shows on **Orders** with the "By shop" badge, and the distributor gets the new-order SMS.
4. Tap **Done** on the form, then **Pay with M-Pesa** in the chat.
5. In the CRM, send any marketing message to that shop. It arrives as the WhatsApp template with **Order now**.

You can also call `POST /api/whatsapp/admin/test-order-form` with body
`{"phone":"07…","distributorId":23,"shopId":…}`. It sends the form straight to a phone, but that phone must
have messaged the Tribe number in the last 24 hours.

## How shops move to WhatsApp

- A shop that messages the Tribe number is switched to WhatsApp automatically, and it has opted in by
  messaging.
- In the CRM, the **Invite to order on WhatsApp** SMS sends a `wa.me` link that opens a chat with "Order"
  already typed.
- The **Prefer WhatsApp** switch on the shop profile does the same by hand.
- **Fallbacks:** if WhatsApp reports that a message can't be delivered (for example, the number isn't on
  WhatsApp), the same message is re-sent by SMS with the web order link, and that shop goes back to SMS.
- **Opt-out:** STOP / START on WhatsApp opt the shop out of offers or back in. Order updates still go out.

## Rules that apply

- **Templates:** Meta only allows messages that open a conversation if they use an approved template. Free text
  (the CRM's "Custom note") goes by WhatsApp only within 24h of the shop's last message; otherwise it goes by
  SMS.
- **Pricing:** Meta charges per message. Marketing templates cost more than utility ones (order updates).
  Replies within 24h of a shop's message are free.
- **Guardrails:** the existing ones still apply: 3 marketing messages a week, no marketing 7 PM–8 AM, and a 10%
  hold-out group.
