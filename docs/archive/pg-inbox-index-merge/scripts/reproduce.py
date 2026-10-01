#!/usr/bin/env python3
"""Unhinted UPDATE experiments in a new, isolated schema. No global settings."""
import argparse
import json
import queue
import re
import subprocess
import threading
import time
import random
from pathlib import Path

HERE = Path(__file__).resolve().parents[1] / 'artifacts'
SCHEMA = 'codex_pg_index_merge_20261001'
PROJECT = Path(__file__).resolve().parents[4]


class Session:
    def __init__(self):
        self.process = subprocess.Popen(
            ['docker', 'exec', '-i', 'mysql-server', 'sh', '-c',
             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot '
             '--batch --raw --skip-column-names --unbuffered --force'],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, bufsize=1)
        self.lines = queue.Queue()
        self.seq = 0
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for line in self.process.stdout:
            self.lines.put(line.rstrip('\n'))
        self.lines.put(None)

    def query(self, sql, timeout=180):
        self.seq += 1
        marker = f'__CODEX_END_{self.seq}__'
        self.process.stdin.write(sql.rstrip().rstrip(';') + ';\n'
                                 + f"SELECT '{marker}';\n")
        self.process.stdin.flush()
        result = []
        deadline = time.monotonic() + timeout
        while True:
            line = self.lines.get(timeout=max(0.01, deadline-time.monotonic()))
            if line is None:
                raise RuntimeError('mysql client exited: ' + '\n'.join(result))
            if line == marker:
                break
            result.append(line)
        errors = [line for line in result if line.startswith('ERROR ')]
        if errors:
            raise RuntimeError('\n'.join(errors))
        return '\n'.join(result)

    def close(self):
        if self.process.poll() is None:
            self.process.stdin.close()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.terminate()


def update_sql(order):
    assert re.fullmatch(r'[a-zA-Z0-9_-]+', order)
    return ("UPDATE pg_inbox SET status='APPROVED',stored_status_result='{}',"
            f"updated_at=NOW(6) WHERE order_id='{order}' AND status='IN_PROGRESS'")


def write_json(name, value):
    (HERE / name).write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n')


def capture(s, label, order='order-0500000'):
    query = update_sql(order)
    s.query("SET SESSION optimizer_trace='enabled=on';"
            "SET SESSION optimizer_trace_max_mem_size=1048576")
    # Stop tracing before Session.query's SELECT delimiter overwrites the trace.
    plan = s.query('EXPLAIN FORMAT=JSON ' + query + "; SET SESSION optimizer_trace='enabled=off'")
    trace = s.query('SELECT TRACE FROM information_schema.OPTIMIZER_TRACE')
    missing = s.query('SELECT MISSING_BYTES_BEYOND_MAX_MEM_SIZE '
                      'FROM information_schema.OPTIMIZER_TRACE')
    s.query("SET SESSION optimizer_trace='enabled=off'")
    item = dict(label=label, sql=query, plan=json.loads(plan),
                trace=json.loads(trace), missing_bytes=missing)
    write_json(f'trace-{label}.json', item)
    table = item['plan']['query_block']['table']
    print(json.dumps(dict(label=label, access=table.get('access_type'),
                          key=table.get('key'), rows=table.get('rows_examined_per_scan'))),
          flush=True)
    return item


