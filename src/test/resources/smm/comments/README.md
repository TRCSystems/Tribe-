# SokoReply comment test set

This folder holds two files:

- **`all-comments.jsonl`** has every comment read from 59 reels and posts (56 Facebook reels, 3 Instagram posts with comments): about 6,450 rows, unlabelled. It includes the sellers' own replies (`bySeller`) and which comments are replies (`reply`). Reels with more than about 350 comments are capped at the first 350 or so. It shows what real buyers write, and replays a reel in shadow mode.
- **`testset.jsonl`** is the labelled answer key described below. It's used to score the Reader agent.

In `all-comments.jsonl`, `who` is a per-reel alias (`U1`, `U2`…, or `SELLER`). The same person keeps the same alias within one reel, so you can follow a conversation.

`testset.jsonl` is one JSON object per line. Each line is a real public comment from a Kenyan seller's Facebook or Instagram reel, labelled by hand.

It's used to score the Reader agent (`CommentReader`) before it answers real buyers, and to catch regressions whenever the prompt changes.

## Privacy

- Commenter names are removed.
- Phone numbers are masked: the first 3 digits are kept so the country and network still show, and the rest are zeros.
- Seller replies keep their wording, with the seller's own number shown as `[number]`.

## Fields

| Field | Meaning |
|---|---|
| `id` | `platform:reelId#n` |
| `platform`, `seller`, `sellerType`, `category` | Where the comment came from. `sellerType` is `goods` or `services`. |
| `text` | The comment. Rows tagged `TRANSLATED` hold Facebook's English translation, not the original wording. |
| `intent` | The `CommentReader.Intent` value the Reader should return. |
| `buyer`, `wantsWhatsApp` | What the Reader should return for these. |
| `phone` | `ke` means a Kenyan mobile that must be detected and hidden. `foreign` and `mistyped` are known gaps for now. `null` means no number. |
| `language` | `en` English, `sw` Swahili or Sheng, `fr` French, `rw`/`rn` Kinyarwanda/Kirundi, `sn` Shona, `zu` Zulu/Ndebele, `ru` Russian, `ar` Arabic, `es` Spanish. |
| `language` (more) | `und` means no real words (a bare mention, a GIF). Other codes found: `mg` Malagasy, `luo`, `kam` Kikamba, `so`, `ha`. |
| `unsure` | Even a person can't be certain of the meaning. Score these separately. |
| `tags` | Situations the current intents don't cover yet (see below). |
| `sellerReply` | What the seller actually replied, or `null` if there was no reply. |

## Tags

- `DELIVERY`, `ABROAD`: delivery questions, and buyers outside Kenya.
- `PAY_ON_DELIVERY`, `PRODUCT_QUESTION`, `QUALITY_DOUBT`, `SONG_QUESTION`, `PRICE_CLAIM`, `CRITICISES_SELLER`: other kinds of comment.
- `APP_NUMBER`: "app number", which means WhatsApp number.
- `WP_MEANS_WAPI`, `WP_AMBIGUOUS`: "wp" usually means WhatsApp, but sometimes means "wapi" (where).
- `PRICE_NOT_GIVEN`, `PHONE_ONLY_REPLY`, `ONE_WORD_REPLY`, `SLOW_REPLY`, `GOOD_REPLY`: how the seller handled it.

More tags: `INSTALMENTS` (lipa mdogo mdogo), `BULK` (wholesale), `TAGS_FRIEND`, `CHAT_BETWEEN_USERS`.

## How it was labelled

- The first 221 rows were labelled by hand in the session.
- The next 619 were picked at random, up to 12 per reel, from the other 53 Facebook reels. They were labelled by three Claude agents working from the same written rules and spot-checked: 29 of 30 agreed with the reviewer.
- Any comment asking for "your number" counts as `wantsWhatsApp`.
- `phone` is worked out from the text with the same pattern as `CommentReplyPlanner.hasPhoneNumber`.

- 135 salon and nail rows were added afterwards, labelled by hand. They come from 9 Facebook reels: Royal Beauty Hub, Ora Nail Spot, Mwas Beauty Parlour, Ben Nalis, Zipporah Queen Mwangi, JM Nail Spa, Lovejoy Margaret, Nails empire and Nairobian Beauty Hub. They were picked with a bias towards price and booking words.

Total: 975 rows from 68 reels and posts, 171 of them services (salons and nails).

| Field | Rows |
|---|---|
| intent | PRICE 208, OTHER 200, READY_TO_BUY 165, LOCATION 156, COMPLIMENT 139, VARIANT 39, CONTACT 38, SPAM 13, AVAILABILITY 11, BOOKING 4, COMPLAINT 2 |
| buyer | 641 |
| unsure | 78 |

**What the salon reels showed:** buyers on salon reels almost never say "book". They ask "how much" (Ben Nalis: 17 of 17 sampled, and the seller answered "price in your inbox"), then "where are you". The few that are booking-like are "I want to come here" and "I want to learn" (training, tagged `TRAINING`). A viral salon reel (Ora Nail Spot) is mostly people arguing about crooked nails: noise that must not get a sales reply. **COMPLAINT is still almost missing:** real complaints have to be collected separately.

## Sources (read 2026-11-07)

Ferigeneralstores soundbar, a sound-system seller, HB Tactical (Instagram), the Liz Clement salon (Instagram) and King Bizzo sneakers. The full list of reels is in the `id` field.
