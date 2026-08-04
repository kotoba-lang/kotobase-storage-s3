const endpoint = process.env.R2_BENCH_URL ?? "http://127.0.0.1:8799";
const samples = Number(process.env.R2_BENCH_SAMPLES ?? 40);
const trials = Number(process.env.R2_BENCH_TRIALS ?? 3);
const sizes = (process.env.R2_BENCH_SIZES ?? "1024,262144")
  .split(",").map(Number);

const results = [];
for (let trial = 1; trial <= trials; trial += 1) {
  for (const size of sizes) {
    const started = performance.now();
    const response = await fetch(endpoint, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ samples, size }),
    });
    const result = await response.json();
    if (!response.ok) throw new Error(`R2 benchmark failed: ${JSON.stringify(result)}`);
    if (result.conditional_race?.winners !== 1) {
      throw new Error(`R2 CAS race had ${result.conditional_race?.winners} winners`);
    }
    results.push({ trial, ...result, client_wall_ms: performance.now() - started });
  }
}

console.log(JSON.stringify({
  schema: "kotobase.r2-live-benchmark-suite.v1",
  observed_at: new Date().toISOString(),
  trials,
  results,
}, null, 2));
