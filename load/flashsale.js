import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
const attempts = new Counter('checkout_attempts');
const created = new Counter('orders_created');
const conflicts = new Counter('checkout_conflicts');
const contention = new Counter('contention_failures');
const unexpected = new Counter('unexpected_responses');
const allLatency = new Trend('checkout_latency', true);
const successLatency = new Trend('successful_checkout_latency', true);
const soldOutLatency = new Trend('sold_out_latency', true);
const base = __ENV.BASE_URL || 'http://localhost:8080';
const stock = Number(__ENV.STOCK || 100);
export const options = {
  scenarios: { buyers: { executor: 'shared-iterations', vus: Number(__ENV.VUS || 100), iterations: Number(__ENV.ATTEMPTS || 1000), maxDuration: '5m' } },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  thresholds: { checks: ['rate==1'], unexpected_responses: ['count==0'], contention_failures: ['count==0'] },
};
export function setup() {
  const response = http.post(`${base}/products`, JSON.stringify({name:'Benchmark',priceCents:1999,stock}), {headers:{'Content-Type':'application/json'}});
  if (response.status !== 201) throw new Error(`Product creation failed: ${response.status}`);
  console.log(`PRODUCT_ID=${response.json('id')}`);
  return {productId:response.json('id')};
}
export default function(data) {
  const buyer = `run-${data.productId}-${exec.scenario.iterationInTest}`;
  const response = http.post(`${base}/checkout`, JSON.stringify({productId:data.productId,userId:buyer,quantity:1}), {
    headers:{'Content-Type':'application/json','Idempotency-Key':buyer},
    tags:{name:'checkout'}, responseCallback:http.expectedStatuses(201,409),
  });
  attempts.add(1);
  allLatency.add(response.timings.duration);
  // A 409 is expected here only when it specifically means stock depletion.
  const soldOut = response.status === 409 && response.json('detail') === 'Insufficient stock';
  const exhausted = response.status === 503 && response.json('detail') === 'Inventory contention; retry with the same key';
  const valid = response.status === 201 || soldOut || exhausted;
  contention.add(exhausted ? 1 : 0);
  check(response, {'expected checkout result': () => valid});
  unexpected.add(valid ? 0 : 1);
  if (response.status === 201) { created.add(1); successLatency.add(response.timings.duration); }
  if (soldOut) { conflicts.add(1); soldOutLatency.add(response.timings.duration); }
}
export function teardown(data) {
  const response = http.get(`${base}/products/${data.productId}`);
  check(response, {'remaining stock is within initial bounds': r => r.status === 200 && r.json('available') >= 0 && r.json('available') <= stock});
  console.log(`Verify SQL conservation for product ${data.productId}.`);
}
export function handleSummary(data) {
  const path = __ENV.RESULT_PATH || 'load/results/summary.json';
  return { [path]: JSON.stringify(data, null, 2), stdout: JSON.stringify({metrics:data.metrics},null,2)+'\n' };
}
