import { useEffect, useId, useState } from 'react';
import { Led } from '../components/Led';
import { formatNumber, formatTime } from '../lib/format';
import { SERVICE_CHECKS, runCheck, type CheckKey, type CheckResult } from './serviceChecks';

type Rows = Record<CheckKey, { checking: boolean; result?: CheckResult }>;

const allChecking = (): Rows =>
  Object.fromEntries(SERVICE_CHECKS.map((check) => [check.key, { checking: true }])) as Rows;

function summaryOf(rows: Rows): string {
  const entries = Object.values(rows);
  if (entries.some((row) => row.checking)) return 'Checking the services…';
  const down = entries.filter((row) => !row.result?.up).length;
  if (down === 0) return `All ${entries.length} services answered.`;
  return down === 1 ? '1 service did not answer.' : `${down} services did not answer.`;
}

/**
 * The five services, each asked one cheap question: a green dot when it answers, a red one when it does
 * not, how long the answer took, and when it was asked. "Check again" asks them all once more.
 */
export function ServiceStatus() {
  const titleId = useId();
  const [rows, setRows] = useState<Rows>(allChecking);
  const [round, setRound] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setRows((before) =>
      Object.fromEntries(
        SERVICE_CHECKS.map((check) => [check.key, { ...before[check.key], checking: true }]),
      ) as Rows,
    );
    for (const check of SERVICE_CHECKS) {
      void runCheck(check, controller.signal).then((result) => {
        if (result === null || controller.signal.aborted) return;
        setRows((before) => ({ ...before, [check.key]: { checking: false, result } }));
      });
    }
    return () => controller.abort();
  }, [round]);

  const busy = Object.values(rows).some((row) => row.checking);

  return (
    <section className="card panel-card status-card" aria-labelledby={titleId}>
      <header className="panel-card-head">
        <h2 id={titleId}>Service status</h2>
        <button type="button" className="btn btn--small" disabled={busy} onClick={() => setRound((n) => n + 1)}>
          Check again
        </button>
      </header>
      <p className="muted status-summary" aria-live="polite">
        {summaryOf(rows)}
      </p>
      <ul className="service-list">
        {SERVICE_CHECKS.map((check) => {
          const { checking, result } = rows[check.key];
          const state = checking ? 'pending' : result?.up ? 'up' : 'down';
          return (
            <li key={check.key} className={`service-row service-row--${state}`}>
              <Led state={state} />
              <span className="service-name">{check.label}</span>
              <span className="service-state">{checking ? 'Checking…' : result?.up ? 'Answering' : 'Not answering'}</span>
              {!checking && result && (
                <>
                  <span className="service-ms mono">{formatNumber(result.ms)} ms</span>
                  <span className="service-time muted">Checked at {formatTime(result.checkedAt)}</span>
                </>
              )}
              {!checking && result && !result.up && result.problem && (
                <span className="service-problem">{result.problem}</span>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}
