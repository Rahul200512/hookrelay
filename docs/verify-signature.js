// Verifying a hookrelay delivery in Node, with no dependencies.
//
// The signature covers `id.timestamp.rawBody`, so you must hash the bytes you received,
// not a re-serialised object. Express: app.post(path, express.raw({type: '*/*'}), ...)
// and read req.body as a Buffer. JSON.stringify(JSON.parse(body)) is not the same bytes.

const crypto = require('crypto');

const TOLERANCE_SECONDS = 5 * 60;

function verify(secret, headers, rawBody) {
  const id = headers['webhook-id'];
  const timestamp = headers['webhook-timestamp'];
  const received = headers['webhook-signature'];
  if (!id || !timestamp || !received) return false;

  // Reject anything too old to be a live delivery: without this, a signature captured
  // once stays valid forever and can be replayed at any time.
  const age = Math.abs(Math.floor(Date.now() / 1000) - Number(timestamp));
  if (!Number.isFinite(age) || age > TOLERANCE_SECONDS) return false;

  const key = Buffer.from(secret.replace(/^whsec_/, ''), 'base64');
  const expected = 'v1,' + crypto.createHmac('sha256', key)
    .update(`${id}.${timestamp}.${rawBody}`)
    .digest('base64');

  // A rotation sends several signatures, space delimited; any one matching is enough.
  return received.split(' ').some((candidate) => {
    const a = Buffer.from(candidate);
    const b = Buffer.from(expected);
    return a.length === b.length && crypto.timingSafeEqual(a, b);
  });
}

module.exports = { verify };