def init(s):
    # Intentionally fail if the schema already exists. Never reset a user database.
    s.query(f'CREATE DATABASE {SCHEMA}; USE {SCHEMA}')
    migrations = PROJECT / 'pg-service/src/main/resources/db/migration'
    for f in sorted(migrations.glob('V[1-7]__*.sql')):
        s.query(f.read_text())
    s.query('CREATE TABLE seed_digit (n INT PRIMARY KEY); '
            'INSERT INTO seed_digit VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)')
    started = time.monotonic()
    s.query("INSERT INTO pg_inbox (id,order_id,status,amount,stored_status_result,"
            "created_at,updated_at,payment_key,vendor_type) "
            "SELECT n,CONCAT('order-',LPAD(n,7,'0')),'APPROVED',1000,"
            "CONCAT('{\"result\":\"',REPEAT('x',400),'\"}'),"
            "NOW(6)-INTERVAL 1 DAY,NOW(6)-INTERVAL 1 DAY,CONCAT('payment-',n),'TOSS_PAYMENTS' "
            "FROM (SELECT 1+a.n+10*b.n+100*c.n+1000*d.n+10000*e.n+100000*f.n AS n "
            "FROM seed_digit a CROSS JOIN seed_digit b CROSS JOIN seed_digit c "
            "CROSS JOIN seed_digit d CROSS JOIN seed_digit e CROSS JOIN seed_digit f) t "
            "WHERE n<=500000 ORDER BY n", timeout=240)
    write_json('environment.json', dict(
        version=s.query('SELECT VERSION(),@@version_comment'),
        settings=s.query('SELECT @@transaction_isolation,@@optimizer_switch,'
                         '@@innodb_stats_persistent,@@innodb_stats_auto_recalc,'
                         '@@innodb_stats_persistent_sample_pages,@@eq_range_index_dive_limit'),
        schema=s.query('SHOW CREATE TABLE pg_inbox'),
        project_revision=subprocess.check_output(['git','-C',str(PROJECT),'rev-parse','HEAD'],text=True).strip(),
        seed_rows=500000, seed_seconds=time.monotonic()-started))
    print('Seeded 500000 historical rows', flush=True)


def matrix(s):
    s.query(f'USE {SCHEMA}')
    summary = []
    for count in [1,2,10,100,1000,10000,100000,500000,10]:
        label = f'ip-{count}-case-{len(summary)}'
        s.query("UPDATE pg_inbox SET status='APPROVED' WHERE status='IN_PROGRESS'")
        s.query(f"UPDATE pg_inbox SET status='IN_PROGRESS' WHERE id>{500000-count}")
        item=capture(s,label+'-before-analyze')
        summary.append(dict(label=item['label'],plan=item['plan']))
        s.query('ANALYZE TABLE pg_inbox')
        item=capture(s,label+'-after-analyze')
        summary.append(dict(label=item['label'],plan=item['plan']))
        write_json('matrix-summary.json',summary)


