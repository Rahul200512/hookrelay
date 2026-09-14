# Problem types

Every error body is an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem. The `type` field is a URL into this page; the fragment is stable and safe to match on.

| Fragment | Status | When |
|---|---|---|
| `#unauthenticated` | 401 | No `Authorization: Bearer hr_live_…` header on a protected route. |
| `#invalid-api-key` | 401 | The key is unknown or revoked. |
| `#not-found` | 404 | The resource doesn't exist, or belongs to another tenant. The two are indistinguishable on purpose. |
| `#bad-request` | 400 | The request parsed but is unacceptable, e.g. a payload over 64 KB. Validation failures use Spring's default `about:blank` type with field details. |
| `#invalid-target` | 422 | The endpoint URL isn't `https`, embeds credentials, doesn't resolve, or resolves to a private, loopback, link-local or reserved address. |
| `#limit-reached` | 422 | A tenant cap was hit (5 endpoints). |
| `#rate-limited` | 429 | Too many tenants created from one address. |
