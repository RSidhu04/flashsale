#!/usr/bin/env python3
"""Reproducible local comparison; test-only PostgreSQL launcher, no production data."""
import argparse, json, os, platform, re, subprocess, time, urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser()
p.add_argument('--k6',default='k6')
p.add_argument('--rounds',type=int,default=3)
p.add_argument('--attempts',type=int,default=10000)
p.add_argument('--vus',type=int,default=100)
p.add_argument('--warmup',type=int,default=2000)
p.add_argument('--output',default='load/results/comparison')
a=p.parse_args()
if min(a.rounds,a.attempts,a.vus,a.warmup)<1: p.error('All counts must be positive')
out=(ROOT/a.output).resolve();out.mkdir(parents=True,exist_ok=False)
os.chdir(ROOT)
java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
k6=str(Path(a.k6).resolve()) if '/' in a.k6 else a.k6
with (out/'build.log').open('w') as log:
 subprocess.run(['./mvnw','-B','test-compile','dependency:build-classpath','-Dmdep.includeScope=test','-Dmdep.outputFile=target/benchmark-classpath.txt'],stdout=log,stderr=subprocess.STDOUT,check=True)
classpath=os.pathsep.join(['target/test-classes','target/classes',Path('target/benchmark-classpath.txt').read_text().strip()])
def get(path):
 with urllib.request.urlopen('http://127.0.0.1:8080'+path,timeout=10) as response: return json.load(response)
def run(label,stock,attempts):
 env=dict(os.environ,STOCK=str(stock),ATTEMPTS=str(attempts),VUS=str(a.vus),RESULT_PATH=str(out/(label+'.json')))
 with (out/(label+'.log')).open('w') as log:
  result=subprocess.run([k6,'run','load/flashsale.js'],env=env,stdout=log,stderr=subprocess.STDOUT,timeout=360)
 text=(out/(label+'.log')).read_text()
 match=re.search(r'PRODUCT_ID=(\d+)',text)
 if not match: raise RuntimeError('No product ID: '+label)
 verification=get('/benchmark/verify/'+match.group(1))
 (out/(label+'-sql.json')).write_text(json.dumps(verification,indent=2)+'\n')
 expected=min(stock,attempts)
 assert verification['orders']==verification['units_sold']==verification['idempotencyKeys']==expected,verification
 assert verification['available']+verification['units_sold']==stock,verification
 if result.returncode: raise RuntimeError(f'k6 thresholds failed ({label}); inspect raw log')
 return verification
metadata={'platform':platform.platform(),'processor':platform.machine(),'logicalCpus':os.cpu_count(),'java':subprocess.check_output([java,'-version'],stderr=subprocess.STDOUT,text=True).strip(),'k6':subprocess.check_output([k6,'version'],text=True).strip(),'commit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'dirty':bool(subprocess.check_output(['git','status','--porcelain'],text=True).strip()),'settings':{k:v for k,v in vars(a).items() if k not in ('k6','output')},'poolSize':16,'jvmHeap':'512 MiB initial/max','runStartedUtc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'runs':[]}
(out/'environment.json').write_text(json.dumps(metadata,indent=2)+'\n')
strategies=['atomic','pessimistic','optimistic']
for round in range(a.rounds):
 for strategy in strategies[round%3:]+strategies[:round%3]:
  label=f'r{round+1}-{strategy}'
  with (out/(label+'-server.log')).open('w') as log:
   env=dict(os.environ,INVENTORY_STRATEGY=strategy)
   server=subprocess.Popen([java,'-Xms512m','-Xmx512m','-cp',classpath,'com.ravan.flashsale.LocalBenchmarkApplication'],env=env,stdout=log,stderr=subprocess.STDOUT)
   try:
    for _ in range(120):
     if server.poll() is not None: raise RuntimeError('Server failed: '+label)
     try:
      environment=get('/benchmark/environment')
      if environment['strategy']!=strategy: raise RuntimeError('Port 8080 belongs to another server')
      break
     except (OSError,urllib.error.URLError): time.sleep(.5)
    else: raise RuntimeError('Server startup timed out')
    warm=run(label+'-warmup',a.warmup,a.warmup)
    previous=warm
    for scenario,stock in [('scarce',100),('in-stock',a.attempts)]:
     name=label+'-'+scenario
     verification=run(name,stock,a.attempts)
     metrics=json.loads((out/(name+'.json')).read_text())['metrics']
     row={'name':name,'strategy':strategy,'round':round+1,'scenario':scenario,'environment':environment,'sql':verification,'retryConflicts':verification['conflictsTotal']-previous['conflictsTotal'],'exhausted':verification['exhaustedTotal']-previous['exhaustedTotal']}
     for metric,key in [('checkout_attempts','rate'),('checkout_latency','p(95)'),('successful_checkout_latency','p(95)')]:
      row[metric+'_'+key]=metrics[metric]['values'][key]
     metadata['runs'].append(row)
     (out/'environment.json').write_text(json.dumps(metadata,indent=2)+'\n')
     previous=verification
     print(json.dumps(row),flush=True)
   finally:
    server.terminate()
    try: server.wait(timeout=20)
    except subprocess.TimeoutExpired: server.kill();server.wait()
print('Verified results: '+str(out),flush=True)