def concurrent(s):
    s.query(f'USE {SCHEMA}')
    s.query("UPDATE pg_inbox SET status='APPROVED' WHERE status='IN_PROGRESS'")
    s.query('ANALYZE TABLE pg_inbox')
    run = str(int(time.time()))
    lock = threading.Lock()
    barrier = threading.Barrier(10)
    results = []
    counts = dict(attempts=0, approved=0, deadlocks=0, other_errors=0,
                  setup_deadlocks=0, retry_deadlocks=0, worker_failures=0)
    started = time.monotonic()
    def worker(worker_id):
        db = Session()
        try:
            db.query(f'USE {SCHEMA}')
            barrier.wait(timeout=30)
            for i in range(500):
                order=f'run-{run}-{worker_id}-{i}'
                for attempt in range(20):
                    try:
                        db.query("INSERT IGNORE INTO pg_inbox(order_id,status,amount,created_at,updated_at) "
                                 f"VALUES('{order}','PENDING',1000,NOW(6),NOW(6))")
                        row_id=db.query(f"SELECT id FROM pg_inbox WHERE order_id='{order}'")
                        db.query('START TRANSACTION')
                        db.query(f"SELECT id FROM pg_inbox WHERE id={row_id} "
                                 "AND status='PENDING' FOR UPDATE SKIP LOCKED")
                        db.query("UPDATE pg_inbox SET status='IN_PROGRESS',updated_at=NOW(6) "
                                 f"WHERE id={row_id} AND status='PENDING'")
                        db.query('COMMIT')
                        break
                    except RuntimeError as e:
                        db.query('ROLLBACK')
                        if 'ERROR 1213' not in str(e):raise
                        with lock:counts['setup_deadlocks']+=1
                        time.sleep(0.01)
                else:
                    raise RuntimeError('setup retries exhausted')
                # Same external delay range as the historical benchmark, outside the transaction.
                time.sleep(random.uniform(0.100,0.300))
                if worker_id==0 and i in (0,100,250):
                    capture(db,f'concurrent-{run}-{i}',order)
                error=None
                try:
                    changed=db.query('START TRANSACTION; '+update_sql(order)+'; SELECT ROW_COUNT(); COMMIT')
                    if changed != '1':raise RuntimeError('unexpected affected rows: '+changed)
                except RuntimeError as e:
                    error=str(e)
                    db.query('ROLLBACK')
                with lock:
                    counts['attempts']+=1
                    if error:
                        key='deadlocks' if 'ERROR 1213' in error else 'other_errors'
                        counts[key]+=1
                        results.append(dict(worker=worker_id,iteration=i,order=order,error=error))
                        capture_dump=key=='deadlocks' and counts[key]<=3
                    else:
                        counts['approved']+=1
                        capture_dump=False
                    if counts['attempts']%500==0:
                        print(json.dumps(counts),flush=True)
                if capture_dump:
                    dump=db.query('SHOW ENGINE INNODB STATUS')
                    (HERE/f'deadlock-{run}-{worker_id}-{i}.txt').write_text(dump+'\n')
                if error:
                    # Separate recovery after the failed transaction, using the same original UPDATE.
                    for retry in range(5):
                        try:
                            db.query(update_sql(order))
                            break
                        except RuntimeError:
                            with lock:counts['retry_deadlocks']+=1
                            time.sleep(0.01)
        except Exception as e:
            with lock:
                counts['worker_failures']+=1
                results.append(dict(worker=worker_id,worker_error=repr(e)))
        finally:
            db.close()
    threads=[threading.Thread(target=worker,args=(n,)) for n in range(10)]
    for t in threads:t.start()
    for t in threads:t.join()
    report=dict(run=run,workers=10,iterations_per_worker=500,counts=counts,
                elapsed_seconds=time.monotonic()-started,errors=results,
                external_delay_ms=[100,300],schema=s.query('SHOW CREATE TABLE pg_inbox'),
                distribution=s.query('SELECT status,COUNT(*) FROM pg_inbox GROUP BY status'))
    write_json(f'concurrent-{run}.json',report)
    print(json.dumps({k:v for k,v in report.items() if k!='errors'}),flush=True)


def probe(s):
    s.query(f'USE {SCHEMA}')
    capture(s,'probe-current')


def composite(s):
    s.query(f'USE {SCHEMA}')
    migration=PROJECT/'pg-service/src/main/resources/db/migration/V8__pg_inbox_status_updated_at_index.sql'
    s.query(migration.read_text())
    s.query('ANALYZE TABLE pg_inbox')
    concurrent(s)


def snapshot(s):
    s.query(f'USE {SCHEMA}')
    write_json('environment-after.json',dict(
        version=s.query('SELECT VERSION()'),
        settings=s.query('SELECT @@transaction_isolation,@@optimizer_switch,'
                         '@@innodb_buffer_pool_size,@@innodb_page_size'),
        schema=s.query('SHOW CREATE TABLE pg_inbox'),
        table_stats=s.query("SELECT TABLE_ROWS,DATA_LENGTH,INDEX_LENGTH FROM information_schema.tables "
                            f"WHERE TABLE_SCHEMA='{SCHEMA}' AND TABLE_NAME='pg_inbox'"),
        orders=s.query("SELECT id,order_id,status FROM pg_inbox WHERE order_id IN "
                       "('run-1790782129-5-48','run-1790782129-4-46')")))


if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('mode',choices=['init','matrix','concurrent','probe','composite','snapshot'])
    args=parser.parse_args()
    session=Session()
    try:
        globals()[args.mode](session)
    finally:
        session.close()
