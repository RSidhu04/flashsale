import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
const created = new Counter('orders_created');
const conflicts = new Counter('checkout_conflicts');
const base = __ENV.BASE_URL || 'http://localhost:8080';
export const options = {
  scenarios: { buyers: { executor: 'shared-iterations', vus: Number(__ENV.VUS || 100), iterations: Number(__ENV.ATTEMPTS || 1000), maxDuration: '2m' } },
  thresholds: { checks: ['rate==1'] },
};
export function setup() {
  const response = http.post(`${base}/products`, JSON.stringify({name:'Benchmark',priceCents:1999,stock:Number(__ENV.STOCK || 100)}), {headers:{'Content-Type':'application/json'}});
  if (response.status !== 201) throw new Error(`Product creation failed: ${response.status}`);
  return {productId:response.json('id')};
}
export default function(data) {
  const buyer = `run-${data.productId}-${exec.scenario.iterationInTest}`;
  const response = http.post(`${base}/checkout`, JSON.stringify({productId:data.productId,userId:buyer,quantity:1}), {headers:{'Content-Type':'application/json','Idempotency-Key':buyer},tags:{name:'checkout'}});
  check(response, {'expected checkout status': r => r.status === 201 || r.status === 409});
  if (response.status === 201) created.add(1);
  if (response.status === 409) conflicts.add(1);
}
export function teardown(data) {
  console.log(`Verify database conservation for product ${data.productId}; HTTP counts alone do not prove no overselling.`);
}
