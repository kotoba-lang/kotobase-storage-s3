const percentile = (values, p) => {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))];
};

const distribution = (values) => ({
  samples: values.length,
  min_ms: Math.min(...values),
  p50_ms: percentile(values, 0.50),
  p95_ms: percentile(values, 0.95),
  p99_ms: percentile(values, 0.99),
  max_ms: Math.max(...values),
});

const timed = async (operation) => {
  const started = performance.now();
  const value = await operation();
  return { value, elapsed: performance.now() - started };
};

export default {
  async fetch(request, env) {
    if (env.BENCH_LIVE_ENABLED !== "1") {
      return Response.json({ error: "live benchmark disabled" }, { status: 503 });
    }
    if (request.method !== "POST") {
      return Response.json({ error: "POST required" }, { status: 405 });
    }

    const input = await request.json();
    const samples = Number(input.samples ?? 40);
    const size = Number(input.size ?? 1024);
    if (!Number.isSafeInteger(samples) || samples < 5 || samples > 100 ||
        !Number.isSafeInteger(size) || size < 1 || size > 262144) {
      return Response.json({ error: "invalid benchmark bounds" }, { status: 400 });
    }

    const run = crypto.randomUUID();
    const prefix = `bench/kotobase-storage-s3/${run}/`;
    const keys = Array.from({ length: samples }, (_, index) => `${prefix}block-${index}`);
    const body = new Uint8Array(size);
    crypto.getRandomValues(body.subarray(0, Math.min(size, 65536)));
    const put = [];
    const head = [];
    const get = [];
    const cleanup = [];
    const casKey = `${prefix}head`;

    try {
      for (const key of keys) {
        put.push((await timed(() => env.BENCH_BUCKET.put(key, body))).elapsed);
      }
      for (const key of keys) {
        head.push((await timed(() => env.BENCH_BUCKET.head(key))).elapsed);
      }
      for (const key of keys) {
        const result = await timed(async () => {
          const object = await env.BENCH_BUCKET.get(key);
          if (!object || object.size !== size) throw new Error("R2 GET mismatch");
          return (await object.arrayBuffer()).byteLength;
        });
        if (result.value !== size) throw new Error("R2 GET body mismatch");
        get.push(result.elapsed);
      }

      const initial = await env.BENCH_BUCKET.put(casKey, new Uint8Array([0]));
      const racers = await Promise.all(
        Array.from({ length: 8 }, (_, index) => timed(() =>
          env.BENCH_BUCKET.put(casKey, new Uint8Array([index + 1]), {
            onlyIf: { etagMatches: initial.etag },
          })
        ))
      );
      const winners = racers.filter(({ value }) => value !== null);
      const final = await env.BENCH_BUCKET.get(casKey);
      const finalByte = final ? new Uint8Array(await final.arrayBuffer())[0] : null;

      return Response.json({
        schema: "kotobase.r2-live-benchmark.v1",
        colo: request.cf?.colo ?? null,
        samples,
        object_bytes: size,
        put: distribution(put),
        head: distribution(head),
        get: distribution(get),
        conditional_race: {
          writers: racers.length,
          winners: winners.length,
          final_byte: finalByte,
          latency: distribution(racers.map(({ elapsed }) => elapsed)),
        },
      });
    } finally {
      for (const key of [...keys, casKey]) {
        cleanup.push((await timed(() => env.BENCH_BUCKET.delete(key))).elapsed);
      }
    }
  },
};
